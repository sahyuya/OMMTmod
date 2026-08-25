package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** 26.x naming adapter for the unchanged raw upload_v1 envelope. */
data class UploadPayload(val bytes: ByteArray) : CustomPacketPayload {
  companion object {
    val ID = CustomPacketPayload.Type<UploadPayload>(Identifier.fromNamespaceAndPath("oyasaimusic", "upload_v1"))
    val CODEC = CustomPacketPayload.codec<RegistryFriendlyByteBuf, UploadPayload>(
        { value, buffer ->
          require(value.bytes.size in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES)
          buffer.writeBytes(value.bytes)
        },
        { buffer ->
          val remaining = buffer.readableBytes()
          require(remaining in OmmtPluginWire.ENVELOPE_BYTES..OmmtPluginWire.MAX_PACKET_BYTES)
          UploadPayload(ByteArray(remaining).also { buffer.readBytes(it) })
        },
    )

    fun registerCodec() {
      PayloadTypeRegistry.clientboundPlay().register(ID, CODEC)
      PayloadTypeRegistry.serverboundPlay().register(ID, CODEC)
    }
  }

  override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = ID
}
