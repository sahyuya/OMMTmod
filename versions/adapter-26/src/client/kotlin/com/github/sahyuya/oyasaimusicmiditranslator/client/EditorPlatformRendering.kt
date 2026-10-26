package com.github.sahyuya.oyasaimusicmiditranslator.client

import cn.enaium.fabric.imgui.DefaultImGui
import cn.enaium.fabric.imgui.FabricImGui
import net.minecraft.client.Minecraft

object EditorPlatformRendering {
  fun startTextInput(owner: Any) { Minecraft.getInstance().textInputManager().startTextInput() }
  fun stopTextInput(owner: Any) { Minecraft.getInstance().textInputManager().stopTextInput() }

  fun invalidateFontTexture(): Boolean {
    val impl = FabricImGui.IMGUI as? DefaultImGui ?: return false
    impl.imGuiImplGl3?.let {
      return runCatching { it.destroyFontsTexture(); true }.getOrDefault(false)
    }
    return impl.imGuiImplBlaze3D?.let(::invalidateBlaze) ?: false
  }

  private fun invalidateBlaze(blaze: Any): Boolean = runCatching {
    blaze.javaClass.getDeclaredMethod("disposeFontResources").apply { isAccessible = true }.invoke(blaze)
    blaze.javaClass.getDeclaredField("fontTexture").apply { isAccessible = true }.set(blaze, null)
    true
  }.getOrDefault(false)
}
