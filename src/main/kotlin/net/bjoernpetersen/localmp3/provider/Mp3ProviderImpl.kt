package net.bjoernpetersen.localmp3.provider

import com.mpatric.mp3agic.BaseException
import com.mpatric.mp3agic.ID3v2
import com.mpatric.mp3agic.Mp3File
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.error
import io.ktor.util.extension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.ChoiceBox
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.PathChooser
import net.bjoernpetersen.musicbot.api.config.PathSerializer
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.loader.SongLoadingException
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import java.io.IOException
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.streams.asSequence

@KtorExperimentalAPI
class Mp3ProviderImpl : Mp3Provider, CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val logger = KotlinLogging.logger { }

    private var folder: Config.SerializedEntry<Path>? = null
    private lateinit var recursive: Config.BooleanEntry
    @Inject
    private lateinit var playbackFactory: Mp3PlaybackFactory
    private lateinit var songById: Map<String, Song>

    private var customSubject: Config.StringEntry? = null

    override val name = "Local MP3"
    override val description = "MP3s from some local directory"
    override val subject
        get() = customSubject?.get() ?: folder?.get()?.fileName?.toString() ?: name

    private lateinit var albumArtHost: Config.SerializedEntry<NetworkInterface>
    private lateinit var albumArtServer: AlbumArtServer

    private fun checkFolder(path: Path?): String? {
        if (path == null) return "Required"
        if (!Files.isDirectory(path)) return "Not a directory"
        return null
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        folder = config.SerializedEntry(
            key = "folder",
            description = "The folder the MP3s should be taken from",
            default = null,
            configChecker = ::checkFolder,
            serializer = PathSerializer,
            uiNode = PathChooser(isDirectory = true)
        )
        recursive = config.BooleanEntry(
            "recursive",
            "Whether to search the folder recursively",
            false
        )

        customSubject = config.StringEntry(
            "DisplayName",
            "Name to display in clients, defaults to folder name",
            { null },
            TextBox
        )

        albumArtHost = config.SerializedEntry(
            "network interface",
            "The network interface to server album arts on",
            NetworkInterfaceSerializer,
            NonnullConfigChecker,
            ChoiceBox({ "${it.displayName} (${it.name})" }, lazy = true, refresh = {
                findNetworkInterfaces()
            })
        )

        return listOf(folder!!, recursive, customSubject!!, albumArtHost)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) {}

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Initializing...")
        val folder = folder?.get() ?: throw InitializationException()
        withContext(coroutineContext) {
            initStateWriter.state("Starting album art server")
            albumArtServer = AlbumArtServer(folder)
            val host = findHost(albumArtHost.get())
                ?: throw InitializationException("Could not find any valid IP address")
            albumArtServer.start(host)

            initStateWriter.state("Looking for songs...")
            val start = Instant.now()
            songById = initializeSongs(initStateWriter, folder, recursive.get())
            val duration = Duration.between(start, Instant.now())
            initStateWriter.state("Done (found ${songById.size} in ${duration.seconds} seconds).")
        }
    }

    override suspend fun loadSong(song: Song): Resource {
        val path = song.id.toPath()
        if (!Files.isRegularFile(path)) throw SongLoadingException("File not found: $path")
        return NoResource
    }

    override suspend fun supplyPlayback(song: Song, resource: Resource): Playback {
        return withContext(coroutineContext) {
            playbackFactory.createPlayback(song.id.toPath().toFile())
        }
    }

    private suspend fun initializeSongs(
        initWriter: InitStateWriter,
        root: Path,
        recursive: Boolean
    ): Map<String, Song> =
        (if (recursive) Files.walk(root).asSequence() else Files.list(root).asSequence())
            .filter { Files.isRegularFile(it) }
            .filter { it.extension.toLowerCase(Locale.US) == "mp3" }
            .map { createSongAsync(initWriter, it) }
            .toList().awaitAll()
            .filterNotNull()
            .associateBy(Song::id) { it }

    private fun createSongAsync(initWriter: InitStateWriter, path: Path): Deferred<Song?> = async {
        logger.debug { "Loading tag for '$path'" }
        createSong(path).also {
            if (it == null) {
                initWriter.warning("Could not load song from '$path'")
            } else {
                initWriter.state("""Loaded song ${it.title}""")
            }
        }
    }

    private suspend fun createSong(path: Path): Song? {
        return withContext(coroutineContext) {
            try {
                val mp3 = try {
                    Mp3File(path)
                } catch (e: IOException) {
                    logger.error(e)
                    return@withContext null
                } catch (e: BaseException) {
                    logger.error(e)
                    return@withContext null
                }

                val id3 = when {
                    mp3.hasId3v1Tag() -> mp3.id3v1Tag
                    mp3.hasId3v2Tag() -> mp3.id3v2Tag
                    else -> return@withContext null
                }

                val id = path.toId()
                val albumArtUrl = if (id3 is ID3v2 && id3.albumImage != null) {
                    albumArtServer.getUrl(id)
                } else null

                Song(
                    id = id,
                    title = id3.title,
                    description = id3.artist ?: "",
                    duration = mp3.lengthInSeconds.toInt(),
                    provider = this@Mp3ProviderImpl,
                    albumArtUrl = albumArtUrl
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun close() {
        albumArtServer.close()
        job.cancel()
    }

    override fun getSongs(): Collection<Song> {
        return songById.values
    }

    override suspend fun search(query: String, offset: Int): List<Song> {
        val queryParts = query.toLowerCase().split(" ")

        return songById.values.filter {
            queryParts.any { query ->
                it.title.toLowerCase().contains(query)
                        || it.description.toLowerCase().contains(query)
            }
        }
    }

    override suspend fun lookup(id: String): Song = songById[id]
        ?: throw NoSuchSongException(id, Mp3Provider::class)
}
