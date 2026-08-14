package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Single source for editor geometry; keeps hit testing and rendering aligned at narrow scales. */
data class EditorLayout(val width: Int, val height: Int) {
  val libraryWidth = (width * .14).toInt().coerceIn(160, 300)
  val editorLeft = libraryWidth + 8
  val keyboardLeft = editorLeft + 12
  val plotLeft = keyboardLeft + 40
  val plotRight = width - 12
  val compact = width < 1060
  fun visible(label: String) = if (compact && label.length > 7) label.take(6) + "…" else label
}
