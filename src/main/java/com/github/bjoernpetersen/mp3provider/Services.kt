package com.github.bjoernpetersen.mp3provider

import com.github.bjoernpetersen.jmusicbot.*
import com.github.bjoernpetersen.jmusicbot.config.Config
import com.github.bjoernpetersen.jmusicbot.config.ui.FileChooserButton
import com.github.bjoernpetersen.jmusicbot.platform.Platform
import com.github.bjoernpetersen.jmusicbot.platform.Support
import com.github.bjoernpetersen.jmusicbot.playback.PlaybackFactory
import com.github.bjoernpetersen.jmusicbot.provider.DependencyMap
import com.github.bjoernpetersen.jmusicbot.provider.NoSuchSongException
import com.github.bjoernpetersen.jmusicbot.provider.Provider
import com.github.bjoernpetersen.jmusicbot.provider.Suggester
import com.github.bjoernpetersen.mp3Playback.Mp3PlaybackFactory
import com.github.zafarkhaja.semver.Version
import com.mpatric.mp3agic.Mp3File
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class Mp3Provider : Provider {
  private lateinit var folder: Config.StringEntry
  private lateinit var recursive: Config.BooleanEntry
  private lateinit var playbackFactory: Mp3PlaybackFactory
  internal lateinit var songById: Map<String, Song>

  override fun initializeConfigEntries(config: Config): List<Config.Entry> {
    folder = config.StringEntry(
        javaClass,
        "folder",
        description = "",
        isSecret = false,
        default = null,
        ui = FileChooserButton(isFolder = true)
    )
    recursive = config.BooleanEntry(
        javaClass,
        "recursive",
        "",
        false
    )

    return listOf(folder, recursive)
  }

  override fun getMissingConfigEntries(): List<Config.Entry> {
    return if (folder.value == null) {
      listOf(folder, recursive)
    } else {
      emptyList()
    }
  }

  override fun destructConfigEntries() {
    folder.destruct()
    recursive.destruct()
  }

  override fun getId(): String = "localmp3"

  override fun getReadableName(): String = "Local MP3"

  override fun getBaseClass(): Class<out Provider> = javaClass

  override fun getSupport(platform: Platform): Support = when (platform) {
    Platform.LINUX, Platform.WINDOWS -> Support.YES
    Platform.UNKNOWN, Platform.ANDROID -> Support.MAYBE
  }

  override fun getPlaybackDependencies(): Set<Class<out PlaybackFactory>> =
      setOf(Mp3PlaybackFactory::class.java)

  override fun getMinSupportedVersion(): Version = Version.forIntegers(0, 10, 0)
  override fun getMaxSupportedVersion(): Version = MusicBot.getVersion()

  override fun initialize(initWriter: InitStateWriter, manager: PlaybackFactoryManager) {
    initWriter.state("Initializing...")
    playbackFactory = manager.getFactory(Mp3PlaybackFactory::class.java)
    initWriter.state("Found Mp3PlaybackFactory! Searching songs...")
    val start = Instant.now()
    songById = initializeSongs(initWriter, File(folder.value), recursive.value)
    val duration = start.until(Instant.now(), ChronoUnit.SECONDS)
    initWriter.state("Done (found ${songById.size} in $duration seconds).")
  }

  private fun initializeSongs(initWriter: InitStateWriter,
      root: File,
      recursive: Boolean): Map<String, Song> = Song.Builder()
      .provider(this)
      .songLoader { true }
      .playbackSupplier { playbackFactory.createPlayback(File(it.id)) }
      .let { builder ->
        (if (recursive) root.walk().asSequence() else root.listFiles().asSequence())
            .filter(File::isFile)
            .filter { it.extension.toLowerCase(Locale.US) == "mp3" }
            .onEach { initWriter.state("Loading tag for '$it'") }
            .map { createSong(builder, it) }
            .filterNotNull()
            .associateBy(Song::getId) { it }
      }

  private fun createSong(builder: Song.Builder, file: File): Song? {
    return try {
      Mp3File(file.path).let {
        builder.duration(it.lengthInSeconds.toInt())
        val id3 = if (it.hasId3v1Tag()) it.id3v1Tag else if (it.hasId3v2Tag()) it.id3v2Tag else null
        if (id3 == null) null
        else builder
            .id(file.path)
            .title(id3.title ?: file.name)
            .description(id3.artist ?: "")
            .build()
      }
    } catch (e: Exception) {
      null
    }
  }

  override fun close() {
  }

  override fun search(query: String): List<Song> {
    val queryParts = query.toLowerCase().split(" ")

    return songById.values.filter {
      queryParts.any { query ->
        it.title.toLowerCase().contains(query) || it.description.toLowerCase().contains(query)
      }
    }
  }

  override fun lookup(songId: String): Song = songById[songId] ?: throw NoSuchSongException()
}

class Mp3Suggester : Suggester {
  private val nextSongs = LinkedList<Song>()
  private lateinit var provider: Mp3Provider

  override fun getId(): String = "localmp3"
  override fun getReadableName(): String = "Random local MP3"

  override fun initializeConfigEntries(config: Config): List<Config.Entry> = emptyList()

  override fun getMissingConfigEntries(): List<Config.Entry> = emptyList()

  override fun destructConfigEntries() = Unit

  override fun getSupport(platform: Platform): Support = when (platform) {
    Platform.WINDOWS, Platform.LINUX -> Support.YES
    Platform.ANDROID, Platform.UNKNOWN -> Support.MAYBE
  }

  override fun getMinSupportedVersion(): Version = Version.forIntegers(0, 10, 0)

  override fun getDependencies(): Set<Class<out Provider>> = setOf(Mp3Provider::class.java)

  override fun initialize(initWriter: InitStateWriter, dependencies: DependencyMap<Provider>) {
    provider = dependencies[Mp3Provider::class.java]
        ?: throw InitializationException("Missing dependency")
    refreshNextSongs()
  }

  override fun close() {
    nextSongs.clear()
  }

  private fun refreshNextSongs() {
    provider.songById.values.forEach { nextSongs.add(it) }
    Collections.shuffle(nextSongs)
  }

  override fun getNextSuggestions(maxSize: Int): List<Song> {
    if (nextSongs.isEmpty()) refreshNextSongs()
    return nextSongs.subList(0, minOf(nextSongs.size, minOf(20, maxOf(1, maxSize))))
  }

  override fun suggestNext(): Song {
    getNextSuggestions(1)
    return nextSongs.pop()
  }

  override fun notifyPlayed(song: Song) {
    removeSuggestion(song)
  }

  override fun removeSuggestion(song: Song) {
    nextSongs.remove(song)
  }
}

