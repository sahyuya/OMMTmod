package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.google.gson.JsonParser
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.pow
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/** Resolves editor and OYPB audio from the resource pack that is actually active in this client. */
object SoundBankManifest {
  data class Resolved(val eventId: String, val pitch: Float, val seed: Long)

  private val manifestId =
      Identifier.fromNamespaceAndPath("oyasaimusic", "ommt-bank-manifest.json")
  private val anchors = intArrayOf(-36, -12, 36, 60)
  private val runtimeEvents =
      arrayOf(
          "minecraft:block.note_block.harp",
          "minecraft:block.note_block.basedrum",
          "minecraft:block.note_block.snare",
          "minecraft:block.note_block.hat",
          "minecraft:block.note_block.bass",
          "minecraft:block.note_block.flute",
          "minecraft:block.note_block.bell",
          "minecraft:block.note_block.guitar",
          "minecraft:block.note_block.chime",
          "minecraft:block.note_block.xylophone",
          "minecraft:block.note_block.iron_xylophone",
          "minecraft:block.note_block.cow_bell",
          "minecraft:block.note_block.didgeridoo",
          "minecraft:block.note_block.bit",
          "minecraft:block.note_block.banjo",
          "minecraft:block.note_block.pling",
          "minecraft:block.note_block.trumpet",
          "minecraft:block.note_block.trumpet_exposed",
          "minecraft:block.note_block.trumpet_oxidized",
          "minecraft:block.note_block.trumpet_weathered",
      )

  fun activeHash(): ByteArray? = readManifest()?.second

  fun matchesActiveHash(expected: ByteArray): Boolean =
      expected.size == 32 && activeHash()?.let { MessageDigest.isEqual(it, expected) } == true

  fun resolveInstrument(runtimeId: Int, pitchCents: Int, requireBank: Boolean): Resolved? {
    val ordinary = runtimeEvents.getOrNull(runtimeId) ?: return null
    if (!requireBank || useVanilla(pitchCents)) {
      return Resolved(ordinary, foldedPitch(pitchCents), 0L)
    }
    val anchor = nearestAnchor(pitchCents)
    return Resolved(
        "oyasaimusic:bank/i/$runtimeId/a/${anchorToken(anchor)}",
        residualPitch(pitchCents, anchor),
        0L,
    )
  }

  fun resolveCustom(
      eventId: String,
      pattern: Int,
      seed: Long,
      pitchCents: Int,
      requireBank: Boolean,
  ): Resolved? {
    val canonical = canonicalEvent(eventId) ?: return null
    if (pattern !in 1..65_535) return null
    if (!requireBank || useVanilla(pitchCents)) {
      return Resolved(canonical, foldedPitch(pitchCents), seed)
    }
    val anchor = nearestAnchor(pitchCents)
    val key = sha256(canonical.toByteArray(Charsets.UTF_8)).toHex().take(16)
    return Resolved(
        "oyasaimusic:bank/c/$key/p/$pattern/a/${anchorToken(anchor)}",
        residualPitch(pitchCents, anchor),
        seed,
    )
  }

  fun isAvailable(eventId: String): Boolean {
    val id = Identifier.tryParse(eventId) ?: return false
    return Minecraft.getInstance().soundManager.getSoundEvent(id) != null
  }

  /** Stable OYMI editor ids differ only for bass/percussion from OYPB runtime ids. */
  fun stableToRuntime(stableId: Int): Int? =
      when (stableId) {
        0 -> 0
        1 -> 4
        2 -> 1
        3 -> 2
        4 -> 3
        in 5..19 -> stableId
        else -> null
      }

  private fun readManifest(): Pair<ByteArray, ByteArray>? = runCatching {
    // Primary: via ResourceManager (handles mod bundled pack with forward slashes and future server packs).
    var resource = Minecraft.getInstance().resourceManager.getResource(manifestId).orElse(null)
    if (resource == null) {
      // Fallback: direct class-loader for bundled mod assets (works even before resource reload).
      try {
        val stream = SoundBankManifest::class.java.getResourceAsStream("/assets/oyasaimusic/ommt-bank-manifest.json")
        if (stream != null) {
          val bytes = stream.use { it.readBytes() }
          require(bytes.size <= MAX_MANIFEST_BYTES)
          val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
          require(root.get("schemaVersion")?.asInt == 1)
          require(root.get("minecraftVersion")?.asString == "26.2")
          require(root.get("assetIndexId")?.asInt == 32)
          val actualAnchors = root.getAsJsonArray("anchors")?.map { it.asInt } ?: emptyList()
          require(actualAnchors == anchors.toList())
          return@runCatching bytes to sha256(bytes)
        }
      } catch (_: Exception) {}
      return null
    }
    val bytes = resource.open().use { input ->
      input.readNBytes(MAX_MANIFEST_BYTES + 1).also { require(it.size <= MAX_MANIFEST_BYTES) }
    }
    val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    require(root.get("schemaVersion")?.asInt == 1)
    require(root.get("minecraftVersion")?.asString == "26.2")
    require(root.get("assetIndexId")?.asInt == 32)
    val actualAnchors = root.getAsJsonArray("anchors")?.map { it.asInt } ?: emptyList()
    require(actualAnchors == anchors.toList())
    bytes to sha256(bytes)
  }.getOrNull()

  private fun canonicalEvent(value: String): String? {
    val canonical = value.lowercase().let { if (':' in it) it else "minecraft:$it" }
    return canonical.takeIf {
      it.toByteArray(Charsets.UTF_8).size in 1..256 &&
          it.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))
    }
  }

  private fun nearestAnchor(pitchCents: Int): Int =
      anchors.minWith(compareBy<Int> { abs(pitchCents - it * 100) }.thenBy { it })

  private fun useVanilla(pitchCents: Int): Boolean =
      pitchCents in VANILLA_MIN_CENTS..VANILLA_MAX_CENTS ||
          pitchCents !in BANK_MIN_CENTS..BANK_MAX_CENTS

  private fun residualPitch(pitchCents: Int, anchor: Int): Float =
      2.0.pow((pitchCents - anchor * 100) / 1200.0).toFloat().coerceIn(0.5f, 2f)

  private fun foldedPitch(pitchCentsInput: Int): Float {
    var cents = pitchCentsInput.coerceIn(-5400, 7300)
    while (cents < 0) cents += 1200
    while (cents > 2400) cents -= 1200
    return 2.0.pow((cents - 1200) / 1200.0).toFloat()
  }

  private fun anchorToken(anchor: Int): String = if (anchor < 0) "m${-anchor}" else "p$anchor"
  private fun sha256(bytes: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(bytes)
  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
  private const val VANILLA_MIN_CENTS = 0
  private const val VANILLA_MAX_CENTS = 2_400
  private const val BANK_MIN_CENTS = -4_800
  private const val BANK_MAX_CENTS = 7_200
}
