package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/** Raw bidirectional `oyasaimusic:upload_v1` envelope. */
data class UploadPayload(val bytes: ByteArray) : CustomPayload {
  companion object {
    val ID: CustomPayload.Id<UploadPayload> = CustomPayload.Id(Identifier.of("oyasaimusic", "upload_v1"))
    val CODEC = CustomPayload.codecOf<RegistryByteBuf, UploadPayload>(
        { value, buffer ->
          require(value.bytes.size in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES)
          buffer.writeBytes(value.bytes)
        },
        { buffer ->
          val remaining = buffer.readableBytes()
          require(remaining in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES)
          UploadPayload(ByteArray(remaining).also(buffer::readBytes))
        },
    )

    fun registerCodec() {
      PayloadTypeRegistry.playS2C().register(ID, CODEC)
      PayloadTypeRegistry.playC2S().register(ID, CODEC)
    }
  }

  override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
