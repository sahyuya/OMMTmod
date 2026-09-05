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
  private const val VERSION = 6
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
    writeStyle(out, settings.style)
    out.writeByte(settings.wheelPlain.ordinal); out.writeByte(settings.wheelShift.ordinal); out.writeByte(settings.wheelControl.ordinal); out.writeByte(settings.wheelAlt.ordinal)
    out.writeByte(settings.rangeSelectionModifier.ordinal); out.writeByte(settings.additiveSelectionModifier.ordinal); out.writeByte(settings.panMouseButton.ordinal)
    out.writeInt(EditorAction.entries.size)
    EditorAction.entries.forEach { action -> writeString(out, settings.keymap[action].encode(), 64) }
    writeString(out, layout, MAX_LAYOUT_BYTES)
  }

  private fun read(input: DataInputStream): EditorSettingsBundle {
    require(input.readInt() == MAGIC) { "Invalid OMMT settings magic" }
    val binaryVersion = input.readUnsignedShort()
    require(binaryVersion in 1..VERSION) { "Unsupported OMMT settings version" }
    val compact = input.readBoolean(); val library = input.readBoolean(); val inspector = input.readBoolean(); val automation = input.readBoolean()
    val density = readString(input, 16).also { require(it in setOf("AUTO", "SPARSE", "NORMAL", "DENSE")) }
    val otherParts = input.readBoolean(); val followLead = input.readInt(); val tool = enumAt<EditorTool>(input.readUnsignedByte(), "editor tool"); val scale = input.readInt(); val theme = enumAt<EditorTheme>(input.readUnsignedByte(), "theme")
    val style = if (binaryVersion >= 2) readStyle(input, binaryVersion, theme) else EditorStylePresets.forTheme(theme)
    val wheelPlain = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action"); val wheelShift = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action"); val wheelControl = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action"); val wheelAlt = enumAt<WheelAction>(input.readUnsignedByte(), "wheel action")
    val range = enumAt<GestureModifier>(input.readUnsignedByte(), "selection modifier"); val additive = enumAt<GestureModifier>(input.readUnsignedByte(), "selection modifier"); val pan = enumAt<PanMouseButton>(input.readUnsignedByte(), "pan button")
    // Keymap is positional by enum order with CONFIRM appended last: v4 blobs carry
    // one fewer entry and decode with CONFIRM at its default.
    val keymapCount = input.readInt()
    require(keymapCount == EditorAction.entries.size || keymapCount == EditorAction.entries.size - 1) { "Invalid OMMT keymap size" }
    val defaults = EditorKeymap()
    val keymap = EditorKeymap(EditorAction.entries.associateWith { action ->
      if (action == EditorAction.CONFIRM && keymapCount < EditorAction.entries.size) action.default
      else EditorKeyStroke.parse(readString(input, 64), defaults[action])
    })
    val settings = EditorSettings(
        version = 6, compactToolbar = compact, showLibrary = library, showInspector = inspector,
        showAutomation = automation, gridDensity = density, showOtherParts = otherParts,
        followLead = followLead, lastTool = tool, uiScalePercent = scale, theme = theme,
        style = style, keymap = keymap, wheelPlain = wheelPlain, wheelShift = wheelShift,
        wheelControl = wheelControl, wheelAlt = wheelAlt, rangeSelectionModifier = range,
        additiveSelectionModifier = additive, panMouseButton = pan,
    )
    validate(settings)
    return EditorSettingsBundle(settings, readString(input, MAX_LAYOUT_BYTES))
  }

  private fun validate(value: EditorSettings) {
    require(value.version == 6 && value.gridDensity in setOf("AUTO", "SPARSE", "NORMAL", "DENSE"))
    require(value.followLead in 20..70 && value.uiScalePercent in 75..150)
    require(value.style == value.style.normalized()) { "Invalid OMMT style metrics" }
  }

  private fun writeStyle(out: DataOutputStream, style: EditorStyle) {
    listOf(
        style.textColor, style.disabledTextColor, style.windowBackgroundColor,
        style.panelBackgroundColor, style.popupBackgroundColor, style.titleBackgroundColor,
        style.libraryHeaderColor, style.workspaceHeaderColor, style.inspectorHeaderColor,
        style.borderColor, style.frameColor, style.frameHoveredColor, style.headerColor,
        style.buttonColor, style.accentColor, style.pianoRollColor, style.gridColor,
        style.outOfRangeColor, style.inactiveTitleBackgroundColor, style.checkMarkColor,
        style.scrollbarGrabColor, style.scrollbarGrabHoveredColor, style.scrollbarGrabActiveColor,
        style.dockedTabColor, style.dockedTabHoveredColor, style.dockedTabActiveColor,
        style.dockedTabUnfocusedColor, style.dockedTabSelectedOverlineColor,
        style.dockedTabDimmedSelectedColor, style.dockedTabDimmedSelectedOverlineColor,
    ).forEach(out::writeInt)
    listOf(
        style.windowPaddingX, style.windowPaddingY, style.framePaddingX, style.framePaddingY,
        style.itemSpacingX, style.itemSpacingY, style.rounding, style.scrollbarSize,
        style.borderSize,
    ).forEach(out::writeInt)
  }

  private fun readStyle(input: DataInputStream, binaryVersion: Int, theme: EditorTheme): EditorStyle = EditorStyle(
      textColor = input.readInt(), disabledTextColor = input.readInt(),
      windowBackgroundColor = input.readInt(), panelBackgroundColor = input.readInt(),
      popupBackgroundColor = input.readInt(), titleBackgroundColor = input.readInt(),
      libraryHeaderColor = readHeaderColor(input, binaryVersion, theme, 3) { it.libraryHeaderColor },
      workspaceHeaderColor = readHeaderColor(input, binaryVersion, theme, 3) { it.workspaceHeaderColor },
      inspectorHeaderColor = readHeaderColor(input, binaryVersion, theme, 3) { it.inspectorHeaderColor },
      borderColor = input.readInt(), frameColor = input.readInt(),
      frameHoveredColor = input.readInt(), headerColor = input.readInt(),
      buttonColor = input.readInt(), accentColor = input.readInt(),
      pianoRollColor = input.readInt(), gridColor = input.readInt(),
      outOfRangeColor = input.readInt(),
      inactiveTitleBackgroundColor = readHeaderColor(input, binaryVersion, theme, 4) { it.inactiveTitleBackgroundColor },
      checkMarkColor = readHeaderColor(input, binaryVersion, theme, 4) { it.checkMarkColor },
      scrollbarGrabColor = readHeaderColor(input, binaryVersion, theme, 4) { it.scrollbarGrabColor },
      scrollbarGrabHoveredColor = readHeaderColor(input, binaryVersion, theme, 4) { it.scrollbarGrabHoveredColor },
      scrollbarGrabActiveColor = readHeaderColor(input, binaryVersion, theme, 4) { it.scrollbarGrabActiveColor },
      dockedTabColor = readHeaderColor(input, binaryVersion, theme, 4) { it.dockedTabColor },
      dockedTabHoveredColor = readHeaderColor(input, binaryVersion, theme, 4) { it.dockedTabHoveredColor },
      dockedTabActiveColor = readHeaderColor(input, binaryVersion, theme, 4) { it.dockedTabActiveColor },
      dockedTabUnfocusedColor = readHeaderColor(input, binaryVersion, theme, 4) { it.dockedTabUnfocusedColor },
      dockedTabSelectedOverlineColor = readHeaderColor(input, binaryVersion, theme, 6) { it.dockedTabSelectedOverlineColor },
      dockedTabDimmedSelectedColor = readHeaderColor(input, binaryVersion, theme, 6) { it.dockedTabDimmedSelectedColor },
      dockedTabDimmedSelectedOverlineColor = readHeaderColor(input, binaryVersion, theme, 6) { it.dockedTabDimmedSelectedOverlineColor },
      windowPaddingX = input.readInt(),
      windowPaddingY = input.readInt(), framePaddingX = input.readInt(),
      framePaddingY = input.readInt(), itemSpacingX = input.readInt(),
      itemSpacingY = input.readInt(), rounding = input.readInt(),
      scrollbarSize = input.readInt(), borderSize = input.readInt(),
  )

  /** Appended colors fall back to the theme preset when the bundle predates them. */
  private inline fun readHeaderColor(
      input: DataInputStream,
      binaryVersion: Int,
      theme: EditorTheme,
      minVersion: Int,
      preset: (EditorStyle) -> Int,
  ): Int =
      if (binaryVersion >= minVersion) input.readInt()
      else preset(EditorStylePresets.forTheme(theme))

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
