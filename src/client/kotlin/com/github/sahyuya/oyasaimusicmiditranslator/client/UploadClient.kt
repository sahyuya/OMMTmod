package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.UploadPayload
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient

/** Client transport queue. It limits burst size and treats a missing Paper endpoint as unavailable. */
object UploadClient {
  private const val VERSION = 1
  private const val MAX_CHUNK = 16 * 1024
  private const val HELLO = 1
  private const val BEGIN = 2
  private const val CHUNK = 3
  private const val COMMIT = 4
  private const val HELLO_ACK = 17
  private const val RESULT = 18
  private val outgoing = ArrayDeque<ByteArray>()
  private var status = "Checking OyasaiMusic…"
  private var helloAt = 0L

  fun initialize() {
    ClientPlayNetworking.registerGlobalReceiver(UploadPayload.ID) { payload, context ->
      context.client().execute { receive(payload.bytes) }
    }
  }

  fun checkAvailability() {
    helloAt = System.currentTimeMillis()
    queue(packet(HELLO) {})
  }

  fun status(): String {
    if (helloAt != 0L && System.currentTimeMillis() - helloAt > 5_000 && status.startsWith("Checking")) status = "OyasaiMusic is unavailable on this server"
    return status
  }

  fun tick() {
    repeat(4) {
      val bytes = outgoing.pollFirst() ?: return
      val connection = MinecraftClient.getInstance().networkHandler ?: return
      ClientPlayNetworking.send(UploadPayload(bytes))
    }
  }

  fun upload(file: ByteArray) {
    if (file.size > 32 * 1024 * 1024) { status = "Export is larger than 32 MiB"; return }
    val request = UUID.randomUUID()
    val digest = MessageDigest.getInstance("SHA-256").digest(file)
    val count = (file.size + MAX_CHUNK - 1) / MAX_CHUNK
    if (count !in 1..2048) { status = "Export needs too many chunks"; return }
    queue(packet(BEGIN) { out ->
      out.writeLong(request.mostSignificantBits); out.writeLong(request.leastSignificantBits)
      out.writeInt(file.size); out.writeShort(count); out.write(digest)
    })
    for (ordinal in 0 until count) {
      val offset = ordinal * MAX_CHUNK
      val length = minOf(MAX_CHUNK, file.size - offset)
      queue(packet(CHUNK) { out ->
        out.writeLong(request.mostSignificantBits); out.writeLong(request.leastSignificantBits)
        out.writeShort(ordinal); out.writeShort(length); out.write(file, offset, length)
      })
    }
    queue(packet(COMMIT) { out -> out.writeLong(request.mostSignificantBits); out.writeLong(request.leastSignificantBits) })
    status = "Uploading $count chunks…"
  }

  private fun receive(bytes: ByteArray) {
    if (bytes.size < 4) return
    try {
      val input = java.io.DataInputStream(bytes.inputStream())
      val type = input.readUnsignedByte()
      val version = input.readUnsignedShort()
      if (version != VERSION) { status = "Server uses an unsupported upload protocol"; return }
      when (type) {
        HELLO_ACK -> status = if (input.readUnsignedByte() == 0) "OyasaiMusic upload ready" else "Server does not support OMMT upload"
        RESULT -> {
          input.readLong(); input.readLong()
          val code = input.readUnsignedByte()
          val length = input.readUnsignedShort().coerceAtMost(256)
          val message = input.readNBytes(length).toString(Charsets.UTF_8)
          val hasSong = input.readBoolean()
          val songId = if (hasSong) input.readLong() else null
          status = if (code == 0) "Imported as draft song #${songId ?: "?"}" else "Upload failed: ${message.ifBlank { "server error" }}"
        }
      }
    } catch (_: Exception) { status = "Invalid response from server" }
  }

  private fun queue(bytes: ByteArray) { outgoing.addLast(bytes) }
  private fun packet(type: Int, body: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { out -> out.writeByte(type); out.writeShort(VERSION); body(out) }
    bytes.toByteArray()
  }
}
