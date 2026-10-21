package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Ranking only: never changes the actual playback gain, pitch, pan or saved notes. */
object MusicVoicePolicy {
  @JvmStatic fun priority(volume: Float, ageMs: Long): Float =
      volume.coerceIn(0f, 1f) / (1f + ageMs.coerceAtLeast(0L) / 250f)

  @JvmStatic fun shouldReplace(incomingVolume: Float, existingPriority: Float): Boolean =
      incomingVolume > existingPriority + 0.001f

  fun <T> onsetOrder(notes: List<T>, time: (T) -> Int, volume: (T) -> Int): List<T> =
      notes.sortedWith(compareBy<T> { time(it) }.thenByDescending { volume(it) })
}
