package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Bounded, pure playback envelope parser used by tests and the client boundary. */
object PlaybackWireCodec {
  const val VERSION=1; const val MAX=24*1024
  fun encode(type:Int,id:UUID,body:DataOutputStream.()->Unit={}):ByteArray=ByteArrayOutputStream().use{b->DataOutputStream(b).use{o->o.writeByte(VERSION);o.writeByte(type);o.writeLong(id.mostSignificantBits);o.writeLong(id.leastSignificantBits);o.body()};b.toByteArray()}
  fun decode(bytes:ByteArray):Triple<Int,UUID,DataInputStream>{require(bytes.size in 18..MAX);val i=DataInputStream(ByteArrayInputStream(bytes));require(i.readUnsignedByte()==VERSION);val type=i.readUnsignedByte();require(type in 1..7);return Triple(type,UUID(i.readLong(),i.readLong()),i)}
}
