package com.github.sahyuya.oyasaimusicmiditranslator.client

import org.lwjgl.glfw.GLFW

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
      GLFW.GLFW_KEY_SPACE -> "SPACE"
      GLFW.GLFW_KEY_HOME -> "HOME"
      GLFW.GLFW_KEY_DELETE -> "DELETE"
      GLFW.GLFW_KEY_BACKSPACE -> "BACKSPACE"
      GLFW.GLFW_KEY_ESCAPE -> "ESCAPE"
      GLFW.GLFW_KEY_ENTER -> "ENTER"
      GLFW.GLFW_KEY_TAB -> "TAB"
      GLFW.GLFW_KEY_EQUAL -> "EQUAL"
      GLFW.GLFW_KEY_MINUS -> "MINUS"
      GLFW.GLFW_KEY_LEFT_BRACKET -> "LEFT_BRACKET"
      GLFW.GLFW_KEY_RIGHT_BRACKET -> "RIGHT_BRACKET"
      GLFW.GLFW_KEY_BACKSLASH -> "BACKSLASH"
      GLFW.GLFW_KEY_COMMA -> "COMMA"
      GLFW.GLFW_KEY_PERIOD -> "PERIOD"
      else -> GLFW.glfwGetKeyName(key, 0)?.uppercase() ?: "KEY_$key"
    }

    private fun keyCode(name: String): Int? = when (name) {
      "SPACE" -> GLFW.GLFW_KEY_SPACE
      "HOME" -> GLFW.GLFW_KEY_HOME
      "DELETE" -> GLFW.GLFW_KEY_DELETE
      "BACKSPACE" -> GLFW.GLFW_KEY_BACKSPACE
      "ESCAPE" -> GLFW.GLFW_KEY_ESCAPE
      "ENTER" -> GLFW.GLFW_KEY_ENTER
      "TAB" -> GLFW.GLFW_KEY_TAB
      "EQUAL", "+" -> GLFW.GLFW_KEY_EQUAL
      "MINUS", "-" -> GLFW.GLFW_KEY_MINUS
      "LEFT_BRACKET" -> GLFW.GLFW_KEY_LEFT_BRACKET
      "RIGHT_BRACKET" -> GLFW.GLFW_KEY_RIGHT_BRACKET
      "BACKSLASH" -> GLFW.GLFW_KEY_BACKSLASH
      "COMMA" -> GLFW.GLFW_KEY_COMMA
      "PERIOD" -> GLFW.GLFW_KEY_PERIOD
      else -> if (name.length == 1) GLFW.glfwGetKeyScancode(name[0].code).takeIf { it >= 0 }?.let { name[0].uppercaseChar().code } else name.removePrefix("KEY_").toIntOrNull()
    }
  }
}

enum class EditorAction(val english: String, val japanese: String, val default: EditorKeyStroke) {
  PLAY_PAUSE("Play / Pause", "再生 / 一時停止", EditorKeyStroke(GLFW.GLFW_KEY_SPACE)),
  REWIND("Return to start", "先頭へ戻る", EditorKeyStroke(GLFW.GLFW_KEY_HOME)),
  FIT("Fit song", "曲全体を表示", EditorKeyStroke(GLFW.GLFW_KEY_F)),
  ZOOM_IN("Time zoom in", "時間方向を拡大", EditorKeyStroke(GLFW.GLFW_KEY_EQUAL, control = true)),
  ZOOM_OUT("Time zoom out", "時間方向を縮小", EditorKeyStroke(GLFW.GLFW_KEY_MINUS, control = true)),
  COPY("Copy", "コピー", EditorKeyStroke(GLFW.GLFW_KEY_C, control = true)),
  CUT("Cut", "切り取り", EditorKeyStroke(GLFW.GLFW_KEY_X, control = true)),
  PASTE("Paste", "貼り付け", EditorKeyStroke(GLFW.GLFW_KEY_V, control = true)),
  DUPLICATE("Duplicate", "複製", EditorKeyStroke(GLFW.GLFW_KEY_D, control = true)),
  UNDO("Undo", "元に戻す", EditorKeyStroke(GLFW.GLFW_KEY_Z, control = true)),
  REDO("Redo", "やり直し", EditorKeyStroke(GLFW.GLFW_KEY_Y, control = true)),
  DELETE("Delete", "削除", EditorKeyStroke(GLFW.GLFW_KEY_DELETE)),
  SELECT_ALL("Select all in view", "表示中を全選択", EditorKeyStroke(GLFW.GLFW_KEY_A, control = true)),
  SNAP_CYCLE("Cycle snap", "スナップ切替", EditorKeyStroke(GLFW.GLFW_KEY_S)),
  FOLLOW_TOGGLE("Toggle follow", "追従切替", EditorKeyStroke(GLFW.GLFW_KEY_L)),
  SETTINGS("Editor settings", "エディター設定", EditorKeyStroke(GLFW.GLFW_KEY_COMMA, control = true)),
  PREVIOUS_PART("Previous part", "前のパート", EditorKeyStroke(GLFW.GLFW_KEY_LEFT_BRACKET)),
  NEXT_PART("Next part", "次のパート", EditorKeyStroke(GLFW.GLFW_KEY_RIGHT_BRACKET)),
  ALL_PARTS("All-parts view", "全パート表示", EditorKeyStroke(GLFW.GLFW_KEY_BACKSLASH)),
  PREVIEW("Preview selected", "選択音の試聴", EditorKeyStroke(GLFW.GLFW_KEY_P)),
  NEW_PART("Create part", "パート作成", EditorKeyStroke.UNBOUND),
  LOAD_SELECTED("Load selected MIDI", "選択MIDIを読込", EditorKeyStroke.UNBOUND),
  UPLOAD_DRAFT("Upload draft", "下書きを送信", EditorKeyStroke.UNBOUND),
  REFRESH_LIBRARY("Refresh library", "ライブラリ更新", EditorKeyStroke.UNBOUND),
  OPEN_MIDI_FOLDER("Open MIDI folder", "MIDIフォルダーを開く", EditorKeyStroke.UNBOUND),
  // Keep last: the share bundle stores bindings positionally, so appending preserves old slots.
  CONFIRM("Confirm / Apply", "確定・適用", EditorKeyStroke(GLFW.GLFW_KEY_ENTER)),
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
