package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Packet-only OMMT v1 wire codec.  It has no Minecraft dependencies so the same exact envelope
 * is compiled for the maintained Minecraft 26.2 target.
 */
object OmmtPluginWire {
  const val VERSION = 1
  const val ENVELOPE_BYTES = 18
  const val MAX_PACKET_BYTES = 24 * 1024
  const val CHUNK_BYTES = 20 * 1024
  const val MAX_CHUNKS = 64
  const val MAX_COMPRESSED_BYTES = 1_048_576
  const val MAX_OYMI_BYTES = 1_048_576
  const val MAX_NOTES = 100_000

  const val UPLOAD_REQUEST = 1
  const val UPLOAD_BEGIN = 2
  const val UPLOAD_CHUNK = 3
  const val UPLOAD_FINISH = 4
  const val UPLOAD_ABORT = 5
  const val UPLOAD_READY = 64
  const val UPLOAD_STATUS = 65
  const val STATUS_PROCESSING = 1
  const val STATUS_DONE = 2
  const val STATUS_ERROR = 3
  const val CAP_COMPACT_ZLIB = 1
  const val CAP_CUSTOM_SOUND = 1 shl 1
  const val CAP_CUSTOM_SOUND_PATTERN = 1 shl 2
  /** OYMI/OYMC v4 signed cents. Never implied by the legacy compact capability. */
  const val CAP_OYMI_V4_PITCH_CENTS = 1 shl 3

  const val PLAYBACK_PROBE = 1
  const val PLAYBACK_BEGIN = 2
  const val PLAYBACK_CHUNK = 3
  const val PLAYBACK_START = 4
  const val PLAYBACK_PAUSE = 5
  const val PLAYBACK_RESUME = 6
  const val PLAYBACK_STOP = 7
  const val PLAYBACK_PROBE_RESPONSE = 8
  const val PLAYBACK_READY = 9
  const val PLAYBACK_SERVER_CAPABILITIES = 10
  const val PLAYBACK_CLIENT_CAPABILITIES = 11
  const val PLAYBACK_STARTED_ACK = 12
  const val PLAYBACK_CLIENT_PLAYBACK_FAILED = 13
  const val PLAYBACK_BANK_CONSENT = 14
  const val CAP_BRASS_NOTE_BLOCK = 1
  const val CAP_OYPB_V2 = 1 shl 1
  const val CAP_BANK_MANIFEST_V1 = 1 shl 2
  const val CAP_SOUND_CATALOG_26_2 = 1 shl 3
  const val CLIENT_CAP_OYPB_V2 = 1
  const val CLIENT_CAP_STARTED_ACK = 1 shl 1
  const val CLIENT_CAP_FIXED_CUSTOM_PATTERN = 1 shl 2
  const val CLIENT_CAP_POSITIONAL_PAN = 1 shl 3
  const val CLIENT_CAP_BANK_MANIFEST_V1 = 1 shl 4

  data class UploadReady(
      val id: UUID,
      val capabilities: Int,
      val maxOymiBytes: Int,
      val maxCompressedBytes: Int,
      val maxChunks: Int,
      val chunkBytes: Int,
  )

  data class UploadStatus(val id: UUID, val status: Int, val detail: String)

  fun uploadRequest(id: UUID): ByteArray = envelope(UPLOAD_REQUEST, id) {}

  fun uploadBegin(
      id: UUID,
      chunks: Int,
      compressedBytes: Int,
      transportBytes: Int,
      oymiBytes: Int,
      notes: Int,
      canonicalHash: ByteArray,
  ): ByteArray {
    require(chunks in 1..MAX_CHUNKS)
    require(compressedBytes in 1..MAX_COMPRESSED_BYTES)
    require(transportBytes in 1..MAX_COMPRESSED_BYTES)
    require(oymiBytes in 20..MAX_OYMI_BYTES)
    require(notes in 1..MAX_NOTES && canonicalHash.size == 32)
    return envelope(UPLOAD_BEGIN, id) {
      writeShort(chunks)
      writeInt(compressedBytes)
      writeInt(transportBytes)
      writeInt(oymiBytes)
      writeInt(notes)
      write(canonicalHash)
    }
  }

  fun uploadChunk(id: UUID, sequence: Int, total: Int, bytes: ByteArray): ByteArray {
    require(sequence in 0 until total && total in 1..MAX_CHUNKS)
    require(bytes.size in 1..CHUNK_BYTES)
    return envelope(UPLOAD_CHUNK, id) {
      writeShort(sequence)
      writeShort(total)
      writeShort(bytes.size)
      write(bytes)
    }
  }

  fun uploadFinish(id: UUID, canonicalHash: ByteArray): ByteArray {
    require(canonicalHash.size == 32)
    return envelope(UPLOAD_FINISH, id) { write(canonicalHash) }
  }

  fun uploadAbort(id: UUID, reason: Int = 0): ByteArray =
      envelope(UPLOAD_ABORT, id) { writeByte(reason.coerceIn(0, 255)) }

  fun decodeUploadServer(bytes: ByteArray): Any {
    val input = input(bytes)
    val type = input.readUnsignedByte()
    val id = UUID(input.readLong(), input.readLong())
    val decoded =
        when (type) {
          UPLOAD_READY ->
              UploadReady(
                  id,
                  input.readInt(),
                  input.readInt(),
                  input.readInt(),
                  input.readUnsignedShort(),
                  input.readUnsignedShort(),
              )
          UPLOAD_STATUS -> {
            val status = input.readUnsignedByte()
            val detail = input.readUTF()
            require(status in STATUS_PROCESSING..STATUS_ERROR)
            require(detail.length <= 64 && detail.all { it.code in 0x20..0x7e })
            UploadStatus(id, status, detail)
          }
          else -> throw IllegalArgumentException("unexpected upload packet type")
        }
    require(input.available() == 0) { "trailing upload packet bytes" }
    return decoded
  }

  fun playbackProbeResponse(nonce: String): ByteArray {
    require(nonce.matches(Regex("[A-Za-z0-9_-]{22}")))
    return envelope(PLAYBACK_PROBE_RESPONSE, UUID(0L, 0L)) { writeUTF(nonce) }
  }

  fun playbackReady(session: UUID, hash: ByteArray): ByteArray {
    require(hash.size == 32)
    return envelope(PLAYBACK_READY, session) { write(hash) }
  }

  fun playbackClientCapabilities(nonce: String, bits: Int): ByteArray {
    require(nonce.matches(Regex("[A-Za-z0-9_-]{22}")))
    return envelope(PLAYBACK_CLIENT_CAPABILITIES, UUID(0L, 0L)) { writeUTF(nonce); writeInt(bits) }
  }

  fun playbackStartedAck(session: UUID, hash: ByteArray, firstDispatchedNoteTimeMs: Int): ByteArray {
    require(hash.size == 32 && firstDispatchedNoteTimeMs >= 0)
    return envelope(PLAYBACK_STARTED_ACK, session) { write(hash); writeInt(firstDispatchedNoteTimeMs) }
  }

  fun playbackFailed(session: UUID, reason: Int, clientPositionMs: Int): ByteArray {
    require(reason in 1..6 && clientPositionMs >= 0)
    return envelope(PLAYBACK_CLIENT_PLAYBACK_FAILED, session) { writeByte(reason); writeInt(clientPositionMs) }
  }

  fun playbackBankConsent(allowed: Boolean, manifestHash: ByteArray): ByteArray {
    require(manifestHash.size == 32)
    // allowed==false must carry zero hash; allowed==true must carry non-zero configured hash.
    require((!allowed && manifestHash.all { it == 0.toByte() }) || (allowed && manifestHash.any { it != 0.toByte() }))
    return envelope(PLAYBACK_BANK_CONSENT, UUID(0L, 0L)) { writeByte(if (allowed) 1 else 0); write(manifestHash) }
  }

  fun envelope(type: Int, id: UUID, body: DataOutputStream.() -> Unit): ByteArray =
      ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
          output.writeByte(VERSION)
          output.writeByte(type)
          output.writeLong(id.mostSignificantBits)
          output.writeLong(id.leastSignificantBits)
          output.body()
        }
        require(bytes.size() in ENVELOPE_BYTES..MAX_PACKET_BYTES)
        bytes.toByteArray()
      }

  fun input(bytes: ByteArray): DataInputStream {
    require(bytes.size in ENVELOPE_BYTES..MAX_PACKET_BYTES) { "packet size out of bounds" }
    val input = DataInputStream(ByteArrayInputStream(bytes))
    require(input.readUnsignedByte() == VERSION) { "unsupported protocol version" }
    return input
  }
}
