package com.github.sahyuya.oyasaimusicmiditranslator.client

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class AutomationCurve { STEP, LINEAR, SMOOTH }

data class TempoControlPoint(
    var tick: Long,
    var bpm: Int,
    var curve: AutomationCurve = AutomationCurve.STEP,
    val id: Long = EditorSession.nextStableId(),
)

data class ReleaseControlPoint(
    val positionPercent: Int,
    val volumePercent: Int,
)

data class RetriggerProfile(
    val enabled: Boolean = false,
    val thresholdMs: Int = 500,
    val intervalMs: Int = 125,
    val startVolumePercent: Int = 100,
    val endVolumePercent: Int = 55,
    val curve: AutomationCurve = AutomationCurve.SMOOTH,
    /** 0 means milliseconds; otherwise the denominator of a whole-note fraction. */
    val thresholdDivisor: Int = 0,
    /** 0 means milliseconds; otherwise the denominator of a whole-note fraction. */
    val intervalDivisor: Int = 0,
    /** Zero to two editable points between the fixed 0% and 100% endpoints. */
    val middlePoints: List<ReleaseControlPoint> = emptyList(),
) {
  fun normalized() = copy(
      thresholdMs = thresholdMs.coerceIn(50, 60_000),
      intervalMs = intervalMs.coerceIn(25, 10_000),
      startVolumePercent = startVolumePercent.coerceIn(0, 100),
      endVolumePercent = endVolumePercent.coerceIn(0, 100),
      thresholdDivisor = thresholdDivisor.takeIf { it in THRESHOLD_DIVISORS } ?: 0,
      intervalDivisor = intervalDivisor.takeIf { it in INTERVAL_DIVISORS } ?: 0,
      middlePoints = middlePoints
          .map { ReleaseControlPoint(it.positionPercent.coerceIn(1, 99), it.volumePercent.coerceIn(0, 100)) }
          .sortedBy { it.positionPercent }
          .distinctBy { it.positionPercent }
          .take(2),
  )

  companion object {
    val THRESHOLD_DIVISORS = setOf(0, 1, 2, 4, 8, 16, 32, 64)
    val INTERVAL_DIVISORS = THRESHOLD_DIVISORS + 128
  }
}

data class RenderedNoteEvent(
    val time: Int,
    val instrument: Int,
    val pitch: Int,
    val volume: Int,
    val pan: Int,
    val customSound: String? = null,
    val customSoundPattern: Int? = null,
)

object EditorAutomation {
  fun timeAtTick(tick: Long, marks: List<TempoMark>, ppq: Int): Int {
    val safeMarks = marks.ifEmpty { listOf(TempoMark(0, 0, 500_000)) }
    val point = safeMarks.lastOrNull { it.tick <= tick } ?: safeMarks.first()
    return (point.timeMs + (tick - point.tick) * point.microsPerQuarter.toDouble() / ppq.coerceAtLeast(1) / 1000.0)
        .roundToInt().coerceAtLeast(0)
  }

  fun tickAtTime(timeMs: Int, marks: List<TempoMark>, ppq: Int): Long {
    val safeMarks = marks.ifEmpty { listOf(TempoMark(0, 0, 500_000)) }
    val point = safeMarks.lastOrNull { it.timeMs <= timeMs } ?: safeMarks.first()
    return (point.tick + (timeMs - point.timeMs).coerceAtLeast(0) * ppq.coerceAtLeast(1).toDouble() * 1000.0 / point.microsPerQuarter.coerceAtLeast(1))
        .toLong().coerceAtLeast(0L)
  }

  /** Compile STEP/LINEAR/SMOOTH controls into bounded, piecewise-constant MIDI tempo marks. */
  fun compileTempo(points: List<TempoControlPoint>, ppq: Int): List<TempoMark> {
    val normalized = points
        .map { it.copy(tick = it.tick.coerceAtLeast(0), bpm = it.bpm.coerceIn(1, 60_000)) }
        .sortedWith(compareBy<TempoControlPoint> { it.tick }.thenBy { it.id })
        .fold(mutableListOf<TempoControlPoint>()) { result, point ->
          if (result.lastOrNull()?.tick == point.tick) result[result.lastIndex] = point else result += point
          result
        }
        .let { if (it.firstOrNull()?.tick == 0L) it else (mutableListOf(TempoControlPoint(0, 120)) + it) }
    val sampled = mutableListOf(normalized.first().tick to normalized.first().bpm)
    normalized.zipWithNext().forEach { (previous, point) ->
      // The curve belongs to the point being approached. This matches what an editor user expects
      // after adding a LINEAR/SMOOTH point: the ramp leads into that point instead of beginning
      // after it. STEP therefore changes exactly at point.tick.
      if (point.curve == AutomationCurve.STEP || point.tick <= previous.tick) {
        sampled += point.tick to point.bpm
      } else {
        // A sixteenth-note sampling interval made audible tempo stairs. 1/128-note targets plus a
        // bounded sample count stay smooth without allowing a multi-hour MIDI to allocate freely.
        val idealStep = (ppq.coerceAtLeast(1) / 32).coerceAtLeast(1).toLong()
        val samples = ceil((point.tick - previous.tick).toDouble() / idealStep).toInt().coerceIn(1, 8_192)
        for (sample in 1..samples) {
          val fraction = sample.toDouble() / samples
          val shaped = shape(point.curve, fraction)
          val tick = previous.tick + ((point.tick - previous.tick) * fraction).roundToLong()
          val bpm = (previous.bpm + (point.bpm - previous.bpm) * shaped).roundToInt().coerceIn(1, 60_000)
          sampled += tick to bpm
        }
      }
    }
    val unique = sampled.sortedBy { it.first }.fold(mutableListOf<Pair<Long, Int>>()) { result, value ->
      if (result.lastOrNull()?.first == value.first) result[result.lastIndex] = value else result += value
      result
    }
    val marks = mutableListOf<TempoMark>()
    var previousTick = 0L
    var previousMicros = 0.0
    var previousTempo = 60_000_000 / unique.first().second.coerceAtLeast(1)
    unique.forEach { (tick, bpm) ->
      previousMicros += (tick - previousTick) * previousTempo.toDouble() / ppq.coerceAtLeast(1)
      val micros = 60_000_000 / bpm.coerceAtLeast(1)
      marks += TempoMark(tick, (previousMicros / 1000.0).roundToInt().coerceAtLeast(0), micros)
      previousTick = tick
      previousTempo = micros
    }
    return marks
  }

  fun retimeNotes(notes: Collection<EditorNote>, oldMarks: List<TempoMark>, newMarks: List<TempoMark>, ppq: Int) {
    notes.forEach { note ->
      val startTick = note.sourceTick.takeIf { it >= 0 } ?: tickAtTime(note.time, oldMarks, ppq)
      val endTick = if (note.sourceDurationTicks >= 0) startTick + note.sourceDurationTicks else tickAtTime(note.time + note.duration, oldMarks, ppq)
      note.sourceTick = startTick
      note.sourceDurationTicks = (endTick - startTick).coerceAtLeast(1)
      val startMs = timeAtTick(startTick, newMarks, ppq)
      val endMs = timeAtTick(startTick + note.sourceDurationTicks, newMarks, ppq)
      note.time = startMs
      note.duration = (endMs - startMs).coerceIn(1, 60_000)
    }
  }

  fun expand(
      notes: Collection<EditorNote>,
      global: RetriggerProfile,
      parts: Map<Int, RetriggerProfile>,
      maxEvents: Int = 100_000,
      tempoMarks: List<TempoMark> = listOf(TempoMark(0, 0, 500_000)),
      ppq: Int = 480,
  ): List<RenderedNoteEvent> {
    val result = ArrayList<RenderedNoteEvent>(notes.size.coerceAtMost(maxEvents))
    notes.sortedWith(compareBy<EditorNote> { it.time }.thenBy { it.id }).forEach { note ->
      val profile = (note.retriggerOverride ?: parts[note.part] ?: global).normalized()
      val offsets = repeatOffsets(note, profile, tempoMarks, ppq)
      require(result.size + offsets.size <= maxEvents) { "Pseudo-release expands beyond $maxEvents events" }
      val lastOffset = offsets.lastOrNull()?.coerceAtLeast(1) ?: 1
      offsets.forEach { offset ->
        // The visible endpoint represents the last emitted strike, not the silent end of the
        // source note after it. This guarantees the edited end-volume is actually audible.
        val fraction = if (offsets.size <= 1) 0.0 else offset.toDouble() / lastOffset
        val scale = releaseEnvelope(profile, fraction)
        result += RenderedNoteEvent(
            note.time + offset,
            note.instrument,
            note.pitch,
            (note.volume * scale / 100.0).roundToInt().coerceIn(0, 100),
            note.pan,
            note.customSound,
            note.customSoundPattern,
        )
      }
    }
    return result.sortedWith(compareBy<RenderedNoteEvent> { it.time }.thenBy { it.instrument }.thenBy { it.pitch })
  }

  fun releaseEnvelope(profileInput: RetriggerProfile, fractionInput: Double): Double {
    val profile = profileInput.normalized()
    val fraction = fractionInput.coerceIn(0.0, 1.0)
    val points = buildList {
      add(ReleaseControlPoint(0, profile.startVolumePercent))
      addAll(profile.middlePoints)
      add(ReleaseControlPoint(100, profile.endVolumePercent))
    }
    val position = fraction * 100.0
    val rightIndex = points.indexOfFirst { it.positionPercent >= position }.let { if (it < 0) points.lastIndex else it }
    if (rightIndex == 0) return points.first().volumePercent.toDouble()
    val leftIndex = rightIndex - 1
    val left = points[leftIndex]
    val right = points[rightIndex]
    val local = ((position - left.positionPercent) / (right.positionPercent - left.positionPercent).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    return when (profile.curve) {
      AutomationCurve.STEP -> left.volumePercent.toDouble()
      AutomationCurve.LINEAR -> left.volumePercent + (right.volumePercent - left.volumePercent) * local
      AutomationCurve.SMOOTH -> {
        val before = points.getOrElse(leftIndex - 1) { left }
        val after = points.getOrElse(rightIndex + 1) { right }
        catmullRom(before.volumePercent.toDouble(), left.volumePercent.toDouble(), right.volumePercent.toDouble(), after.volumePercent.toDouble(), local)
            .coerceIn(0.0, 100.0)
      }
    }
  }

  private fun repeatOffsets(note: EditorNote, profile: RetriggerProfile, marks: List<TempoMark>, ppq: Int): List<Int> {
    if (!profile.enabled) return listOf(0)
    val safePpq = ppq.coerceAtLeast(1)
    val startTick = note.sourceTick.takeIf { it >= 0 } ?: tickAtTime(note.time, marks, safePpq)
    val durationTicks = note.sourceDurationTicks.takeIf { it > 0 }
        ?: (tickAtTime(note.time + note.duration, marks, safePpq) - startTick).coerceAtLeast(1)
    val thresholdReached = if (profile.thresholdDivisor > 0) {
      durationTicks >= divisionTicks(safePpq, profile.thresholdDivisor, 1)
    } else note.duration >= profile.thresholdMs
    if (!thresholdReached) return listOf(0)
    if (profile.intervalDivisor <= 0) {
      return buildList {
        var offset = 0
        while (offset < note.duration) {
          add(offset)
          if (size >= 100_000 || Int.MAX_VALUE - offset < profile.intervalMs) break
          offset += profile.intervalMs
        }
      }.ifEmpty { listOf(0) }
    }
    val baseTime = timeAtTick(startTick, marks, safePpq)
    return buildList {
      var index = 0
      var previousTickOffset = -1L
      while (size < 100_000) {
        val tickOffset = divisionTicks(safePpq, profile.intervalDivisor, index)
        if (tickOffset >= durationTicks) break
        if (tickOffset > previousTickOffset) {
          add((timeAtTick(startTick + tickOffset, marks, safePpq) - baseTime).coerceAtLeast(0))
          previousTickOffset = tickOffset
        }
        index++
      }
    }.ifEmpty { listOf(0) }
  }

  private fun divisionTicks(ppq: Int, divisor: Int, multiplier: Int): Long =
      (multiplier.toDouble() * ppq.coerceAtLeast(1) * 4.0 / divisor.coerceAtLeast(1)).roundToLong().coerceAtLeast(if (multiplier == 0) 0 else 1)

  private fun shape(curve: AutomationCurve, fraction: Double) = when (curve) {
    AutomationCurve.STEP -> 0.0
    AutomationCurve.LINEAR -> fraction
    AutomationCurve.SMOOTH -> fraction * fraction * (3.0 - 2.0 * fraction)
  }

  private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3)
  }
}
