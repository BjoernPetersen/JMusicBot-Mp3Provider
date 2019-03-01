package net.bjoernpetersen.localmp3.provider

import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.Provider

@IdBase("Local MP3s")
interface Mp3Provider : Provider {

    fun getSongs(): Collection<Song>
}
