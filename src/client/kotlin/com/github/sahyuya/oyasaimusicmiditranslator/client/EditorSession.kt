package com.github.sahyuya.oyasaimusicmiditranslator.client

/**
 * Client-process editor state, deliberately independent from a Screen instance.  Closing the
 * screen stops preview and commits fields, but only an explicit MIDI load replaces this session.
 */
data class EditorNote(
    var time: Int,
    var duration: Int,
    var instrument: Int,
    /** Display pitch relative to MIDI F#3; playback/export octave-fold this into vanilla 0..24. */
    var pitch: Int,
    var volume: Int,
    var pan: Int,
    val id: Long = EditorSession.nextStableId(),
    var part: Int = 0,
)

data class EditorSnapshot(
    val notes: List<EditorNote>, val selectedIds: Set<Long>, val selected: Int,
    val title: String, val bpm: Int, val offset: Int, val span: Int,
    val part: Int, val parts: List<String>, val ppq: Int, val beats: Int, val unit: Int,
    val pitchMin: Int, val visiblePitches: Int,
    val snapDivisor: Int, val followPlayback: Boolean, val playheadMs: Int, val allPartsView: Boolean,
    val tempos: List<TempoMark>, val signatures: List<SignatureMark>, val grid: List<GridMark>,
)

data class TempoMark(val tick: Long, val timeMs: Int, val microsPerQuarter: Int)
data class SignatureMark(val tick: Long, val numerator: Int, val denominator: Int)
data class GridMark(val tick: Long, val timeMs: Int, val bar: Int, val beat: Int, val subdivision: Int, val isBar: Boolean, val isBeat: Boolean)

object EditorSession {
  private var nextId = 1L
  private var snapshot: EditorSnapshot? = null
  /** Process-lifetime editing auxiliaries survive closing/reopening a Screen. */
  val history = EditorHistory()
  fun nextStableId(): Long = nextId++
  fun restore(): EditorSnapshot? = snapshot?.copy(notes = snapshot!!.notes.map { it.copy() }, selectedIds = snapshot!!.selectedIds.toSet(), parts = snapshot!!.parts.toList(), tempos = snapshot!!.tempos.toList(), signatures = snapshot!!.signatures.toList(), grid = snapshot!!.grid.toList())
  fun save(value: EditorSnapshot) { snapshot = value.copy(notes = value.notes.map { it.copy() }, selectedIds = value.selectedIds.toSet(), parts = value.parts.toList(), tempos = value.tempos.toList(), signatures = value.signatures.toList(), grid = value.grid.toList()) }
  fun replace() { snapshot = null }
}
