package com.github.sahyuya.oyasaimusicmiditranslator.client

import imgui.flag.ImGuiKey
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeys

object EditorKeyboard {
  fun ownsShortcut(capturing: Boolean, textInput: Boolean, composing: Boolean): Boolean = capturing || (!textInput && !composing)
  fun isModifier(key: Int): Boolean = key in EditorKeys.KEY_LEFT_SHIFT..EditorKeys.KEY_RIGHT_SUPER

  /** Full GLFW keyboard range, including non-printable rebind targets. No native lookup. */
  fun imguiKey(key: Int): Int? = when (key) {
    in 65..90 -> ImGuiKey.A + key - 65
    in 48..57 -> ImGuiKey._0 + key - 48
    in 290..313 -> ImGuiKey.F1 + key - 290
    in 320..329 -> ImGuiKey.Keypad0 + key - 320
    else -> special[key]
  }
  private val special = mapOf(
      32 to ImGuiKey.Space, 39 to ImGuiKey.Apostrophe, 44 to ImGuiKey.Comma, 45 to ImGuiKey.Minus,
      46 to ImGuiKey.Period, 47 to ImGuiKey.Slash, 59 to ImGuiKey.Semicolon, 61 to ImGuiKey.Equal,
      91 to ImGuiKey.LeftBracket, 92 to ImGuiKey.Backslash, 93 to ImGuiKey.RightBracket, 96 to ImGuiKey.GraveAccent,
      256 to ImGuiKey.Escape, 257 to ImGuiKey.Enter, 258 to ImGuiKey.Tab, 259 to ImGuiKey.Backspace,
      260 to ImGuiKey.Insert, 261 to ImGuiKey.Delete, 262 to ImGuiKey.RightArrow, 263 to ImGuiKey.LeftArrow,
      264 to ImGuiKey.DownArrow, 265 to ImGuiKey.UpArrow, 266 to ImGuiKey.PageUp, 267 to ImGuiKey.PageDown,
      268 to ImGuiKey.Home, 269 to ImGuiKey.End, 280 to ImGuiKey.CapsLock, 281 to ImGuiKey.ScrollLock,
      282 to ImGuiKey.NumLock, 283 to ImGuiKey.PrintScreen, 284 to ImGuiKey.Pause,
      330 to ImGuiKey.KeypadDecimal, 331 to ImGuiKey.KeypadDivide, 332 to ImGuiKey.KeypadMultiply,
      333 to ImGuiKey.KeypadSubtract, 334 to ImGuiKey.KeypadAdd, 335 to ImGuiKey.KeypadEnter, 336 to ImGuiKey.KeypadEqual,
      348 to ImGuiKey.Menu,
  )
}
