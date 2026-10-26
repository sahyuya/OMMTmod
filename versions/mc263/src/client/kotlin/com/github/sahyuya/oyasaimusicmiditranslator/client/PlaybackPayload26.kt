package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry

/** 26.x official-name implementation of the unchanged playback_v1 raw envelope. */
data class PlaybackPayload(val bytes: ByteArray) : CustomPacketPayload {
  companion object {
    val ID =
        CustomPacketPayload.Type<PlaybackPayload>(
            Identifier.fromNamespaceAndPath("oyasaimusic", "playback_v1"),
        )
    val CODEC =
        CustomPacketPayload.codec<RegistryFriendlyByteBuf, PlaybackPayload>(
            { value, buffer ->
              require(value.bytes.size in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES)
              buffer.writeBytes(value.bytes)
            },
            { buffer ->
              val remaining = buffer.readableBytes()
              require(remaining in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES) { "invalid raw playback envelope length" }
              PlaybackPayload(ByteArray(remaining).also { buffer.readBytes(it) })
            },
        )

    fun registerCodec() {
      PayloadTypeRegistry.clientboundPlay().register(ID, CODEC)
      PayloadTypeRegistry.serverboundPlay().register(ID, CODEC)
    }
  }

  override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}
