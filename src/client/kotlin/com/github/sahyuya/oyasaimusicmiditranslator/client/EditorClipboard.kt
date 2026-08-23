package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Process-local normalized note clipboard; stable IDs are intentionally never copied. */
object EditorClipboard {
  data class Entry(val time: Int, val duration: Int, val instrument: Int, val pitch: Int, val volume: Int, val pan: Int, val part: Int, val sourceTrack: Int, val sourceChannel: Int, val sourceTick: Long, val sourceDurationTicks: Long, val retriggerOverride: RetriggerProfile?, val customSound: String?)
  private var entries: List<Entry> = emptyList()
  fun copy(notes: Collection<EditorNote>) { val origin = notes.minOfOrNull { it.time } ?: return; entries = notes.map { Entry(it.time-origin,it.duration,it.instrument,it.pitch,it.volume,it.pan,it.part,it.sourceTrack,it.sourceChannel,it.sourceTick,it.sourceDurationTicks,it.retriggerOverride,it.customSound) } }
  fun entries(): List<Entry> = entries
  fun clear() { entries = emptyList() }
}
