package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry

/** Raw, deliberately bounded S2C envelope for Paper's `oyasaimusic:playback_v1` plugin channel. */
data class PlaybackPayload(val bytes: ByteArray) : CustomPayload {
  companion object {
    val ID: CustomPayload.Id<PlaybackPayload> = CustomPayload.Id(Identifier.of("oyasaimusic", "playback_v1"))
    val CODEC = CustomPayload.codecOf<RegistryByteBuf, PlaybackPayload>(
      // Plugin messages are already a framed payload.  Do not add PacketByteBuf's byte-array
      // varint length: Paper writes the contract envelope as the complete channel body.
      { value, buffer -> require(value.bytes.size in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES); buffer.writeBytes(value.bytes) },
      { buffer ->
        val remaining = buffer.readableBytes()
        require(remaining in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES) { "invalid raw playback envelope length" }
        PlaybackPayload(ByteArray(remaining).also { buffer.readBytes(it) })
      }
    )

    fun registerCodec() {
      PayloadTypeRegistry.playS2C().register(ID, CODEC)
      PayloadTypeRegistry.playC2S().register(ID, CODEC)
    }
  }
  override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
