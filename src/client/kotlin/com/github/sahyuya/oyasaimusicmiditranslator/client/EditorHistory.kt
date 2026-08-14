package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Bounded structural snapshots: 50 entries and at most 32MiB of approximate note payload. */
class EditorHistory {
  data class State(val notes: List<EditorNote>, val selected: Set<Long>, val primary: Long?, val title: String, val bpm: Int, val parts: List<String>, val activePart: Int)
  private val undo = ArrayDeque<State>(); private val redo = ArrayDeque<State>()
  private fun size(state: State) = state.notes.size * 48 + state.selected.size * 8 + state.title.length * 2 + state.parts.sumOf { it.length * 2 + 8 } + 48
  private fun totalBytes() = undo.sumOf(::size) + redo.sumOf(::size)
  private fun trim() { while (undo.size + redo.size > 50 || totalBytes() > 32*1024*1024) { if (undo.isNotEmpty()) undo.removeFirst() else redo.removeFirst() } }
  fun push(state: State) { undo.addLast(state.copy(notes = state.notes.map { it.copy() }, selected = state.selected.toSet())); redo.clear(); trim() }
  fun undo(current: State): State? { val target=undo.removeLastOrNull()?:return null; redo.addLast(current); trim(); return target }
  fun redo(current: State): State? { val target=redo.removeLastOrNull()?:return null; undo.addLast(current); trim(); return target }
}
