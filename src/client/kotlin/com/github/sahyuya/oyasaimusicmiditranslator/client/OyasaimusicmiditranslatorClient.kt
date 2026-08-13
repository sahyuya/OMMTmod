package com.github.sahyuya.oyasaimusicmiditranslator.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class OyasaimusicmiditranslatorClient : ClientModInitializer {

    override fun onInitializeClient() {
        val openEditor = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.oyasaimusicmiditranslator.open_editor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyBinding.Category.create(Identifier.of("oyasaimusicmiditranslator", "general"))
            )
        )
        UploadClient.initialize()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openEditor.wasPressed()) { client.setScreen(OyasaiEditorScreen()); UploadClient.checkAvailability() }
            UploadClient.tick()
        }
    }
}
