package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Pure OYMI/OYMC codec. v4 stores signed cents without changing legacy v1..v3 bytes. */
object UploadV2Codec {
  const val MAX_BYTES = 1_048_576
  const val MAX_NOTES = 100_000
  data class Note(val time: Int, val instrument: Int, val pitch: Int, val volume: Int, val pan: Int, val pitchCents: Int = pitch * 100)
  data class Compact(val metadata: ByteArray, val duration: Int, val notes: List<Note>, val oymiVersion: Int = 1)

  fun unicode15(bytes: ByteArray): String { val chars=StringBuilder((bytes.size*8+14)/15);var bits=0;var count=0;bytes.forEach{b->bits=(bits shl 8)or(b.toInt()and 255);count+=8;while(count>=15){count-=15;chars.append(codePoint((bits ushr count)and 0x7fff))}};if(count>0)chars.append(codePoint((bits shl(15-count))and 0x7fff));return chars.toString() }
  fun unicode15Decode(text: String, byteCount: Int): ByteArray { require(byteCount in 0..MAX_BYTES&&text.length==(byteCount*8+14)/15);val out=ByteArrayOutputStream(byteCount);var bits=0;var count=0;text.forEach{c->bits=(bits shl 15)or value(c);count+=15;while(count>=8&&out.size()<byteCount){count-=8;out.write((bits ushr count)and 255)}};require(out.size()==byteCount);if(count>0)require((bits and((1 shl count)-1))==0){"nonzero Unicode15 padding"};return out.toByteArray() }
  private fun codePoint(v:Int):Char=when(v){in 0..6591->(0x3400+v).toChar();in 6592..27583->(0x4e00+v-6592).toChar();else->(0xe000+v-27584).toChar()}
  private fun value(c:Char):Int=when(c.code){in 0x3400..0x4dbf->c.code-0x3400;in 0x4e00..0x9fff->c.code-0x4e00+6592;in 0xe000..0xf43f->c.code-0xe000+27584;else->throw IllegalArgumentException("invalid Unicode15 alphabet")}

  fun compactFromOymi(oymi: ByteArray): ByteArray {
    require(oymi.size in 20..MAX_BYTES){"OYMI size"}; val input=DataInputStream(ByteArrayInputStream(oymi));require(input.readInt()==0x4f594d49)
    val version=input.readUnsignedShort();require(version in 1..4&&input.readUnsignedShort()==0);val metadataLen=input.readInt();val count=input.readInt();val duration=input.readInt()
    require(metadataLen in 2..(oymi.size-20)&&count in 1..MAX_NOTES&&duration>=0){"OYMI header"};val recordSize=if(version==4)9 else 8
    require(20L+metadataLen.toLong()+count.toLong()*recordSize==oymi.size.toLong()){"OYMI record length"};val metadata=ByteArray(metadataLen);input.readFully(metadata);val notes=ArrayList<Note>(count)
    repeat(count){val time=input.readInt();val inst=input.readUnsignedByte();val cents=if(version==4)input.readShort().toInt() else input.readUnsignedByte()*100;val vol=input.readUnsignedByte();val pan=input.readByte().toInt();validate(time,inst,cents,vol,pan,duration, version);notes+=Note(time,inst,cents/100,vol,pan,cents)}
    require(input.available()==0);return compact(Compact(metadata,duration,notes,version))
  }

  fun compact(value: Compact): ByteArray=ByteArrayOutputStream().use{bytes->DataOutputStream(bytes).use{out->
    val notes=value.notes.sortedBy{it.time};require(notes.size in 1..MAX_NOTES&&value.metadata.size in 0..MAX_BYTES&&value.oymiVersion in 1..4);out.writeInt(0x4f594d43);out.writeByte(value.oymiVersion);varUInt(out,value.metadata.size);varUInt(out,notes.size);varUInt(out,value.duration);out.write(value.metadata)
    var previous=0;notes.forEach{note->validate(note.time,note.instrument,note.pitchCents,note.volume,note.pan,value.duration, value.oymiVersion);require(note.time>=previous);varUInt(out,note.time-previous);previous=note.time;if(value.oymiVersion==4){out.writeByte(note.instrument);out.writeShort(note.pitchCents);out.writeByte(note.volume);out.writeByte(note.pan+100)}else{require(note.pitchCents%100==0&&note.pitchCents in 0..2400);val p=note.pitchCents/100;out.writeByte((note.instrument shl 4)or(p ushr 1));out.writeByte(((p and 1)shl 7)or note.volume);out.writeByte(note.pan+100)}}
  };bytes.toByteArray()}

  fun reconstructOymi(compact: ByteArray): ByteArray {
    require(compact.size in 1..MAX_BYTES){"OYMC size"};val input=DataInputStream(ByteArrayInputStream(compact));require(input.readInt()==0x4f594d43);val version=input.readUnsignedByte();require(version in 1..4);val metadataLen=varUInt(input);val count=varUInt(input);val duration=varUInt(input);require(metadataLen in 0..MAX_BYTES&&count in 1..MAX_NOTES&&duration>=0);require(9L+metadataLen.toLong()+count.toLong()*(if(version==4)5 else 3)<=compact.size.toLong()){"OYMC record length"};val metadata=ByteArray(metadataLen);input.readFully(metadata);val notes=ArrayList<Note>(count);var time=0
    repeat(count){time=Math.addExact(time,varUInt(input));val inst:Int;val cents:Int;val vol:Int;val pan:Int;if(version==4){inst=input.readUnsignedByte();cents=input.readShort().toInt();vol=input.readUnsignedByte();pan=input.readUnsignedByte()-100}else{val b0=input.readUnsignedByte();val b1=input.readUnsignedByte();val p=input.readUnsignedByte();inst=b0 ushr 4;cents=(((b0 and 15)shl 1)or(b1 ushr 7))*100;vol=b1 and 127;pan=p-100};validate(time,inst,cents,vol,pan,duration, version);notes+=Note(time,inst,cents/100,vol,pan,cents)}
    require(input.available()==0);val size=20L+metadata.size+count.toLong()*(if(version==4)9 else 8);require(size<=MAX_BYTES);return ByteArrayOutputStream(size.toInt()).use{bytes->DataOutputStream(bytes).use{out->out.writeInt(0x4f594d49);out.writeShort(version);out.writeShort(0);out.writeInt(metadata.size);out.writeInt(count);out.writeInt(duration);out.write(metadata);notes.forEach{n->out.writeInt(n.time);out.writeByte(n.instrument);if(version==4)out.writeShort(n.pitchCents)else out.writeByte(n.pitchCents/100);out.writeByte(n.volume);out.writeByte(n.pan)}};bytes.toByteArray()}
  }
  private fun validate(time:Int,inst:Int,cents:Int,volume:Int,pan:Int,duration:Int, oymiVersion:Int = 4){require(time in 0..duration&&inst in 0..(if (oymiVersion==4) 19 else 15)&&cents in -5400..7300&&volume in 0..100&&pan in -100..100){"OYMI note"}}
  private fun varUInt(out:DataOutputStream,value:Int){require(value>=0);var v=value;repeat(5){if(v and -128==0){out.writeByte(v);return};out.writeByte((v and 127)or 128);v=v ushr 7};error("overlong varuint")}
  private fun varUInt(input:DataInputStream):Int{var value=0;repeat(5){i->val b=input.readUnsignedByte();if(i==4&&b>15)throw IllegalArgumentException("overlong varuint");value=value or((b and 127)shl(i*7));if(b and 128==0){if(i>0&&b==0)throw IllegalArgumentException("nonminimal varuint");return value}};throw IllegalArgumentException("overlong varuint")}
}
