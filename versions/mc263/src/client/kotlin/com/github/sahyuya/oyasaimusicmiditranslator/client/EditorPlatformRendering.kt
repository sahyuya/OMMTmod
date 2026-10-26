package com.github.sahyuya.oyasaimusicmiditranslator.client

import cn.enaium.fabric.imgui.DefaultImGui
import cn.enaium.fabric.imgui.FabricImGui
import net.minecraft.client.Minecraft

object EditorPlatformRendering {
  fun startTextInput(owner: Any) { Minecraft.getInstance().textInputManager().startTextInput(owner) }
  fun stopTextInput(owner: Any) { Minecraft.getInstance().textInputManager().stopTextInput(owner) }

  // The pinned 26.3 ImGui bridge makes its renderers private. On an incompatible
  // bridge, report failure so the editor keeps its existing scale fallback.
  fun invalidateFontTexture(): Boolean = runCatching {
    val impl = FabricImGui.IMGUI as? DefaultImGui ?: return false
    val gl = DefaultImGui::class.java.getDeclaredField("imGuiImplGl3")
        .apply { isAccessible = true }.get(impl) as? imgui.gl3.ImGuiImplGl3
    if (gl != null) {
      gl.destroyFontsTexture()
      return true
    }
    val blaze = DefaultImGui::class.java.getDeclaredField("imGuiImplBlaze3D")
        .apply { isAccessible = true }.get(impl) ?: return false
    invalidateBlaze(blaze)
  }.getOrDefault(false)

  private fun invalidateBlaze(blaze: Any): Boolean = runCatching {
    blaze.javaClass.getDeclaredMethod("disposeFontResources").apply { isAccessible = true }.invoke(blaze)
    blaze.javaClass.getDeclaredField("fontTexture").apply { isAccessible = true }.set(blaze, null)
    true
  }.getOrDefault(false)
}
