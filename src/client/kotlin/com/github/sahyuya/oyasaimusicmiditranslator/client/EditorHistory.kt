package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Bounded structural snapshots: 50 entries and at most 32MiB of approximate note payload. */
class EditorHistory {
  data class State(val notes: List<EditorNote>, val selected: Set<Long>, val primary: Long?, val title: String, val bpm: Int, val parts: List<String>, val activePart: Int, val tempos: List<TempoControlPoint> = emptyList(), val globalRetrigger: RetriggerProfile = RetriggerProfile(), val partRetriggers: Map<Int, RetriggerProfile> = emptyMap())
  private val undo = ArrayDeque<State>(); private val redo = ArrayDeque<State>()
  private fun clone(state: State) = state.copy(notes = state.notes.map { it.copy() }, selected = state.selected.toSet(), parts = state.parts.toList(), tempos = state.tempos.map { it.copy() }, partRetriggers = state.partRetriggers.toMap())
  private fun size(state: State) = state.notes.size * 48 + state.selected.size * 8 + state.title.length * 2 + state.parts.sumOf { it.length * 2 + 8 } + 48
  private fun totalBytes() = undo.sumOf(::size) + redo.sumOf(::size)
  private fun trim() { while (undo.size + redo.size > 50 || totalBytes() > 32*1024*1024) { if (undo.isNotEmpty()) undo.removeFirst() else redo.removeFirst() } }
  fun push(state: State) { undo.addLast(clone(state)); redo.clear(); trim() }
  fun undo(current: State): State? { val target=undo.removeLastOrNull()?:return null; redo.addLast(clone(current)); trim(); return clone(target) }
  fun redo(current: State): State? { val target=redo.removeLastOrNull()?:return null; undo.addLast(clone(current)); trim(); return clone(target) }
  fun clear() { undo.clear(); redo.clear() }

  companion object {
    /** IDs identify handles, not musical timing. Only values that affect the compiled grid matter. */
    fun hasSameTempoLayout(left: List<TempoControlPoint>, right: List<TempoControlPoint>): Boolean =
        left.size == right.size && left.indices.all { index ->
          val a = left[index]
          val b = right[index]
          a.tick == b.tick && a.bpm == b.bpm && a.curve == b.curve
        }
  }
}
