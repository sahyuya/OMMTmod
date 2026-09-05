package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.NoteBlockPitch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Versioned, bounded local project format used by the `.ommt` files in `OMMT/saves`. */
object EditorProjectCodec {
  private const val MAGIC = 0x4f4d4d54 // OMMT
  /** v2 adds a signed, absolute pitch-in-cents field after the legacy display pitch. */
  private const val VERSION = 2
  private const val FLAG_GZIP = 1
  private const val HEADER_BYTES = 20
  private const val MAX_COMPRESSED_BYTES = 64 * 1024 * 1024
  private const val MAX_BODY_BYTES = 128 * 1024 * 1024
  private const val MAX_NOTES = 1_000_000
  private const val MAX_PARTS = 4_096
  private const val MAX_TEMPO_POINTS = 8_192
  private const val MAX_TIMING_MARKS = 1_000_000
  private const val MAX_SIGNATURES = 4_096
  private const val MAX_GRID_MARKS = 4_000_000

  fun encode(snapshot: EditorSnapshot): ByteArray {
    validateSnapshot(snapshot)
    val body = ByteArrayOutputStream().use { bytes ->
      DataOutputStream(bytes).use { out -> writeBody(out, snapshot) }
      bytes.toByteArray()
    }
    require(body.size <= MAX_BODY_BYTES) { "OMMT project body exceeds 128 MiB" }
    val compressed = ByteArrayOutputStream().use { bytes ->
      GZIPOutputStream(bytes).use { it.write(body) }
      bytes.toByteArray()
    }
    require(compressed.size <= MAX_COMPRESSED_BYTES) { "OMMT project exceeds 64 MiB after compression" }
    val crc = CRC32().apply { update(body) }.value.toInt()
    return ByteArrayOutputStream(HEADER_BYTES + compressed.size).use { bytes ->
      DataOutputStream(bytes).use { out ->
        out.writeInt(MAGIC)
        out.writeShort(VERSION)
        out.writeShort(FLAG_GZIP)
        out.writeInt(body.size)
        out.writeInt(compressed.size)
        out.writeInt(crc)
        out.write(compressed)
      }
      bytes.toByteArray()
    }
  }

  fun decode(file: ByteArray): EditorSnapshot {
    require(file.size in HEADER_BYTES..(HEADER_BYTES + MAX_COMPRESSED_BYTES)) { "Invalid OMMT project size" }
    val compressed: ByteArray
    val expectedBodySize: Int
    val expectedCrc: Int
    val version: Int
    DataInputStream(ByteArrayInputStream(file)).use { input ->
      require(input.readInt() == MAGIC) { "Invalid OMMT project signature" }
      version = input.readUnsignedShort()
      require(version in 1..VERSION) { "Unsupported OMMT project version" }
      require(input.readUnsignedShort() == FLAG_GZIP) { "Unsupported OMMT project compression" }
      expectedBodySize = input.readInt()
      val compressedSize = input.readInt()
      expectedCrc = input.readInt()
      require(expectedBodySize in 1..MAX_BODY_BYTES) { "Invalid OMMT project body size" }
      require(compressedSize in 1..MAX_COMPRESSED_BYTES && compressedSize == input.available()) { "Invalid OMMT project compressed size" }
      compressed = input.readNBytes(compressedSize)
      require(compressed.size == compressedSize && input.available() == 0) { "Truncated OMMT project" }
    }
    val body = inflateBounded(compressed, expectedBodySize)
    require(CRC32().apply { update(body) }.value.toInt() == expectedCrc) { "OMMT project checksum mismatch" }
    return DataInputStream(ByteArrayInputStream(body)).use { input ->
      readBody(input, version).also { require(input.available() == 0) { "Trailing OMMT project data" } }
    }
  }

  private fun writeBody(out: DataOutputStream, value: EditorSnapshot) {
    writeString(out, value.title, 512)
    out.writeInt(value.bpm)
    out.writeInt(value.offset)
    out.writeInt(value.span)
    out.writeInt(value.part)
    out.writeInt(value.ppq)
    out.writeInt(value.beats)
    out.writeInt(value.unit)
    out.writeInt(value.pitchMin)
    out.writeInt(value.visiblePitches)
    out.writeInt(value.snapDivisor)
    out.writeBoolean(value.followPlayback)
    out.writeInt(value.playheadMs)
    out.writeBoolean(value.allPartsView)
    out.writeInt(value.parts.size)
    value.parts.forEach { writeString(out, it, 512) }
    out.writeInt(value.tempoControls.size)
    value.tempoControls.forEach { point -> out.writeLong(point.tick); out.writeInt(point.bpm); out.writeByte(point.curve.ordinal) }
    out.writeInt(value.tempos.size)
    value.tempos.forEach { mark -> out.writeLong(mark.tick); out.writeInt(mark.timeMs); out.writeInt(mark.microsPerQuarter) }
    out.writeInt(value.signatures.size)
    value.signatures.forEach { mark -> out.writeLong(mark.tick); out.writeInt(mark.numerator); out.writeInt(mark.denominator) }
    out.writeInt(value.grid.size)
    value.grid.forEach { mark ->
      out.writeLong(mark.tick); out.writeInt(mark.timeMs); out.writeInt(mark.bar); out.writeInt(mark.beat); out.writeInt(mark.subdivision); out.writeBoolean(mark.isBar); out.writeBoolean(mark.isBeat)
    }
    out.writeInt(value.notes.size)
    value.notes.forEach { note ->
      out.writeInt(note.time); out.writeInt(note.duration); out.writeInt(note.instrument); out.writeInt(note.pitch); out.writeInt(note.pitchCents); out.writeInt(note.volume); out.writeInt(note.pan); out.writeInt(note.part)
      out.writeInt(note.sourceTrack); out.writeInt(note.sourceChannel); out.writeLong(note.sourceTick); out.writeLong(note.sourceDurationTicks)
      out.writeBoolean(note.retriggerOverride != null); note.retriggerOverride?.let { writeProfile(out, it) }
      out.writeBoolean(note.customSound != null); note.customSound?.let { writeString(out, it, 512); out.writeInt(note.customSoundPattern ?: 1) }
    }
    val selectedIndices = value.notes.indices.filter { value.notes[it].id in value.selectedIds }
    out.writeInt(selectedIndices.size); selectedIndices.forEach(out::writeInt)
    out.writeInt(value.selected.coerceIn(0, (value.notes.size - 1).coerceAtLeast(0)))
  }

  private fun readBody(input: DataInputStream, version: Int): EditorSnapshot {
    val title = readString(input, 512).take(120).ifBlank { "Untitled song" }
    val bpm = input.readInt().also { require(it in 1..60_000) { "Invalid BPM" } }
    val offset = input.readInt().also { require(it >= 0) { "Invalid timeline offset" } }
    val span = input.readInt().also { require(it in 2_000..Int.MAX_VALUE) { "Invalid timeline span" } }
    val activePart = input.readInt()
    val ppq = input.readInt().also { require(it in 1..32_767) { "Invalid PPQ" } }
    val beats = input.readInt().also { require(it in 1..32) { "Invalid time signature numerator" } }
    val unit = input.readInt().also { require(it in setOf(1, 2, 4, 8, 16, 32)) { "Invalid time signature denominator" } }
    val pitchMin = input.readInt().also { require(it in NoteBlockPitch.DISPLAY_MIN..NoteBlockPitch.DISPLAY_MAX) { "Invalid pitch viewport" } }
    val visiblePitches = input.readInt().also { require(it in 1..(NoteBlockPitch.DISPLAY_MAX - NoteBlockPitch.DISPLAY_MIN + 1)) { "Invalid visible pitch count" } }
    val snap = input.readInt().also { require(it in setOf(0, 4, 8, 16, 32, 64)) { "Invalid snap division" } }
    val follow = input.readBoolean()
    val playhead = input.readInt().also { require(it >= 0) { "Invalid playhead" } }
    val allParts = input.readBoolean()
    val partCount = readCount(input, 1, MAX_PARTS, "parts")
    val parts = List(partCount) { readString(input, 512).take(120).ifBlank { "Part ${it + 1}" } }
    require(activePart in parts.indices) { "Invalid active part" }
    val tempoControls = List(readCount(input, 1, MAX_TEMPO_POINTS, "tempo controls")) {
      TempoControlPoint(readTick(input), readBpm(input), readCurve(input))
    }
    val tempoMarks = List(readCount(input, 1, MAX_TIMING_MARKS, "tempo marks")) {
      TempoMark(readTick(input), input.readInt().also { require(it >= 0) }, input.readInt().also { require(it in 1..60_000_000) })
    }
    val signatures = List(readCount(input, 1, MAX_SIGNATURES, "signatures")) {
      SignatureMark(readTick(input), input.readInt().also { require(it in 1..32) }, input.readInt().also { require(it in setOf(1, 2, 4, 8, 16, 32)) })
    }
    val grid = List(readCount(input, 1, MAX_GRID_MARKS, "grid marks")) {
      GridMark(readTick(input), input.readInt().also { require(it >= 0) }, input.readInt().also { require(it >= 1) }, input.readInt().also { require(it in 1..32) }, input.readInt().also { require(it in 0..63) }, input.readBoolean(), input.readBoolean())
    }
    val noteCount = readCount(input, 0, MAX_NOTES, "notes")
    val notes = List(noteCount) {
      val note = EditorNote(
          time = input.readInt().also { require(it >= 0) { "Invalid note time" } },
          duration = input.readInt().also { require(it in 1..60_000) { "Invalid note duration" } },
          instrument = input.readInt().also { require(it in 0..15) { "Invalid note instrument" } },
          pitch = input.readInt().also { require(it in NoteBlockPitch.DISPLAY_MIN..NoteBlockPitch.DISPLAY_MAX) { "Invalid note pitch" } },
          pitchCents = if (version >= 2) input.readInt().also { require(it in -5400..7300) { "Invalid note pitch cents" } } else 0,
          volume = input.readInt().also { require(it in 0..100) { "Invalid note volume" } },
          pan = input.readInt().also { require(it in -100..100) { "Invalid note pan" } },
          part = input.readInt().also { require(it in parts.indices) { "Invalid note part" } },
          sourceTrack = input.readInt().also { require(it in -1..65_535) { "Invalid source track" } },
          sourceChannel = input.readInt().also { require(it in -1..15) { "Invalid source channel" } },
          sourceTick = readOptionalTick(input),
          sourceDurationTicks = readOptionalTick(input),
      )
      if (version == 1) note.pitchCents = note.pitch * 100
      if (input.readBoolean()) note.retriggerOverride = readProfile(input)
      if (input.readBoolean()) {
        note.customSound = readString(input, 512).also { require(SOUND_ID.matches(it)) { "Invalid Minecraft sound ID" } }
        note.customSoundPattern = input.readInt().also { require(it in 1..65_535) { "Invalid Minecraft sound pattern" } }
      }
      note
    }
    val selectedCount = readCount(input, 0, noteCount, "selected notes")
    val selectedIndices = LinkedHashSet<Int>()
    repeat(selectedCount) { selectedIndices += input.readInt().also { require(it in notes.indices) { "Invalid selected-note index" } } }
    require(selectedIndices.size == selectedCount) { "Duplicate selected-note index" }
    val primary = input.readInt().also { require(noteCount == 0 && it == 0 || it in notes.indices) { "Invalid primary note" } }
    return EditorSnapshot(
        notes = notes,
        selectedIds = selectedIndices.mapTo(linkedSetOf()) { notes[it].id },
        selected = primary,
        title = title,
        bpm = bpm,
        offset = offset,
        span = span,
        part = activePart,
        parts = parts,
        ppq = ppq,
        beats = beats,
        unit = unit,
        pitchMin = pitchMin,
        visiblePitches = visiblePitches,
        snapDivisor = snap,
        followPlayback = follow,
        playheadMs = playhead,
        allPartsView = allParts,
        tempos = tempoMarks,
        signatures = signatures,
        grid = grid,
        tempoControls = tempoControls,
    ).also(::validateSnapshot)
  }

  private fun writeProfile(out: DataOutputStream, value: RetriggerProfile) {
    val profile = value.normalized()
    out.writeBoolean(profile.enabled); out.writeInt(profile.thresholdMs); out.writeInt(profile.intervalMs); out.writeInt(profile.startVolumePercent); out.writeInt(profile.endVolumePercent)
    out.writeByte(profile.curve.ordinal); out.writeInt(profile.thresholdDivisor); out.writeInt(profile.intervalDivisor); out.writeInt(profile.middlePoints.size)
    profile.middlePoints.forEach { out.writeInt(it.positionPercent); out.writeInt(it.volumePercent) }
  }

  private fun readProfile(input: DataInputStream): RetriggerProfile = RetriggerProfile(
      enabled = input.readBoolean(),
      thresholdMs = input.readInt(),
      intervalMs = input.readInt(),
      startVolumePercent = input.readInt(),
      endVolumePercent = input.readInt(),
      curve = readCurve(input),
      thresholdDivisor = input.readInt(),
      intervalDivisor = input.readInt(),
      middlePoints = List(readCount(input, 0, 2, "release middle points")) { ReleaseControlPoint(input.readInt(), input.readInt()) },
  ).also { require(it == it.normalized()) { "Invalid pseudo-release profile" } }

  private fun validateSnapshot(value: EditorSnapshot) {
    require(value.notes.size <= MAX_NOTES && value.parts.size in 1..MAX_PARTS)
    require(value.tempoControls.size in 1..MAX_TEMPO_POINTS && value.tempos.size in 1..MAX_TIMING_MARKS)
    require(value.signatures.size in 1..MAX_SIGNATURES && value.grid.size in 1..MAX_GRID_MARKS)
    require(value.title.toByteArray(StandardCharsets.UTF_8).size <= 512 && value.parts.all { it.isNotBlank() && it.toByteArray(StandardCharsets.UTF_8).size <= 512 })
    require(value.bpm in 1..60_000 && value.offset >= 0 && value.span >= 2_000 && value.playheadMs >= 0)
    require(value.ppq in 1..32_767 && value.beats in 1..32 && value.unit in setOf(1, 2, 4, 8, 16, 32))
    require(value.pitchMin in NoteBlockPitch.DISPLAY_MIN..NoteBlockPitch.DISPLAY_MAX && value.visiblePitches in 1..(NoteBlockPitch.DISPLAY_MAX - NoteBlockPitch.DISPLAY_MIN + 1))
    require(value.pitchMin.toLong() + value.visiblePitches <= NoteBlockPitch.DISPLAY_MAX.toLong() + 1L)
    require(value.snapDivisor in setOf(0, 4, 8, 16, 32, 64))
    require(value.part in value.parts.indices && value.notes.all { it.part in value.parts.indices })
    require(value.notes.all { note ->
      note.time >= 0 && note.duration in 1..60_000 && note.instrument in 0..15 && note.pitch in NoteBlockPitch.DISPLAY_MIN..NoteBlockPitch.DISPLAY_MAX && note.pitchCents in -5400..7300 && note.volume in 0..100 && note.pan in -100..100 &&
          note.sourceTrack in -1..65_535 && note.sourceChannel in -1..15 && (note.sourceTick == -1L || note.sourceTick in 0..MAX_TICK) && (note.sourceDurationTicks == -1L || note.sourceDurationTicks in 0..MAX_TICK) &&
          (note.retriggerOverride == null || note.retriggerOverride == note.retriggerOverride?.normalized()) &&
          (note.customSound == null && note.customSoundPattern == null || note.customSound?.matches(SOUND_ID) == true && (note.customSoundPattern ?: 0) in 1..65_535)
    })
    require(value.tempoControls.all { it.tick in 0..MAX_TICK && it.bpm in 1..60_000 })
    require(value.tempos.all { it.tick in 0..MAX_TICK && it.timeMs >= 0 && it.microsPerQuarter in 1..60_000_000 })
    require(value.signatures.all { it.tick in 0..MAX_TICK && it.numerator in 1..32 && it.denominator in setOf(1, 2, 4, 8, 16, 32) })
    require(value.grid.all { it.tick in 0..MAX_TICK && it.timeMs >= 0 && it.bar >= 1 && it.beat in 1..32 && it.subdivision in 0..63 })
    require(value.selectedIds.all { id -> value.notes.any { it.id == id } })
    require(value.notes.isEmpty() && value.selected == 0 || value.selected in value.notes.indices)
    require(value.globalRetrigger == RetriggerProfile() && value.partRetriggers.isEmpty()) { "Only note-level pseudo-release is supported" }
  }

  private fun writeString(out: DataOutputStream, value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= maximumBytes) { "Text field exceeds $maximumBytes UTF-8 bytes" }
    out.writeInt(bytes.size); out.write(bytes)
  }

  private fun readString(input: DataInputStream, maximumBytes: Int): String {
    val size = input.readInt()
    require(size in 0..maximumBytes && size <= input.available()) { "Invalid text field length" }
    return input.readNBytes(size).toString(StandardCharsets.UTF_8)
  }

  private fun readCount(input: DataInputStream, minimum: Int, maximum: Int, label: String) =
      input.readInt().also { require(it in minimum..maximum) { "Invalid $label count" } }
  private fun readTick(input: DataInputStream) = input.readLong().also { require(it in 0..MAX_TICK) { "Invalid MIDI tick" } }
  private fun readOptionalTick(input: DataInputStream) = input.readLong().also { require(it == -1L || it in 0..MAX_TICK) { "Invalid optional MIDI tick" } }
  private fun readBpm(input: DataInputStream) = input.readInt().also { require(it in 1..60_000) { "Invalid tempo BPM" } }
  private fun readCurve(input: DataInputStream): AutomationCurve {
    val ordinal = input.readUnsignedByte()
    return AutomationCurve.entries.getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid automation curve")
  }

  private fun inflateBounded(compressed: ByteArray, expectedSize: Int): ByteArray =
      GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
        val output = ByteArrayOutputStream(expectedSize.coerceAtMost(1024 * 1024))
        val buffer = ByteArray(8192)
        while (true) {
          val read = gzip.read(buffer)
          if (read < 0) break
          require(output.size().toLong() + read <= expectedSize.toLong()) { "OMMT project expands beyond its declared size" }
          output.write(buffer, 0, read)
        }
        output.toByteArray().also { require(it.size == expectedSize) { "Truncated OMMT project body" } }
      }

  private const val MAX_TICK = 1_000_000_000_000L
  private val SOUND_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
}
