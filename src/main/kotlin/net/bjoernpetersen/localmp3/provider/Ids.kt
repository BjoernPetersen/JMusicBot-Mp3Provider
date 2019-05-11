package net.bjoernpetersen.localmp3.provider

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

internal fun String.toPath(): Path {
    val encoded = toByteArray(StandardCharsets.UTF_8)
    val decoded = Base64.getDecoder().decode(encoded)
    return Paths.get(String(decoded, StandardCharsets.UTF_8))
}

internal fun Path.toId(): String {
    val encoded = Base64.getEncoder()
        .encode(toString().toByteArray(StandardCharsets.UTF_8))
    return String(encoded, StandardCharsets.UTF_8)
}
