package com.github.sahyuya.oyasaimusicmiditranslator.client

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/** Raw, deliberately bounded S2C envelope for Paper's `oyasaimusic:playback_v1` plugin channel. */
data class PlaybackPayload(val bytes: ByteArray) : CustomPayload {
  companion object {
    const val MAX_BYTES = 24 * 1024
    val ID: CustomPayload.Id<PlaybackPayload> = CustomPayload.Id(Identifier.of("oyasaimusic", "playback_v1"))
    val CODEC = CustomPayload.codecOf<RegistryByteBuf, PlaybackPayload>(
      // Plugin messages are already a framed payload.  Do not add PacketByteBuf's byte-array
      // varint length: Paper writes the contract envelope as the complete channel body.
      { value, buffer -> require(value.bytes.size in 18..MAX_BYTES); buffer.writeBytes(value.bytes) },
      { buffer ->
        val remaining = buffer.readableBytes()
        require(remaining in 18..MAX_BYTES) { "invalid raw playback envelope length" }
        PlaybackPayload(ByteArray(remaining).also { buffer.readBytes(it) })
      }
    )
  }
  override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
