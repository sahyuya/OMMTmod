package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire
import com.github.sahyuya.oyasaimusicmiditranslator.interop.UploadV2Codec
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayNetworkHandler

/** Packet-only upload client. A UUID-only REQUEST is the sole pre-READY client packet. */
object UploadClient {
  data class Progress(val phase: String, val sent: Int, val total: Int, val percent: Int, val etaSeconds: Int?)

  private const val READY_TIMEOUT_MS = 5_000L
  private const val STATUS_LINGER_MS = 5_000L
  private const val STATUS_LINGER_ERROR_MS = 8_000L
  private enum class Stage { IDLE, WAITING_READY, BEGIN, CHUNKS, FINISH, IMPORTING }
  private data class Prepared(
      val id: UUID, val oymi: ByteArray, val compressed: ByteArray, val chunks: List<ByteArray>,
      val hash: ByteArray, val notes: Int, val requiresCustomSound: Boolean,
  )

  private var initialized = false
  private var stage = Stage.IDLE
  private var prepared: Prepared? = null
  private var connection: ClientPlayNetworkHandler? = null
  private var requestedAtMs = 0L
  private var availabilityOnly = false
  private var nextChunk = 0
  private var status = "OyasaiMusic upload not checked"
  private var statusVisibleUntilMs = 0L

  /** Kept only so existing local editor-settings files remain readable. Packet upload is binary. */
  fun setEncodingPreference(@Suppress("UNUSED_PARAMETER") value: String) = Unit

  fun initialize() {
    if (initialized) return
    initialized = true
    UploadPayload.registerCodec()
    // Fabric invokes client play receivers on the client execution path. Keep this handler pure
    // model/UI state; it does not touch world state or a dedicated-server class.
    ClientPlayNetworking.registerGlobalReceiver(UploadPayload.ID) { payload, _ -> receive(payload.bytes) }
  }

  fun checkAvailability() {
    if (stage != Stage.IDLE) return
    val handler = MinecraftClient.getInstance().networkHandler ?: run {
      reset("Upload cancelled: not connected to a server", true, false); return
    }
    if (!ClientPlayNetworking.canSend(UploadPayload.ID)) {
      reset("OyasaiMusic packet upload is unavailable on this server", true, false); return
    }
    val id = UUID.randomUUID()
    prepared = Prepared(id, ByteArray(0), ByteArray(0), emptyList(), ByteArray(32), 0, false)
    availabilityOnly = true
    connection = handler; requestedAtMs = System.currentTimeMillis(); stage = Stage.WAITING_READY
    if (send(OmmtPluginWire.uploadRequest(id))) {
      status = "Checking OyasaiMusic upload capability..."
    }
  }

  fun upload(oymi: ByteArray) {
    if (stage != Stage.IDLE) reset("Upload cancelled: replacing the previous request", true, true)
    val header = runCatching { validateOymiHeader(oymi) }.getOrElse {
      reset("Upload rejected: ${it.message ?: "invalid OYMI"}", true, false); return
    }
    val compact = runCatching { UploadV2Codec.compactFromOymi(oymi) }.getOrElse {
      reset("Upload rejected: ${it.message ?: "invalid OYMI"}", true, false); return
    }
    if (compact.size !in 1..OmmtPluginWire.MAX_COMPRESSED_BYTES) {
      reset("Upload rejected: compact data exceeds 1 MiB", true, false); return
    }
    val compressed = runCatching { deflate(compact) }.getOrElse {
      reset("Upload rejected: ${it.message ?: "compression failed"}", true, false); return
    }
    if (compressed.size !in 1..OmmtPluginWire.MAX_COMPRESSED_BYTES) {
      reset("Upload rejected: compressed data exceeds 1 MiB", true, false); return
    }
    val chunks = compressed.asList().chunked(OmmtPluginWire.CHUNK_BYTES).map { it.toByteArray() }
    if (chunks.size !in 1..OmmtPluginWire.MAX_CHUNKS) {
      reset("Upload rejected: too many packet chunks", true, false); return
    }
    val handler = MinecraftClient.getInstance().networkHandler ?: run {
      reset("Upload cancelled: not connected to a server", true, false); return
    }
    if (!ClientPlayNetworking.canSend(UploadPayload.ID)) {
      // No supported plugin channel means zero body bytes are sent to another server.
      reset("OyasaiMusic packet upload is unavailable on this server", true, false); return
    }
    val id = UUID.randomUUID()
    availabilityOnly = false
    prepared = Prepared(
        id, oymi.copyOf(), compressed, chunks, MessageDigest.getInstance("SHA-256").digest(oymi),
        header.noteCount, header.version >= 3 && hasCustomSoundMetadata(oymi),
    )
    connection = handler; requestedAtMs = System.currentTimeMillis(); nextChunk = 0; stage = Stage.WAITING_READY
    if (send(OmmtPluginWire.uploadRequest(id))) {
      status = "Waiting for OyasaiMusic upload capability..."
    }
  }

  fun tick() {
    if (stage != Stage.IDLE && MinecraftClient.getInstance().networkHandler !== connection) {
      reset("Upload cancelled: server connection changed", true, true); return
    }
    if (stage == Stage.WAITING_READY && System.currentTimeMillis() - requestedAtMs > READY_TIMEOUT_MS) {
      reset("OyasaiMusic upload is unavailable", true, false); return
    }
    val value = prepared ?: return
    when (stage) {
      Stage.BEGIN -> {
        if (send(OmmtPluginWire.uploadBegin(value.id, value.chunks.size, value.compressed.size, value.compressed.size, value.oymi.size, value.notes, value.hash))) {
          stage = Stage.CHUNKS; status = "Uploading packet data..."
        }
      }
      Stage.CHUNKS -> {
        var sentThisTick = 0
        while (sentThisTick < 2 && nextChunk < value.chunks.size) {
          if (!send(OmmtPluginWire.uploadChunk(value.id, nextChunk, value.chunks.size, value.chunks[nextChunk]))) return
          nextChunk++
          sentThisTick++
        }
        if (nextChunk == value.chunks.size) stage = Stage.FINISH
      }
      Stage.FINISH -> {
        if (send(OmmtPluginWire.uploadFinish(value.id, value.hash))) {
          stage = Stage.IMPORTING; status = "Verifying upload..."
        }
      }
      else -> Unit
    }
  }

  fun status(): String = status
  fun isVisible(): Boolean = stage != Stage.IDLE || System.currentTimeMillis() < statusVisibleUntilMs
  fun progress(): Progress {
    val total = prepared?.chunks?.size ?: 0
    val phase = when (stage) {
      Stage.WAITING_READY -> "CHECKING"; Stage.BEGIN, Stage.CHUNKS -> "UPLOADING"; Stage.FINISH -> "VERIFYING"; Stage.IMPORTING -> "IMPORTING"
      Stage.IDLE -> if (status.startsWith("Imported")) "DONE" else "ERROR"
    }
    return Progress(phase, nextChunk, total, if (total == 0) 0 else (nextChunk * 100 / total).coerceIn(0, 100), null)
  }

  private fun receive(bytes: ByteArray) {
    val decoded = runCatching { OmmtPluginWire.decodeUploadServer(bytes) }.getOrNull() ?: return
    val value = prepared ?: return
    when (decoded) {
      is OmmtPluginWire.UploadReady -> {
        if (decoded.id != value.id || stage != Stage.WAITING_READY) return
        if (availabilityOnly) { reset("OyasaiMusic packet upload is ready", false, false); return }
        val allowed = decoded.maxOymiBytes in value.oymi.size..OmmtPluginWire.MAX_OYMI_BYTES &&
          decoded.maxCompressedBytes in value.compressed.size..OmmtPluginWire.MAX_COMPRESSED_BYTES &&
          decoded.maxChunks in value.chunks.size..OmmtPluginWire.MAX_CHUNKS &&
          decoded.chunkBytes in OmmtPluginWire.CHUNK_BYTES..OmmtPluginWire.MAX_PACKET_BYTES &&
          (decoded.capabilities and OmmtPluginWire.CAP_COMPACT_ZLIB) != 0 &&
          (!value.requiresCustomSound || (decoded.capabilities and OmmtPluginWire.CAP_CUSTOM_SOUND_PATTERN) != 0)
        if (!allowed) reset("Server upload capability does not support this song", true, false)
        else { stage = Stage.BEGIN; status = "Preparing packet upload..." }
      }
      is OmmtPluginWire.UploadStatus -> if (decoded.id == value.id) when (decoded.status) {
        OmmtPluginWire.STATUS_PROCESSING -> { stage = Stage.IMPORTING; status = "Importing draft..." }
        OmmtPluginWire.STATUS_DONE -> reset("Imported draft ${decoded.detail}", false, false)
        OmmtPluginWire.STATUS_ERROR -> reset("Upload failed: ${decoded.detail.ifBlank { "server error" }}", true, false)
      }
    }
  }

  private fun send(bytes: ByteArray): Boolean {
    if (!ClientPlayNetworking.canSend(UploadPayload.ID)) {
      reset("OyasaiMusic packet upload is unavailable", true, false)
      return false
    }
    return try {
      ClientPlayNetworking.send(UploadPayload(bytes))
      true
    } catch (_: RuntimeException) {
      reset("OyasaiMusic packet upload failed", true, false)
      false
    }
  }

  private fun reset(message: String, error: Boolean, sendAbort: Boolean) {
    val value = prepared
    if (sendAbort && value != null && ClientPlayNetworking.canSend(UploadPayload.ID)) {
      try {
        ClientPlayNetworking.send(UploadPayload(OmmtPluginWire.uploadAbort(value.id)))
      } catch (_: RuntimeException) {
        // The session is being discarded locally already; connection loss makes ABORT best-effort.
      }
    }
    stage = Stage.IDLE; prepared = null; connection = null; nextChunk = 0; requestedAtMs = 0L; availabilityOnly = false; status = message
    statusVisibleUntilMs = System.currentTimeMillis() + if (error) STATUS_LINGER_ERROR_MS else STATUS_LINGER_MS
  }

  private data class OymiHeader(val version: Int, val noteCount: Int)
  private fun validateOymiHeader(bytes: ByteArray): OymiHeader {
    require(bytes.size in 20..OmmtPluginWire.MAX_OYMI_BYTES) { "OYMI must be 20 bytes to 1 MiB" }
    val input = ByteBuffer.wrap(bytes); require(input.int == 0x4F594D49) { "invalid OYMI header" }
    val version = input.short.toInt(); require(version in 1..3 && input.short.toInt() == 0) { "unsupported OYMI version" }
    val metadataSize = input.int; val noteCount = input.int; input.int
    require(metadataSize in 2..(bytes.size - 20)) { "invalid OYMI metadata length" }
    require(noteCount in 1..OmmtPluginWire.MAX_NOTES) { "invalid OYMI note count" }
    require(20L + metadataSize.toLong() + noteCount.toLong() * 8L == bytes.size.toLong()) { "invalid OYMI note data length" }
    return OymiHeader(version, noteCount)
  }
  private fun hasCustomSoundMetadata(bytes: ByteArray): Boolean {
    val metadataSize = ByteBuffer.wrap(bytes).apply { position(8) }.int
    return "\"customSounds\"" in bytes.copyOfRange(20, 20 + metadataSize).toString(Charsets.UTF_8)
  }
  private fun deflate(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, false); deflater.setInput(bytes); deflater.finish()
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
    while (!deflater.finished()) { val count = deflater.deflate(buffer); require(count > 0) { "zlib compression stalled" }; output.write(buffer, 0, count); require(output.size() <= OmmtPluginWire.MAX_COMPRESSED_BYTES) }
    deflater.end(); return output.toByteArray()
  }
}
