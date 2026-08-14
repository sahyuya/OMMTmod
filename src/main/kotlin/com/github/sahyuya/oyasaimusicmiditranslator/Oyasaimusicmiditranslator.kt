package com.github.sahyuya.oyasaimusicmiditranslator

import net.fabricmc.api.ModInitializer

class Oyasaimusicmiditranslator : ModInitializer {

    override fun onInitialize() {
        // The upload transport is vanilla commands and is client-only.  Do not register a
        // custom payload here: a dedicated server may load this entry point.
    }
}
