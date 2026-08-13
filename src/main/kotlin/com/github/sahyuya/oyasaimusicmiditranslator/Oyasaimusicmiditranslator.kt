package com.github.sahyuya.oyasaimusicmiditranslator

import net.fabricmc.api.ModInitializer
import com.github.sahyuya.oyasaimusicmiditranslator.interop.UploadPayload
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry

class Oyasaimusicmiditranslator : ModInitializer {

    override fun onInitialize() {
        // Registration is common-side only; no client classes are loaded by a dedicated server.
        PayloadTypeRegistry.playC2S().register(UploadPayload.ID, UploadPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(UploadPayload.ID, UploadPayload.CODEC)
    }
}
