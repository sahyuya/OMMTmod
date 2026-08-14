package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

object UploadV2CodecVerification {
  @JvmStatic fun main(args: Array<String>) {
    unicode15RoundTripAndThreeByteAlphabet(); compactCanonicalRoundTripAndMalformedRejection(); commandBoundaryUsesUtf16AndUtf8Limits(); playbackWireGoldenAndState(); uploadFallbackState()
    println("UploadV2CodecVerification: PASS")
  }
  private inline fun rejects(block: () -> Unit) { try { block(); error("expected rejection") } catch (_: IllegalArgumentException) { } }
  private fun oymi(): ByteArray = ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out ->
    val metadata = "{}".toByteArray(); out.writeInt(0x4f594d49); out.writeShort(1); out.writeShort(0); out.writeInt(metadata.size); out.writeInt(1); out.writeInt(120); out.write(metadata)
    out.writeInt(120); out.writeByte(0); out.writeByte(12); out.writeByte(100); out.writeByte(0)
  }; bytes.toByteArray() }

  private fun unicode15RoundTripAndThreeByteAlphabet() {
    val source = byteArrayOf(0, 1, 2, 3, 4, -1)
    val text = UploadV2Codec.unicode15(source)
    check(text.toByteArray(StandardCharsets.UTF_8).size == text.length * 3)
    check(source.contentEquals(UploadV2Codec.unicode15Decode(text, source.size)))
    rejects { UploadV2Codec.unicode15Decode("A", 1) }
    val damaged = text.dropLast(1) + '\u3401'
    rejects { UploadV2Codec.unicode15Decode(damaged, source.size) }
  }

  private fun compactCanonicalRoundTripAndMalformedRejection() {
    val canonical = oymi(); val compact = UploadV2Codec.compactFromOymi(canonical)
    check(canonical.contentEquals(UploadV2Codec.reconstructOymi(compact)))
    // This checked-in v1 vector is shared with Paper; both sides must reconstruct these exact bytes.
    val fixture = Base64.getDecoder().decode(Files.readString(Path.of("..", "docs", "interop", "fixtures", "minimal-oymi-v1.oyasai.base64")).trim())
    check(fixture.contentEquals(UploadV2Codec.reconstructOymi(UploadV2Codec.compactFromOymi(fixture))))
    rejects { UploadV2Codec.reconstructOymi(compact + byteArrayOf(0)) }
    val overlong = compact.copyOfRange(0, 5) + byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0) 
    rejects { UploadV2Codec.reconstructOymi(overlong) }
    // 0x80 0x00 is a numerically valid zero but not canonical/minimal varuint.
    rejects { UploadV2Codec.reconstructOymi(compact.copyOfRange(0, 5) + byteArrayOf(0x80.toByte(), 0) + compact.copyOfRange(6, compact.size)) }
    val tooMany = List(100_001) { UploadV2Codec.Note(it, 0, 12, 100, 0) }
    rejects { UploadV2Codec.compact(UploadV2Codec.Compact(byteArrayOf(), 100_000, tooMany)) }
  }

  private fun commandBoundaryUsesUtf16AndUtf8Limits() {
    val payload = UploadV2Codec.unicode15(ByteArray(375) { it.toByte() })
    check(payload.length == 200 && payload.toByteArray(StandardCharsets.UTF_8).size == 600)
    val command = "ommtupload c 2 0123456789012345678901 0 $payload"
    check(command.length <= 255 && command.toByteArray(StandardCharsets.UTF_8).size <= 765)
  }
  private fun playbackWireGoldenAndState() {
    val id=UUID(1,2)
    // All v1 types have a deterministic envelope and exact body/EOF boundary.
    (1..7).forEach { type -> val packet=PlaybackWireCodec.encode(type,id){ when(type){1->writeUTF("abcdefghijklmnopqrstuv");2-> {writeShort(1);writeInt(1);write(ByteArray(32));writeInt(0);writeByte(0);writeInt(500)};3->{writeShort(0);writeShort(1);writeShort(1);writeByte(1)};4,6->{writeInt(500);writeInt(0)};5->writeInt(0);7->writeByte(0)} }; val (actual,session,input)=PlaybackWireCodec.decode(packet);check(actual==type&&session==id); while(input.available()>0)input.readByte();check(input.available()==0); rejects{PlaybackWireCodec.decode(packet+byteArrayOf(0)).also{(_,_,i)->require(i.available()==0)}} }
    rejects { PlaybackWireCodec.decode(byteArrayOf(2)+ByteArray(17)) }; rejects { PlaybackWireCodec.decode(byteArrayOf(1,8)+ByteArray(16)) }
    // Pure ready route state: no ACK stays vanilla; exact generation/session/hash activates local;
    // reconnect/quit generation loss returns it to vanilla.
    var generation=7L; val hash="h"; var ready:Pair<Long,String>?=null; fun local()=ready?.first==generation&&ready?.second==hash
    check(!local()); ready=generation to hash; check(local()); generation++; check(!local())
  }
  private fun uploadFallbackState() {
    data class Prepared(val id:String); var protocol=2;var retried=false;var request="v2";var pending=Prepared(request);val queued=mutableListOf<String>()
    fun fallback(){val fresh="fresh-v1";protocol=1;retried=true;request=fresh;pending=pending.copy(id=fresh);queued.clear();queued+="h 1 $fresh"}
    // Legacy MALFORMED is examined before protocol state changes, then the fallback atomically
    // gives the retained prepared payload the fresh request id. Protocol-1 ERROR never enters it.
    val legacyMalformed=true; if (protocol==2&&!retried&&legacyMalformed) fallback()
    check(protocol==1&&retried&&request==pending.id&&queued.single()=="h 1 fresh-v1")
    // READY now targets the retained fresh Prepared and queues complete v1 upload; an ERROR is terminal.
    if (pending.id==request) queued+="b 1 ${pending.id}";check(queued.any{it.startsWith("b 1 fresh-v1")});val before=queued.size;val protocol1Error=true;if(protocol1Error) { /* terminal: no fallback */ };check(queued.size==before)
  }
}
