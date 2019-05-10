package net.bjoernpetersen.localmp3.provider

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.InvalidPathException
import java.util.concurrent.TimeUnit

@KtorExperimentalAPI
internal class AlbumArtServer {
    private val logger = KotlinLogging.logger { }

    private lateinit var engine: ApplicationEngine
    private lateinit var host: String

    // TODO we should probably limit the possible paths to a directory

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
        val path = params[PARAM_NAME] ?: throw BadRequestException("Missing parameter: $PARAM_NAME")
        val mp3File = try {
            Mp3File(path)
        } catch (e: IOException) {
            throw NotFoundException()
        } catch (e: BaseException) {
            throw NotFoundException()
        } catch (e: InvalidPathException) {
            throw NotFoundException()
        }

        if (mp3File.hasId3v2Tag()) {
            val tag = mp3File.id3v2Tag
            val image = tag.albumImage ?: throw NotFoundException()
            val contentType = tag.albumImageMimeType?.let { ContentType.parse(it) } ?: ContentType.Image.Any
            call.respondBytes(image, contentType = contentType)
        }
    }

    fun getUrl(file: File): String {
        return URLBuilder(
            host = host,
            port = PORT,
            encodedPath = PATH,
            parameters = ParametersBuilder().apply {
                append(PARAM_NAME, file.path)
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

@Throws(IOException::class)
internal suspend fun findFreePort(): Int {
    return withContext(Dispatchers.IO) {
        ServerSocket(0).use { it.localPort }
    }
}
