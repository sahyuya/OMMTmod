package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Bound work, including silent notes, without discarding a dense chord or duplicating a cursor. */
inline fun playbackBatchEnd(cursor: Int, size: Int, elapsedMs: Int, budget: Int = 256, timeAt: (Int) -> Int): Int {
  var end = cursor
  val limit = (cursor.toLong() + budget.coerceAtLeast(0)).coerceAtMost(size.toLong()).toInt()
  while (end < limit && timeAt(end) <= elapsedMs) end++
  return end
}
