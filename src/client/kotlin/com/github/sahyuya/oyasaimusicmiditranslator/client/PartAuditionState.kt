package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Preview-only controls; a group is a bulk edit of its leaves, never an extra override. */
class PartAuditionState {
  private val muted = mutableSetOf<Int>()
  private val soloed = mutableSetOf<Int>()
  fun muted(part: Int) = part in muted
  fun soloed(part: Int) = part in soloed
  fun audible(part: Int) = part !in muted && (soloed.isEmpty() || part in soloed)
  fun previewAudible(part: Int, visiblePartOnly: Boolean, allPartsView: Boolean, activePart: Int) =
      audible(part) && (!visiblePartOnly || allPartsView || part == activePart)
  fun visible(part: Int, opacityPercent: Int) = audible(part) || opacityPercent > 0
  fun toggleMute(parts: Collection<Int>) = toggle(muted, parts)
  fun toggleSolo(parts: Collection<Int>) = toggle(soloed, parts)
  private fun toggle(target: MutableSet<Int>, parts: Collection<Int>) {
    if (parts.isEmpty()) return
    if (parts.all { it in target }) target.removeAll(parts.toSet()) else target.addAll(parts)
  }
  fun retainParts(count: Int) { muted.removeAll { it !in 0 until count }; soloed.removeAll { it !in 0 until count } }
  fun reset() { muted.clear(); soloed.clear() }
  fun noteColor(color: Int, part: Int, opacityPercent: Int = 25): Int = if (audible(part)) color else
      (color and 0x00ffffff) or (((color ushr 24) * opacityPercent.coerceIn(0, 100) / 100) shl 24)

  fun <T> drawOrder(items: List<T>, part: (T) -> Int): List<T> =
      items.filter { !audible(part(it)) } + items.filter { audible(part(it)) }

  /** Call only with candidates inside the hit region; muted items remain editable alone. */
  fun <T> hit(items: List<T>, part: (T) -> Int, distance: (T) -> Double): T? =
      items.minWithOrNull(compareByDescending<T> { audible(part(it)) }.thenBy(distance))
}
