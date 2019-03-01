package net.bjoernpetersen.localmp3.provider

import com.mpatric.mp3agic.Mp3File
import net.bjoernpetersen.localmp3.FileConfigSerializer
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.FileChooser
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.loader.SongLoadingException
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import javax.inject.Inject

class Mp3ProviderImpl : Mp3Provider {
    private var folder: Config.SerializedEntry<File>? = null
    private lateinit var recursive: Config.BooleanEntry
    @Inject
    private lateinit var playbackFactory: Mp3PlaybackFactory
    private lateinit var songById: Map<String, Song>

    override val name = "Local MP3"
    override val description = "MP3s from some local directory"
    override val subject = folder?.get()?.name ?: name

    private fun checkFolder(file: File?): String? {
        if (file == null) return "Required"
        if (!file.isDirectory) return "Not a directory"
        return null
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        folder = config.SerializedEntry(
            key = "folder",
            description = "The folder the MP3s should be taken from",
            default = null,
            configChecker = ::checkFolder,
            serializer = FileConfigSerializer,
            uiNode = FileChooser(isDirectory = true)
        )
        recursive = config.BooleanEntry(
            "recursive",
            "Whether to search the folder recursively",
            false
        )

        return listOf(folder!!, recursive)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) {}

    override fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Initializing...")
        val start = Instant.now()
        val folder = folder?.get() ?: throw InitializationException()
        songById = initializeSongs(initStateWriter, folder, recursive.get())
        val duration = start.until(Instant.now(), ChronoUnit.SECONDS)
        initStateWriter.state("Done (found ${songById.size} in $duration seconds).")
    }

    private fun toPath(id: String): File {
        val encoded = id.toByteArray(StandardCharsets.UTF_8)
        val decoded = Base64.getDecoder().decode(encoded)
        return File(String(decoded, StandardCharsets.UTF_8))
    }

    private fun toId(file: File): String {
        val encoded = Base64.getEncoder()
            .encode(file.path.toByteArray(StandardCharsets.UTF_8))
        return String(encoded, StandardCharsets.UTF_8)
    }

    override fun loadSong(song: Song): Resource {
        val file = toPath(song.id)
        if (!file.isFile) throw SongLoadingException("File not found: ${file.path}")
        return NoResource
    }

    override fun supplyPlayback(song: Song, resource: Resource): Playback {
        return playbackFactory.createPlayback(toPath(song.id))
    }

    private fun initializeSongs(
        initWriter: InitStateWriter,
        root: File,
        recursive: Boolean
    ): Map<String, Song> =
        (if (recursive) root.walk().asSequence() else root.listFiles().asSequence())
            .filter(File::isFile)
            .filter { it.extension.toLowerCase(Locale.US) == "mp3" }
            .onEach { initWriter.state("Loading tag for '$it'") }
            .map { createSong(it) }
            .filterNotNull()
            .associateBy(Song::id) { it }

    private fun createSong(file: File): Song? {
        return try {
            Mp3File(file.path).let {
                val id3 = when {
                    it.hasId3v1Tag() -> it.id3v1Tag
                    it.hasId3v2Tag() -> it.id3v2Tag
                    else -> return null
                }

                Song(
                    id = toId(File(file.path)),
                    title = id3.title,
                    description = id3.artist ?: "",
                    duration = it.lengthInSeconds.toInt(),
                    provider = this
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {}

    override fun getSongs(): Collection<Song> {
        return songById.values
    }

    override fun search(query: String, offset: Int): List<Song> {
        val queryParts = query.toLowerCase().split(" ")

        return songById.values.filter {
            queryParts.any { query ->
                it.title.toLowerCase().contains(query)
                    || it.description.toLowerCase().contains(query)
            }
        }
    }

    override fun lookup(id: String): Song = songById[id]
        ?: throw NoSuchSongException(id, Mp3Provider::class)
}