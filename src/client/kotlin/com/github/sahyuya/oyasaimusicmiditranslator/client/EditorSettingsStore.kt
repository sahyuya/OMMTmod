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
}

data class EditorSettings(
  val version: Int = 4,
  val compactToolbar: Boolean = true,
  val showLibrary: Boolean = true,
  val showInspector: Boolean = true,
  val showAutomation: Boolean = true,
  val gridDensity: String = "AUTO",
  val showOtherParts: Boolean = true,
  val followLead: Int = 45,
  val lastTool: EditorTool = EditorTool.SELECT,
  val uiScalePercent: Int = 100,
  val theme: EditorTheme = EditorTheme.STUDIO_SLATE,
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
  private val path get() = FabricLoader.getInstance().configDir.resolve("ommt-editor.json")
  val layoutPath get() = FabricLoader.getInstance().configDir.resolve("ommt-imgui-layout.ini")
  fun load(): EditorSettings = try {
    parse(Files.readString(path))
  } catch (_: Exception) { EditorSettings() }
  fun save(value: EditorSettings) { runCatching { atomicWrite(path, toJson(value)) } }
  fun exportText(value: EditorSettings): String {
    val layout = if (Files.isRegularFile(layoutPath)) Files.readString(layoutPath).also { require(it.toByteArray(StandardCharsets.UTF_8).size <= 512 * 1024) { "OMMT layout exceeds 512 KiB" } } else ""
    return EditorSettingsBundleCodec.encode(value.copy(version = 4), layout)
  }
  fun importText(value: String): EditorSettings {
    val decoded = EditorSettingsBundleCodec.decode(value)
    atomicWrite(path, toJson(decoded.settings))
    atomicWrite(layoutPath, decoded.layout)
    return decoded.settings
  }

  private fun parse(text: String): EditorSettings {
    val defaults=EditorKeymap()
    val keymap=EditorKeymap(EditorAction.entries.associateWith { action -> EditorKeyStroke.parse(str(text,"key_${action.name}",defaults[action].encode()),defaults[action]) })
    return EditorSettings(
      compactToolbar=bool(text,"compactToolbar",true), showLibrary=bool(text,"showLibrary",true), showInspector=bool(text,"showInspector",true), showAutomation=bool(text,"showAutomation",true),
      gridDensity=str(text,"gridDensity","AUTO").uppercase().takeIf { it in setOf("AUTO","SPARSE","NORMAL","DENSE") }?:"AUTO", showOtherParts=bool(text,"showOtherParts",true),
      followLead=int(text,"followLead",45).coerceIn(20,70), lastTool=enum(text,"lastTool",EditorTool.SELECT),
      uiScalePercent=int(text,"uiScalePercent",100).coerceIn(75,150), theme=enum(text,"theme",EditorTheme.STUDIO_SLATE), keymap=keymap,
      wheelPlain=enum(text,"wheelPlain",WheelAction.PITCH_SCROLL), wheelShift=enum(text,"wheelShift",WheelAction.TIMELINE_SCROLL), wheelControl=enum(text,"wheelControl",WheelAction.TIME_ZOOM), wheelAlt=enum(text,"wheelAlt",WheelAction.PITCH_ZOOM),
      rangeSelectionModifier=enum(text,"rangeSelectionModifier",GestureModifier.SHIFT), additiveSelectionModifier=enum(text,"additiveSelectionModifier",GestureModifier.CONTROL), panMouseButton=enum(text,"panMouseButton",PanMouseButton.RIGHT),
    )
  }
  private fun toJson(value: EditorSettings): String {
    val bindings=value.keymap.bindings.entries.sortedBy { it.key.name }.joinToString("") { (action,stroke) -> ",\"key_${action.name}\":\"${stroke.encode()}\"" }
    return "{\"version\":4,\"compactToolbar\":${value.compactToolbar},\"showLibrary\":${value.showLibrary},\"showInspector\":${value.showInspector},\"showAutomation\":${value.showAutomation},\"gridDensity\":\"${value.gridDensity}\",\"showOtherParts\":${value.showOtherParts},\"followLead\":${value.followLead},\"lastTool\":\"${value.lastTool}\",\"uiScalePercent\":${value.uiScalePercent},\"theme\":\"${value.theme}\",\"wheelPlain\":\"${value.wheelPlain}\",\"wheelShift\":\"${value.wheelShift}\",\"wheelControl\":\"${value.wheelControl}\",\"wheelAlt\":\"${value.wheelAlt}\",\"rangeSelectionModifier\":\"${value.rangeSelectionModifier}\",\"additiveSelectionModifier\":\"${value.additiveSelectionModifier}\",\"panMouseButton\":\"${value.panMouseButton}\"$bindings}"
  }
  private fun atomicWrite(target: java.nio.file.Path, value: String) {
    Files.createDirectories(target.parent)
    val temp=target.resolveSibling(target.fileName.toString()+".tmp"); Files.writeString(temp,value,StandardCharsets.UTF_8)
    try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING) }
  }
  private fun str(t:String,k:String,d:String)=Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1)?:d
  private fun bool(t:String,k:String,d:Boolean)=Regex("\"$k\"\\s*:\\s*(true|false)").find(t)?.groupValues?.get(1)?.toBooleanStrictOrNull()?:d
  private fun int(t:String,k:String,d:Int)=Regex("\"$k\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()?:d
  private inline fun <reified T:Enum<T>> enum(t:String,k:String,d:T)=runCatching{enumValueOf<T>(str(t,k,d.name))}.getOrDefault(d)
}
