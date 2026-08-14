package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Pure v3 upload codec. No Minecraft API: it is also suitable for golden-vector tests. */
object UploadV2Codec {
  const val MAX_BYTES = 1_048_576
  const val MAX_NOTES = 100_000

  data class Note(val time: Int, val instrument: Int, val pitch: Int, val volume: Int, val pan: Int)
  data class Compact(val metadata: ByteArray, val duration: Int, val notes: List<Note>)

  fun unicode15(bytes: ByteArray): String {
    val chars = StringBuilder((bytes.size * 8 + 14) / 15); var bits = 0; var count = 0
    bytes.forEach { byte -> bits = (bits shl 8) or (byte.toInt() and 255); count += 8
      while (count >= 15) { count -= 15; chars.append(codePoint((bits ushr count) and 0x7fff)) }
    }
    if (count > 0) chars.append(codePoint((bits shl (15 - count)) and 0x7fff))
    return chars.toString()
  }
  fun unicode15Decode(text: String, byteCount: Int): ByteArray {
    require(byteCount in 0..MAX_BYTES && text.length == (byteCount * 8 + 14) / 15)
    val out = ByteArrayOutputStream(byteCount); var bits = 0; var count = 0
    text.forEach { char -> bits = (bits shl 15) or value(char); count += 15
      while (count >= 8 && out.size() < byteCount) { count -= 8; out.write((bits ushr count) and 255) }
    }
    require(out.size() == byteCount)
    if (count > 0) require((bits and ((1 shl count) - 1)) == 0) { "nonzero Unicode15 padding" }
    return out.toByteArray()
  }
  private fun codePoint(value: Int): Char = when (value) { in 0..6591 -> (0x3400 + value).toChar(); in 6592..27583 -> (0x4e00 + value - 6592).toChar(); else -> (0xe000 + value - 27584).toChar() }
  private fun value(char: Char): Int = when (char.code) { in 0x3400..0x4dbf -> char.code - 0x3400; in 0x4e00..0x9fff -> char.code - 0x4e00 + 6592; in 0xe000..0xf43f -> char.code - 0xe000 + 27584; else -> throw IllegalArgumentException("invalid Unicode15 alphabet") }

  fun compactFromOymi(oymi: ByteArray): ByteArray {
    val input = DataInputStream(ByteArrayInputStream(oymi)); require(input.readInt() == 0x4f594d49 && input.readUnsignedShort() == 1 && input.readUnsignedShort() == 0)
    val metadata = ByteArray(input.readInt()); val count = input.readInt(); val duration = input.readInt(); require(count in 1..MAX_NOTES); input.readFully(metadata)
    val notes = ArrayList<Note>(count); repeat(count) { notes += Note(input.readInt(), input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte(), input.readByte().toInt()) }; require(input.available() == 0)
    return compact(Compact(metadata, duration, notes))
  }
  fun compact(value: Compact): ByteArray = ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out ->
    val ordered = value.notes.sortedBy { it.time }; require(ordered.size in 1..MAX_NOTES && value.metadata.size in 0..MAX_BYTES)
    out.writeInt(0x4f594d43); out.writeByte(1); varUInt(out, value.metadata.size); varUInt(out, ordered.size); varUInt(out, value.duration); out.write(value.metadata)
    var previous = 0; ordered.forEach { note -> require(note.time >= previous && note.time <= value.duration && note.instrument in 0..15 && note.pitch in 0..24 && note.volume in 0..100 && note.pan in -100..100); varUInt(out, note.time - previous); previous = note.time; out.writeByte((note.instrument shl 4) or (note.pitch ushr 1)); out.writeByte(((note.pitch and 1) shl 7) or note.volume); out.writeByte(note.pan + 100) }
  }; bytes.toByteArray() }
  fun reconstructOymi(compact: ByteArray): ByteArray {
    val input = DataInputStream(ByteArrayInputStream(compact)); require(input.readInt() == 0x4f594d43 && input.readUnsignedByte() == 1)
    val metadataLen = varUInt(input); val count = varUInt(input); val duration = varUInt(input); require(metadataLen in 0..MAX_BYTES && count in 1..MAX_NOTES && duration in 0..Int.MAX_VALUE)
    val metadata = ByteArray(metadataLen); input.readFully(metadata); val notes = ArrayList<Note>(count); var time = 0
    repeat(count) { time = Math.addExact(time, varUInt(input)); val b0 = input.readUnsignedByte(); val b1 = input.readUnsignedByte(); val pan = input.readUnsignedByte(); val note = Note(time, b0 ushr 4, ((b0 and 15) shl 1) or (b1 ushr 7), b1 and 127, pan - 100); require(note.instrument in 0..15 && note.pitch in 0..24 && note.volume in 0..100 && pan <= 200 && time <= duration); notes += note }
    require(input.available() == 0)
    return ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out -> out.writeInt(0x4f594d49); out.writeShort(1); out.writeShort(0); out.writeInt(metadata.size); out.writeInt(count); out.writeInt(duration); out.write(metadata); notes.forEach { out.writeInt(it.time); out.writeByte(it.instrument); out.writeByte(it.pitch); out.writeByte(it.volume); out.writeByte(it.pan) } }; bytes.toByteArray() }
  }
  private fun varUInt(out: DataOutputStream, value: Int) { require(value >= 0); var v = value; repeat(5) { if (v and -128 == 0) { out.writeByte(v); return }; out.writeByte((v and 127) or 128); v = v ushr 7 }; error("overlong varuint") }
  private fun varUInt(input: DataInputStream): Int { var value = 0; repeat(5) { index -> val b = input.readUnsignedByte(); if (index == 4 && b > 15) throw IllegalArgumentException("overlong varuint"); value = value or ((b and 127) shl (index * 7)); if (b and 128 == 0) { if (index > 0 && b == 0) throw IllegalArgumentException("nonminimal varuint"); return value } }; throw IllegalArgumentException("overlong varuint") }
}
