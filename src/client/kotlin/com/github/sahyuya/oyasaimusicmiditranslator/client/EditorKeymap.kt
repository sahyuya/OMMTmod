package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeys

data class EditorKeyStroke(
    val key: Int,
    val control: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
  fun matches(key: Int, control: Boolean, shift: Boolean, alt: Boolean) =
      this.key >= 0 && this.key == key && this.control == control && this.shift == shift && this.alt == alt

  fun encode(): String = if (key < 0) "UNBOUND" else buildList {
    if (control) add("CTRL")
    if (shift) add("SHIFT")
    if (alt) add("ALT")
    add(keyName(key))
  }.joinToString("+")

  companion object {
    val UNBOUND = EditorKeyStroke(-1)

    fun parse(value: String, fallback: EditorKeyStroke): EditorKeyStroke {
      if (value.equals("UNBOUND", true)) return UNBOUND
      val tokens = value.uppercase().split('+').filter(String::isNotBlank)
      val key = tokens.lastOrNull()?.let(::keyCode) ?: return fallback
      return EditorKeyStroke(key, "CTRL" in tokens, "SHIFT" in tokens, "ALT" in tokens)
    }

    private fun keyName(key: Int): String = when (key) {
      EditorKeys.KEY_SPACE -> "SPACE"
      EditorKeys.KEY_HOME -> "HOME"
      EditorKeys.KEY_DELETE -> "DELETE"
      EditorKeys.KEY_BACKSPACE -> "BACKSPACE"
      EditorKeys.KEY_ESCAPE -> "ESCAPE"
      EditorKeys.KEY_ENTER -> "ENTER"
      EditorKeys.KEY_TAB -> "TAB"
      EditorKeys.KEY_EQUAL -> "EQUAL"
      EditorKeys.KEY_MINUS -> "MINUS"
      EditorKeys.KEY_LEFT_BRACKET -> "LEFT_BRACKET"
      EditorKeys.KEY_RIGHT_BRACKET -> "RIGHT_BRACKET"
      EditorKeys.KEY_BACKSLASH -> "BACKSLASH"
      EditorKeys.KEY_COMMA -> "COMMA"
      EditorKeys.KEY_PERIOD -> "PERIOD"
      in EditorKeys.KEY_A..EditorKeys.KEY_Z, in EditorKeys.KEY_0..EditorKeys.KEY_9 -> key.toChar().toString()
      else -> "KEY_$key"
    }

    private fun keyCode(name: String): Int? = when (name) {
      "SPACE" -> EditorKeys.KEY_SPACE
      "HOME" -> EditorKeys.KEY_HOME
      "DELETE" -> EditorKeys.KEY_DELETE
      "BACKSPACE" -> EditorKeys.KEY_BACKSPACE
      "ESCAPE" -> EditorKeys.KEY_ESCAPE
      "ENTER" -> EditorKeys.KEY_ENTER
      "TAB" -> EditorKeys.KEY_TAB
      "EQUAL", "+" -> EditorKeys.KEY_EQUAL
      "MINUS", "-" -> EditorKeys.KEY_MINUS
      "LEFT_BRACKET" -> EditorKeys.KEY_LEFT_BRACKET
      "RIGHT_BRACKET" -> EditorKeys.KEY_RIGHT_BRACKET
      "BACKSLASH" -> EditorKeys.KEY_BACKSLASH
      "COMMA" -> EditorKeys.KEY_COMMA
      "PERIOD" -> EditorKeys.KEY_PERIOD
      else -> if (name.length == 1 && name[0] in 'A'..'Z' || name.length == 1 && name[0] in '0'..'9') name[0].code else name.removePrefix("KEY_").toIntOrNull()
    }
  }
}

enum class EditorAction(val english: String, val japanese: String, val default: EditorKeyStroke) {
  PLAY_PAUSE("Play / Pause", "再生 / 一時停止", EditorKeyStroke(EditorKeys.KEY_SPACE)),
  REWIND("Return to start", "先頭へ戻る", EditorKeyStroke(EditorKeys.KEY_HOME)),
  FIT("Fit song", "曲全体を表示", EditorKeyStroke(EditorKeys.KEY_F)),
  ZOOM_IN("Time zoom in", "時間方向を拡大", EditorKeyStroke(EditorKeys.KEY_EQUAL, control = true)),
  ZOOM_OUT("Time zoom out", "時間方向を縮小", EditorKeyStroke(EditorKeys.KEY_MINUS, control = true)),
  COPY("Copy", "コピー", EditorKeyStroke(EditorKeys.KEY_C, control = true)),
  CUT("Cut", "切り取り", EditorKeyStroke(EditorKeys.KEY_X, control = true)),
  PASTE("Paste", "貼り付け", EditorKeyStroke(EditorKeys.KEY_V, control = true)),
  DUPLICATE("Duplicate", "複製", EditorKeyStroke(EditorKeys.KEY_D, control = true)),
  UNDO("Undo", "元に戻す", EditorKeyStroke(EditorKeys.KEY_Z, control = true)),
  REDO("Redo", "やり直し", EditorKeyStroke(EditorKeys.KEY_Y, control = true)),
  DELETE("Delete", "削除", EditorKeyStroke(EditorKeys.KEY_DELETE)),
  SELECT_ALL("Select all in view", "表示中を全選択", EditorKeyStroke(EditorKeys.KEY_A, control = true)),
  SNAP_CYCLE("Cycle snap", "スナップ切替", EditorKeyStroke(EditorKeys.KEY_S)),
  FOLLOW_TOGGLE("Toggle follow", "追従切替", EditorKeyStroke(EditorKeys.KEY_L)),
  SETTINGS("Editor settings", "エディター設定", EditorKeyStroke(EditorKeys.KEY_COMMA, control = true)),
  PREVIOUS_PART("Previous part", "前のパート", EditorKeyStroke(EditorKeys.KEY_LEFT_BRACKET)),
  NEXT_PART("Next part", "次のパート", EditorKeyStroke(EditorKeys.KEY_RIGHT_BRACKET)),
  ALL_PARTS("All-parts view", "全パート表示", EditorKeyStroke(EditorKeys.KEY_BACKSLASH)),
  PREVIEW("Preview selected", "選択音の試聴", EditorKeyStroke(EditorKeys.KEY_P)),
  NEW_PART("Create part", "パート作成", EditorKeyStroke.UNBOUND),
  LOAD_SELECTED("Load selected MIDI", "選択MIDIを読込", EditorKeyStroke.UNBOUND),
  UPLOAD_DRAFT("Upload draft", "下書きを送信", EditorKeyStroke.UNBOUND),
  REFRESH_LIBRARY("Refresh library", "ライブラリ更新", EditorKeyStroke.UNBOUND),
  OPEN_MIDI_FOLDER("Open MIDI folder", "MIDIフォルダーを開く", EditorKeyStroke.UNBOUND),
  // Keep last: the share bundle stores bindings positionally, so appending preserves old slots.
  CONFIRM("Confirm / Apply", "確定・適用", EditorKeyStroke(EditorKeys.KEY_ENTER)),
}

data class EditorKeymap(val bindings: Map<EditorAction, EditorKeyStroke> = EditorAction.entries.associateWith { it.default }) {
  operator fun get(action: EditorAction) = bindings[action] ?: action.default
  fun with(action: EditorAction, stroke: EditorKeyStroke) = copy(bindings = bindings + (action to stroke))
  fun matching(key: Int, control: Boolean, shift: Boolean, alt: Boolean) = EditorAction.entries.firstOrNull { this[it].matches(key, control, shift, alt) }
}

enum class WheelAction(val english: String, val japanese: String) {
  TIMELINE_SCROLL("Timeline scroll", "タイムライン移動"),
  TIME_ZOOM("Time zoom", "時間方向拡大縮小"),
  PITCH_ZOOM("Pitch zoom", "音高方向拡大縮小"),
  PITCH_SCROLL("Pitch scroll", "音高方向移動"),
  NONE("Disabled", "無効"),
}

enum class GestureModifier(val english: String, val japanese: String) { NONE("None", "なし"), SHIFT("Shift", "Shift"), CONTROL("Ctrl", "Ctrl"), ALT("Alt", "Alt") }
enum class PanMouseButton(val english: String, val japanese: String) { RIGHT("Right mouse", "右クリック"), MIDDLE("Middle mouse", "中クリック") }
