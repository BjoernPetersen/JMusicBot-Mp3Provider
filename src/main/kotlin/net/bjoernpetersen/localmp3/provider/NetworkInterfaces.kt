package net.bjoernpetersen.localmp3.provider

import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.SerializationException
import java.net.NetworkInterface
import java.net.SocketException

private val logger = KotlinLogging.logger { }

internal object NetworkInterfaceSerializer : ConfigSerializer<NetworkInterface> {
    override fun deserialize(string: String): NetworkInterface {
        return try {
            NetworkInterface.getByName(string)
        } catch (e: SocketException) {
            throw SerializationException()
        } ?: throw SerializationException()
    }

    override fun serialize(obj: NetworkInterface): String {
        return obj.name
    }
}

internal fun findNetworkInterfaces(): List<NetworkInterface> = NetworkInterface.getNetworkInterfaces().asSequence()
    .filter { !it.isLoopback }
    .filter { it.isUp }
    .toList()

internal fun findHost(networkInterface: NetworkInterface?): String? {
    if (networkInterface != null && networkInterface.isUp) {
        val address = networkInterface.firstLocalOrNull()
        if (address != null) return address
        logger.warn { "Did not find a valid IP for selected network interface, falling back to anything else..." }
    }

    // Return any instead
    val fallback = findNetworkInterfaces()
        .mapNotNull { it.firstLocalOrNull() }
        .firstOrNull()

    if (fallback == null) logger.error { "Could not find any valid local IP address" }
    return fallback
}

private fun NetworkInterface.firstLocalOrNull(): String? =
    inetAddresses.asSequence()
        .filter { !it.isLoopbackAddress }
        .map { it.hostAddress }
        .firstOrNull()
