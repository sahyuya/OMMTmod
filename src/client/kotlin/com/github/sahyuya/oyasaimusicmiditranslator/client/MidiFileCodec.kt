package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.NoteBlockPitch
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bounded, deterministic MIDI importer shared by the editor and the pure regression verifier.
 *
 * Exact MIDI tempo events remain in [ImportResult.tempoMarks] and therefore continue to drive
 * note timing. Only [ImportResult.tempoControls] is simplified for interactive drawing/editing.
 */
object MidiFileCodec {
  const val MAX_FILE_BYTES = 64 * 1024 * 1024
  const val MAX_NOTES = 1_000_000
  private const val MAX_UI_TEMPO_POINTS_PER_DENSE_RUN = 64
  private const val MIN_DENSE_TEMPO_POINTS = 8

  data class ImportResult(
      val notes: List<EditorNote>,
      val ppq: Int,
      val tempoMarks: List<TempoMark>,
      val signatureMarks: List<SignatureMark>,
      val gridMarks: List<GridMark>,
      val tempoControls: List<TempoControlPoint>,
      val title: String,
      val trackHints: List<String>,
      val rawTempoPointCount: Int,
      val sustainExtendedNotes: Int,
  )

  private data class TimedEvent(val tick: Long, val track: Int, val order: Int, val event: MidiEvent)
  private data class NoteKey(val track: Int, val channel: Int, val pitch: Int)
  private data class ChannelKey(val track: Int, val channel: Int)
  private data class ChannelState(
      var program: Int = 0,
      var volume: Int = 127,
      var expression: Int = 127,
      var pan: Int = 64,
      var sustain: Boolean = false,
  )

  fun decode(bytes: ByteArray): ImportResult {
    require(bytes.size in 1..MAX_FILE_BYTES) { "MIDI file must be between 1 byte and 64 MiB" }
    return ByteArrayInputStream(bytes).use(MidiSystem::getSequence).let(::decode)
  }

  fun decode(sequence: Sequence): ImportResult {
    require(sequence.divisionType == Sequence.PPQ) { "SMPTE MIDI is unsupported; export as PPQ MIDI" }
    require(sequence.resolution > 0) { "MIDI PPQ resolution is zero" }
    val events = orderedEvents(sequence)
    val title = midiTitle(sequence)
    val trackHints = midiTrackHints(sequence)
    // Note conversion only needs exact tempo marks. Building a 1/64 ruler before we know the
    // audible note range can explode on MIDI files with a distant metadata/end-of-track event.
    val preliminaryTiming = buildTiming(sequence, events, 0, audibleEndTick = null, includeGrid = false)
    val parsed = parseNotes(sequence, events, preliminaryTiming.first, trackHints)
    require(parsed.first.size <= MAX_NOTES) { "This MIDI has more than $MAX_NOTES playable notes" }
    val notes = parsed.first.sortedWith(compareBy<EditorNote> { it.time }.thenBy { it.id })
    val audibleEndTick = notes.maxOfOrNull { it.sourceTick + it.sourceDurationTicks.coerceAtLeast(1) } ?: 1L
    val timing = buildTiming(sequence, events, notes.maxOfOrNull { it.time + it.duration } ?: 0, audibleEndTick, includeGrid = true)
    val controls = simplifyTempoControls(timing.first, sequence.resolution)
    return ImportResult(
        notes,
        sequence.resolution,
        timing.first,
        timing.second,
        timing.third,
        controls,
        title,
        trackHints,
        timing.first.size,
        parsed.second,
    )
  }

  /**
   * Converts dense tempo ramps to a bounded polyline while retaining sparse step changes.
   * Playback/export timing continues to use the unsimplified [TempoMark] list.
   */
  fun simplifyTempoControls(marks: List<TempoMark>, ppq: Int): List<TempoControlPoint> {
    require(ppq > 0) { "MIDI PPQ resolution is zero" }
    if (marks.isEmpty()) return listOf(TempoControlPoint(0, 120))
    val points = buildList<Pair<TempoMark, Int>> {
      marks.sortedBy { it.tick }.forEach { mark ->
        val bpm = (60_000_000.0 / mark.microsPerQuarter.coerceAtLeast(1)).roundToInt().coerceIn(1, 60_000)
        val value = mark to bpm
        when {
          isNotEmpty() && last().first.tick == mark.tick -> this[lastIndex] = value
          isNotEmpty() && last().second == bpm -> Unit
          else -> add(value)
        }
      }
    }
    if (points.size == 1) return listOf(TempoControlPoint(points[0].first.tick, points[0].second))

    data class UiPoint(val tick: Long, val bpm: Int, var linearFromPrevious: Boolean)
    val ui = mutableListOf<UiPoint>()
    fun append(point: Pair<TempoMark, Int>, linearFromPrevious: Boolean) {
      val existing = ui.lastOrNull()
      if (existing?.tick == point.first.tick) {
        existing.linearFromPrevious = existing.linearFromPrevious || linearFromPrevious
      } else {
        ui += UiPoint(point.first.tick, point.second, linearFromPrevious)
      }
    }

    // Imported tempo curves commonly place one event every fraction of a beat with slightly
    // irregular tick gaps. Up to two quarter notes still constitutes a dense automation run;
    // isolated musical tempo changes remain sparse and keep STEP semantics.
    val denseGap = (ppq * 2L).coerceAtLeast(1L)
    var start = 0
    while (start < points.size) {
      var end = start
      while (end + 1 < points.size && points[end + 1].first.tick - points[end].first.tick in 1..denseGap) end++
      if (end - start + 1 >= MIN_DENSE_TEMPO_POINTS) {
        val run = points.subList(start, end + 1)
        var tolerance = 1.0
        var retained = simplifyLinearRun(run, tolerance)
        while (retained.size > MAX_UI_TEMPO_POINTS_PER_DENSE_RUN) {
          tolerance *= 1.5
          retained = simplifyLinearRun(run, tolerance)
        }
        // EditorAutomation stores the curve on the point being approached.
        retained.forEachIndexed { index, point -> append(point, index > 0) }
      } else {
        for (index in start..end) append(points[index], false)
      }
      start = end + 1
    }
    return ui.map { TempoControlPoint(it.tick, it.bpm, if (it.linearFromPrevious) AutomationCurve.LINEAR else AutomationCurve.STEP) }
  }

  private fun simplifyLinearRun(points: List<Pair<TempoMark, Int>>, toleranceBpm: Double): List<Pair<TempoMark, Int>> {
    if (points.size <= 2) return points.toList()
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.lastIndex)
    while (stack.isNotEmpty()) {
      val (start, end) = stack.removeLast()
      val startTick = points[start].first.tick
      val endTick = points[end].first.tick
      if (endTick <= startTick + 1L) continue
      val startBpm = points[start].second.toDouble()
      val endBpm = points[end].second.toDouble()
      var maximumError = -1.0
      var maximumIndex = -1
      for (index in start + 1 until end) {
        val fraction = (points[index].first.tick - startTick).toDouble() / (endTick - startTick).toDouble()
        val expected = startBpm + (endBpm - startBpm) * fraction
        val error = abs(points[index].second - expected)
        if (error > maximumError) {
          maximumError = error
          maximumIndex = index
        }
      }
      if (maximumIndex >= 0 && maximumError > toleranceBpm) {
        keep[maximumIndex] = true
        stack.addLast(start to maximumIndex)
        stack.addLast(maximumIndex to end)
      }
    }
    return points.filterIndexed { index, _ -> keep[index] }
  }

  private fun parseNotes(
      sequence: Sequence,
      events: List<TimedEvent>,
      tempos: List<TempoMark>,
      trackHints: List<String>,
  ): Pair<List<EditorNote>, Int> {
    fun millisecondsAt(target: Long): Int {
      val point = tempos.lastOrNull { it.tick <= target } ?: tempos.first()
      return (point.timeMs + (target - point.tick) * point.microsPerQuarter.toDouble() / sequence.resolution / 1000.0)
          .roundToInt().coerceAtLeast(0)
    }
    val states = Array(sequence.tracks.size) { Array(16) { ChannelState() } }
    val converted = ArrayList<EditorNote>()
    val active = mutableMapOf<NoteKey, ArrayDeque<EditorNote>>()
    val sustained = mutableMapOf<ChannelKey, MutableList<EditorNote>>()
    var sustainExtended = 0

    fun finish(note: EditorNote, endTick: Long) {
      val endMs = millisecondsAt(endTick)
      note.duration = (endMs - note.time).coerceIn(1, 60_000)
      note.sourceDurationTicks = (endTick - note.sourceTick).coerceAtLeast(1)
    }
    fun releaseSustained(channel: ChannelKey, tick: Long) {
      sustained.remove(channel)?.forEach { finish(it, tick) }
    }
    fun releaseActive(channel: ChannelKey, tick: Long, respectSustain: Boolean) {
      active.keys.filter { it.track == channel.track && it.channel == channel.channel }.toList().forEach { key ->
        val queue = active.remove(key) ?: return@forEach
        while (queue.isNotEmpty()) {
          val note = queue.removeFirst()
          if (respectSustain && states[channel.track][channel.channel].sustain) {
            sustained.getOrPut(channel) { mutableListOf() } += note
            sustainExtended++
          } else finish(note, tick)
        }
      }
    }

    events.forEach { timed ->
      val message = timed.event.message as? ShortMessage ?: return@forEach
      val state = states[timed.track][message.channel]
      val channel = ChannelKey(timed.track, message.channel)
      when (message.command) {
        ShortMessage.PROGRAM_CHANGE -> state.program = message.data1
        ShortMessage.CONTROL_CHANGE -> when (message.data1) {
          7 -> state.volume = message.data2
          10 -> state.pan = message.data2
          11 -> state.expression = message.data2
          64 -> {
            val enabled = message.data2 >= 64
            if (state.sustain && !enabled) releaseSustained(channel, timed.tick)
            state.sustain = enabled
          }
          120 -> {
            releaseActive(channel, timed.tick, respectSustain = false)
            releaseSustained(channel, timed.tick)
          }
          121 -> {
            releaseSustained(channel, timed.tick)
            state.volume = 127
            state.expression = 127
            state.pan = 64
            state.sustain = false
          }
          123 -> releaseActive(channel, timed.tick, respectSustain = true)
        }
        ShortMessage.NOTE_ON -> if (message.data2 > 0) {
          require(converted.size < MAX_NOTES) { "This MIDI has more than $MAX_NOTES playable notes" }
          val note = convertedNote(millisecondsAt(timed.tick), timed.tick, timed.track, message, state, trackHints.getOrElse(timed.track) { "" })
          converted += note
          active.getOrPut(NoteKey(timed.track, message.channel, message.data1)) { ArrayDeque() }.addLast(note)
        } else {
          val key = NoteKey(timed.track, message.channel, message.data1)
          val note = active[key]?.pollFirst()
          if (active[key]?.isEmpty() == true) active.remove(key)
          if (note != null) {
            if (state.sustain) {
              sustained.getOrPut(channel) { mutableListOf() } += note
              sustainExtended++
            } else finish(note, timed.tick)
          }
        }
        ShortMessage.NOTE_OFF -> {
          val key = NoteKey(timed.track, message.channel, message.data1)
          val note = active[key]?.pollFirst()
          if (active[key]?.isEmpty() == true) active.remove(key)
          if (note != null) {
            if (state.sustain) {
              sustained.getOrPut(channel) { mutableListOf() } += note
              sustainExtended++
            } else finish(note, timed.tick)
          }
        }
      }
    }
    val finalTick = sequence.tickLength.coerceAtLeast(1L)
    active.values.forEach { queue -> queue.forEach { finish(it, finalTick) } }
    sustained.values.forEach { notes -> notes.forEach { finish(it, finalTick) } }
    return converted to sustainExtended
  }

  private fun convertedNote(
      time: Int,
      tick: Long,
      track: Int,
      message: ShortMessage,
      state: ChannelState,
      trackHint: String,
  ): EditorNote {
    val drum = message.channel == 9
    val instrument = if (drum) MidiInstrumentMapper.drumInstrument(message.data1) else MidiInstrumentMapper.mapProgram(state.program, trackHint)
    val pitch = if (drum) MidiInstrumentMapper.drumPitch(message.data1) else NoteBlockPitch.fromMidiKey(message.data1)
    val volume = ((message.data2 / 127.0) * (state.volume / 127.0) * (state.expression / 127.0) * 100)
        .roundToInt().coerceIn(0, 100)
    val pan = (((state.pan - 64) / 63.0) * 100).roundToInt().coerceIn(-100, 100)
    return EditorNote(time, 120, instrument, pitch, volume, pan, sourceTrack = track, sourceChannel = message.channel, sourceTick = tick)
  }

  private fun buildTiming(
      sequence: Sequence,
      events: List<TimedEvent>,
      lastNoteMs: Int,
      audibleEndTick: Long?,
      includeGrid: Boolean,
  ): Triple<List<TempoMark>, List<SignatureMark>, List<GridMark>> {
    val rawTempo = mutableListOf<Pair<Long, Int>>()
    val rawSignature = mutableListOf<SignatureMark>()
    events.forEach { event ->
      val meta = event.event.message as? MetaMessage ?: return@forEach
      when {
        meta.type == 0x51 && meta.data.size == 3 -> rawTempo += event.tick to
            ((meta.data[0].toInt().and(255) shl 16) or (meta.data[1].toInt().and(255) shl 8) or meta.data[2].toInt().and(255))
        meta.type == 0x58 && meta.data.size >= 2 -> rawSignature += SignatureMark(
            event.tick,
            meta.data[0].toInt().and(255).coerceIn(1, 32),
            (1 shl meta.data[1].toInt().and(7)).coerceIn(1, 32),
        )
      }
    }
    fun normalizeTempo(values: List<Pair<Long, Int>>): List<Pair<Long, Int>> {
      val normalized = mutableListOf<Pair<Long, Int>>()
      values.sortedBy { it.first }.forEach { value ->
        if (normalized.lastOrNull()?.first == value.first) normalized[normalized.lastIndex] = value else normalized += value
      }
      return normalized
    }
    fun normalizeSignature(values: List<SignatureMark>): List<SignatureMark> {
      val normalized = mutableListOf<SignatureMark>()
      values.sortedBy { it.tick }.forEach { value ->
        if (normalized.lastOrNull()?.tick == value.tick) normalized[normalized.lastIndex] = value else normalized += value
      }
      return normalized
    }
    val normalizedTempo = normalizeTempo(listOf(0L to 500_000) + rawTempo)
    val signatures = normalizeSignature(listOf(SignatureMark(0, 4, 4)) + rawSignature)
    val tempos = mutableListOf<TempoMark>()
    var previousTick = 0L
    var previousMicros = 0.0
    var tempo = 500_000
    normalizedTempo.forEach { (tick, value) ->
      previousMicros += (tick - previousTick) * tempo.toDouble() / sequence.resolution
      previousTick = tick
      tempo = value
      val mark = TempoMark(tick, (previousMicros / 1000.0).roundToInt().coerceAtLeast(0), value)
      if (tempos.lastOrNull()?.tick == tick) tempos[tempos.lastIndex] = mark else tempos += mark
    }
    fun timeAt(tick: Long): Int {
      var index = tempos.indexOfLast { it.tick <= tick }
      if (index < 0) index = 0
      val point = tempos[index]
      return (point.timeMs + (tick - point.tick) * point.microsPerQuarter.toDouble() / sequence.resolution / 1000.0)
          .roundToInt().coerceAtLeast(0)
    }
    if (!includeGrid) return Triple(tempos, signatures, emptyList())
    val grid = mutableListOf<GridMark>()
    var bar = 1
    val songEndTick = (audibleEndTick ?: sequence.tickLength).coerceAtLeast(1L)
    val audibleSignatures = signatures.filter { it.tick <= songEndTick }.ifEmpty { listOf(SignatureMark(0, 4, 4)) }
    audibleSignatures.forEachIndexed { index, signature ->
      val endTick = audibleSignatures.getOrNull(index + 1)?.tick ?: songEndTick
      val segmentLength = (endTick - signature.tick).coerceAtLeast(0L)
      fun roundedRatio(indexValue: Long, numerator: Long, denominator: Long): Long =
          (indexValue * numerator + denominator / 2) / denominator
      val subdivisionOffsets = linkedSetOf<Long>()
      var subdivisionIndex = 0L
      while (true) {
        val offset = roundedRatio(subdivisionIndex, sequence.resolution.toLong(), 16)
        if (offset > segmentLength) break
        subdivisionOffsets += offset
        subdivisionIndex++
      }
      val beatOffsets = linkedSetOf<Long>()
      var beatIndex = 0L
      while (true) {
        val offset = roundedRatio(beatIndex, sequence.resolution.toLong() * 4L, signature.denominator.toLong())
        if (offset > segmentLength) break
        beatOffsets += offset
        beatIndex++
      }
      val barOffsets = linkedSetOf<Long>()
      var barIndex = 0L
      while (true) {
        val offset = roundedRatio(barIndex * signature.numerator.toLong(), sequence.resolution.toLong() * 4L, signature.denominator.toLong())
        if (offset > segmentLength) break
        barOffsets += offset
        barIndex++
      }
      val segmentTicks = linkedSetOf<Long>()
      subdivisionOffsets.forEach { segmentTicks += signature.tick + it }
      beatOffsets.forEach { segmentTicks += signature.tick + it }
      barOffsets.forEach { segmentTicks += signature.tick + it }
      val orderedSubdivisions = subdivisionOffsets.toList()
      val orderedBeats = beatOffsets.toList()
      var subdivisionCursor = 0
      var beatCursor = 0
      segmentTicks.filter { it <= endTick }.sorted().forEach { gridTick ->
        val offset = gridTick - signature.tick
        val isBar = offset in barOffsets
        val isBeat = offset in beatOffsets
        if (isBar && gridTick != signature.tick) bar++
        while (subdivisionCursor + 1 < orderedSubdivisions.size && orderedSubdivisions[subdivisionCursor + 1] <= offset) subdivisionCursor++
        while (beatCursor + 1 < orderedBeats.size && orderedBeats[beatCursor + 1] <= offset) beatCursor++
        val beat = beatCursor.coerceAtLeast(0) % signature.numerator + 1
        val subdivision = subdivisionCursor.coerceAtLeast(0) % 16
        grid += GridMark(gridTick, timeAt(gridTick), bar, beat, subdivision, isBar, isBeat)
      }
      if (grid.lastOrNull()?.tick != endTick && endTick == songEndTick) {
        grid += GridMark(endTick, timeAt(endTick), bar, signature.numerator, 0, false, true)
      }
      if (index + 1 < audibleSignatures.size) bar++
    }
    val extensionOrigin = grid.lastOrNull()?.tick ?: songEndTick
    var extensionIndex = 0L
    var finalSubdivision = ((grid.lastOrNull()?.subdivision ?: -1) + 1) % 16
    while ((grid.lastOrNull()?.timeMs ?: 0) < lastNoteMs) {
      extensionIndex++
      val finalTick = extensionOrigin + ((extensionIndex * sequence.resolution.toLong() + 8L) / 16L).coerceAtLeast(extensionIndex)
      grid += GridMark(finalTick, timeAt(finalTick), bar, 1, finalSubdivision, false, false)
      finalSubdivision = (finalSubdivision + 1) % 16
    }
    return Triple(tempos, signatures, grid.distinctBy { it.tick }.sortedBy { it.tick })
  }

  private fun orderedEvents(sequence: Sequence): List<TimedEvent> = sequence.tracks.flatMapIndexed { trackIndex, track ->
    (0 until track.size()).map { index -> TimedEvent(track.get(index).tick, trackIndex, index, track.get(index)) }
  }.sortedWith(compareBy<TimedEvent> { it.tick }.thenBy { it.track }.thenBy { it.order })

  private fun midiTitle(sequence: Sequence): String = sequence.tracks.asSequence().flatMap { track ->
    (0 until track.size()).asSequence().map { track.get(it).message }
  }.filterIsInstance<MetaMessage>().firstOrNull { it.type == 0x03 }?.data?.let(MidiInstrumentMapper::decodeText).orEmpty()

  private fun midiTrackHints(sequence: Sequence): List<String> = sequence.tracks.map { track ->
    (0 until track.size()).asSequence()
        .mapNotNull { track.get(it).message as? MetaMessage }
        .filter { it.type == 0x03 || it.type == 0x04 }
        .map { MidiInstrumentMapper.decodeText(it.data) }
        .filter(String::isNotBlank)
        .distinct()
        .take(4)
        .toList()
        .joinToString(" / ")
  }
}
