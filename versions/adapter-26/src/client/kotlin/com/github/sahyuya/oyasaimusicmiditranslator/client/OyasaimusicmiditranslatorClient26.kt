package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

class OyasaimusicmiditranslatorClient : ClientModInitializer {
  override fun onInitializeClient() {
    EditorWorkspace.initialize().onFailure { error ->
      System.err.println("[OMMT] Could not initialize OMMT folders: ${error.message ?: error.javaClass.simpleName}")
    }
    val openEditor =
        KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.oyasaimusicmiditranslator.open_editor",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("oyasaimusicmiditranslator", "general"),
                ),
            ),
        )
    UploadClient.initialize()
    PlaybackClient.initialize()
    ClientTickEvents.END_CLIENT_TICK.register { client ->
      while (openEditor.consumeClick()) client.setScreenAndShow(OyasaiEditorScreen(EditorSession))
      UploadClient.tick()
    }
  }
}
