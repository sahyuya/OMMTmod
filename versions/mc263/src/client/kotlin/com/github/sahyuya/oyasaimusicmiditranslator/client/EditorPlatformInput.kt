package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorType
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyInput

/** SDL3 boundary; saved editor bindings keep their existing key identifiers. */
object EditorPlatformInput {
  fun keyCode(input: KeyInput): Int = EditorKeys.fromSdlScancode(input.key())
  fun isImeToggle(input: KeyInput): Boolean = input.key() == 0 || input.key() in 135..152
  fun isKeyDown(window: Long, key: Int): Boolean = window != 0L &&
      EditorKeys.toSdlScancode(key)?.let(InputConstants::isKeyDown) == true
  fun resetCursor(window: Long) { if (window != 0L) Minecraft.getInstance().window.selectCursor(CursorType.DEFAULT) }
  fun getClipboard(window: Long): String? = if (window == 0L) null else Minecraft.getInstance().keyboardHandler.clipboard
  fun setClipboard(window: Long, text: String) { if (window != 0L) Minecraft.getInstance().keyboardHandler.clipboard = text }
}
