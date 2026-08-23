package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.zip.Deflater
import com.github.sahyuya.oyasaimusicmiditranslator.interop.UploadV2Codec
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.text.Text

/**
 * Command-only upload queue.  A HELLO is the capability probe: until a matching READY chat line
 * arrives, no OYMI bytes are queued. This keeps a new client safe on old or unknown servers.
 */
object UploadClient {
  data class Progress(val phase: String, val sent: Int, val total: Int, val percent: Int, val etaSeconds: Int?)
  private const val COMMAND_MAX = 255
  private const val INTERVAL_MS = 1_200L
  private const val HELLO_TIMEOUT_MS = 5_000L
  private const val MAX_CHUNKS = 400
  private const val MAX_ENCODED = 80_000
  private const val MAX_COMPRESSED = 60_000
  private const val MAX_OYMI = 1_048_576
  private const val MAX_NOTES = 100_000
  /** サヒュヤ氏の指示: アップロード状況は常時表示せず、進行中または完了/失敗直後だけ表示する。 */
  private const val STATUS_LINGER_MS = 5_000L
  private const val STATUS_LINGER_ERROR_MS = 8_000L
  private val commands = ArrayBlockingQueue<String>(MAX_CHUNKS + 3)
  private var status = "OyasaiMusic upload not checked"
  private var statusVisibleUntilMs = 0L
  private var requestId: String? = null
  private var helloAt = 0L
  private var nextSendAt = 0L
  private var ready = false
  private var protocol = 2
  private var v1Retried = false
  private var initialized = false
  private var totalChunks = 0
  private var sentChunks = 0
  private var importing = false
  private var helloConnection: ClientPlayNetworkHandler? = null
  /** Persisted editor preference. AUTO intentionally remains the compact Unicode-15 default. */
  private var encodingPreference = "AUTO"

  fun setEncodingPreference(value: String) {
    encodingPreference = value.uppercase().takeIf { it in setOf("AUTO", "U15", "BASE64") } ?: "AUTO"
  }

  fun initialize() {
    if (initialized) return
    initialized = true
    // OMMT protocol acknowledgements are machine messages, not player chat. Consume only the
    // matching live request and replace terminal results with one localized summary.
    ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ -> !receive(message.string) }
  }

  fun checkAvailability() {
    if (requestId != null) return
    val id = id22(UUID.randomUUID())
    val connection = MinecraftClient.getInstance().networkHandler
      ?: run { markStatus("Upload cancelled: not connected to a server", STATUS_LINGER_ERROR_MS); return }
    requestId = id; helloConnection = connection; helloAt = 0L; ready = false; protocol = 2; v1Retried = false; commands.clear(); nextSendAt = 0L
    queue("ommtupload h 2 $id")
    status = "Checking OyasaiMusic upload..."
  }

  fun status(): String {
    // The five seconds begin when tick() actually sends HELLO, not while it is still queued.
    if (!ready && requestId != null && helloAt != 0L && System.currentTimeMillis() - helloAt > HELLO_TIMEOUT_MS) {
      if (protocol == 2 && !v1Retried) fallbackV1()
      else { commands.clear(); requestId = null; helloAt = 0L; markStatus("OyasaiMusic upload is unavailable", STATUS_LINGER_ERROR_MS) }
    }
    return status
  }

  /** 進行中、または直近の完了・失敗からしばらくの間だけtrue。UIはこれがtrueの間だけ状況を表示する。 */
  fun isVisible(): Boolean = requestId != null || pending != null || System.currentTimeMillis() < statusVisibleUntilMs

  fun progress(): Progress { val phase = when { requestId == null && status.startsWith("Imported") -> "DONE"; requestId == null -> "ERROR"; importing -> "IMPORTING"; !ready -> "CHECKING"; pending != null -> "PREPARING"; commands.isNotEmpty() -> "UPLOADING"; else -> "VERIFYING" }; val percent = if (totalChunks == 0) 0 else (sentChunks * 100 / totalChunks).coerceIn(0,100); val eta = if (sentChunks >= 2 && !importing) ((totalChunks-sentChunks)*INTERVAL_MS/1000).toInt() else null; return Progress(phase,sentChunks,totalChunks,percent,eta) }

  fun upload(oymi: ByteArray) {
    if (oymi.size !in 1..MAX_OYMI) { markStatus("OYMI must be at most 1 MiB", STATUS_LINGER_ERROR_MS); return }
    val oymiVersion = try { validateOymiHeader(oymi) }
    catch (error: IllegalArgumentException) { markStatus("Upload rejected: ${error.message}", STATUS_LINGER_ERROR_MS); return }
    // Always restart the probe. A stale READY must not authorize an upload after reconnect.
    requestId = null; checkAvailability()
    val id = requestId ?: return
    val compact = UploadV2Codec.compactFromOymi(oymi)
    val compressed = deflate(compact)
    if (compressed.size > MAX_COMPRESSED) { abortLocal("Compressed OYMI exceeds 60,000 bytes"); return }
    val unicode15 = encodingPreference != "BASE64"
    val encoded = if (unicode15) UploadV2Codec.unicode15(compressed) else Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    if (encoded.length > MAX_ENCODED) { abortLocal("Encoded OYMI exceeds 80,000 characters"); return }
    val chunks = encoded.chunked(200)
    if (chunks.size !in 1..MAX_CHUNKS) { abortLocal("Upload needs too many command chunks"); return }
    // Retain prepared data only after READY; never queue it behind HELLO on an unknown endpoint.
    totalChunks = chunks.size; sentChunks = 0; pending = Prepared(id, if (unicode15) "u" else "a", chunks, encoded.length, compressed.size, oymi.size, hash43(oymi), compact.size, validateOymiCount(oymi), oymiVersion, oymi.copyOf())
    status = "Waiting for OyasaiMusic upload capability..."
  }

  private data class Prepared(val id: String, val encoding: String, val chunks: List<String>, val encoded: Int, val compressed: Int, val oymi: Int, val hash: String, val transport: Int, val notes: Int, val oymiVersion: Int, val canonicalOymi: ByteArray)
  private var pending: Prepared? = null

  fun tick() {
    status()
    if (requestId != null && MinecraftClient.getInstance().networkHandler !== helloConnection) {
      abortLocal("Upload cancelled: server connection changed")
      return
    }
    val command = commands.peek() ?: return
    if (System.currentTimeMillis() < nextSendAt) return
    val connection = MinecraftClient.getInstance().networkHandler ?: run { abortLocal("Upload cancelled: disconnected"); return }
    commands.poll()
    if (command.startsWith("ommtupload c ")) sentChunks++
    if (command.startsWith("ommtupload h ") && helloAt == 0L) helloAt = System.currentTimeMillis()
    connection.sendChatCommand(command)
    nextSendAt = System.currentTimeMillis() + INTERVAL_MS
  }

  private fun receive(raw: String): Boolean {
    val words = raw.trim().removePrefix("[").removeSuffix("]").split(Regex("\\s+"))
    if (words.size < 4 || words[0] != "OMMT" || words[2] != requestId) return false
    if (words[1] !in setOf("UPLOAD1", "UPLOAD2")) return false
    when (words[3]) {
      "READY" -> {
        if (ready) return true
        val capabilities = words.drop(4).toSet()
        if (words[1] == "UPLOAD2" && protocol == 2) {
          val wanted = if (encodingPreference == "BASE64") "b64c1" else "u15c1"
          if (wanted !in capabilities) { abortLocal("OyasaiMusic does not advertise required upload encoding"); friendly(false, "UNSUPPORTED"); return true }
          if (pending?.oymiVersion == 2 && "cs1" !in capabilities) { abortLocal("OyasaiMusic does not support legacy custom Minecraft sounds"); friendly(false, "CUSTOM_SOUND_UNSUPPORTED"); return true }
          if (pending?.oymiVersion == 3 && "csp1" !in capabilities) { abortLocal("OyasaiMusic does not support fixed Minecraft sound patterns"); friendly(false, "CUSTOM_SOUND_PATTERN_UNSUPPORTED"); return true }
        }
        ready = true
        val prepared = pending
        if (prepared == null || prepared.id != requestId) { status = "OyasaiMusic upload ready"; return true }
        if (words[1] == "UPLOAD2" && protocol == 2) {
          val transportBytes = if (prepared.encoding == "u") prepared.encoded * 3 else prepared.encoded
          enqueueAll(buildList { add("ommtupload b 2 ${prepared.id} ${prepared.encoding} c ${prepared.chunks.size.toString(36)} ${prepared.encoded.toString(36)} ${transportBytes.toString(36)} ${prepared.compressed.toString(36)} ${prepared.transport.toString(36)} ${prepared.oymi.toString(36)} ${prepared.notes.toString(36)} ${prepared.hash}"); prepared.chunks.forEachIndexed { sequence, payload -> add("ommtupload c 2 ${prepared.id} ${sequence.toString(36)} $payload") }; add("ommtupload f 2 ${prepared.id} ${prepared.hash}") })
        } else {
          // v1 keeps ASCII Base64 and original OYMI semantics.
          val original = prepared; val compressedV1 = deflate(original.canonicalOymi); val ascii = Base64.getUrlEncoder().withoutPadding().encodeToString(compressedV1)
          val pieces = ascii.chunked(200); if (pieces.size !in 1..MAX_CHUNKS) { abortLocal("Legacy upload needs too many chunks"); friendly(false, "OVERSIZED"); return true }
          enqueueAll(buildList { add("ommtupload b 1 ${original.id} ${pieces.size.toString(36)} ${ascii.length.toString(36)} ${compressedV1.size.toString(36)} ${original.oymi.toString(36)} ${original.hash}"); pieces.forEachIndexed { sequence, payload -> add("ommtupload c 1 ${original.id} ${sequence.toString(36)} $payload") }; add("ommtupload f 1 ${original.id} ${original.hash}") })
        }
        pending = null; status = "Uploading ${prepared.chunks.size} command chunks..."
      }
      "PROCESSING" -> { importing = true; status = "Importing on OyasaiMusic..." }
      "DONE" -> { val draft = words.getOrNull(4); markStatus("Imported as private draft #${draft ?: "-"}"); friendly(true, draft); clearCompleted() }
      "ERROR" -> {
        // Only a v2 compatibility signal may cross the version boundary. v1 errors are terminal.
        if (protocol == 2 && !v1Retried && words[1] == "UPLOAD1" && words.getOrNull(4) == "MALFORMED") fallbackV1()
        else { val code=words.getOrNull(4) ?: "SERVER_ERROR"; markStatus("Upload failed: $code", STATUS_LINGER_ERROR_MS); friendly(false, code); clearCompleted() }
      }
    }
    return true
  }

  private fun friendly(success: Boolean, detail: String?) {
    val client = MinecraftClient.getInstance()
    val japanese = client.languageManager.language.lowercase().startsWith("ja")
    val message = if (success) {
      if (japanese) "OMMT: 送信が完了しました${detail?.let { "（下書き #$it）" } ?: ""}" else "OMMT: Upload complete${detail?.let { " (draft #$it)" } ?: ""}"
    } else {
      if (japanese) "OMMT: 送信に失敗しました（${detail ?: "不明なエラー"}）" else "OMMT: Upload failed (${detail ?: "unknown error"})"
    }
    client.inGameHud.chatHud.addMessage(Text.literal(message))
  }

  private fun clearCompleted() { commands.clear(); pending = null; requestId = null; ready = false; importing=false; helloAt = 0L; nextSendAt = 0L; helloConnection = null }
  private fun abortLocal(message: String) {
    commands.clear(); pending = null; requestId = null; ready = false; importing=false; helloAt = 0L; nextSendAt = 0L; helloConnection = null
    markStatus(message, STATUS_LINGER_ERROR_MS)
  }
  /** 状況テキストを更新し、しばらくの間だけUIへ表示され続けるようにする（[isVisible]参照）。 */
  private fun markStatus(message: String, lingerMs: Long = STATUS_LINGER_MS) {
    status = message
    statusVisibleUntilMs = System.currentTimeMillis() + lingerMs
  }
  private fun fallbackV1() {
    if ((pending?.oymiVersion ?: 1) > 1) { abortLocal("OyasaiMusic does not support custom Minecraft sound patterns"); friendly(false, "CUSTOM_SOUND_UNSUPPORTED"); return }
    // The probe identity and retained payload identity are one state transition.  A READY for the
    // fresh v1 id must authorize the same Prepared instance, never the superseded v2 id.
    val fresh=id22(UUID.randomUUID()); protocol=1; v1Retried=true; requestId=fresh; pending=pending?.copy(id=fresh)
    commands.clear(); helloAt=0L; nextSendAt=0L; queue("ommtupload h 1 $fresh"); status="Retrying legacy OyasaiMusic upload..."
  }
  private fun enqueueAll(values: List<String>) { if (values.any { it.length > COMMAND_MAX || it.toByteArray(Charsets.UTF_8).size > 765 } || commands.remainingCapacity() < values.size) { abortLocal("Upload command exceeds transport limits"); return }; values.forEach { commands.offer(it) } }
  private fun queue(command: String) { if (command.length > COMMAND_MAX || command.toByteArray(Charsets.UTF_8).size > 765 || !commands.offer(command)) abortLocal("Upload command exceeds transport limits") }
  private fun id22(value: UUID): String = Base64.getUrlEncoder().withoutPadding().encodeToString(java.nio.ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array())
  private fun hash43(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
  /** Match the Paper boundary before any HELLO/command data is sent. */
  private fun validateOymiHeader(bytes: ByteArray): Int {
    require(bytes.size in 20..MAX_OYMI) { "OYMI must be 20 bytes to 1 MiB" }
    val input = ByteBuffer.wrap(bytes)
    require(input.int == 0x4F594D49) { "invalid OYMI header" }
    val version = input.short.toInt()
    require(version in 1..3 && input.short.toInt() == 0) { "unsupported OYMI version" }
    val metadataSize = input.int
    val noteCount = input.int
    input.int // OYMI v1 duration is retained for Paper's authoritative importer.
    require(metadataSize in 2..(bytes.size - 20)) { "invalid OYMI metadata length" }
    require(noteCount in 1..MAX_NOTES) { "OYMI note count must be 1..$MAX_NOTES" }
    require(bytes.size == 20 + metadataSize + noteCount * 8) { "invalid OYMI note data length" }
    return version
  }
  private fun validateOymiCount(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).apply { position(12) }.int.also { require(it in 1..MAX_NOTES) }
  private fun deflate(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, false); deflater.setInput(bytes); deflater.finish()
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
    while (!deflater.finished()) { val count = deflater.deflate(buffer); if (count == 0) break; output.write(buffer, 0, count) }
    deflater.end(); return output.toByteArray()
  }
}
