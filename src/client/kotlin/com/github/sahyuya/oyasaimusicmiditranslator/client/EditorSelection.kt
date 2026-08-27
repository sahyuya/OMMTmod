package com.github.sahyuya.oyasaimusicmiditranslator.client

import kotlin.math.max
import kotlin.math.min

/** Pure selection geometry shared by the ImGui piano roll and model verification. */
object EditorSelection {
  /**
   * The marquee's right edge is exclusive. A note beginning exactly on the visible right border
   * must not be selected, because its rendered body lies outside the box. Notes already crossing
   * the left edge still count as intersecting, matching normal DAW marquee behaviour.
   */
  fun intersectsMarquee(
      noteTime: Int,
      noteDuration: Int,
      notePitch: Int,
      firstTime: Int,
      secondTime: Int,
      firstPitch: Int,
      secondPitch: Int,
  ): Boolean {
    val left = min(firstTime, secondTime)
    val right = max(firstTime, secondTime)
    val lowPitch = min(firstPitch, secondPitch)
    val highPitch = max(firstPitch, secondPitch)
    if (notePitch !in lowPitch..highPitch) return false
    val noteEnd = noteTime.toLong() + noteDuration.coerceAtLeast(1).toLong()
    return if (left == right) noteTime == left else noteTime < right && noteEnd > left.toLong()
  }
}
