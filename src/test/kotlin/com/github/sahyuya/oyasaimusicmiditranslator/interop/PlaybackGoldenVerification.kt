package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.HexFormat
import java.util.UUID

/** Calls the production receiver's decoders without starting Minecraft. */
internal object PlaybackGoldenVerification {
  fun run() {
    val fixture =
        checkNotNull(javaClass.getResourceAsStream("/oypb-306.hex")).bufferedReader().use { it.readLines() }
    val type = Class.forName("com.github.sahyuya.oyasaimusicmiditranslator.client.PlaybackLane")
    val lane = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    val decode = type.getDeclaredMethod("decodeV2", ByteArray::class.java).apply {
      isAccessible = true
    }
    val bytes = HexFormat.of().parseHex(fixture[1])
    val decoded = decode.invoke(lane, bytes)
    check(field(decoded, "duration") == 50)
    val note = (field(decoded, "notes") as List<*>).single()!!
    check(field(note, "time") == 50)
    check(field(note, "volume") == 63)
    check(field(note, "pan") == -20)
    check(field(note, "pitchCents") == 3600)
    check(field(note, "customEvent") == "minecraft:block.note_block.harp")
    check(field(note, "customPattern") == 2)
    check(field(note, "customSeed") == 421L)
    for (invalid in listOf(
        HexFormat.of().parseHex(fixture[0]),
        bytes.copyOf(bytes.size - 1),
        bytes + 0.toByte(),
        bytes.copyOf().apply { this[4] = 99 },
    )) {
      val failure = runCatching { decode.invoke(lane, invalid) }.exceptionOrNull()
      check(failure is InvocationTargetException) { "OYPB v2 accepted unsupported or invalid bytes" }
    }
    verifyDuplicateBegin(type)
    println("OYPB shared golden v2, legacy rejection, malformed payloads and duplicate BEGIN: PASS")
  }

  private fun verifyDuplicateBegin(type: Class<*>) {
    val lane = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    val begin =
        type
            .getDeclaredMethod("receiveBegin", UUID::class.java, DataInputStream::class.java)
            .apply { isAccessible = true }
    fun body(compressed: Int = 100, mode: Int = 1) =
        DataInputStream(
            ByteArrayInputStream(
                ByteArrayOutputStream()
                    .also { bytes ->
                      DataOutputStream(bytes).use {
                        it.writeShort(1)
                        it.writeInt(compressed)
                        it.write(ByteArray(32) { 7 })
                        it.writeInt(1000)
                        it.writeByte(mode)
                        it.writeInt(500)
                      }
                    }
                    .toByteArray()
            )
        )
    val id = UUID.randomUUID()
    begin.invoke(lane, id, body(mode = 0))
    check(field(lane, "pending") == null) { "Legacy BEGIN allocated pending data" }
    begin.invoke(lane, id, body())
    val original = field(lane, "pending")!!
    begin.invoke(lane, id, body())
    check(field(lane, "pending") === original) { "Duplicate BEGIN reset pending data" }
    begin.invoke(lane, id, body(101))
    check(field(lane, "pending") === original) { "Conflicting BEGIN replaced an existing attempt" }
    type.getDeclaredField("pending").apply { isAccessible = true }.set(lane, null)
    type.getDeclaredField("sessionId").apply { isAccessible = true }.set(lane, id)
    type.getDeclaredField("cursor").apply { isAccessible = true }.setInt(lane, 5)
    begin.invoke(lane, id, body())
    check(field(lane, "sessionId") == id && field(lane, "pending") == null)
    check(field(lane, "cursor") == 5) { "Late BEGIN reset active playback" }
  }

  private fun field(instance: Any, name: String): Any? =
      instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
