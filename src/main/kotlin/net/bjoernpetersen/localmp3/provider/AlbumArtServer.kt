package net.bjoernpetersen.localmp3.provider

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.mpatric.mp3agic.BaseException
import com.mpatric.mp3agic.Mp3File
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.BadRequestException
import io.ktor.features.NotFoundException
import io.ktor.features.StatusPages
import io.ktor.http.ContentType
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.response.respondBytes
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.pipeline.PipelineContext
import mu.KotlinLogging
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private class AlbumArt(val data: ByteArray, val contentType: ContentType)

private suspend fun ApplicationCall.respondAlbumArt(albumArt: AlbumArt) {
    respondBytes(albumArt.data, contentType = albumArt.contentType)
}

private class AlbumArtLoader(private val directory: Path) {
    private val logger = KotlinLogging.logger { }

    private val cache: Cache<String, AlbumArt> = CacheBuilder.newBuilder()
        .maximumSize(256)
        .build()

    private fun loadFromFile(path: Path): AlbumArt? {
        if (!path.startsWith(directory)) {
            logger.warn { "Tried to load data from restricted file: $path" }
            return null
        }

        val mp3File = try {
            Mp3File(path)
        } catch (e: IOException) {
            return null
        } catch (e: BaseException) {
            return null
        }

        if (!mp3File.hasId3v2Tag()) return null
        val tag = mp3File.id3v2Tag
        val image = tag.albumImage ?: return null
        val contentType = tag.albumImageMimeType?.let { ContentType.parse(it) } ?: ContentType.Image.Any
        return AlbumArt(image, contentType)
    }

    fun getAlbumArt(path: Path): AlbumArt? {
        val normalized = path.toAbsolutePath().normalize()
        val cached = cache.getIfPresent(normalized.toString())
        if (cached != null) return cached

        val loaded = loadFromFile(normalized)
        if (loaded != null) {
            cache.put(normalized.toString(), loaded)
            return loaded
        }

        return null
    }
}

@KtorExperimentalAPI
internal class AlbumArtServer(directory: Path) {
    private val logger = KotlinLogging.logger { }

    private val albumArtLoader = AlbumArtLoader(directory)

    private lateinit var engine: ApplicationEngine
    private lateinit var host: String

    suspend fun start(host: String) {
        this.host = host
        engine = embeddedServer(CIO, port = PORT) {
            install(StatusPages)
            routing {
                get(PATH) {
                    serveImage()
                }
            }
        }
        engine.start()
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.serveImage() {
        val params = call.request.queryParameters
        val id = params[PARAM_NAME] ?: throw BadRequestException("Missing parameter: $PARAM_NAME")
        val path = id.toPath()

        val albumArt = albumArtLoader.getAlbumArt(path)
        if (albumArt != null) {
            call.respondAlbumArt(albumArt)
        } else {
            throw NotFoundException()
        }
    }

    fun getUrl(encodedPath: String): String {
        return URLBuilder(
            host = host,
            port = PORT,
            encodedPath = PATH,
            parameters = ParametersBuilder().apply {
                append(PARAM_NAME, encodedPath)
            }
        ).buildString()
    }

    fun close() {
        engine.stop(500, 1000, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val PORT = 64375
        const val PATH = "/localAlbumArt"
        const val PARAM_NAME = "file"
    }
}
