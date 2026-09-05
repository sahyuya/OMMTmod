package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Strict, bounded reader for the classic and Open Note Block Studio NBS v0..v6 formats. */
object NbsFileCodec {
  const val MAX_FILE_BYTES = 64 * 1024 * 1024
  const val MAX_NOTES = 1_000_000
  private const val MAX_STRING_BYTES = 1 * 1024 * 1024
  private const val MAX_TICK = 100_000_000

  data class Header(
      val version: Int,
      val defaultInstruments: Int,
      val songLengthTicks: Int,
      val layerCount: Int,
      val songName: String,
      val author: String,
      val originalAuthor: String,
      val description: String,
      val ticksPerSecond: Double,
      val beatsPerBar: Int,
  )

  data class Note(
      val tick: Int,
      val layer: Int,
      val instrument: Int,
      val key: Int,
      val velocity: Int,
      val panning: Int,
      val detuneCents: Int,
  )

  data class Layer(val id: Int, val name: String, val volume: Int, val panning: Int)

  data class CustomInstrument(
      val id: Int,
      val name: String,
      val soundFile: String,
      val key: Int,
      val pressKey: Boolean,
  )

  data class Song(
      val header: Header,
      val notes: List<Note>,
      val layers: List<Layer>,
      val customInstruments: List<CustomInstrument>,
      /** Number of out-of-spec velocity/pan/detune values safely clamped while importing. */
      val normalizedValueCount: Int,
  ) {
    val customInstrumentCount: Int get() = customInstruments.size
  }

  fun decode(bytes: ByteArray): Song {
    require(bytes.size in 2..MAX_FILE_BYTES) { "NBS file must be between 2 bytes and 64 MiB" }
    val input = Reader(bytes)
    var normalizedValueCount = 0
    fun normalized(value: Int, range: IntRange): Int {
      if (value !in range) normalizedValueCount++
      return value.coerceIn(range)
    }
    val classicLength = input.u16()
    val version = if (classicLength == 0) input.u8().also { require(it in 1..6) { "Unsupported NBS version: $it" } } else 0
    val defaultInstruments = if (version > 0) input.u8().also { require(it > 0) { "NBS default instrument count is zero" } } else 10
    val songLength = if (version >= 3) input.u16() else classicLength
    val layerCount = input.u16()
    val name = input.string()
    val author = input.string()
    val originalAuthor = input.string()
    val description = input.string()
    val tempoHundredths = input.u16().also { require(it > 0) { "NBS tempo is zero" } }
    input.u8() // legacy autosave flag
    input.u8() // legacy autosave interval
    val beatsPerBar = input.u8().also { require(it in 1..32) { "NBS time-signature numerator is outside 1..32" } }
    repeat(5) { input.u32("NBS editor statistic") }
    input.string() // original MIDI/schematic name
    if (version >= 4) {
      input.u8() // loop enabled
      input.u8() // maximum loop count
      input.u16() // loop start tick
    }

    val notes = ArrayList<Note>()
    var currentTick = -1
    while (true) {
      val tickJump = input.u16()
      if (tickJump == 0) break
      currentTick = Math.addExact(currentTick, tickJump)
      require(currentTick in 0..MAX_TICK) { "NBS tick exceeds the supported range" }
      var currentLayer = -1
      while (true) {
        val layerJump = input.u16()
        if (layerJump == 0) break
        currentLayer = Math.addExact(currentLayer, layerJump)
        require(currentLayer in 0 until layerCount) { "NBS note references missing layer $currentLayer" }
        val instrument = input.u8()
        val key = input.u8().also { require(it in 0..87) { "NBS key is outside 0..87" } }
        // A number of widely shared NBS files contain values written by old macros outside the
        // UI's documented range. These are still single bounded bytes/shorts, so clamping the
        // musical value is safe and substantially more compatible than rejecting the whole song.
        val velocity = if (version >= 4) normalized(input.u8(), 0..100) else 100
        val panning = if (version >= 4) normalized(input.u8(), 0..200) - 100 else 0
        val detune = if (version >= 4) normalized(input.i16(), -1200..1200) else 0
        require(notes.size < MAX_NOTES) { "NBS file contains more than $MAX_NOTES notes" }
        notes += Note(currentTick, currentLayer, instrument, key, velocity, panning, detune)
      }
    }

    val layers = List(layerCount) { layer ->
      val layerName = input.string()
      if (version >= 4) input.u8() // lock flag
      val volume = normalized(input.u8(), 0..100)
      val panning = if (version >= 2) normalized(input.u8(), 0..200) - 100 else 0
      Layer(layer, layerName, volume, panning)
    }

    val customCount = input.u8()
    require(defaultInstruments + customCount <= 256) { "NBS instrument table exceeds 256 entries" }
    require(notes.all { it.instrument < defaultInstruments + customCount }) {
      "NBS note references an undefined instrument"
    }
    val customInstruments = List(customCount) { offset ->
      val customName = input.string()
      val soundFile = input.string()
      val key = input.u8().also { value -> require(value in 0..87) { "NBS custom instrument key is outside 0..87" } }
      val pressKey = input.u8() != 0
      CustomInstrument(defaultInstruments + offset, customName, soundFile, key, pressKey)
    }
    input.requireExhausted()

    return Song(
        Header(version, defaultInstruments, songLength, layerCount, name, author, originalAuthor, description, tempoHundredths / 100.0, beatsPerBar),
        notes,
        layers,
        customInstruments,
        normalizedValueCount,
    )
  }

  fun customInstrument(song: Song, nbsInstrument: Int): CustomInstrument? =
      song.customInstruments.getOrNull(nbsInstrument - song.header.defaultInstruments)

  /** Converts NBS key 33 (F#3) to OMMT zero; custom root key 45 is unshifted. */
  fun toOmmtPitchCents(key: Int, detuneCents: Int, customRootKey: Int? = null): Int =
      (key + (customRootKey ?: 45) - 45 - 33) * 100 + detuneCents

  /** NBS combines the centered note and layer panning values by averaging them. */
  fun effectivePanning(notePanning: Int, layerPanning: Int): Int =
      ((notePanning.coerceIn(-100, 100) + layerPanning.coerceIn(-100, 100)) / 2)
          .coerceIn(-100, 100)

  /**
   * Community NBS files sometimes store editor/export control events in custom-instrument
   * lanes. They are not audible samples and must never fall back to Harp.
   *
   * Their exact behaviour is exporter-specific and is not part of the NBS v0..v6 wire
   * format, so the importer preserves song timing and deliberately omits the control notes
   * instead of guessing an audible result.
   */
  fun isControlInstrument(song: Song, nbsInstrument: Int): Boolean {
    val name = customInstrument(song, nbsInstrument)?.name?.trim()?.lowercase() ?: return false
    return name == "tempo changer" || name == "sound stopper"
  }

  /** Converts Tempo Changer detuneCents (NBS v4 detune -1200..1200) directly to BPM. */
  fun tempoChangerDetuneToBpm(detuneCents: Int): Int = kotlin.math.abs(detuneCents).coerceIn(1, 60000)
  fun tempoChangerDetuneToTps(detuneCents: Int): Double = tempoChangerDetuneToBpm(detuneCents) / 15.0

  /** Legacy key-based fallback for files without detune (v0..3). */
  fun tempoChangerKeyToTps(key: Int): Double {
    // Community convention: key 33 = base tempo (header), higher = faster, lower = slower.
    // Map key 33 -> header-like 10 tps baseline, with 0.4 tps per semitone.
    return ((key - 33) * 0.4 + 10.0).coerceIn(0.25, 60.0)
  }

  /** Normalized resource path used to match NBS custom samples to Minecraft sound patterns. */
  fun normalizedSoundPath(raw: String): String = raw.trim().lowercase()
      .replace('\\', '/')
      .removePrefix("assets/")
      .substringAfter("/sounds/", missingDelimiterValue = raw.trim().lowercase().replace('\\', '/'))
      .removePrefix("sounds/")
      .removeSuffix(".ogg")
      .removeSuffix(".wav")
      .removePrefix("minecraft/")
      .removePrefix("minecraft:")
      .trimStart('/')

  /** OpenNBS indices 5..7 differ from OyasaiMusic's stable instrument order. */
  fun toOmmtInstrument(nbsInstrument: Int, defaultInstruments: Int): Int? {
    if (nbsInstrument !in 0 until defaultInstruments || nbsInstrument !in 0..15) return null
    return when (nbsInstrument) {
      5 -> 7 // guitar
      6 -> 5 // flute
      7 -> 6 // bell
      else -> nbsInstrument
    }
  }

  /** NBS v6 adds four copper-family trumpet instruments after the original 16. */
  fun toMinecraftSound(nbsInstrument: Int, defaultInstruments: Int): String? {
    if (nbsInstrument !in 0 until defaultInstruments) return null
    return when (nbsInstrument) {
      16 -> "minecraft:block.note_block.trumpet"
      17 -> "minecraft:block.note_block.trumpet_exposed"
      18 -> "minecraft:block.note_block.trumpet_weathered"
      19 -> "minecraft:block.note_block.trumpet_oxidized"
      else -> null
    }
  }

  private class Reader(private val bytes: ByteArray) {
    private var index = 0

    fun u8(): Int {
      requireRemaining(1)
      return bytes[index++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun i16(): Int {
      val value = u16()
      return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    fun u32(label: String): Long {
      val value = u8().toLong() or (u8().toLong() shl 8) or (u8().toLong() shl 16) or (u8().toLong() shl 24)
      require(value <= Int.MAX_VALUE.toLong()) { "$label exceeds the supported range" }
      return value
    }

    fun string(): String {
      val length = u32("NBS string length").toInt()
      require(length <= MAX_STRING_BYTES) { "NBS string exceeds 1 MiB" }
      requireRemaining(length)
      // OpenNBS writes UTF-8; legacy files may be Shift-JIS or Windows-1252.
      return MidiInstrumentMapper.decodeText(bytes.copyOfRange(index, index + length)).also { index += length }
    }

    fun requireExhausted() {
      require(index == bytes.size) { "NBS file has ${bytes.size - index} trailing bytes" }
    }

    private fun requireRemaining(count: Int) {
      require(count >= 0 && index <= bytes.size - count) { "Truncated NBS file" }
    }
  }
}
