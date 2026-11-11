package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.InflaterInputStream
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

fun bufferedElapsedMillis(nowNanos: Long, startAtNanos: Long): Int? =
    if (startAtNanos <= 0L || nowNanos < startAtNanos) null
    else ((nowNanos - startAtNanos) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Strict OYPB v1/v2 receiver. Sound dispatch is fully local after hash-bound START. */
private class PlaybackLane {
  private val logger = LoggerFactory.getLogger("OMMT/BufferedPlayback")
  private val MAX_BUFFER = 4 * 1024 * 1024
  private val MAX_CHUNKS = 256
  private val MAX_NOTES = 100_000
  private val MAX_STRINGS = 4096
  private val MAX_STRING_BYTES = 1_048_576
  private val BASE_CLIENT_CAPABILITIES =
      OmmtPluginWire.CLIENT_CAP_OYPB_V2 or
          OmmtPluginWire.CLIENT_CAP_STARTED_ACK or
          OmmtPluginWire.CLIENT_CAP_FIXED_CUSTOM_PATTERN or
          OmmtPluginWire.CLIENT_CAP_POSITIONAL_PAN or
          (1 shl 5)

  private fun clientCapabilities(): Int {
    var bits = BASE_CLIENT_CAPABILITIES
    val hash = SoundBankManifest.activeHash()
    if (hash != null) {
      // If bundled pack is outdated vs server's consent, don't advertise BANK so server sends fold
      // and the player can re-allow to get the new server pack (with load screen) or update the
      // mod.
      val outdated = ServerBankConsent.isOutdated(hash)
      if (!outdated) bits = bits or OmmtPluginWire.CLIENT_CAP_BANK_MANIFEST_V1
    }
    return bits
  }

  private data class Note(
      val time: Int,
      val instrument: Int,
      val pitchCents: Int,
      val volume: Int,
      val pan: Int,
      val customEvent: String? = null,
      val customPattern: Int = 0,
      val customSeed: Long = 0L,
  )

  private data class Decoded(
      val duration: Int,
      val notes: List<Note>,
      val spatialMode: Int,
      val bankPolicy: Int,
      val manifestHash: ByteArray,
  )

  private data class Pending(
      val id: UUID,
      val chunks: Int,
      val compressedBytes: Int,
      val hash: ByteArray,
      val duration: Int,
      val data: Array<ByteArray?>,
      var joined: ByteArray? = null,
      var decoded: Decoded? = null,
      var decoding: Boolean = false,
  )

  private var pending: Pending? = null
  private var decoded: Decoded? = null
  private var sessionId: UUID? = null
  private var sessionHash = ByteArray(0)
  private var startAtNanos = 0L
  private var pausedAtMs = 0
  private var cursor = 0
  private var startedAckSent = false
  private var answeredProbeNonce: String? = null
  private val decoder
    get() = PlaybackClient.decoder

  var budget = 256
  private var validationCursor = 0
  private val checkedEvents = mutableSetOf<String>()
  private var preparedRoutes = emptyArray<SoundBankManifest.Resolved?>()
  private val sharedRoutes = mutableMapOf<SoundBankManifest.Resolved, SoundBankManifest.Resolved>()
  private var validated = false
  val finished
    get() = pending == null && sessionId == null

  @Volatile private var pendingOutdatedNotice = false

  fun receive(raw: ByteArray) {
    try {
      val input = DataInputStream(ByteArrayInputStream(raw))
      if (input.readUnsignedByte() != OmmtPluginWire.VERSION) return
      val type = input.readUnsignedByte()
      val id = UUID(input.readLong(), input.readLong())
      when (type) {
        OmmtPluginWire.PLAYBACK_SERVER_CAPABILITIES -> receiveCapabilities(id, input)
        OmmtPluginWire.PLAYBACK_BANK_CONSENT -> receiveBankConsent(id, input)
        OmmtPluginWire.PLAYBACK_PROBE -> receiveProbe(id, input)
        OmmtPluginWire.PLAYBACK_BEGIN -> receiveBegin(id, input)
        OmmtPluginWire.PLAYBACK_CHUNK -> receiveChunk(id, input)
        OmmtPluginWire.PLAYBACK_START -> receiveStart(id, input)
        OmmtPluginWire.PLAYBACK_PAUSE -> receivePause(id, input)
        OmmtPluginWire.PLAYBACK_RESUME -> receiveResume(id, input)
        OmmtPluginWire.PLAYBACK_STOP -> receiveStop(id, input)
      }
    } catch (error: Exception) {
      val active = sessionId
      if (active != null) fail(active, 6, currentPosition(), "invalid runtime packet", error)
      else clearPlayback()
    }
  }

  private fun receiveCapabilities(id: UUID, input: DataInputStream) {
    if (id != UUID(0L, 0L)) return
    val capabilities = input.readInt()
    if (input.available() != 0) return
    val previousReceived = ServerPlaybackCapabilities.received
    val previousBrass = ServerPlaybackCapabilities.supportsBrassNoteBlockSounds
    ServerPlaybackCapabilities.update(capabilities)
    val brass = ServerPlaybackCapabilities.supportsBrassNoteBlockSounds
    if (!previousReceived || previousBrass != brass) {
      logger.debug(
          "Main-server OMMT capabilities received: OYPB v2={}, bank manifest={}, brass note-block sounds={}",
          capabilities and OmmtPluginWire.CAP_OYPB_V2 != 0,
          capabilities and OmmtPluginWire.CAP_BANK_MANIFEST_V1 != 0,
          brass,
      )
    }
  }

  private fun receiveBankConsent(id: UUID, input: DataInputStream) {
    if (id != UUID(0L, 0L)) return
    val allowedByte = input.readUnsignedByte()
    val hash = input.readNBytes(32)
    if (input.available() != 0 || hash.size != 32 || allowedByte !in 0..1) return
    val allowed = allowedByte == 1
    val previousOutdated =
        SoundBankManifest.activeHash()?.let { ServerBankConsent.isOutdated(it) } ?: false
    ServerBankConsent.update(allowed, hash)
    val bundledHash = SoundBankManifest.activeHash()
    val outdated = ServerBankConsent.isOutdated(bundledHash)
    logger.debug(
        "Bank consent received: allowed={}, serverHash={}, bundledActive={}, outdated={}",
        allowed,
        hash.joinToString("") { "%02x".format(it) }.take(8) + "...",
        bundledHash != null,
        outdated,
    )
    if (outdated) {
      logger.warn(
          "Bundled bank is outdated (server hash differs). Server playback will use vanilla fold until MOD is updated; allow will trigger server pack download with load screen fallback."
      )
      // Report to the player in-game (delivered on client tick once in-game).
      pendingOutdatedNotice = true
      // NOTE: no re-advertise here. The old nonce no longer matches the server's acceptedNonces
      // after refreshClientCapabilities, so a re-sent CLIENT_CAPABILITIES would be ignored.
      // The next resolveForPlayback probe (presence was cleared server-side) carries fresh caps.
    } else if (previousOutdated && !outdated && allowed) {
      logger.debug(
          "Bundled bank is now up-to-date with the server manifest; next probe will advertise BANK."
      )
    }
  }

  private fun receiveProbe(id: UUID, input: DataInputStream) {
    PlaybackClient.recordProbe("received")
    if (id != UUID(0L, 0L)) return
    val nonce = input.readUTF()
    if (input.available() != 0 || !nonce.matches(Regex("[A-Za-z0-9_-]{22}"))) return
    if (nonce == answeredProbeNonce || !ClientPlayNetworking.canSend(PlaybackPayload.ID)) {
      PlaybackClient.recordProbe("cannot-answer")
      return
    }
    // Report actual active bank bytes for this discovery, independently of stored consent.
    SoundBankManifest.invalidateCache()
    val bankHash = SoundBankManifest.activeHash() ?: ByteArray(32)
    val caps = clientCapabilities()
    ClientPlayNetworking.send(
        PlaybackPayload(OmmtPluginWire.playbackClientBankManifest(nonce, bankHash))
    )
    ClientPlayNetworking.send(
        PlaybackPayload(OmmtPluginWire.playbackClientCapabilities(nonce, caps))
    )
    ClientPlayNetworking.send(PlaybackPayload(OmmtPluginWire.playbackProbeResponse(nonce)))
    answeredProbeNonce = nonce
    PlaybackClient.recordProbe("answered")
    logger.debug(
        "Buffered-playback probe received; OMMT capability response sent (bankManifestActive={})",
        caps and OmmtPluginWire.CLIENT_CAP_BANK_MANIFEST_V1 != 0,
    )
  }

  private fun receiveBegin(id: UUID, input: DataInputStream) {
    val total = input.readUnsignedShort()
    val compressed = input.readInt()
    val hash = input.readNBytes(32)
    val duration = input.readInt()
    val mode = input.readUnsignedByte()
    val lead = input.readInt()
    if (
        input.available() != 0 ||
            total !in 1..MAX_CHUNKS ||
            compressed !in 1..MAX_BUFFER ||
            hash.size != 32 ||
            duration < 0 ||
            mode != 1 ||
            lead !in 500..30_000
    )
        return
    // The first valid BEGIN owns this attempt UUID. Retransmission (including a
    // conflicting body) must not reset chunks, decoder work or an active song.
    if (id == sessionId || id == pending?.id) return
    clearPlayback()
    pending = Pending(id, total, compressed, hash, duration, arrayOfNulls(total))
    logger.debug(
        "Buffering playback data: session={}, mode={}, chunks={}, compressedBytes={}, durationMs={}",
        id,
        mode,
        total,
        compressed,
        duration,
    )
  }

  private fun receiveChunk(id: UUID, input: DataInputStream) {
    val active = pending ?: return
    if (active.id != id) return
    val sequence = input.readUnsignedShort()
    val total = input.readUnsignedShort()
    val length = input.readUnsignedShort()
    val bytes = input.readNBytes(length)
    if (
        input.available() != 0 ||
            total != active.chunks ||
            sequence !in 0 until active.chunks ||
            length != bytes.size ||
            length !in 1..24 * 1024
    )
        return
    val previous = active.data[sequence]
    if (previous != null && !previous.contentEquals(bytes)) {
      clearPlayback()
      return
    }
    if (active.decoding || active.decoded != null) return
    if (previous == null) active.data[sequence] = bytes
    if (active.data.any { it == null }) return

    val joined = ByteArray(active.compressedBytes)
    var offset = 0
    for (chunk in active.data) {
      val value = chunk ?: return
      if (value.size > joined.size - offset) {
        clearPlayback()
        return
      }
      value.copyInto(joined, offset)
      offset += value.size
    }
    if (offset != joined.size) {
      clearPlayback()
      return
    }
    active.decoding = true
    // Only immutable bytes and metadata cross this boundary. Resource/world checks and
    // READY stay on the client thread; a replaced/disconnected session discards the result.
    try {
      CompletableFuture.supplyAsync(
              {
                require(
                    MessageDigest.isEqual(
                        MessageDigest.getInstance("SHA-256").digest(joined),
                        active.hash,
                    )
                )
                decodeV2(inflate(joined))
              },
              decoder,
          )
          .whenComplete { value, error ->
            Minecraft.getInstance().execute {
              if (pending !== active) return@execute
              if (error != null) {
                logger.warn("Rejected OYPB payload before READY: session={}", id, error)
                clearPlayback()
              } else completeBuffer(active, joined, value)
            }
          }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      logger.warn("Playback decoder is busy; retaining ordinary Paper fallback")
      clearPlayback()
    }
  }

  private fun completeBuffer(active: Pending, joined: ByteArray, value: Decoded) {
    val id = active.id
    val preflightGate = preflightFailure(value)
    if (value.duration != active.duration || preflightGate != null) {
      val reason =
          if (value.bankPolicy == 1 && !SoundBankManifest.matchesActiveHash(value.manifestHash)) 1
          else 2
      fail(id, reason, 0, "playback route preflight failed gate=${preflightGate ?: "duration"}")
      return
    }
    active.decoded = value
    active.joined = joined
    validationCursor = 0
    checkedEvents.clear()
    preparedRoutes = arrayOfNulls(value.notes.size)
    sharedRoutes.clear()
    validated = false
  }

  private fun validatePending() {
    val active = pending ?: return
    val value = active.decoded ?: return
    if (validated) return
    repeat(512) {
      if (validationCursor < value.notes.size) {
        val index = validationCursor++
        val route = resolve(value.notes[index], value)
        if (
            route == null ||
                (checkedEvents.add(route.eventId) && !SoundBankManifest.isAvailable(route.eventId))
        ) {
          fail(active.id, 2, 0, "sound-unavailable")
          return
        }
        // Keep the validated route; dispatch must not hash custom events or rebuild names.
        // Share repeated routes without retaining an unbounded interning table.
        preparedRoutes[index] =
            sharedRoutes[route]
                ?: route.also { if (sharedRoutes.size < 4096) sharedRoutes[route] = route }
      }
    }
    if (validationCursor < value.notes.size) return
    validated = true
    sharedRoutes.clear()
    val id = active.id
    if (!ClientPlayNetworking.canSend(PlaybackPayload.ID)) {
      clearPlayback()
      return
    }
    ClientPlayNetworking.send(PlaybackPayload(OmmtPluginWire.playbackReady(id, active.hash)))
    logger.debug(
        "Playback buffer is ready: session={}, notes={}",
        id,
        value.notes.size,
    )
  }

  private fun receiveStart(id: UUID, input: DataInputStream) {
    val active = pending ?: return
    if (active.id != id) return
    val delay = input.readInt()
    val position = input.readInt()
    if (
        input.available() != 0 ||
            delay !in 0..30_000 ||
            position < 0 ||
            active.data.any { it == null }
    )
        return
    // This buffer was hash-checked by the worker before READY and is never mutated.
    active.joined ?: return
    val value = active.decoded ?: return
    if (!validated) return
    decoded = value
    sessionId = id
    sessionHash = active.hash.copyOf()
    cursor =
        value.notes
            .indexOfFirst { it.time >= position }
            .let { if (it < 0) value.notes.size else it }
    pausedAtMs = position
    startedAckSent = false
    startAtNanos = System.nanoTime() + delay * 1_000_000L - position * 1_000_000L
    pending = null
    logger.debug(
        "Buffered playback selected and scheduled locally: session={}, notes={}, positionMs={}, startDelayMs={}",
        id,
        value.notes.size,
        position,
        delay,
    )
  }

  private fun receivePause(id: UUID, input: DataInputStream) {
    if (id != sessionId) return
    val position = input.readInt()
    if (input.available() == 0 && position >= 0) {
      pausedAtMs = position
      PreviewSoundPlayer.stopSession(id)
      startAtNanos = 0L
      logger.debug("Buffered playback paused: session={}, positionMs={}", id, position)
    }
  }

  private fun receiveResume(id: UUID, input: DataInputStream) {
    if (id != sessionId) return
    val delay = input.readInt()
    val position = input.readInt()
    if (input.available() == 0 && delay in 0..30_000 && position >= 0) {
      val value = decoded ?: return
      cursor =
          maxOf(
              cursor,
              value.notes
                  .indexOfFirst { it.time >= position }
                  .let { if (it < 0) value.notes.size else it },
          )
      pausedAtMs = position
      startAtNanos = System.nanoTime() + delay * 1_000_000L - position * 1_000_000L
      logger.debug(
          "Buffered playback resumed: session={}, positionMs={}, startDelayMs={}",
          id,
          position,
          delay,
      )
    }
  }

  private fun receiveStop(id: UUID, input: DataInputStream) {
    if (id != sessionId && id != pending?.id) return
    val reason = input.readUnsignedByte()
    if (input.available() == 0) {
      logger.debug("Buffered playback stopped: session={}, reason={}", id, reason)
      clearPlayback()
    }
  }

  fun tick() {
    validatePending()
    if (pendingOutdatedNotice) {
      val client = Minecraft.getInstance()
      if (client.player != null && client.level != null) {
        pendingOutdatedNotice = false
        runCatching {
          client.player?.sendSystemMessage(
              net.minecraft.network.chat.Component.literal(
                  "§e[OMMT] 内蔵リソースパックが古いため通常音域で再生します。最新MODへの更新で拡張音域になります（/mm rp allow でも可）。"
              )
          )
        }
      }
    }
    val activeId = sessionId ?: return
    val value = decoded ?: return
    if (startAtNanos == 0L) return
    val elapsed = bufferedElapsedMillis(System.nanoTime(), startAtNanos) ?: return
    val client = Minecraft.getInstance()
    if (client.player == null || client.level == null) {
      fail(activeId, 4, elapsed, "client world is unavailable")
      return
    }
    val batchEnd =
        playbackBatchEnd(cursor, value.notes.size, elapsed, budget) { value.notes[it].time }
    while (cursor < batchEnd) {
      val noteIndex = cursor
      val note = value.notes[noteIndex]
      cursor += 1
      // Silent notes consume bounded work too, without generating audio or a START ACK.
      if (note.volume == 0) continue
      if (!startedAckSent && !ClientPlayNetworking.canSend(PlaybackPayload.ID)) {
        fail(activeId, 5, elapsed, "plugin channel disappeared before START ACK")
        return
      }
      val resolved =
          preparedRoutes.getOrNull(noteIndex)
              ?: run {
                fail(
                    activeId,
                    if (value.bankPolicy == 1) 1 else 2,
                    elapsed,
                    "sound route disappeared",
                )
                return
              }
      try {
        if (
            !PreviewSoundPlayer.playId(
                resolved.eventId,
                note.volume / 100f,
                resolved.pitch,
                resolved.seed,
                if (value.spatialMode == 1) note.pan else 0,
                requireStart = !startedAckSent,
                session = activeId,
            )
        ) {
          fail(activeId, 2, elapsed, "sound event is no longer available")
          return
        }
      } catch (error: Exception) {
        fail(activeId, 3, elapsed, "SoundManager dispatch failed", error)
        return
      }
      if (PreviewSoundPlayer.lastDispatchStarted) {
        PlaybackClient.recordDispatch(startAtNanos, note.time)
      }
      if (!startedAckSent && PreviewSoundPlayer.lastDispatchStarted) {
        ClientPlayNetworking.send(
            PlaybackPayload(OmmtPluginWire.playbackStartedAck(activeId, sessionHash, note.time))
        )
        startedAckSent = true
        logger.debug(
            "Local playback START acknowledged after first dispatch: session={}, firstNoteMs={}",
            activeId,
            note.time,
        )
      }
    }
    if (cursor == value.notes.size) {
      logger.debug("Buffered playback finished locally: session={}", activeId)
      sessionId = null
      decoded = null
    }
  }

  /** Returns null when preflight passes, otherwise a short tag naming the failed gate for logs. */
  private fun preflightFailure(value: Decoded): String? {
    if (value.bankPolicy == 1) {
      if (!ServerPlaybackCapabilities.supportsBankManifest) return "server-caps"
      if (!SoundBankManifest.matchesActiveHash(value.manifestHash)) return "manifest-hash"
      if (!ServerBankConsent.isAllowed) return "consent"
      val bundledHash = SoundBankManifest.activeHash()
      if (bundledHash != null && ServerBankConsent.isOutdated(bundledHash)) return "outdated"
      val consentHash = ServerBankConsent.manifestHash
      if (
          consentHash != null &&
              !java.security.MessageDigest.isEqual(consentHash, value.manifestHash)
      )
          return "consent-hash"
    }
    return null
  }

  private fun resolve(note: Note, value: Decoded): SoundBankManifest.Resolved? =
      if (note.customEvent == null)
          SoundBankManifest.resolveInstrument(
              note.instrument,
              note.pitchCents,
              value.bankPolicy == 1,
          )
      else
          SoundBankManifest.resolveCustom(
              note.customEvent,
              note.customPattern,
              note.customSeed,
              note.pitchCents,
              value.bankPolicy == 1,
          )

  private fun fail(
      id: UUID,
      reason: Int,
      position: Int,
      message: String,
      error: Exception? = null,
  ) {
    PlaybackClient.recordFailure(reason)
    if (error == null)
        logger.warn("Buffered playback failed: session={}, reason={}, {}", id, reason, message)
    else
        logger.warn(
            "Buffered playback failed: session={}, reason={}, {}",
            id,
            reason,
            message,
            error,
        )
    if (ClientPlayNetworking.canSend(PlaybackPayload.ID))
        runCatching {
          ClientPlayNetworking.send(
              PlaybackPayload(OmmtPluginWire.playbackFailed(id, reason, position.coerceAtLeast(0)))
          )
        }
    clearPlayback()
  }

  private fun currentPosition(): Int =
      if (startAtNanos == 0L) pausedAtMs
      else bufferedElapsedMillis(System.nanoTime(), startAtNanos) ?: pausedAtMs

  fun resourceReloaded() {
    (sessionId ?: pending?.id)?.let { fail(it, 1, currentPosition(), "resources reloaded") }
  }

  fun clearPlayback() {
    pending?.id?.let { PreviewSoundPlayer.stopSession(it) }
    sessionId?.let { PreviewSoundPlayer.stopSession(it) }
    pending = null
    decoded = null
    preparedRoutes = emptyArray()
    sharedRoutes.clear()
    sessionId = null
    sessionHash = ByteArray(0)
    cursor = 0
    startAtNanos = 0L
    pausedAtMs = 0
    startedAckSent = false
  }

  private fun inflate(compressed: ByteArray): ByteArray =
      InflaterInputStream(ByteArrayInputStream(compressed)).use {
        it.readNBytes(MAX_BUFFER + 1).also { bytes -> require(bytes.size <= MAX_BUFFER) }
      }

  private fun decodeV2(bytes: ByteArray): Decoded =
      DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 0x4F595042 && input.readUnsignedByte() == 2)
        val duration = readVarUInt(input)
        val count = readVarUInt(input)
        val stringCount = readVarUInt(input)
        require(count in 1..MAX_NOTES && stringCount in 0..MAX_STRINGS)
        val spatialMode = input.readUnsignedByte()
        val bankPolicy = input.readUnsignedByte()
        val manifestHash = input.readNBytes(32)
        require(spatialMode in 0..1 && bankPolicy in 0..1 && manifestHash.size == 32)
        require(
            (bankPolicy == 0 && manifestHash.all { it == 0.toByte() }) ||
                (bankPolicy == 1 && manifestHash.any { it != 0.toByte() })
        )
        var totalStringBytes = 0
        val strings = ArrayList<String>(stringCount)
        repeat(stringCount) {
          val length = readVarUInt(input)
          require(length in 1..256)
          totalStringBytes = Math.addExact(totalStringBytes, length)
          require(totalStringBytes <= MAX_STRING_BYTES)
          val raw = input.readNBytes(length)
          require(raw.size == length)
          val text = decodeUtf8(raw)
          require(text.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")))
          require(strings.lastOrNull()?.let { compareUtf8(it, text) < 0 } != false)
          strings += text
        }
        var time = 0
        val notes =
            buildList(count) {
              repeat(count) {
                time = Math.addExact(time, readVarUInt(input))
                val kind = input.readUnsignedByte()
                val instrument = input.readUnsignedByte()
                val pitchCents = input.readShort().toInt()
                val volume = input.readUnsignedByte()
                val pan = input.readUnsignedByte() - 100
                require(
                    time <= duration &&
                        kind in 0..1 &&
                        pitchCents in -5400..7300 &&
                        volume <= 100 &&
                        pan in -100..100
                )
                if (kind == 0) {
                  require(instrument in 0..19)
                  add(Note(time, instrument, pitchCents, volume, pan))
                } else {
                  val stringIndex = readVarUInt(input)
                  val pattern = readVarUInt(input)
                  val seed = input.readLong()
                  require(stringIndex in strings.indices && pattern in 1..65_535)
                  add(
                      Note(
                          time,
                          instrument,
                          pitchCents,
                          volume,
                          pan,
                          strings[stringIndex],
                          pattern,
                          seed,
                      )
                  )
                }
              }
            }
        require(input.available() == 0)
        Decoded(
            duration,
            MusicVoicePolicy.onsetOrder(notes, { it.time }, { it.volume }),
            spatialMode,
            bankPolicy,
            manifestHash,
        )
      }

  private fun decodeUtf8(bytes: ByteArray): String =
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString()

  private fun compareUtf8(left: String, right: String): Int {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    val common = minOf(a.size, b.size)
    for (index in 0 until common) {
      val comparison = (a[index].toInt() and 0xff).compareTo(b[index].toInt() and 0xff)
      if (comparison != 0) return comparison
    }
    return a.size.compareTo(b.size)
  }

  private fun readVarUInt(input: DataInputStream): Int {
    var result = 0
    repeat(5) { index ->
      val byte = input.readUnsignedByte()
      if (index == 4 && byte > 0x07) throw IllegalArgumentException("varint overflow")
      result = result or ((byte and 127) shl (index * 7))
      if (byte and 128 == 0) {
        if (index > 0 && byte == 0) throw IllegalArgumentException("nonminimal varint")
        return result
      }
    }
    throw IllegalArgumentException("overlong varint")
  }
}

/** One network receiver, bounded independent lanes and a shared frame dispatch budget. */
object PlaybackClient {
  private val diagnosticsEnabled = java.lang.Boolean.getBoolean("ommt.playback.diagnostics")
  private val diagnosticsLogger = LoggerFactory.getLogger("OMMT/PlaybackStats")

  internal fun recordProbe(stage: String) {
    if (diagnosticsEnabled) diagnosticsLogger.info("capabilityProbe={}", stage)
  }

  private var nextReportNanos = System.nanoTime()
  private var dispatched = 0L
  private var totalDelayMs = 0L
  private var maxDelayMs = 0
  private val failureCounts = mutableMapOf<Int, Int>()

  internal fun recordDispatch(startNanos: Long, noteMs: Int) {
    if (!diagnosticsEnabled) return
    val elapsed = bufferedElapsedMillis(System.nanoTime(), startNanos) ?: return
    val delay = (elapsed - noteMs).coerceAtLeast(0)
    dispatched++
    totalDelayMs += delay
    maxDelayMs = maxOf(maxDelayMs, delay)
  }

  internal fun recordFailure(reason: Int) {
    if (diagnosticsEnabled) failureCounts[reason] = (failureCounts[reason] ?: 0) + 1
  }

  private fun reportDiagnostics() {
    if (!diagnosticsEnabled || (dispatched == 0L && failureCounts.isEmpty())) return
    val now = System.nanoTime()
    if (now - nextReportNanos < 0L) return
    diagnosticsLogger.info(
        "localLanes={} dispatched={} avgDispatchDelayMs={} maxDispatchDelayMs={} failures={}",
        lanes.size,
        dispatched,
        totalDelayMs / dispatched.coerceAtLeast(1),
        maxDelayMs,
        failureCounts,
    )
    nextReportNanos = now + 10_000_000_000L
    dispatched = 0
    totalDelayMs = 0
    maxDelayMs = 0
    failureCounts.clear()
  }

  internal val decoder =
      java.util.concurrent.ThreadPoolExecutor(
          1,
          1,
          0L,
          java.util.concurrent.TimeUnit.MILLISECONDS,
          java.util.concurrent.ArrayBlockingQueue<Runnable>(8),
          java.util.concurrent.ThreadFactory { r ->
            Thread(r, "OMMT-PlaybackDecode").apply { isDaemon = true }
          },
          java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
      )
  private val lanes = linkedMapOf<UUID, PlaybackLane>()
  private var control = PlaybackLane()
  private var initialized = false

  fun initialize() {
    if (initialized) return
    initialized = true
    PlaybackPayload.registerCodec()
    ClientPlayNetworking.registerGlobalReceiver(PlaybackPayload.ID) { payload, _ ->
      receive(payload.bytes)
    }
    ClientTickEvents.END_CLIENT_TICK.register { tick() }
    ClientLifecycleEvents.CLIENT_STOPPING.register {
      clear()
      decoder.shutdownNow()
    }
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
      clear()
      control = PlaybackLane()
      ServerPlaybackCapabilities.clear()
      ServerBankConsent.clear()
    }
  }

  private fun receive(bytes: ByteArray) {
    if (bytes.size !in 18..24576) return
    val input = DataInputStream(ByteArrayInputStream(bytes))
    if (input.readUnsignedByte() != OmmtPluginWire.VERSION) return
    val type = input.readUnsignedByte()
    val id = UUID(input.readLong(), input.readLong())
    if (id == UUID(0L, 0L)) {
      control.receive(bytes)
      return
    }
    if (type == OmmtPluginWire.PLAYBACK_STOP) PreviewSoundPlayer.stopSession(id)
    if (type == OmmtPluginWire.PLAYBACK_BEGIN && id !in lanes) {
      // Check the retained BEGIN layout and mode before it can replace or allocate a lane.
      if (input.available() != 47) return
      input.skipNBytes(42) // chunk count, compressed size, hash and duration
      if (input.readUnsignedByte() != 1) return
      if (!ServerPlaybackCapabilities.supportsMultiSession) clear()
      if (lanes.size >= 8) {
        recordFailure(4)
        if (ClientPlayNetworking.canSend(PlaybackPayload.ID))
            ClientPlayNetworking.send(PlaybackPayload(OmmtPluginWire.playbackFailed(id, 4, 0)))
        return
      }
      lanes[id] = PlaybackLane()
    }
    lanes[id]?.receive(bytes)
    if (lanes[id]?.finished == true) lanes.remove(id)
  }

  fun tick() {
    control.tick()
    val active = lanes.values.toList()
    val allowance = 256 / active.size.coerceAtLeast(1)
    active.forEach {
      it.budget = allowance
      it.tick()
    }
    lanes.entries.removeAll { it.value.finished }
    PreviewSoundPlayer.pruneSessionVoices()
    reportDiagnostics()
  }

  fun resourceReloaded() {
    lanes.values.toList().forEach { it.resourceReloaded() }
    clear()
  }

  fun clear() {
    lanes.values.forEach { it.clearPlayback() }
    lanes.clear()
    PreviewSoundPlayer.stopAllSessions()
  }
}
