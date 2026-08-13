package com.github.sahyuya.oyasaimusicmiditranslator.interop

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/** Raw bounded packet envelope. The binary layout inside is specified in INTEROP_CONTRACT.md. */
data class UploadPayload(val bytes: ByteArray) : CustomPayload {
  companion object {
    const val CHANNEL = "oyasaimusic:upload_v1"
    const val MAX_PACKET_BYTES = 16 * 1024 + 32
    val ID = CustomPayload.Id<UploadPayload>(Identifier.of(CHANNEL))
    val CODEC: PacketCodec<RegistryByteBuf, UploadPayload> = object : PacketCodec<RegistryByteBuf, UploadPayload> {
      override fun decode(buf: RegistryByteBuf): UploadPayload {
        val size = buf.readableBytes()
        require(size in 3..MAX_PACKET_BYTES) { "Invalid OMMT payload length" }
        return UploadPayload(ByteArray(size).also(buf::readBytes))
      }
      override fun encode(buf: RegistryByteBuf, value: UploadPayload) {
        require(value.bytes.size in 3..MAX_PACKET_BYTES) { "Invalid OMMT payload length" }
        buf.writeBytes(value.bytes)
      }
    }
  }
  override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}
