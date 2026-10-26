package com.github.sahyuya.oyasaimusicmiditranslator.interop

import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeys
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeyStroke
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorAction
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeymap
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorPlatformInput
import net.minecraft.client.input.KeyInput

object EditorInputCompatibilityVerification {
  fun run() {
    // Exercise the compiled platform adapter without starting Minecraft or native input.
    val sdl = KeyInput::class.java.methods.any { it.name == "keycode" }
    val letter = KeyInput(if (sdl) 4 else 65, if (sdl) 97 else 38, 0)
    check(EditorPlatformInput.keyCode(letter) == EditorKeys.KEY_A)
    val escape = KeyInput(if (sdl) 41 else 256, 0, 0)
    check(EditorPlatformInput.keyCode(escape) == EditorKeys.KEY_ESCAPE)
    check(EditorPlatformInput.isImeToggle(KeyInput(if (sdl) 135 else -1, if (sdl) 0 else 0x70, 0)))
    check(!EditorPlatformInput.isImeToggle(letter))
    check(EditorKeys.KEY_A == 65 && EditorKeys.KEY_O == 79 && EditorKeys.KEY_ESCAPE == 256)
    check(EditorKeys.fromSdlScancode(4) == EditorKeys.KEY_A)
    check(EditorKeys.fromSdlScancode(18) == EditorKeys.KEY_O)
    check(EditorKeys.fromSdlScancode(41) == EditorKeys.KEY_ESCAPE)
    check(EditorKeys.fromSdlScancode(58) == EditorKeys.KEY_F1)
    check(EditorKeys.fromSdlScancode(115) == EditorKeys.KEY_F24)
    check(EditorKeys.fromSdlScancode(89) == EditorKeys.KEY_KP_1)
    check(EditorKeys.fromSdlScancode(98) == EditorKeys.KEY_KP_0)
    check(EditorKeys.fromSdlScancode(224) == EditorKeys.KEY_LEFT_CONTROL)
    check(EditorKeys.fromSdlScancode(229) == EditorKeys.KEY_RIGHT_SHIFT)
    check(EditorKeys.toSdlScancode(EditorKeys.KEY_COMMA) == 54)
    check(EditorKeys.toSdlScancode(EditorKeys.KEY_LEFT_BRACKET) == 47)
    check(EditorKeys.toSdlScancode(EditorKeys.KEY_RIGHT_ALT) == 230)
    check(EditorKeys.toSdlScancode(EditorKeys.KEY_F25) == null)
    check(EditorKeys.toSdlScancode(EditorKeys.KEY_UNKNOWN) == null)
    for (scan in -1..512) {
      val key = EditorKeys.fromSdlScancode(scan)
      if (key != EditorKeys.KEY_UNKNOWN) check(EditorKeys.toSdlScancode(key) == scan)
    }
    for (action in EditorAction.entries) {
      val stroke = EditorKeymap()[action]
      if (stroke.key != EditorKeys.KEY_UNKNOWN) {
        val scan = checkNotNull(EditorKeys.toSdlScancode(stroke.key))
        check(EditorKeys.fromSdlScancode(scan) == stroke.key)
      }
    }
    check(EditorKeyStroke(EditorKeys.fromSdlScancode(6), control = true) == EditorKeyStroke(67, control = true))
    println("EditorInputCompatibilityVerification: PASS")
  }
}
