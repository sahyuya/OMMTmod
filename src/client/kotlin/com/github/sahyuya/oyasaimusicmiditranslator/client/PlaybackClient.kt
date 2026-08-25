package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.InflaterInputStream
import kotlin.math.pow
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import com.github.sahyuya.oyasaimusicmiditranslator.interop.OmmtPluginWire
import net.minecraft.client.MinecraftClient
import net.minecraft.sound.SoundEvents

fun bufferedElapsedMillis(nowNanos: Long, startAtNanos: Long): Int? =
    if (startAtNanos <= 0L || nowNanos < startAtNanos) null
    else ((nowNanos - startAtNanos) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Client half of the bounded buffered route. Invalid or incomplete sessions are simply discarded. */
object PlaybackClient {
  private const val MAX_BUFFER = 4 * 1024 * 1024
  private const val MAX_CHUNKS = 256
  private const val MAX_NOTES_PER_TICK = 64
  private data class Note(val time: Int, val instrument: Int, val pitch: Int, val volume: Int, val pan: Int)
  private data class Pending(val id: UUID, val chunks: Int, val compressedBytes: Int, val hash: ByteArray, val data: Array<ByteArray?>, var joined: ByteArray? = null, var decoded: List<Note>? = null)
  private var pending: Pending? = null
  private var notes: List<Note> = emptyList()
  private var sessionId: UUID? = null
  private var startAtNanos = 0L
  private var pausedAtMs = 0
  private var cursor = 0
  private var initialized = false
  private var answeredProbeNonce: String? = null

  fun initialize() {
    if (initialized) return
    initialized = true
    PlaybackPayload.registerCodec()
    ClientPlayNetworking.registerGlobalReceiver(PlaybackPayload.ID) { payload, _ -> receive(payload.bytes) }
    ClientTickEvents.END_CLIENT_TICK.register { tick() }
    // Capability is intentionally not announced at JOIN. Paper probes only when this
    // connection requests its first eligible playback; no MOD means no reply and vanilla
    // playback begins after the server-side three-second decision window.
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }
  }

  private fun receive(raw: ByteArray) {
    try {
      val input = DataInputStream(ByteArrayInputStream(raw))
      if (input.readUnsignedByte() != OmmtPluginWire.VERSION) return; val type = input.readUnsignedByte()
      val id = UUID(input.readLong(), input.readLong())
      when (type) {
        OmmtPluginWire.PLAYBACK_PROBE -> {
          val nonce = input.readUTF()
          if (input.available() != 0 || !nonce.matches(Regex("[A-Za-z0-9_-]{22}"))) return
          if (nonce == answeredProbeNonce) return
          if (ClientPlayNetworking.canSend(PlaybackPayload.ID)) {
            ClientPlayNetworking.send(PlaybackPayload(OmmtPluginWire.playbackProbeResponse(nonce)))
            answeredProbeNonce = nonce
          }
        }
        OmmtPluginWire.PLAYBACK_BEGIN -> {
          val total = input.readUnsignedShort(); val compressed = input.readInt(); val hash = input.readNBytes(32); val duration = input.readInt(); val mode = input.readUnsignedByte(); val lead = input.readInt()
          if (input.available() != 0 || total !in 1..MAX_CHUNKS || compressed !in 1..MAX_BUFFER || hash.size != 32 || duration < 0 || mode != 0 || lead !in 500..30_000) return
          pending = Pending(id, total, compressed, hash, arrayOfNulls(total)); notes = emptyList(); sessionId = null
        }
        OmmtPluginWire.PLAYBACK_CHUNK -> {
          val active = pending ?: return; if (active.id != id) return
          val sequence = input.readUnsignedShort(); val total = input.readUnsignedShort(); val length = input.readUnsignedShort(); val bytes = input.readNBytes(length)
          if (input.available() != 0 || total != active.chunks || sequence !in 0 until active.chunks || length != bytes.size || length > 24 * 1024) return
          if (active.data[sequence] == null) active.data[sequence] = bytes
          if (active.data.all { it != null }) {
            val joined = ByteArray(active.compressedBytes)
            var offset = 0
            for (chunk in active.data) {
              val bytes = chunk ?: return
              if (bytes.size > joined.size - offset) { clear(); return }
              bytes.copyInto(joined, offset)
              offset += bytes.size
            }
            if (offset != joined.size) { clear(); return }
            if (joined.size != active.compressedBytes || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(joined),active.hash)) { clear(); return }
            // READY proves the exact compressed bytes can inflate and fully decode, not merely hash.
            active.decoded = try { decode(inflate(joined)) } catch (_: Exception) { clear(); return }
            active.joined = joined
            if (ClientPlayNetworking.canSend(PlaybackPayload.ID)) {
              ClientPlayNetworking.send(PlaybackPayload(OmmtPluginWire.playbackReady(id, active.hash)))
            } else clear()
          }
        }
        OmmtPluginWire.PLAYBACK_START -> {
          val active = pending ?: return; if (active.id != id) return
          val delay = input.readInt(); val position=input.readInt(); if (input.available() != 0 || delay !in 0..30_000 || position != 0 || active.data.any { it == null }) return
          val joined = active.joined ?: return
          if (joined.size != active.compressedBytes || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(joined), active.hash)) return
          notes = active.decoded ?: return; sessionId = id; cursor = notes.indexOfFirst { it.time >= position }.coerceAtLeast(0); pausedAtMs = 0; startAtNanos = System.nanoTime() + delay * 1_000_000L; pending = null
        }
        OmmtPluginWire.PLAYBACK_PAUSE -> if (id == sessionId) { val position = input.readInt(); if (input.available() == 0 && position >= 0) { pausedAtMs = position; startAtNanos = 0L } }
        OmmtPluginWire.PLAYBACK_RESUME -> if (id == sessionId) { val delay = input.readInt(); val position=input.readInt(); if (input.available()==0 && delay in 0..30_000 && position>=0) { pausedAtMs=position; startAtNanos = System.nanoTime() + delay * 1_000_000L - position * 1_000_000L } }
        OmmtPluginWire.PLAYBACK_STOP -> if (id == sessionId) { input.readUnsignedByte(); if (input.available()==0) clear() }
      }
    } catch (_: Exception) { clear() }
  }

  private fun tick() {
    if (sessionId == null || startAtNanos == 0L) return
    val now = System.nanoTime()
    // START carries a future common deadline. Treating a negative elapsed time as 0 caused all
    // time-zero notes to sound immediately, before the server/local route switch had completed.
    val elapsed = bufferedElapsedMillis(now, startAtNanos) ?: return
    var sent = 0; val player = MinecraftClient.getInstance().player ?: return
    while (cursor < notes.size && notes[cursor].time <= elapsed && sent++ < MAX_NOTES_PER_TICK) {
      val note = notes[cursor++]; val pitch = 2.0.pow((note.pitch - 12) / 12.0).toFloat()
      // The server's DEFAULT route uses the vanilla note-block instrument order.  Client-local
      // playback is restricted to this reproducible mode; unsupported routes never ACK READY.
      player.playSound(noteBlockSound(note.instrument), note.volume / 100f, pitch)
    }
    if (cursor == notes.size) clear()
  }
  private fun noteBlockSound(instrument: Int) = when (instrument) {
    0 -> SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(); 1 -> SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value()
    2 -> SoundEvents.BLOCK_NOTE_BLOCK_SNARE.value(); 3 -> SoundEvents.BLOCK_NOTE_BLOCK_HAT.value()
    4 -> SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(); 5 -> SoundEvents.BLOCK_NOTE_BLOCK_FLUTE.value()
    6 -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(); 7 -> SoundEvents.BLOCK_NOTE_BLOCK_GUITAR.value()
    8 -> SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(); 9 -> SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE.value()
    10 -> SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE.value(); 11 -> SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL.value()
    12 -> SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO.value(); 13 -> SoundEvents.BLOCK_NOTE_BLOCK_BIT.value()
    14 -> SoundEvents.BLOCK_NOTE_BLOCK_BANJO.value(); 15 -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()
    else -> SoundEvents.BLOCK_NOTE_BLOCK_HARP.value()
  }
  private fun clear() { pending = null; notes = emptyList(); sessionId = null; cursor = 0; startAtNanos = 0L; pausedAtMs = 0; answeredProbeNonce = null }
  private fun inflate(compressed: ByteArray): ByteArray = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readNBytes(MAX_BUFFER + 1).also { bytes -> require(bytes.size <= MAX_BUFFER) } }
  private fun decode(bytes: ByteArray): List<Note> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
    require(input.readInt() == 0x4F595042 && input.readUnsignedByte() == 1)
    val duration = readVarUInt(input); val count = readVarUInt(input); val strings = readVarUInt(input)
    require(duration in 0..Int.MAX_VALUE && count in 0..100_000 && strings == 0)
    var time = 0; buildList(count) { repeat(count) {
      time = Math.addExact(time, readVarUInt(input)); val instrument=readVarUInt(input); val pitch=input.readUnsignedByte(); val volume=input.readUnsignedByte(); val pan=input.readUnsignedByte()-100; val custom=readVarUInt(input)
      require(time in 0..duration && instrument in 0..255 && pitch<=24 && volume<=100 && pan in -100..100 && custom==0); add(Note(time,instrument,pitch,volume,pan))
    } }.also { require(input.available()==0) }
  }
  private fun readVarUInt(input: DataInputStream): Int {
    var result = 0
    repeat(5) { index ->
      val b = input.readUnsignedByte()
      // Int is signed: bits 31..34 must be zero, so only 0x00..0x07 is valid here.
      if (index == 4 && b > 0x07) throw IllegalArgumentException("varint overflow")
      result = result or ((b and 127) shl (index * 7))
      if (b and 128 == 0) {
        if (index > 0 && b == 0) throw IllegalArgumentException("nonminimal varint")
        return result
      }
    }
    throw IllegalArgumentException("overlong varint")
  }
}
