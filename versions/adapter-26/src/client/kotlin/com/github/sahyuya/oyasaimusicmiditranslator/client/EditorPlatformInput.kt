package com.github.sahyuya.oyasaimusicmiditranslator.client

import net.minecraft.client.input.KeyInput
import org.lwjgl.glfw.GLFW

/** GLFW boundary for the maintained 26.2 target. */
object EditorPlatformInput {
  private val imeToggleScancodes = setOf(0x29, 0x70, 0x79, 0x7B, 0x3A)
  fun keyCode(input: KeyInput): Int = input.key()
  fun isImeToggle(input: KeyInput): Boolean = input.key() == EditorKeys.KEY_UNKNOWN && input.scancode() in imeToggleScancodes
  fun isKeyDown(window: Long, key: Int): Boolean = window != 0L && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS
  fun resetCursor(window: Long) { GLFW.glfwSetCursor(window, 0L) }
  fun getClipboard(window: Long): String? = GLFW.glfwGetClipboardString(window)
  fun setClipboard(window: Long, text: String) { GLFW.glfwSetClipboardString(window, text) }
}
