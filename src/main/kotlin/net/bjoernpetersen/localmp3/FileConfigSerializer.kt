package net.bjoernpetersen.localmp3

import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import java.io.File

internal object FileConfigSerializer : ConfigSerializer<File> {
    override fun deserialize(string: String): File {
        return File(string)
    }

    override fun serialize(obj: File): String {
        return obj.path
    }
}
