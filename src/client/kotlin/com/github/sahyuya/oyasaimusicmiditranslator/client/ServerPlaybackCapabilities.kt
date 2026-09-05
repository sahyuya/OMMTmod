package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire

/** Capabilities advertised by the authoritative main backend for the current connection. */
object ServerPlaybackCapabilities {
  @Volatile private var bits = 0
  @Volatile var received = false
    private set

  val supportsBrassNoteBlockSounds: Boolean
    get() = received && bits and OmmtPluginWire.CAP_BRASS_NOTE_BLOCK != 0

  val supportsOypbV2: Boolean
    get() = received && bits and OmmtPluginWire.CAP_OYPB_V2 != 0

  val supportsBankManifest: Boolean
    get() = received && bits and OmmtPluginWire.CAP_BANK_MANIFEST_V1 != 0

  internal fun update(value: Int) {
    bits = value
    received = true
  }

  internal fun clear() {
    bits = 0
    received = false
  }
}
