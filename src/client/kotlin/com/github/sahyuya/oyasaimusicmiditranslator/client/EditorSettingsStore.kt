package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import net.fabricmc.loader.api.FabricLoader

enum class EditorTheme(val english: String, val japanese: String) {
  STUDIO_SLATE("Studio Slate", "スタジオ・スレート"),
  OBSIDIAN("Obsidian", "オブシディアン"),
  MIDNIGHT_BLUE("Midnight Blue", "ミッドナイト・ブルー"),
  WARM_GRAPHITE("Warm Graphite", "ウォーム・グラファイト"),
  DAW_GRAPHITE("DAW Graphite", "DAWグラファイト"),
}

/** Persisted design tokens used by both ImGui widgets and the custom DAW canvases. */
data class EditorStyle(
  val textColor: Int,
  val disabledTextColor: Int,
  val windowBackgroundColor: Int,
  val panelBackgroundColor: Int,
  val popupBackgroundColor: Int,
  val titleBackgroundColor: Int,
  val libraryHeaderColor: Int,
  val workspaceHeaderColor: Int,
  val inspectorHeaderColor: Int,
  val borderColor: Int,
  val frameColor: Int,
  val frameHoveredColor: Int,
  val headerColor: Int,
  val buttonColor: Int,
  val accentColor: Int,
  val pianoRollColor: Int,
  val gridColor: Int,
  val outOfRangeColor: Int,
  val inactiveTitleBackgroundColor: Int,
  val checkMarkColor: Int,
  val scrollbarGrabColor: Int,
  val scrollbarGrabHoveredColor: Int,
  val scrollbarGrabActiveColor: Int,
  val dockedTabColor: Int,
  val dockedTabHoveredColor: Int,
  val dockedTabActiveColor: Int,
  val dockedTabUnfocusedColor: Int,
  val dockedTabSelectedOverlineColor: Int,
  val dockedTabDimmedSelectedColor: Int,
  val dockedTabDimmedSelectedOverlineColor: Int,
  val windowPaddingX: Int = 10,
  val windowPaddingY: Int = 10,
  val framePaddingX: Int = 8,
  val framePaddingY: Int = 5,
  val itemSpacingX: Int = 7,
  val itemSpacingY: Int = 6,
  val rounding: Int = 4,
  val scrollbarSize: Int = 14,
  val borderSize: Int = 1,
) {
  fun normalized() = copy(
    windowPaddingX = windowPaddingX.coerceIn(2, 24),
    windowPaddingY = windowPaddingY.coerceIn(2, 24),
    framePaddingX = framePaddingX.coerceIn(2, 20),
    framePaddingY = framePaddingY.coerceIn(2, 16),
    itemSpacingX = itemSpacingX.coerceIn(1, 20),
    itemSpacingY = itemSpacingY.coerceIn(1, 20),
    rounding = rounding.coerceIn(0, 12),
    scrollbarSize = scrollbarSize.coerceIn(8, 24),
    borderSize = borderSize.coerceIn(0, 2),
  )
}

object EditorStylePresets {
  private fun argb(red: Int, green: Int, blue: Int, alpha: Int = 255): Int =
    ((alpha and 255) shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)

  fun forTheme(theme: EditorTheme): EditorStyle = when (theme) {
    EditorTheme.STUDIO_SLATE -> EditorStyle(
      argb(234, 240, 248), argb(141, 152, 169), argb(28, 31, 36), argb(26, 28, 33),
      argb(26, 28, 33, 250), argb(38, 48, 63), argb(48, 60, 78), argb(38, 48, 63),
      argb(30, 40, 54), argb(58, 67, 82), argb(41, 43, 51),
      argb(53, 59, 71), argb(51, 77, 107), argb(46, 51, 61), argb(51, 102, 128),
      argb(16, 19, 26), argb(45, 52, 64), argb(78, 105, 140, 88),
      argb(26, 28, 33), argb(66, 150, 250), argb(46, 51, 61),
      argb(51, 77, 107), argb(51, 102, 128), argb(41, 43, 51),
      argb(51, 102, 128), argb(51, 102, 128), argb(41, 43, 51),
      argb(51, 102, 128), argb(51, 77, 107), argb(128, 128, 128, 0),
      windowPaddingX = 8, windowPaddingY = 8, framePaddingX = 8, framePaddingY = 3,
      itemSpacingX = 6, itemSpacingY = 5, rounding = 4, scrollbarSize = 13, borderSize = 0,
    )
    EditorTheme.OBSIDIAN -> EditorStyle(
      argb(235, 237, 242), argb(132, 138, 149), argb(14, 15, 18), argb(11, 13, 15),
      argb(11, 13, 15, 250), argb(31, 33, 41), argb(38, 40, 50), argb(31, 33, 41),
      argb(24, 26, 33), argb(55, 58, 66), argb(27, 28, 33),
      argb(46, 48, 56), argb(64, 69, 79), argb(31, 33, 38), argb(79, 89, 102),
      argb(9, 11, 15), argb(39, 43, 50), argb(96, 104, 122, 84),
      argb(11, 13, 15), argb(66, 150, 250), argb(31, 33, 38),
      argb(64, 69, 79), argb(79, 89, 102), argb(27, 28, 33),
      argb(79, 89, 102), argb(79, 89, 102), argb(27, 28, 33),
      argb(79, 89, 102), argb(64, 69, 79), argb(128, 128, 128, 0),
      windowPaddingX = 6, windowPaddingY = 4, framePaddingX = 6, framePaddingY = 3,
      itemSpacingX = 3, itemSpacingY = 2, rounding = 0, scrollbarSize = 10, borderSize = 2,
    )
    EditorTheme.MIDNIGHT_BLUE -> EditorStyle(
      argb(232, 242, 252), argb(132, 154, 178), argb(14, 20, 29), argb(11, 17, 24),
      argb(11, 17, 24, 250), argb(20, 54, 87), argb(26, 64, 102), argb(20, 54, 87),
      argb(15, 43, 70), argb(43, 69, 94), argb(20, 33, 51),
      argb(31, 56, 84), argb(20, 87, 135), argb(20, 41, 64), argb(15, 115, 163),
      argb(8, 17, 27), argb(35, 57, 78), argb(48, 100, 143, 92),
      argb(11, 17, 24), argb(66, 150, 250), argb(20, 41, 64),
      argb(20, 87, 135), argb(15, 115, 163), argb(20, 33, 51),
      argb(15, 115, 163), argb(15, 115, 163), argb(20, 33, 51),
      argb(15, 115, 163), argb(20, 87, 135), argb(128, 128, 128, 0),
      windowPaddingX = 6, windowPaddingY = 5, framePaddingX = 7, framePaddingY = 3,
      itemSpacingX = 7, itemSpacingY = 4, rounding = 5, scrollbarSize = 13, borderSize = 1,
    )
    EditorTheme.WARM_GRAPHITE -> EditorStyle(
      argb(240, 234, 226), argb(160, 147, 133), argb(33, 31, 28), argb(28, 27, 24),
      argb(28, 27, 24, 250), argb(64, 48, 36), argb(76, 57, 43), argb(64, 48, 36),
      argb(51, 38, 29), argb(78, 67, 55), argb(48, 43, 37),
      argb(71, 61, 48), argb(107, 74, 43), argb(56, 48, 41), argb(133, 87, 43),
      argb(27, 24, 21), argb(61, 53, 44), argb(139, 94, 49, 82),
      argb(28, 27, 24), argb(66, 150, 250), argb(56, 48, 41),
      argb(107, 74, 43), argb(133, 87, 43), argb(48, 43, 37),
      argb(133, 87, 43), argb(133, 87, 43), argb(48, 43, 37),
      argb(133, 87, 43), argb(107, 74, 43), argb(128, 128, 128, 0),
      windowPaddingX = 9, windowPaddingY = 9, framePaddingX = 9, framePaddingY = 3,
      itemSpacingX = 5, itemSpacingY = 6, rounding = 12, scrollbarSize = 13, borderSize = 2,
    )
    EditorTheme.DAW_GRAPHITE -> EditorStyle(
      argb(224, 231, 237), argb(126, 142, 154), argb(22, 27, 30), argb(18, 23, 26),
      argb(17, 22, 25, 250), argb(34, 76, 88), argb(41, 90, 104), argb(34, 76, 88),
      argb(27, 61, 71), argb(50, 68, 75), argb(31, 40, 45),
      argb(44, 59, 65), argb(39, 92, 106), argb(32, 53, 61), argb(37, 151, 173),
      argb(12, 18, 21), argb(38, 54, 61), argb(50, 95, 105, 104),
      argb(18, 23, 26), argb(66, 150, 250), argb(32, 53, 61),
      argb(39, 92, 106), argb(37, 151, 173), argb(31, 40, 45),
      argb(37, 151, 173), argb(37, 151, 173), argb(31, 40, 45),
      argb(37, 151, 173), argb(39, 92, 106), argb(128, 128, 128, 0),
      windowPaddingX = 6, windowPaddingY = 5, framePaddingX = 8, framePaddingY = 3,
      itemSpacingX = 5, itemSpacingY = 4, rounding = 6, scrollbarSize = 13, borderSize = 1,
    )
  }
}

data class EditorSettings(
  val version: Int = 6,
  val compactToolbar: Boolean = true,
  val showLibrary: Boolean = true,
  val showInspector: Boolean = true,
  val showAutomation: Boolean = true,
  val gridDensity: String = "AUTO",
  val showOtherParts: Boolean = true,
  val followLead: Int = 45,
  val lastTool: EditorTool = EditorTool.SELECT,
  val uiScalePercent: Int = 100,
  /** Text-only size multiplier, independent from the whole-UI scale above. */
  val textScalePercent: Int = 110,
  val theme: EditorTheme = EditorTheme.STUDIO_SLATE,
  val style: EditorStyle = EditorStylePresets.forTheme(EditorTheme.STUDIO_SLATE),
  val keymap: EditorKeymap = EditorKeymap(),
  // Default navigation favors direct vertical movement. Every gesture remains user-remappable.
  val wheelPlain: WheelAction = WheelAction.PITCH_SCROLL,
  val wheelShift: WheelAction = WheelAction.TIMELINE_SCROLL,
  val wheelControl: WheelAction = WheelAction.TIME_ZOOM,
  val wheelAlt: WheelAction = WheelAction.PITCH_ZOOM,
  val rangeSelectionModifier: GestureModifier = GestureModifier.SHIFT,
  val additiveSelectionModifier: GestureModifier = GestureModifier.CONTROL,
  val panMouseButton: PanMouseButton = PanMouseButton.RIGHT,
)

object EditorSettingsStore {
  private const val SETTINGS_VERSION = 6
  private val path get() = FabricLoader.getInstance().configDir.resolve("ommt-editor.json")
  val layoutPath get() = FabricLoader.getInstance().configDir.resolve("ommt-imgui-layout.ini")

  fun load(): EditorSettings = try {
    parse(Files.readString(path))
  } catch (_: Exception) { EditorSettings() }

  fun save(value: EditorSettings) { runCatching { atomicWrite(path, toJson(value.copy(version = SETTINGS_VERSION, style = value.style.normalized()))) } }

  fun exportText(value: EditorSettings): String {
    val layout = if (Files.isRegularFile(layoutPath)) Files.readString(layoutPath).also {
      require(it.toByteArray(StandardCharsets.UTF_8).size <= 512 * 1024) { "OMMT layout exceeds 512 KiB" }
    } else ""
    return EditorSettingsBundleCodec.encode(value.copy(version = SETTINGS_VERSION, style = value.style.normalized()), layout)
  }

  fun importText(value: String): EditorSettings {
    val decoded = EditorSettingsBundleCodec.decode(value)
    atomicWrite(path, toJson(decoded.settings))
    atomicWrite(layoutPath, decoded.layout)
    return decoded.settings
  }

  private fun parse(text: String): EditorSettings {
    val defaults = EditorKeymap()
    val keymap = EditorKeymap(EditorAction.entries.associateWith { action ->
      EditorKeyStroke.parse(str(text, "key_${action.name}", defaults[action].encode()), defaults[action])
    })
    val theme = enum(text, "theme", EditorTheme.DAW_GRAPHITE)
    val preset = EditorStylePresets.forTheme(theme)
    val style = EditorStyle(
      textColor = color(text, "styleText", preset.textColor),
      disabledTextColor = color(text, "styleDisabledText", preset.disabledTextColor),
      windowBackgroundColor = color(text, "styleWindowBackground", preset.windowBackgroundColor),
      panelBackgroundColor = color(text, "stylePanelBackground", preset.panelBackgroundColor),
      popupBackgroundColor = color(text, "stylePopupBackground", preset.popupBackgroundColor),
      titleBackgroundColor = color(text, "styleTitleBackground", preset.titleBackgroundColor),
      libraryHeaderColor = color(text, "styleLibraryHeader", preset.libraryHeaderColor),
      workspaceHeaderColor = color(text, "styleWorkspaceHeader", preset.workspaceHeaderColor),
      inspectorHeaderColor = color(text, "styleInspectorHeader", preset.inspectorHeaderColor),
      borderColor = color(text, "styleBorder", preset.borderColor),
      frameColor = color(text, "styleFrame", preset.frameColor),
      frameHoveredColor = color(text, "styleFrameHovered", preset.frameHoveredColor),
      headerColor = color(text, "styleHeader", preset.headerColor),
      buttonColor = color(text, "styleButton", preset.buttonColor),
      accentColor = color(text, "styleAccent", preset.accentColor),
      pianoRollColor = color(text, "stylePianoRoll", preset.pianoRollColor),
      gridColor = color(text, "styleGrid", preset.gridColor),
      outOfRangeColor = color(text, "styleOutOfRange", preset.outOfRangeColor),
      inactiveTitleBackgroundColor = color(text, "styleInactiveTitleBackground", preset.inactiveTitleBackgroundColor),
      checkMarkColor = color(text, "styleCheckMark", preset.checkMarkColor),
      scrollbarGrabColor = color(text, "styleScrollbarGrab", preset.scrollbarGrabColor),
      scrollbarGrabHoveredColor = color(text, "styleScrollbarGrabHovered", preset.scrollbarGrabHoveredColor),
      scrollbarGrabActiveColor = color(text, "styleScrollbarGrabActive", preset.scrollbarGrabActiveColor),
      dockedTabColor = color(text, "styleDockedTab", preset.dockedTabColor),
      dockedTabHoveredColor = color(text, "styleDockedTabHovered", preset.dockedTabHoveredColor),
      dockedTabActiveColor = color(text, "styleDockedTabActive", preset.dockedTabActiveColor),
      dockedTabUnfocusedColor = color(text, "styleDockedTabUnfocused", preset.dockedTabUnfocusedColor),
      dockedTabSelectedOverlineColor = color(text, "styleDockedTabSelectedOverline", preset.dockedTabSelectedOverlineColor),
      dockedTabDimmedSelectedColor = color(text, "styleDockedTabDimmedSelected", preset.dockedTabDimmedSelectedColor),
      dockedTabDimmedSelectedOverlineColor = color(text, "styleDockedTabDimmedSelectedOverline", preset.dockedTabDimmedSelectedOverlineColor),
      windowPaddingX = int(text, "styleWindowPaddingX", preset.windowPaddingX),
      windowPaddingY = int(text, "styleWindowPaddingY", preset.windowPaddingY),
      framePaddingX = int(text, "styleFramePaddingX", preset.framePaddingX),
      framePaddingY = int(text, "styleFramePaddingY", preset.framePaddingY),
      itemSpacingX = int(text, "styleItemSpacingX", preset.itemSpacingX),
      itemSpacingY = int(text, "styleItemSpacingY", preset.itemSpacingY),
      rounding = int(text, "styleRounding", preset.rounding),
      scrollbarSize = int(text, "styleScrollbarSize", preset.scrollbarSize),
      borderSize = int(text, "styleBorderSize", preset.borderSize),
    ).normalized()
    return EditorSettings(
      version = SETTINGS_VERSION,
      compactToolbar = bool(text, "compactToolbar", true),
      showLibrary = bool(text, "showLibrary", true),
      showInspector = bool(text, "showInspector", true),
      showAutomation = bool(text, "showAutomation", true),
      gridDensity = str(text, "gridDensity", "AUTO").uppercase().takeIf { it in setOf("AUTO", "SPARSE", "NORMAL", "DENSE") } ?: "AUTO",
      showOtherParts = bool(text, "showOtherParts", true),
      followLead = int(text, "followLead", 45).coerceIn(20, 70),
      lastTool = enum(text, "lastTool", EditorTool.SELECT),
      uiScalePercent = int(text, "uiScalePercent", 100).coerceIn(75, 150),
      textScalePercent = int(text, "textScalePercent", 100).coerceIn(75, 150),
      theme = theme,
      style = style,
      keymap = keymap,
      wheelPlain = enum(text, "wheelPlain", WheelAction.PITCH_SCROLL),
      wheelShift = enum(text, "wheelShift", WheelAction.TIMELINE_SCROLL),
      wheelControl = enum(text, "wheelControl", WheelAction.TIME_ZOOM),
      wheelAlt = enum(text, "wheelAlt", WheelAction.PITCH_ZOOM),
      rangeSelectionModifier = enum(text, "rangeSelectionModifier", GestureModifier.SHIFT),
      additiveSelectionModifier = enum(text, "additiveSelectionModifier", GestureModifier.CONTROL),
      panMouseButton = enum(text, "panMouseButton", PanMouseButton.RIGHT),
    )
  }

  private fun toJson(value: EditorSettings): String {
    val style = value.style.normalized()
    val bindings = value.keymap.bindings.entries.sortedBy { it.key.name }.joinToString("") { (action, stroke) ->
      ",\"key_${action.name}\":\"${stroke.encode()}\""
    }
    return buildString {
      append("{\"version\":$SETTINGS_VERSION")
      append(",\"compactToolbar\":${value.compactToolbar},\"showLibrary\":${value.showLibrary},\"showInspector\":${value.showInspector},\"showAutomation\":${value.showAutomation}")
      append(",\"gridDensity\":\"${value.gridDensity}\",\"showOtherParts\":${value.showOtherParts},\"followLead\":${value.followLead},\"lastTool\":\"${value.lastTool}\",\"uiScalePercent\":${value.uiScalePercent},\"textScalePercent\":${value.textScalePercent}")
      append(",\"theme\":\"${value.theme}\"")
      append(",\"styleText\":\"${hex(style.textColor)}\",\"styleDisabledText\":\"${hex(style.disabledTextColor)}\",\"styleWindowBackground\":\"${hex(style.windowBackgroundColor)}\"")
      append(",\"stylePanelBackground\":\"${hex(style.panelBackgroundColor)}\",\"stylePopupBackground\":\"${hex(style.popupBackgroundColor)}\",\"styleTitleBackground\":\"${hex(style.titleBackgroundColor)}\"")
      append(",\"styleLibraryHeader\":\"${hex(style.libraryHeaderColor)}\",\"styleWorkspaceHeader\":\"${hex(style.workspaceHeaderColor)}\",\"styleInspectorHeader\":\"${hex(style.inspectorHeaderColor)}\"")
      append(",\"styleBorder\":\"${hex(style.borderColor)}\",\"styleFrame\":\"${hex(style.frameColor)}\",\"styleFrameHovered\":\"${hex(style.frameHoveredColor)}\"")
      append(",\"styleHeader\":\"${hex(style.headerColor)}\",\"styleButton\":\"${hex(style.buttonColor)}\",\"styleAccent\":\"${hex(style.accentColor)}\"")
      append(",\"stylePianoRoll\":\"${hex(style.pianoRollColor)}\",\"styleGrid\":\"${hex(style.gridColor)}\",\"styleOutOfRange\":\"${hex(style.outOfRangeColor)}\"")
      append(",\"styleInactiveTitleBackground\":\"${hex(style.inactiveTitleBackgroundColor)}\",\"styleCheckMark\":\"${hex(style.checkMarkColor)}\"")
      append(",\"styleScrollbarGrab\":\"${hex(style.scrollbarGrabColor)}\",\"styleScrollbarGrabHovered\":\"${hex(style.scrollbarGrabHoveredColor)}\",\"styleScrollbarGrabActive\":\"${hex(style.scrollbarGrabActiveColor)}\"")
      append(",\"styleDockedTab\":\"${hex(style.dockedTabColor)}\",\"styleDockedTabHovered\":\"${hex(style.dockedTabHoveredColor)}\",\"styleDockedTabActive\":\"${hex(style.dockedTabActiveColor)}\",\"styleDockedTabUnfocused\":\"${hex(style.dockedTabUnfocusedColor)}\"")
      append(",\"styleDockedTabSelectedOverline\":\"${hex(style.dockedTabSelectedOverlineColor)}\",\"styleDockedTabDimmedSelected\":\"${hex(style.dockedTabDimmedSelectedColor)}\",\"styleDockedTabDimmedSelectedOverline\":\"${hex(style.dockedTabDimmedSelectedOverlineColor)}\"")
      append(",\"styleWindowPaddingX\":${style.windowPaddingX},\"styleWindowPaddingY\":${style.windowPaddingY},\"styleFramePaddingX\":${style.framePaddingX},\"styleFramePaddingY\":${style.framePaddingY}")
      append(",\"styleItemSpacingX\":${style.itemSpacingX},\"styleItemSpacingY\":${style.itemSpacingY},\"styleRounding\":${style.rounding},\"styleScrollbarSize\":${style.scrollbarSize},\"styleBorderSize\":${style.borderSize}")
      append(",\"wheelPlain\":\"${value.wheelPlain}\",\"wheelShift\":\"${value.wheelShift}\",\"wheelControl\":\"${value.wheelControl}\",\"wheelAlt\":\"${value.wheelAlt}\"")
      append(",\"rangeSelectionModifier\":\"${value.rangeSelectionModifier}\",\"additiveSelectionModifier\":\"${value.additiveSelectionModifier}\",\"panMouseButton\":\"${value.panMouseButton}\"")
      append(bindings)
      append('}')
    }
  }

  private fun atomicWrite(target: java.nio.file.Path, value: String) {
    Files.createDirectories(target.parent)
    val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.writeString(temp, value, StandardCharsets.UTF_8)
    try {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun str(text: String, key: String, default: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: default
  private fun bool(text: String, key: String, default: Boolean) = Regex("\"$key\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: default
  private fun int(text: String, key: String, default: Int) = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: default
  private fun color(text: String, key: String, default: Int): Int = str(text, key, hex(default)).takeIf { it.matches(Regex("[0-9A-Fa-f]{8}")) }?.toLongOrNull(16)?.toInt() ?: default
  private fun hex(value: Int) = "%08X".format(value.toLong() and 0xffffffffL)
  private inline fun <reified T : Enum<T>> enum(text: String, key: String, default: T) = runCatching { enumValueOf<T>(str(text, key, default.name)) }.getOrDefault(default)
}
