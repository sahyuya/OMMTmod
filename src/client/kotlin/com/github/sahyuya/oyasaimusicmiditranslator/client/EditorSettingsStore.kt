package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import net.fabricmc.loader.api.FabricLoader

data class EditorSettings(
  val version: Int = 3,
  val compactToolbar: Boolean = true,
  val showLibrary: Boolean = true,
  val showInspector: Boolean = true,
  val showAutomation: Boolean = true,
  val gridDensity: String = "AUTO",
  val showOtherParts: Boolean = true,
  val followLead: Int = 45,
  val lastTool: EditorTool = EditorTool.SELECT,
  val uploadEncoding: String = "AUTO",
  val uiScalePercent: Int = 100,
  val keymap: EditorKeymap = EditorKeymap(),
  val wheelPlain: WheelAction = WheelAction.TIMELINE_SCROLL,
  val wheelShift: WheelAction = WheelAction.PITCH_ZOOM,
  val wheelControl: WheelAction = WheelAction.TIME_ZOOM,
  val wheelAlt: WheelAction = WheelAction.PITCH_SCROLL,
  val rangeSelectionModifier: GestureModifier = GestureModifier.SHIFT,
  val additiveSelectionModifier: GestureModifier = GestureModifier.CONTROL,
  val panMouseButton: PanMouseButton = PanMouseButton.RIGHT,
)
object EditorSettingsStore {
  private val path get() = FabricLoader.getInstance().configDir.resolve("ommt-editor.json")
  fun load(): EditorSettings = try {
    val text=Files.readString(path); val defaults=EditorKeymap()
    val keymap=EditorKeymap(EditorAction.entries.associateWith { action -> EditorKeyStroke.parse(str(text,"key_${action.name}",defaults[action].encode()),defaults[action]) })
    EditorSettings(
      compactToolbar=bool(text,"compactToolbar",true), showLibrary=bool(text,"showLibrary",true), showInspector=bool(text,"showInspector",true), showAutomation=bool(text,"showAutomation",true),
      gridDensity=str(text,"gridDensity","AUTO").uppercase().takeIf { it in setOf("AUTO","SPARSE","NORMAL","DENSE") }?:"AUTO", showOtherParts=bool(text,"showOtherParts",true),
      followLead=int(text,"followLead",45).coerceIn(20,70), lastTool=enum(text,"lastTool",EditorTool.SELECT), uploadEncoding=str(text,"uploadEncoding","AUTO").uppercase().takeIf{it in setOf("AUTO","U15","BASE64") }?:"AUTO",
      uiScalePercent=int(text,"uiScalePercent",100).coerceIn(75,150), keymap=keymap,
      wheelPlain=enum(text,"wheelPlain",WheelAction.TIMELINE_SCROLL), wheelShift=enum(text,"wheelShift",WheelAction.PITCH_ZOOM), wheelControl=enum(text,"wheelControl",WheelAction.TIME_ZOOM), wheelAlt=enum(text,"wheelAlt",WheelAction.PITCH_SCROLL),
      rangeSelectionModifier=enum(text,"rangeSelectionModifier",GestureModifier.SHIFT), additiveSelectionModifier=enum(text,"additiveSelectionModifier",GestureModifier.CONTROL), panMouseButton=enum(text,"panMouseButton",PanMouseButton.RIGHT),
    )
  } catch (_: Exception) { EditorSettings() }
  fun save(value: EditorSettings) { try {
    Files.createDirectories(path.parent)
    val bindings=value.keymap.bindings.entries.sortedBy { it.key.name }.joinToString("") { (action,stroke) -> ",\"key_${action.name}\":\"${stroke.encode()}\"" }
    val json="{\"version\":3,\"compactToolbar\":${value.compactToolbar},\"showLibrary\":${value.showLibrary},\"showInspector\":${value.showInspector},\"showAutomation\":${value.showAutomation},\"gridDensity\":\"${value.gridDensity}\",\"showOtherParts\":${value.showOtherParts},\"followLead\":${value.followLead},\"lastTool\":\"${value.lastTool}\",\"uploadEncoding\":\"${value.uploadEncoding}\",\"uiScalePercent\":${value.uiScalePercent},\"wheelPlain\":\"${value.wheelPlain}\",\"wheelShift\":\"${value.wheelShift}\",\"wheelControl\":\"${value.wheelControl}\",\"wheelAlt\":\"${value.wheelAlt}\",\"rangeSelectionModifier\":\"${value.rangeSelectionModifier}\",\"additiveSelectionModifier\":\"${value.additiveSelectionModifier}\",\"panMouseButton\":\"${value.panMouseButton}\"$bindings}"
    val temp=path.resolveSibling(path.fileName.toString()+".tmp"); Files.writeString(temp,json,StandardCharsets.UTF_8); try { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING) }
  } catch (_: Exception) { } }
  private fun str(t:String,k:String,d:String)=Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1)?:d
  private fun bool(t:String,k:String,d:Boolean)=Regex("\"$k\"\\s*:\\s*(true|false)").find(t)?.groupValues?.get(1)?.toBooleanStrictOrNull()?:d
  private fun int(t:String,k:String,d:Int)=Regex("\"$k\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()?:d
  private inline fun <reified T:Enum<T>> enum(t:String,k:String,d:T)=runCatching{enumValueOf<T>(str(t,k,d.name))}.getOrDefault(d)
}
