package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class EditorSettingsBundle(val settings: EditorSettings, val layout: String)

/** Portable text containing editor preferences, keymap, theme and the ImGui docking layout. */
object EditorSettingsBundleCodec {
  private const val PREFIX = "OMMTCFG1:"
  private const val MAGIC = 0x4f4d4346 // OMCF
  private const val VERSION = 1
  private const val MAX_LAYOUT_BYTES = 512 * 1024
  private const val MAX_BINARY_BYTES = 1024 * 1024

  fun encode(settings: EditorSettings, layout: String): String {
    validate(settings)
    val body = ByteArrayOutputStream().use { bytes ->
      GZIPOutputStream(bytes).use { gzip -> DataOutputStream(gzip).use { out -> write(out, settings, layout) } }
      bytes.toByteArray()
    }
    require(body.size <= MAX_BINARY_BYTES) { "Encoded OMMT settings exceed 1 MiB" }
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body)
  }

  fun decode(value: String): EditorSettingsBundle {
    val normalized = value.trim()
    require(normalized.startsWith(PREFIX)) { "Invalid OMMT settings prefix" }
    val binary = runCatching { Base64.getUrlDecoder().decode(normalized.removePrefix(PREFIX)) }
        .getOrElse { throw IllegalArgumentException("Invalid OMMT settings text", it) }
    require(binary.size in 1..MAX_BINARY_BYTES) { "Invalid OMMT settings size" }
    return GZIPInputStream(ByteArrayInputStream(binary)).use { gzip ->
      DataInputStream(gzip).use { input -> read(input).also { require(input.read() == -1) { "Trailing OMMT settings data" } } }
    }
  }

  private fun write(out: DataOutputStream, settings: EditorSettings, layout: String) {
    out.writeInt(MAGIC); out.writeShort(VERSION)
    out.writeBoolean(settings.compactToolbar); out.writeBoolean(settings.showLibrary); out.writeBoolean(settings.showInspector); out.writeBoolean(settings.showAutomation)
    writeString(out, settings.gridDensity, 16); out.writeBoolean(settings.showOtherParts); out.writeInt(settings.followLead); out.writeByte(settings.lastTool.ordinal); out.writeInt(settings.uiScalePercent); out.writeByte(settings.theme.ordinal)
    out.writeByte(settings.wheelPlain.ordinal); out.writeByte(settings.wheelShift.ordinal); out.writeByte(settings.wheelControl.ordinal); out.writeByte(settings.wheelAlt.ordinal)
    out.writeByte(settings.rangeSelectionModifier.ordinal); out.writeByte(settings.additiveSelectionModifier.ordinal); out.writeByte(settings.panMouseButton.ordinal)
    out.writeInt(EditorAction.entries.size)
    EditorAction.entries.forEach { action -> writeString(out, settings.keymap[action].encode(), 64) }
    writeString(out, layout, MAX_LAYOUT_BYTES)
  }

  private fun read(input: DataInputStream): EditorSettingsBundle {
    require(input.readInt() == MAGIC && input.readUnsignedShort() == VERSION) { "Unsupported OMMT settings version" }
    val compact = input.readBoolean(); val library = input.readBoolean(); val inspector = input.readBoolean(); val automation = input.readBoolean()
    val density = readString(input, 16).also { require(it in setOf("AUTO", "SPARSE", "NORMAL", "DENSE")) }
    val otherParts = input.readBoolean(); val followLead = input.readInt(); val tool = enumAt<EditorTool>(input.readUnsignedByte(), "editor tool"); val scale = input.readInt(); val theme = enumAt<EditorTheme>(input.readUnsignedByte(), "theme")
    val wheelPlain = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action"); val wheelShift = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action"); val wheelControl = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action"); val wheelAlt = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action")
    val range = enumAt<GestureModifier>(input.readUnsignedByte(), "selection modifier"); val additive = enumAt<GestureModifier>(input.readUnsignedByte(), "selection modifier"); val pan = enumAt<PanMouseButton>(input.readUnsignedByte(), "pan button")
    require(input.readInt() == EditorAction.entries.size) { "Invalid OMMT keymap size" }
    val defaults = EditorKeymap()
    val keymap = EditorKeymap(EditorAction.entries.associateWith { action -> EditorKeyStroke.parse(readString(input, 64), defaults[action]) })
    val settings = EditorSettings(4, compact, library, inspector, automation, density, otherParts, followLead, tool, scale, theme, keymap, wheelPlain, wheelShift, wheelControl, wheelAlt, range, additive, pan)
    validate(settings)
    return EditorSettingsBundle(settings, readString(input, MAX_LAYOUT_BYTES))
  }

  private fun validate(value: EditorSettings) {
    require(value.version == 4 && value.gridDensity in setOf("AUTO", "SPARSE", "NORMAL", "DENSE"))
    require(value.followLead in 20..70 && value.uiScalePercent in 75..150)
  }

  private fun writeString(out: DataOutputStream, value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8); require(bytes.size <= maximumBytes)
    out.writeInt(bytes.size); out.write(bytes)
  }
  private fun readString(input: DataInputStream, maximumBytes: Int): String {
    val size = input.readInt(); require(size in 0..maximumBytes)
    val bytes = input.readNBytes(size); require(bytes.size == size)
    return bytes.toString(StandardCharsets.UTF_8)
  }
  private inline fun <reified T : Enum<T>> enumAt(ordinal: Int, label: String): T =
      enumValues<T>().getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid $label")
}
