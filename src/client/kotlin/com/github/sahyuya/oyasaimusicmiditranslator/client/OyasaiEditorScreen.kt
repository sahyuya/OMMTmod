package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import cn.enaium.fabric.imgui.ImGuiRenderable
import imgui.ImColor
import imgui.ImDrawList
import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiConfigFlags
import imgui.ImGuiInputTextCallbackData
import imgui.callback.ImGuiInputTextCallback
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import imgui.flag.ImGuiWindowFlags
import imgui.internal.ImGui as ImGuiInternal
import imgui.type.ImInt
import imgui.type.ImBoolean
import imgui.type.ImString
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import com.github.sahyuya.oyasaimusicmiditranslator.NoteBlockPitch

fun editorPlaybackPositionMs(
  playbackStartMs: Int,
  playbackStartedAtNanos: Long,
  nowNanos: Long,
  durationMs: Int,
): Int {
  val elapsedMs = bufferedElapsedMillis(nowNanos, playbackStartedAtNanos) ?: 0
  return (playbackStartMs.toLong().coerceAtLeast(0L) + elapsedMs.toLong())
    .coerceAtMost(durationMs.coerceAtLeast(0).toLong())
    .toInt()
}

private const val TIMELINE_AXIS_WIDTH = 58f
private const val TIMELINE_SCROLLBAR_WIDTH = 8f
private const val PART_TABS_PER_PAGE = 18
private const val MAX_VISIBLE_NOTE_COMMANDS = 20_000

/**
 * Local, client-only MIDI editor. The stored notes deliberately use the same stable instrument IDs
 * and limits as OYMI v1, so exporting does not depend on a server round trip.
 */
class OyasaiEditorScreen(private val editorSession: EditorSession = EditorSession) : Screen(Text.literal("OMMT MIDI editor")), ImGuiRenderable {
  private data class ChannelState(var program: Int = 0, var volume: Int = 127, var expression: Int = 127, var pan: Int = 64)
  private data class TimedEvent(val tick: Long, val track: Int, val order: Int, val event: MidiEvent)
  private enum class AutomationLane { VOLUME, PAN, TEMPO, RELEASE }
  private enum class SettingsPage { GENERAL, STYLE, LAYOUT, KEYMAP, SHARE }

  private val notes = mutableListOf<EditorNote>()
  private var selected = 0
  private val selectedIds = linkedSetOf<Long>()
  private var activePart = 0
  private var allPartsView = true
  private val parts = mutableListOf("Part 1")
  private var ppq = 480
  private var pitchMin = 0
  private var visiblePitchCount = 25
  private var beatsPerBar = 4
  private var beatUnit = 4
  private var tempoMarks = listOf(TempoMark(0, 0, 500_000))
  private val tempoControls = mutableListOf(TempoControlPoint(0, 120))
  private var signatureMarks = listOf(SignatureMark(0, 4, 4))
  private var gridMarks = listOf(GridMark(0, 0, 1, 1, 0, true, true))
  // Performance caches for per-frame O(n) scans
  private var cachedDurationMs = 1
  private var cachedDurationNotesSize = -1
  private var cachedDurationMaxId: Long = -1
  private var cachedVisibleMarks: List<GridMark> = emptyList()
  private var cachedVisibleMarksOffset = Int.MIN_VALUE
  private var cachedVisibleMarksSpan = Int.MIN_VALUE
  private var cachedVisibleMarksSize = -1
  private var snapDivisor = 4
  private var selectionStart: Pair<Int, Int>? = null
  private var selectionEnd: Pair<Int, Int>? = null
  /** Automation-lane marquee: (timeMs, lane value). Lanes share the piano roll only via selectedIds. */
  private var laneMarqueeStart: Pair<Int, Int>? = null
  private var laneMarqueeEnd: Pair<Int, Int>? = null
  /** Tempo-lane marquee: (tick, bpm). Selects into [selectedTempoPointIds]. */
  private var tempoMarqueeStart: Pair<Long, Int>? = null
  private var tempoMarqueeEnd: Pair<Long, Int>? = null
  private var selectedTempoPointIds = mutableSetOf<Long>()
  /**
   * Edge auto-scroll dwell state. [edgeScrollDelta] resets it on re-entry and on
   * gesture gaps, so release handlers need no explicit cleanup.
   */
  private var edgeScrollStartNanos: Long? = null
  private var edgeScrollLastNanos: Long = 0L
  private var draggingNotes = false
  private var dragOriginTime = 0
  private var dragOriginPitch = 0
  private var dragMouseTime = 0
  private var dragMousePitch = 0
  private var dragBase = emptyMap<Long, Pair<Int, Int>>()
  private var dragBaseCents = emptyMap<Long, Int>()
  private var noteDragArmed = false
  private var noteDragStartX = 0f
  private var noteDragStartY = 0f
  private var noteResizeArmed = false
  private var resizingNote = false
  private var resizeNoteId: Long? = null
  private var resizeStartX = 0f
  private val history get() = EditorSession.history
  private var panOriginX = 0
  private var panning = false
  private var horizontalScrollbar = false
  private var verticalScrollbar = false
  private var contextNoteId: Long? = null
  private var contextX = 0
  private var contextY = 0
  private var contextPartOffset = 0
  private var partStripPage = 0
  private var songTitle = "Untitled song"
  private var bpm = 120
  private var horizontalOffset = 0
  private var viewSpanMs = 30_000
  private var playheadMs = 0
  private var playbackStartMs = 0
  private var playbackStartedAtNanos = 0L
  private var visualPlayheadMs = 0f
  private var lastVisualRenderNanos = 0L
  private var nextPlaybackIndex = 0
  private var playbackEvents = emptyList<RenderedNoteEvent>()
  private var playing = false
  private var followPlayback = true
  private var state = "Select a MIDI or NBS file from the library"
  private var settings = EditorSettingsStore.load()
  private var tool = settings.lastTool
  private var settingsOpen = false
  private var settingsPage = SettingsPage.GENERAL
  private var capturingBinding: EditorAction? = null
  private var imeComposing = false
  private var automationLane = AutomationLane.VOLUME
  private var automationDockInitialized = false
  private var focusDefaultAutomation = true
  private var automationDragId: Long? = null
  private var automationDragBase = emptyMap<Long, Int>()
  private var automationDragStartValue = 0
  private var selectedTempoPointId: Long? = null
  private var globalRetrigger = RetriggerProfile()
  private val partRetriggers = mutableMapOf<Int, RetriggerProfile>()
  /** A lane change is a gesture, just like a note drag: retain one undo state, not one per sample. */
  private var laneGesture = false
  private val midiDirectory: Path by lazy(EditorWorkspace::midiDirectory)
  private val nbsDirectory: Path by lazy(EditorWorkspace::nbsDirectory)
  private val saveDirectory: Path by lazy(EditorWorkspace::saveDirectory)
  private var midiFiles: List<Path> = emptyList()
  private var nbsFiles: List<Path> = emptyList()
  private var saveFiles: List<Path> = emptyList()
  private var selectedMidi: Path? = null
  private var selectedNbs: Path? = null
  private var selectedSave: Path? = null
  private var currentProjectFile: Path? = null
  private var libraryScroll = 0
  private lateinit var titleField: TextFieldWidget
  private lateinit var bpmField: TextFieldWidget
  private lateinit var timeField: TextFieldWidget
  private lateinit var durationField: TextFieldWidget
  private lateinit var instrumentField: TextFieldWidget
  private lateinit var pitchField: TextFieldWidget
  private lateinit var volumeField: TextFieldWidget
  private lateinit var panField: TextFieldWidget
  // 120 chars max; 120 * 4 bytes (emoji) + NUL. ImString is byte-sized, so 121 truncates CJK mid-character.
  private val imTitle = ImString(513)
  private val imBpm = ImInt(120)
  private val imTime = ImInt()
  private val imDuration = ImInt()
  private val imDurationUnit = ImInt(0)
  private val durationDivisors = intArrayOf(0, 1, 2, 4, 8, 16, 32, 64)
  private val imInstrument = ImInt()
  private val imCustomSound = ImString(257)
  /** Drops non-ASCII at input for sound-ID search (minecraft:... is lowercase ASCII). */
  private val asciiCharFilter = object : ImGuiInputTextCallback() {
    override fun accept(data: ImGuiInputTextCallbackData) {
      if (data.eventChar >= 0x80) data.eventChar = 0
    }
  }
  private val imCustomSoundPattern = ImInt(1)
  private val imPitch = ImInt()
  private val imPitchCents = ImInt()
  private val imVolume = ImInt()
  private val imPan = ImInt()
  private val imMovePart = ImInt(0)
  private val imPartName = ImString(513)
  private val imSettingsText = ImString(1024 * 1024 + 1)
  private val imReleaseThreshold = ImInt(500)
  private val imReleaseInterval = ImInt(125)
  private val imReleaseStart = ImInt(100)
  private val imReleaseEnd = ImInt(55)
  private val imReleaseCurve = ImInt(AutomationCurve.SMOOTH.ordinal)
  private val releaseThresholdDivisors = intArrayOf(0, 1, 2, 4, 8, 16, 32, 64)
  private val releaseIntervalDivisors = intArrayOf(0, 1, 2, 4, 8, 16, 32, 64, 128)
  private val imReleaseThresholdUnit = ImInt(0)
  private val imReleaseIntervalUnit = ImInt(0)
  private val releaseDraftPoints = mutableListOf<ReleaseControlPoint>()
  private var selectedReleasePoint = 0
  private var draggingReleasePoint = false
  private val imTempoBpm = ImInt(120)
  private val imTempoCurve = ImInt(AutomationCurve.STEP.ordinal)
  private var imReleaseEnabled = false
  private var imguiConfigured = false
  private var imguiAppliedStyle: EditorStyle? = null
  private var imguiAppliedScale = 1f
  private var clearImGuiInputOnFirstFrame = true
  private var imguiRightPanning = false
  private var imguiPanStartOffset = 0
  private var imguiPanStartX = 0f
  private var externalUiActive = true
  private data class CustomSoundDefinition(val id: String, val patternNames: List<String>, val patternWeights: List<Int>) {
    val patterns get() = patternNames.size
  }
  private data class CustomSoundSelection(val id: String, val pattern: Int)
  private data class NbsPartLeaf(val partIndex: Int, val sourceLayer: Int)
  private data class NbsPartGroup(val sourceInstrument: Int, val label: String, val leaves: List<NbsPartLeaf>)
  private var nbsPartHierarchy: List<NbsPartGroup> = emptyList()
  private val deterministicPatternSeeds = mutableMapOf<Pair<String, Int>, Long>()
  private val allCustomSounds: List<CustomSoundDefinition> by lazy {
    val runtimeIds = Registries.SOUND_EVENT.ids.map { it.toString() }.toHashSet()
    val resource = javaClass.getResourceAsStream("/assets/oyasaimusicmiditranslator/sound-catalog.json")
    resource?.reader(Charsets.UTF_8)?.use { reader ->
      JsonParser.parseReader(reader).asJsonObject.entrySet().asSequence().mapNotNull { (rawId, value) ->
        val id = if (':' in rawId) rawId.lowercase() else "minecraft:${rawId.lowercase()}"
        val sounds = value.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("sounds")
        val patternEntries = sounds?.mapNotNull { sound ->
          val name = when {
            sound.isJsonPrimitive -> sound.asString
            sound.isJsonObject -> sound.asJsonObject.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
          } ?: return@mapNotNull null
          val weight = sound.takeIf { it.isJsonObject }?.asJsonObject?.get("weight")
              ?.takeIf { it.isJsonPrimitive }?.asInt?.coerceAtLeast(1) ?: 1
          NbsFileCodec.normalizedSoundPath(name) to weight
        }.orEmpty()
        val patternNames = patternEntries.map { it.first }
        val patternWeights = patternEntries.map { it.second }
        val patternCount = patternNames.size
        id.takeIf { it in runtimeIds && patternCount in 1..65_535 }
            ?.let { CustomSoundDefinition(it, patternNames, patternWeights) }
      }.sortedBy { it.id }.toList()
    }.orEmpty()
  }
  private val allCustomSoundById by lazy { allCustomSounds.associateBy { it.id } }
  private val supportedCustomSounds: List<CustomSoundDefinition>
    get() = allCustomSounds
  private fun supportedCustomSound(id: String): CustomSoundDefinition? =
      allCustomSoundById[id]
  private fun deterministicPatternSeed(definition: CustomSoundDefinition, patternInput: Int): Long {
    val pattern = patternInput.coerceIn(1, definition.patterns)
    return deterministicPatternSeeds.getOrPut(definition.id to pattern) {
      val weights = definition.patternWeights
      val totalWeight = weights.sum().coerceAtLeast(1)
      for (seed in 0L..1_000_000L) {
        var remaining = java.util.Random(seed).nextInt(totalWeight)
        for (index in weights.indices) {
          remaining -= weights[index]
          if (remaining < 0) {
            if (index + 1 == pattern) return@getOrPut seed
            break
          }
        }
      }
      pattern.toLong()
    }
  }
  private fun resolveNbsCustomSound(song: NbsFileCodec.Song, instrument: Int): CustomSoundSelection? {
    NbsFileCodec.toMinecraftSound(instrument, song.header.defaultInstruments)?.let { id ->
      return supportedCustomSound(id)?.let { CustomSoundSelection(it.id, 1) }
    }
    val custom = NbsFileCodec.customInstrument(song, instrument) ?: return null
    val namedId = custom.name.trim().lowercase().let { if (':' in it) it else "minecraft:$it" }
    supportedCustomSound(namedId)?.let { return CustomSoundSelection(it.id, 1) }
    val sample = NbsFileCodec.normalizedSoundPath(custom.soundFile)
    if (sample.isBlank()) return null
    supportedCustomSounds.forEach { definition ->
      val pattern = definition.patternNames.indexOf(sample)
      if (pattern >= 0) return CustomSoundSelection(definition.id, pattern + 1)
    }
    return null
  }
  private data class KeyModifiers(val control: Boolean, val shift: Boolean, val alt: Boolean)

  private val japanese get() = MinecraftClient.getInstance().languageManager.language.lowercase().startsWith("ja_")
  private fun t(english: String, japaneseText: String) = if (japanese) japaneseText else english
  private fun windowTitle(english: String, japaneseText: String, stableId: String = english) = "${t(english, japaneseText)}###$stableId"
  private fun actionName(action: EditorAction) = t(action.english, action.japanese)


  override fun init() {
    // A Screen can be closed while ImGui is advertising a horizontal-resize cursor. Reset the
    // native GLFW cursor as well as ImGui's logical cursor before the new frame starts.
    resetNativeCursor()
    refreshLibraries()
    if (state == "Select a MIDI or NBS file from the library") state = t("Select a MIDI or NBS file from the library", "ライブラリからMIDIまたはNBSファイルを選択してください")
    val restored = editorSession.restore()
    restored?.let { saved ->
      notes.clear(); notes += saved.notes.map { it.copy() }; selectedIds.clear(); selectedIds += saved.selectedIds
      selected = saved.selected; songTitle = saved.title; bpm = saved.bpm; horizontalOffset = saved.offset; viewSpanMs = saved.span
      activePart = saved.part; allPartsView = saved.allPartsView; parts.clear(); parts += saved.parts.mapIndexed(::normalizePartLabel); ppq = saved.ppq; beatsPerBar = saved.beats; beatUnit = saved.unit; pitchMin = saved.pitchMin; visiblePitchCount = saved.visiblePitches; snapDivisor = saved.snapDivisor; followPlayback = saved.followPlayback; playheadMs = saved.playheadMs; visualPlayheadMs = playheadMs.toFloat(); tempoMarks = saved.tempos; signatureMarks = saved.signatures; gridMarks = saved.grid
      tempoControls.clear(); tempoControls += saved.tempoControls.ifEmpty { saved.tempos.map { TempoControlPoint(it.tick, (60_000_000 / it.microsPerQuarter.coerceAtLeast(1)).coerceAtLeast(1)) } }
      selectedTempoPointId = tempoControls.firstOrNull()?.id; selectedTempoPointIds.clear(); selectedTempoPointId?.let { selectedTempoPointIds += it }
      migrateLegacyReleaseScopes(saved.globalRetrigger, saved.partRetriggers)
    }
    titleField = field(editorLeft() + 16, 58, 190, "Song title").also { it.text = songTitle; it.setMaxLength(120) }
    bpmField = field(editorLeft() + 218, 58, 62, "BPM").also { it.text = bpm.toString(); it.setMaxLength(5) }
    timeField = field(editorLeft() + 16, 128, 66, "ms")
    durationField = field(editorLeft() + 90, 128, 66, "length")
    instrumentField = field(editorLeft() + 164, 128, 48, "inst")
    pitchField = field(editorLeft() + 220, 128, 48, "pitch")
    volumeField = field(editorLeft() + 276, 128, 48, "vol")
    panField = field(editorLeft() + 332, 128, 52, "pan")
    val knownIds = notes.asSequence().map { it.id }.toSet()
    selectedIds.retainAll(knownIds)
    if (selectedIds.isEmpty()) {
      choose(selected)
    } else {
      // Reopening the screen must only populate the primary inspector fields. It must not turn a
      // restored multi-selection into a single selected ID.
      val primaryId = notes.getOrNull(selected)?.id?.takeIf { it in selectedIds } ?: selectedIds.first()
      selected = notes.indexOfFirst { it.id == primaryId }.coerceAtLeast(0)
      choose(selected, replaceSelection = false)
    }
    followPlayback = followPlayback && settings.followLead in 20..70
    applyPanelVisibility()
    syncImGuiProject()
    syncImGuiInspector()
    syncPartControls()
    // The old widgets remain as a validated value bridge while the new external UI is active.
    // They must never render or retain focus behind ImGui windows.
    // NOTE: EditBox.setFocused(false) calls TextInputManager.stopTextInput(), so the IME
    // start below must run AFTER this block, or OS IME is forced off every tick.
    allLegacyFields().forEach { it.visible = false; it.setFocused(false) }
    setFocused(null)
    // Vanilla IME contract (EditBox/Sign editor equivalent): without startTextInput the
    // per-tick TextInputManager forces OS IME off, so preedit/committed CJK never arrives.
    runCatching { Minecraft.getInstance().textInputManager().startTextInput() }
    selectionStart = null; selectionEnd = null; draggingNotes = false; noteDragArmed = false
    noteResizeArmed = false; resizingNote = false; resizeNoteId = null; draggingReleasePoint = false
    automationDragId = null; laneGesture = false; panning = false; imguiRightPanning = false
    horizontalScrollbar = false; verticalScrollbar = false; lastVisualRenderNanos = 0L
    clearImGuiInputOnFirstFrame = true
  }

  private fun field(x: Int, y: Int, fieldWidth: Int, hint: String) =
    TextFieldWidget(textRenderer, x, y, fieldWidth, 22, Text.literal(hint)).also {
      it.setDrawsBackground(false)
      it.setTextShadow(false)
      it.setEditableColor(0xFFEAF0F8.toInt())
      addDrawableChild(it)
    }

  private fun allLegacyFields() = listOf(titleField, bpmField, timeField, durationField, instrumentField, pitchField, volumeField, panField)

  private fun syncImGuiProject() {
    imTitle.set(songTitle)
    imBpm.set(bpm)
  }

  private fun syncImGuiInspector() {
    val note = notes.getOrNull(selected) ?: return
    imTime.set(note.time); imDuration.set(note.duration)
    imInstrument.set(if (note.customSound == null) note.instrument else NoteBlockInstruments.OTHER_INDEX)
    imCustomSound.set(note.customSound.orEmpty())
    imCustomSoundPattern.set(note.customSoundPattern ?: 1)
    imPitch.set(note.pitch); imPitchCents.set(pitchOffset(note)); imVolume.set(note.volume); imPan.set(note.pan)
    if (automationLane == AutomationLane.RELEASE) syncReleaseControls()
  }

  private fun syncPartControls() {
    if (parts.isEmpty()) return
    activePart = activePart.coerceIn(0, parts.lastIndex)
    imMovePart.set(activePart)
    imPartName.set(parts[activePart])
  }

  private fun copyImGuiValuesToValidatedFields() {
    titleField.text = imTitle.get(); bpmField.text = imBpm.get().toString()
    timeField.text = imTime.get().toString(); durationField.text = imDuration.get().toString()
    instrumentField.text = imInstrument.get().toString(); pitchField.text = imPitch.get().toString()
    volumeField.text = imVolume.get().toString(); panField.text = imPan.get().toString()
  }

  private fun choose(index: Int, replaceSelection: Boolean = true, ensureVisible: Boolean = true) {
    selected = index.coerceIn(0, (notes.size - 1).coerceAtLeast(0))
    notes.getOrNull(selected)?.let { note ->
      if (replaceSelection) { selectedIds.clear(); selectedIds += note.id }
      timeField.text = note.time.toString(); durationField.text = note.duration.toString(); instrumentField.text = note.instrument.toString()
      pitchField.text = note.pitch.toString(); volumeField.text = note.volume.toString(); panField.text = note.pan.toString()
    }
    if (ensureVisible) keepSelectedVisible()
    imDurationUnit.set(0)
    syncImGuiInspector()
  }

  private fun keepSelectedVisible() {
    val time = notes.getOrNull(selected)?.time ?: return
    val span = visibleSpan()
    if (time < horizontalOffset) horizontalOffset = time
    if (time > horizontalOffset + span) horizontalOffset = (time - span / 2).coerceAtLeast(0)
  }

  /**
   * Edge auto-scroll during drags. Call every frame while a drag is active with the
   * canvas rect. Returns the scroll delta in pixels once the cursor has dwelled outside
   * for 0.75s: slow start, accelerating with dwell time and outside distance. Only the
   * nearest of the four sides moves (single axis). Null while inside, dwelling, or on
   * gesture gaps (which also reset the dwell state, so release needs no cleanup).
   */
  private fun edgeScrollDelta(io: ImGuiIO, left: Float, top: Float, right: Float, bottom: Float): Pair<Float, Float>? {
    val now = System.nanoTime()
    val sinceLast = now - edgeScrollLastNanos
    edgeScrollLastNanos = now
    val dx = when {
      io.mousePosX < left -> io.mousePosX - left
      io.mousePosX > right -> io.mousePosX - right
      else -> 0f
    }
    val dy = when {
      io.mousePosY < top -> io.mousePosY - top
      io.mousePosY > bottom -> io.mousePosY - bottom
      else -> 0f
    }
    if (dx == 0f && dy == 0f || sinceLast > 500_000_000L) {
      edgeScrollStartNanos = if (dx == 0f && dy == 0f) null else now
      return null
    }
    val start = edgeScrollStartNanos
    if (start == null) {
      edgeScrollStartNanos = now
      return null
    }
    val dwell = (now - start) / 1_000_000_000.0 - 0.75
    if (dwell <= 0) return null
    val dt = (sinceLast / 1_000_000_000.0).coerceIn(0.0, 0.1).toFloat()
    val dist = max(abs(dx), abs(dy))
    val speed = 80f * (1f + dwell.toFloat() * 2f) * (1f + dist / 150f) // px/sec
    return if (abs(dx) >= abs(dy)) {
      kotlin.math.sign(dx) * speed * dt to 0f
    } else {
      0f to kotlin.math.sign(dy) * speed * dt
    }
  }

  private fun applySelected() {
    if (::titleField.isInitialized) copyImGuiValuesToValidatedFields()
    val note = notes.getOrNull(selected) ?: return
    val before = currentHistory()
    val targets = selectedNotes().ifEmpty { listOf(note) }
    val newTime = timeField.text.toIntOrNull()?.coerceAtLeast(0) ?: note.time
    val selectedDurationDivisor = durationDivisors[imDurationUnit.get().coerceIn(0, durationDivisors.lastIndex)]
    val newDuration = durationField.text.toIntOrNull()?.coerceIn(1, 60_000) ?: note.duration
    val instrumentChoice = imInstrument.get().coerceIn(0, NoteBlockInstruments.OTHER_INDEX)
    val newCustomSound = if (instrumentChoice == NoteBlockInstruments.OTHER_INDEX) {
      imCustomSound.get().trim().lowercase().also { sound ->
        require(supportedCustomSound(sound) != null) { t("Select a supported Minecraft sound", "対応するMinecraftサウンドを選択してください") }
      }
    } else null
    val newCustomSoundPattern = newCustomSound?.let { sound ->
      val maximum = supportedCustomSound(sound)!!.patterns
      imCustomSoundPattern.get().also { pattern ->
        require(pattern in 1..maximum) {
          t("Sound pattern must be between 1 and $maximum", "サウンドのパターンは1～${maximum}で指定してください")
        }
      }
    }
    val newInstrument = if (newCustomSound != null) 0 else instrumentChoice
    val deltaTime = newTime - note.time
    val deltaDuration = newDuration - note.duration
    val deltaPitch = (pitchField.text.toIntOrNull() ?: note.pitch) - note.pitch
    val deltaVolume = (volumeField.text.toIntOrNull() ?: note.volume) - note.volume
    val deltaPan = (panField.text.toIntOrNull() ?: note.pan) - note.pan
    // Detune row wins when it changed: move every target's offset by the same delta.
    // The placed pitch is never touched here; only the absolute -5400..7300 clamp applies.
    val centsDelta = imPitchCents.get() - (note.pitchCents - note.pitch * 100)
    val useCents = centsDelta != 0
    targets.forEach { target ->
      target.time = (target.time + deltaTime).coerceAtLeast(0)
      target.duration = if (selectedDurationDivisor == 0) (target.duration + deltaDuration).coerceIn(1, 60_000)
      else EditorAutomation.durationForDivision(target, selectedDurationDivisor, tempoMarks, ppq)
      target.instrument = newInstrument
      target.customSound = newCustomSound
      target.customSoundPattern = newCustomSoundPattern
      if (useCents) {
        target.pitchCents = (target.pitchCents + centsDelta).coerceIn(-5400, 7300)
      } else {
        target.pitch = (target.pitch + deltaPitch).coerceIn(NoteBlockPitch.DISPLAY_MIN, NoteBlockPitch.DISPLAY_MAX)
        target.pitchCents = (target.pitchCents + deltaPitch * 100).coerceIn(-5400, 7300)
      }
      target.volume = (target.volume + deltaVolume).coerceIn(0, 100)
      target.pan = (target.pan + deltaPan).coerceIn(-100, 100)
      target.sourceTick = EditorAutomation.tickAtTime(target.time, tempoMarks, ppq)
      target.sourceDurationTicks = (EditorAutomation.tickAtTime(target.time + target.duration, tempoMarks, ppq) - target.sourceTick).coerceAtLeast(1)
    }
    songTitle = titleField.text.trim().take(120).ifBlank { "Untitled song" }
    bpm = bpmField.text.toIntOrNull()?.coerceIn(1, 60_000) ?: bpm
    sortNotesAndResolvePrimary(note.id)
    if (before != currentHistory()) history.push(before)
    choose(selected, replaceSelection = false); state = t("Edited ${targets.size} selected note(s)", "選択した${targets.size}音を編集しました")
  }

  /**
   * Song-wide velocity normalize: scales every note so the peak becomes 100
   * (e.g. peak 50 with others at 15 becomes 100 and 30). Volume 0 stays 0,
   * preserving intentional mutes. Single undo step.
   */
  private fun normalizeVelocities() {
    val peak = notes.maxOfOrNull { it.volume } ?: 0
    if (notes.isEmpty() || peak <= 0) {
      state = t("No audible velocities to normalize", "正規化できる音量がありません")
      return
    }
    if (peak >= 100) {
      state = t("Velocities already peak at 100", "音量は既に最大100です")
      return
    }
    val factor = 100.0 / peak
    val before = currentHistory()
    notes.forEach { it.volume = (it.volume * factor).roundToInt().coerceIn(0, 100) }
    if (before != currentHistory()) history.push(before)
    syncImGuiInspector()
    state = t("Normalized velocities x${"%.2f".format(factor)} (peak $peak→100)", "音量を正規化しました x${"%.2f".format(factor)}（最大$peak→100）")
  }

  /** Keep playback's lowerBound invariant after every edit that changes note times. */
  private fun sortNotesAndResolvePrimary(preferredId: Long? = notes.getOrNull(selected)?.id) {
    notes.sortWith(compareBy<EditorNote> { it.time }.thenBy { it.id })
    selectedIds.retainAll(notes.asSequence().map { it.id }.toSet())
    val primaryId = preferredId?.takeIf { id -> notes.any { it.id == id } }
      ?: selectedIds.firstOrNull { id -> notes.any { it.id == id } }
    selected = primaryId?.let { id -> notes.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
  }

  private fun deleteSelected() {
    if (notes.isEmpty()) return
    val deleting = if (selectedIds.isNotEmpty()) selectedIds.toSet() else setOf(notes[selected.coerceIn(0, notes.lastIndex)].id)
    rememberHistory(); notes.removeIf { it.id in deleting }; selectedIds.removeAll(deleting)
    choose(selected.coerceAtMost(notes.lastIndex)); state = t("Deleted note", "ノートを削除しました")
  }

  private fun loadMidi() {
    try {
      val input = selectedMidi ?: throw IllegalStateException("Select a .mid or .midi file in the MIDI library")
      require(Files.isRegularFile(input) && input.fileName.toString().lowercase().let { it.endsWith(".mid") || it.endsWith(".midi") }) { "Select a .mid or .midi file" }
      require(Files.size(input) <= MidiFileCodec.MAX_FILE_BYTES) { "MIDI file exceeds 64 MiB" }
      val imported = MidiFileCodec.decode(Files.readAllBytes(input))
      pausePlayback(); EditorSession.replace(); history.clear()
      notes.clear(); notes += imported.notes
      assignPartsByMidiSource(imported.trackHints)
      nbsPartHierarchy = emptyList()
      activePart = 0; allPartsView = true; contextPartOffset = 0; partStripPage = 0; selectedIds.clear(); fitPitchRange(); bpm = 120; beatsPerBar = 4; beatUnit = 4; snapDivisor = 4; playheadMs = 0; visualPlayheadMs = 0f; horizontalOffset = 0
      ppq = imported.ppq
      tempoMarks = imported.tempoMarks; signatureMarks = imported.signatureMarks; gridMarks = imported.gridMarks
      tempoControls.clear(); tempoControls += imported.tempoControls
      globalRetrigger = RetriggerProfile(); partRetriggers.clear(); selectedTempoPointId = tempoControls.firstOrNull()?.id; selectedTempoPointIds.clear(); selectedTempoPointId?.let { selectedTempoPointIds += it }
      bpm = (60_000_000 / tempoMarks.first().microsPerQuarter).coerceIn(1, 60_000)
      beatsPerBar = signatureMarks.first().numerator; beatUnit = signatureMarks.first().denominator
      // Song title comes from the file name (MIDI track names are part labels, not the title).
      songTitle = input.fileName.toString().substringBeforeLast('.').trim().take(120).ifBlank { "Untitled song" }
      titleField.text = songTitle; bpmField.text = bpm.toString(); selectedIds.clear(); choose(0); rewind(); fitTimeline()
      syncImGuiProject(); syncImGuiInspector()
      currentProjectFile = null; selectedSave = null; syncPartControls()
      val tempoSummary = if (imported.rawTempoPointCount > imported.tempoControls.size) {
        t(", tempo UI ${imported.rawTempoPointCount}→${imported.tempoControls.size} points", "、テンポ表示 ${imported.rawTempoPointCount}→${imported.tempoControls.size}点")
      } else ""
      val sustainSummary = if (imported.sustainExtendedNotes > 0) {
        t(", sustain extended ${imported.sustainExtendedNotes} notes", "、サステインで${imported.sustainExtendedNotes}音を延長")
      } else ""
      state = t("Loaded ${notes.size} notes into ${parts.size} source parts$tempoSummary$sustainSummary", "${notes.size}音をMIDI元パートに沿った${parts.size}パートへ読み込みました$tempoSummary$sustainSummary")
    } catch (error: Exception) { state = t("MIDI import failed: ${error.message ?: "invalid file"}", "MIDIの読み込みに失敗しました: ${error.message ?: "不正なファイル"}") }
  }

  private fun loadSelectedSource() {
    if (selectedNbs != null && selectedMidi == null) loadNbs() else loadMidi()
  }

  private fun loadNbs() {
    try {
      val input = selectedNbs ?: throw IllegalStateException("Select an .nbs file in the NBS library")
      require(Files.isRegularFile(input) && input.fileName.toString().lowercase().endsWith(".nbs")) { "Select an .nbs file" }
      require(Files.size(input) <= NbsFileCodec.MAX_FILE_BYTES) { "NBS file exceeds 64 MiB" }
      val song = NbsFileCodec.decode(Files.readAllBytes(input))
      require(song.notes.isNotEmpty()) { "NBS file contains no notes" }
      val controlNoteCount = song.notes.count { NbsFileCodec.isControlInstrument(song, it.instrument) }
      val playableNotes = if (controlNoteCount == 0) song.notes else song.notes.filterNot {
        NbsFileCodec.isControlInstrument(song, it.instrument)
      }
      require(playableNotes.isNotEmpty()) { "NBS file contains only non-audio control notes" }

      // NBS stores tempo as ticks per second. One NBS tick is a sixteenth note, independent
      // from the header's time-signature numerator.
      val importedPpq = 64
      val microsDouble = 4_000_000.0 / song.header.ticksPerSecond
      require(microsDouble in 1.0..Int.MAX_VALUE.toDouble()) { "NBS tempo is outside the supported range" }
      val baseTempo = TempoMark(0, 0, microsDouble.roundToInt())
      // Handle Tempo Changer control notes as tempo automation
      val tempoChangerNotes = song.notes.filter {
        NbsFileCodec.customInstrument(song, it.instrument)?.name?.trim()?.lowercase() == "tempo changer"
      }.sortedBy { it.tick }
      val nbsTempoMarks: List<TempoMark>
      val nbsTempoControls: List<TempoControlPoint>
      if (tempoChangerNotes.isEmpty()) {
        nbsTempoMarks = listOf(baseTempo)
        nbsTempoControls = listOf(TempoControlPoint(0, (song.header.ticksPerSecond * 15).roundToInt().coerceIn(1, 60000)))
      } else {
        val controls = mutableListOf<TempoControlPoint>()
        val headerBpm = (song.header.ticksPerSecond * 15).roundToInt().coerceIn(1, 60000)
        controls += TempoControlPoint(0, headerBpm)
        for (note in tempoChangerNotes) {
          val tick = note.tick.toLong() * 16
          // Avoid duplicate tick entries
          if (controls.any { it.tick == tick }) continue
          // Generic: detuneCents holds BPM directly (e.g., Machine Love 350, Living 400), key is dummy
          val bpm = if (note.detuneCents != 0) NbsFileCodec.tempoChangerDetuneToBpm(note.detuneCents)
          else (NbsFileCodec.tempoChangerKeyToTps(note.key) * 15).roundToInt().coerceIn(1, 60000)
          controls += TempoControlPoint(tick, bpm)
        }
        // Compile to TempoMarks with correct timeAtTick
        nbsTempoMarks = EditorAutomation.compileTempo(controls.sortedBy { it.tick }, importedPpq)
        nbsTempoControls = controls.sortedBy { it.tick }
      }
      val soundSelections = playableNotes.asSequence().map { it.instrument }.distinct()
          .associateWith { resolveNbsCustomSound(song, it) }
      val fallbackCount = playableNotes.count { source ->
        NbsFileCodec.toOmmtInstrument(source.instrument, song.header.defaultInstruments) == null && soundSelections[source.instrument] == null
      }
      val converted = playableNotes.map { source ->
        val layer = song.layers[source.layer]
        val startTick = Math.multiplyExact(source.tick.toLong(), 16L)
        val endTick = Math.addExact(startTick, 16L)
        val startMs = EditorAutomation.timeAtTick(startTick, nbsTempoMarks, importedPpq)
        val endMs = EditorAutomation.timeAtTick(endTick, nbsTempoMarks, importedPpq)
        val customSelection = soundSelections[source.instrument]
        val customSound = customSelection?.id
        val instrument = if (customSound != null) 0 else NbsFileCodec.toOmmtInstrument(source.instrument, song.header.defaultInstruments) ?: 0
        val customInstrument = NbsFileCodec.customInstrument(song, source.instrument)
        // NBS pitch is independent from the visual `pressKey` flag.  A custom sample's root
        // key shifts its absolute pitch; detune is retained as cents instead of being rounded.
        val pitchCents = NbsFileCodec.toOmmtPitchCents(source.key, source.detuneCents, customInstrument?.key)
        require(pitchCents in -5400..7300) { "NBS note pitch is outside the supported range" }
        val pitch = Math.floorDiv(pitchCents, 100).coerceIn(NoteBlockPitch.DISPLAY_MIN, NoteBlockPitch.DISPLAY_MAX)
        val volume = (source.velocity * layer.volume / 100.0).roundToInt().coerceIn(0, 100)
        val pan = NbsFileCodec.effectivePanning(source.panning, layer.panning)
        EditorNote(
            startMs,
            (endMs - startMs).coerceIn(1, 60_000),
            instrument,
            pitch,
            volume,
            pan,
            sourceTrack = source.layer,
            sourceChannel = source.instrument,
            sourceTick = startTick,
            sourceDurationTicks = 16L,
            customSound = customSound,
            customSoundPattern = customSelection?.pattern,
            pitchCents = pitchCents,
        )
      }

      pausePlayback(); EditorSession.replace(); history.clear()
      notes.clear(); notes += converted.sortedWith(compareBy<EditorNote> { it.time }.thenBy { it.id })
      selectedIds.clear(); activePart = 0; allPartsView = true; contextPartOffset = 0; partStripPage = 0
      ppq = importedPpq; beatsPerBar = song.header.beatsPerBar; beatUnit = 4
      tempoMarks = nbsTempoMarks; signatureMarks = listOf(SignatureMark(0, beatsPerBar, beatUnit))
      val endTick = playableNotes.maxOf { Math.multiplyExact(it.tick.toLong() + 1L, 16L) }
      gridMarks = buildNbsGrid(endTick, nbsTempoMarks, ppq, beatsPerBar)
      bpm = nbsTempoControls.firstOrNull()?.bpm ?: (song.header.ticksPerSecond * 15.0).roundToInt().coerceIn(1, 60_000)
      snapDivisor = 4; playheadMs = 0; visualPlayheadMs = 0f; horizontalOffset = 0
      tempoControls.clear(); tempoControls += nbsTempoControls
      globalRetrigger = RetriggerProfile(); partRetriggers.clear(); selectedTempoPointId = tempoControls.first().id; selectedTempoPointIds.clear(); selectedTempoPointIds += tempoControls.first().id
      assignPartsByNbsSource(song); fitPitchRange()
      // Song title comes from the file name (internal NBS title ignored by design).
      songTitle = input.fileName.toString().substringBeforeLast('.').trim().take(120).ifBlank { "Untitled song" }
      titleField.text = songTitle; bpmField.text = bpm.toString(); choose(0); rewind(); fitTimeline()
      syncImGuiProject(); syncImGuiInspector(); syncPartControls()
      currentProjectFile = null; selectedSave = null
      val fallback = if (fallbackCount > 0) t("; $fallbackCount unsupported-instrument notes use Harp", "、未対応音源の${fallbackCount}音はハープへ変換") else ""
      val normalized = if (song.normalizedValueCount > 0) t("; normalized ${song.normalizedValueCount} legacy values", "、旧形式の範囲外値${song.normalizedValueCount}件を補正") else ""
      val controls = if (controlNoteCount > 0) t("; omitted $controlNoteCount non-audio control notes", "、非音声制御ノート${controlNoteCount}件を除外") else ""
      state = t("Loaded ${notes.size} NBS notes into ${parts.size} layer parts$fallback$normalized$controls", "NBSの${notes.size}音を${parts.size}レイヤーパートへ読み込みました$fallback$normalized$controls")
    } catch (error: Exception) {
      state = t("NBS import failed: ${error.message ?: "invalid file"}", "NBSの読み込みに失敗しました: ${error.message ?: "不正なファイル"}")
    }
  }

  private fun assignPartsByNbsSource(song: NbsFileCodec.Song) {
    data class PartKey(val layer: Int, val sourceInstrument: Int, val instrument: Int, val customSound: String?)
    val keys = notes.map { PartKey(it.sourceTrack, it.sourceChannel, it.instrument, it.customSound) }
        .distinct()
        .sortedWith(compareBy<PartKey> { it.layer }.thenBy { it.sourceInstrument }.thenBy { it.instrument })
    fun instrumentName(key: PartKey): String = when (key.customSound) {
      "minecraft:block.note_block.trumpet" -> t("Trumpet / Copper", "トランペット / 銅")
      "minecraft:block.note_block.trumpet_exposed" -> t("Trumpet / Exposed copper", "トランペット / 風化した銅")
      "minecraft:block.note_block.trumpet_weathered" -> t("Trumpet / Weathered copper", "トランペット / 錆びた銅")
      "minecraft:block.note_block.trumpet_oxidized" -> t("Trumpet / Oxidized copper", "トランペット / 酸化した銅")
      else -> key.customSound?.substringAfter(':') ?: NoteBlockInstruments.displayName(key.instrument, japanese)
    }
    parts.clear()
    parts += keys.mapIndexed { index, key ->
      val layerName = song.layers.getOrNull(key.layer)?.name.orEmpty().ifBlank { t("Layer ${key.layer + 1}", "レイヤー${key.layer + 1}") }.take(48)
      normalizePartLabel(index, "$layerName / ${instrumentName(key)}")
    }
    val indexByKey = keys.withIndex().associate { (index, key) -> key to index }
    notes.forEach { note -> note.part = indexByKey.getValue(PartKey(note.sourceTrack, note.sourceChannel, note.instrument, note.customSound)) }
    nbsPartHierarchy = keys.withIndex().groupBy { it.value.sourceInstrument }.map { (sourceInstrument, entries) ->
      val first = entries.first().value
      NbsPartGroup(
          sourceInstrument,
          instrumentName(first),
          entries.map { entry -> NbsPartLeaf(entry.index, entry.value.layer) },
      )
    }
  }

  private fun buildNbsGrid(endTickInput: Long, tempos: List<TempoMark>, resolution: Int, beatsInBar: Int): List<GridMark> {
    val step = (resolution / 16).coerceAtLeast(1).toLong()
    val endTick = endTickInput.coerceAtLeast(step)
    val count = endTick / step + 2L
    require(count <= 1_000_000L) { "NBS timeline requires more than 1,000,000 grid points" }
    val barTicks = resolution.toLong() * beatsInBar
    return buildList(count.toInt()) {
      var tick = 0L
      while (tick <= endTick) {
        val subdivision = ((tick / step) % 16L).toInt()
        val isBeat = tick % resolution == 0L
        val isBar = tick % barTicks == 0L
        val bar = (tick / barTicks).toInt() + 1
        val beat = ((tick / resolution) % beatsInBar).toInt() + 1
        add(GridMark(tick, EditorAutomation.timeAtTick(tick, tempos, resolution), bar, beat, subdivision, isBar, isBeat))
        tick = Math.addExact(tick, step)
      }
    }
  }

  /** Preserve MIDI track/channel boundaries; equal converted instruments are never merged across them. */
  private fun assignPartsByMidiSource(trackHints: List<String>) {
    if (notes.isEmpty()) { parts.clear(); parts += t("Part 1", "パート1"); return }
    data class PartKey(val track: Int, val channel: Int, val instrument: Int)
    val keys = notes.map { PartKey(it.sourceTrack, it.sourceChannel, it.instrument) }.distinct().sortedWith(compareBy<PartKey> { it.track }.thenBy { it.channel }.thenBy { it.instrument })
    val trackNames = trackHints.mapIndexed { index, hint ->
      hint.substringBefore(" / ").ifBlank { t("Track ${index + 1}", "トラック${index + 1}") }.take(48)
    }
    parts.clear()
    parts += keys.mapIndexed { index, key -> normalizePartLabel(index, "${trackNames.getOrElse(key.track) { t("Track ${key.track + 1}", "トラック${key.track + 1}") }} / ${NoteBlockInstruments.displayName(key.instrument, japanese)} / Ch ${key.channel + 1}") }
    val indexByKey = keys.withIndex().associate { (index, key) -> key to index }
    notes.forEach { note -> note.part = indexByKey.getValue(PartKey(note.sourceTrack, note.sourceChannel, note.instrument)) }
  }

  /** Repair separators persisted by older builds whose unsupported glyph rendered as '?'. */
  private fun normalizePartLabel(index: Int, value: String): String = value
      .replace('\uFFFD', ' ')
      .replace(Regex("\\s*[?？]+\\s*/\\s*"), " / ")
      .replace(Regex("\\s*/\\s*"), " / ")
      .trim()
      .ifBlank { t("Part ${index + 1}", "パート${index + 1}") }

  private fun midiTitle(sequence: Sequence): String = sequence.tracks.asSequence().flatMap { track ->
    (0 until track.size()).asSequence().map { track.get(it).message }
  }.filterIsInstance<MetaMessage>().firstOrNull { it.type == 0x03 }?.data?.let(MidiInstrumentMapper::decodeText).orEmpty()

  private fun midiTrackHints(sequence: Sequence): List<String> = sequence.tracks.map { track ->
    val values = (0 until track.size()).asSequence()
        .mapNotNull { track.get(it).message as? MetaMessage }
        .filter { it.type == 0x03 || it.type == 0x04 }
        .map { MidiInstrumentMapper.decodeText(it.data) }
        .filter(String::isNotBlank)
        .distinct()
        .take(4)
        .toList()
    values.joinToString(" / ")
  }

  /**
   * MIDI meta events are collected with the same deterministic tick/track/event order as note
   * conversion. A signature change deliberately starts a fresh bar at its own tick, even if the
   * preceding bar was incomplete; this makes ruler, snap, and editing boundaries agree.
   */
  private fun buildTiming(sequence: Sequence, lastNoteMs: Int): Triple<List<TempoMark>, List<SignatureMark>, List<GridMark>> {
    val events = sequence.tracks.flatMapIndexed { trackIndex, track ->
      (0 until track.size()).map { index -> TimedEvent(track.get(index).tick, trackIndex, index, track.get(index)) }
    }.sortedWith(compareBy<TimedEvent> { it.tick }.thenBy { it.track }.thenBy { it.order })
    val rawTempo = mutableListOf<Pair<Long, Int>>(); val rawSignature = mutableListOf<SignatureMark>()
    events.forEach { event ->
      val meta = event.event.message as? MetaMessage ?: return@forEach
      when {
        meta.type == 0x51 && meta.data.size == 3 -> rawTempo += event.tick to ((meta.data[0].toInt().and(255) shl 16) or (meta.data[1].toInt().and(255) shl 8) or meta.data[2].toInt().and(255))
        meta.type == 0x58 && meta.data.size >= 2 -> rawSignature += SignatureMark(event.tick, meta.data[0].toInt().and(255).coerceIn(1, 32), (1 shl meta.data[1].toInt().and(7)).coerceIn(1, 32))
      }
    }
    // A default at tick zero is always present first, then MIDI event order provides last-wins
    // semantics for duplicate meta events at the same tick.
    fun normalizeTempo(values: List<Pair<Long, Int>>): List<Pair<Long, Int>> {
      val normalized = mutableListOf<Pair<Long, Int>>()
      values.sortedBy { it.first }.forEach { value ->
        if (normalized.lastOrNull()?.first == value.first) normalized[normalized.lastIndex] = value else normalized += value
      }
      return normalized
    }
    fun normalizeSignature(values: List<SignatureMark>): List<SignatureMark> {
      val normalized = mutableListOf<SignatureMark>()
      values.sortedBy { it.tick }.forEach { value ->
        if (normalized.lastOrNull()?.tick == value.tick) normalized[normalized.lastIndex] = value else normalized += value
      }
      return normalized
    }
    val normalizedTempo = normalizeTempo(listOf(0L to 500_000) + rawTempo)
    val normalizedSignature = normalizeSignature(listOf(SignatureMark(0, 4, 4)) + rawSignature)
    val tempos = mutableListOf<TempoMark>(); var previousTick = 0L; var previousMicros = 0.0; var tempo = 500_000
    normalizedTempo.forEach { (tick, value) ->
      previousMicros += (tick - previousTick) * tempo.toDouble() / sequence.resolution
      previousTick = tick; tempo = value
      val mark = TempoMark(tick, (previousMicros / 1000.0).roundToInt().coerceAtLeast(0), value)
      if (tempos.lastOrNull()?.tick == tick) tempos[tempos.lastIndex] = mark else tempos += mark
    }
    val signatures = normalizedSignature
    fun timeAt(tick: Long): Int {
      var index = tempos.indexOfLast { it.tick <= tick }; if (index < 0) index = 0
      val point = tempos[index]; return (point.timeMs + (tick - point.tick) * point.microsPerQuarter.toDouble() / sequence.resolution / 1000.0).roundToInt().coerceAtLeast(0)
    }
    val grid = mutableListOf<GridMark>(); var bar = 1
    val songEndTick = sequence.tickLength.coerceAtLeast(1L)
    signatures.forEachIndexed { index, signature ->
      val endTick = signatures.getOrNull(index + 1)?.tick ?: songEndTick
      val segmentLength = (endTick - signature.tick).coerceAtLeast(0L)
      // Calculate every tick directly from the signature segment origin. The finest stored
      // position is 1/64 of a whole note (PPQ/16), so every selectable SNAP value is backed by
      // a real candidate. Repeatedly adding floor(PPQ/16) would drift for unusual PPQ values;
      // round(k * PPQ / 16) does not.
      fun roundedRatio(index: Long, numerator: Long, denominator: Long): Long =
        (index * numerator + denominator / 2) / denominator
      val subdivisionOffsets = linkedSetOf<Long>()
      var subdivisionIndex = 0L
      while (true) {
        val offset = roundedRatio(subdivisionIndex, sequence.resolution.toLong(), 16)
        if (offset > segmentLength) break
        subdivisionOffsets += offset; subdivisionIndex++
      }
      val beatOffsets = linkedSetOf<Long>()
      var beatIndex = 0L
      while (true) {
        val offset = roundedRatio(beatIndex, sequence.resolution.toLong() * 4L, signature.denominator.toLong())
        if (offset > segmentLength) break
        beatOffsets += offset; beatIndex++
      }
      val barOffsets = linkedSetOf<Long>()
      var barIndex = 0L
      while (true) {
        val offset = roundedRatio(barIndex * signature.numerator.toLong(), sequence.resolution.toLong() * 4L, signature.denominator.toLong())
        if (offset > segmentLength) break
        barOffsets += offset; barIndex++
      }
      val segmentTicks = linkedSetOf<Long>()
      subdivisionOffsets.forEach { segmentTicks += signature.tick + it }
      beatOffsets.forEach { segmentTicks += signature.tick + it }
      barOffsets.forEach { segmentTicks += signature.tick + it }
      val orderedSubdivisions = subdivisionOffsets.toList()
      val orderedBeats = beatOffsets.toList()
      var subdivisionCursor = 0
      var beatCursor = 0
      segmentTicks.filter { it <= endTick }.sorted().forEach { gridTick ->
        val offset = gridTick - signature.tick; val isBar = offset in barOffsets; val isBeat = offset in beatOffsets
        if (isBar && gridTick != signature.tick) bar++
        while (subdivisionCursor + 1 < orderedSubdivisions.size && orderedSubdivisions[subdivisionCursor + 1] <= offset) subdivisionCursor++
        while (beatCursor + 1 < orderedBeats.size && orderedBeats[beatCursor + 1] <= offset) beatCursor++
        val currentBeat = beatCursor.coerceAtLeast(0)
        val beat = currentBeat % signature.numerator + 1
        val subdivision = subdivisionCursor.coerceAtLeast(0) % 16
        grid += GridMark(gridTick, timeAt(gridTick), bar, beat, subdivision, isBar, isBeat)
      }
      // A signature boundary is always a major grid point, even when PPQ is not divisible by 4.
      if (grid.lastOrNull()?.tick != endTick && endTick == songEndTick) grid += GridMark(endTick, timeAt(endTick), bar, signature.numerator, 0, false, true)
      if (index + 1 < signatures.size) bar++
    }
    // Unfinished MIDI notes can extend the audible song past a sparse sequence tick length.
    val extensionOrigin = grid.lastOrNull()?.tick ?: songEndTick
    var extensionIndex = 0L
    var finalTick = extensionOrigin
    var finalSubdivision = ((grid.lastOrNull()?.subdivision ?: -1) + 1) % 16
    while (grid.lastOrNull()?.timeMs ?: 0 < lastNoteMs) {
      extensionIndex++
      finalTick = extensionOrigin + ((extensionIndex * sequence.resolution.toLong() + 8L) / 16L).coerceAtLeast(extensionIndex)
      grid += GridMark(finalTick, timeAt(finalTick), bar, 1, finalSubdivision, false, false)
      finalSubdivision = (finalSubdivision + 1) % 16
    }
    return Triple(tempos, signatures, grid.distinctBy { it.tick }.sortedBy { it.tick })
  }

  private fun parseSequence(sequence: Sequence): List<EditorNote> {
    val events = sequence.tracks.flatMapIndexed { trackIndex, track ->
      (0 until track.size()).map { index -> TimedEvent(track.get(index).tick, trackIndex, index, track.get(index)) }
    }.sortedWith(compareBy<TimedEvent> { it.tick }.thenBy { it.track }.thenBy { it.order })
    val tempos = buildTiming(sequence, 0).first
    fun millisecondsAt(target: Long): Int {
      val point = tempos.lastOrNull { it.tick <= target } ?: tempos.first()
      return (point.timeMs + (target - point.tick) * point.microsPerQuarter.toDouble() / sequence.resolution / 1000.0).roundToInt().coerceAtLeast(0)
    }
    val states = Array(sequence.tracks.size) { Array(16) { ChannelState() } }
    val trackHints = midiTrackHints(sequence)
    val converted = mutableListOf<EditorNote>()
    val active = mutableMapOf<String, ArrayDeque<EditorNote>>()
    events.forEach { timed ->
      val message = timed.event.message as? ShortMessage ?: return@forEach
      val state = states[timed.track][message.channel]
      when (message.command) {
        ShortMessage.PROGRAM_CHANGE -> state.program = message.data1
        ShortMessage.CONTROL_CHANGE -> when (message.data1) { 7 -> state.volume = message.data2; 10 -> state.pan = message.data2; 11 -> state.expression = message.data2; 121 -> { state.volume = 127; state.expression = 127; state.pan = 64 } }
        ShortMessage.NOTE_ON -> if (message.data2 > 0) {
          val note = convertedNote(millisecondsAt(timed.tick), timed.tick, timed.track, message, state, trackHints.getOrElse(timed.track) { "" })
          converted += note
          active.getOrPut("${timed.track}:${message.channel}:${message.data1}") { ArrayDeque() }.addLast(note)
        } else finishNote(active, timed, message, millisecondsAt(timed.tick))
        ShortMessage.NOTE_OFF -> finishNote(active, timed, message, millisecondsAt(timed.tick))
      }
    }
    return converted
  }

  private fun finishNote(active: MutableMap<String, ArrayDeque<EditorNote>>, timed: TimedEvent, message: ShortMessage, endMs: Int) {
    val key = "${timed.track}:${message.channel}:${message.data1}"
    val queue = active[key] ?: return
    val note = queue.pollFirst() ?: return
    note.duration = (endMs - note.time).coerceIn(1, 60_000)
    note.sourceDurationTicks = (timed.tick - note.sourceTick).coerceAtLeast(1)
    if (queue.isEmpty()) active.remove(key)
  }

  private fun convertedNote(time: Int, tick: Long, track: Int, message: ShortMessage, state: ChannelState, trackHint: String): EditorNote {
    val drum = message.channel == 9
    val instrument = if (drum) MidiInstrumentMapper.drumInstrument(message.data1) else MidiInstrumentMapper.mapProgram(state.program, trackHint)
    val pitch = if (drum) MidiInstrumentMapper.drumPitch(message.data1) else NoteBlockPitch.fromMidiKey(message.data1)
    val volume = ((message.data2 / 127.0) * (state.volume / 127.0) * (state.expression / 127.0) * 100).roundToInt().coerceIn(0, 100)
    val pan = (((state.pan - 64) / 63.0) * 100).roundToInt().coerceIn(-100, 100)
    return EditorNote(time, 120, instrument, pitch, volume, pan, sourceTrack = track, sourceChannel = message.channel, sourceTick = tick)
  }

  private fun preview() { notes.getOrNull(selected)?.let(::previewNote) }

  private fun previewNote(note: EditorNote) {
    previewEvent(RenderedNoteEvent(note.time, note.instrument, note.pitch, note.volume, note.pan, note.customSound, note.customSoundPattern, note.pitchCents))
  }

  // Per-tick cache for bank state and isAvailable to avoid per-note hash/digest/registry lookup
  private var tickCachedBundledHash: ByteArray? = null
  private var tickCachedPreferBank: Boolean = false
  private var tickCachedPreferBankTick: Long = -1
  private val tickAvailableCache = mutableMapOf<String, Boolean>()
  private fun tickPreferBank(): Boolean {
    // Preview gating is LOCAL-only: a self-consistent bundled pack (manifest + sounds both
    // present) authorizes extended preview without any server state. Server consent is only
    // a deny veto — an explicit deny folds preview to vanilla. Outdated-vs-server does NOT
    // fold preview (server playback still folds and reports). See INTEROP_CONTRACT bank §.
    val tick = System.nanoTime() / 50_000_000L // 50ms granularity, matches tick
    if (tickCachedPreferBankTick != tick) {
      tickCachedBundledHash = SoundBankManifest.activeHash()
      val h = tickCachedBundledHash
      val denied = ServerBankConsent.isDenied
      tickCachedPreferBank = h != null && !denied
      tickCachedPreferBankTick = tick
      tickAvailableCache.clear()
    }
    return tickCachedPreferBank
  }
  private fun isAvailableCached(eventId: String): Boolean = tickAvailableCache.getOrPut(eventId) { SoundBankManifest.isAvailable(eventId) }

  private fun previewEvent(note: RenderedNoteEvent) {
    // Volume 0 is display-only: no sound dispatch
    if (note.volume == 0) return
    val seed = note.customSound?.let(::supportedCustomSound)?.let { definition ->
      deterministicPatternSeed(definition, note.customSoundPattern ?: 1)
    } ?: 0L
    val preferBank = tickPreferBank()
    val resolved =
        if (note.customSound != null) {
          SoundBankManifest.resolveCustom(
              note.customSound,
              note.customSoundPattern ?: 1,
              seed,
              note.pitchCents,
              preferBank,
          )
        } else {
          SoundBankManifest.stableToRuntime(note.instrument)?.let { runtime ->
            SoundBankManifest.resolveInstrument(runtime, note.pitchCents, preferBank)
          }
        }
    val playable =
        resolved?.takeIf { isAvailableCached(it.eventId) }
            ?: if (preferBank) {
              if (note.customSound != null) {
                SoundBankManifest.resolveCustom(
                    note.customSound,
                    note.customSoundPattern ?: 1,
                    seed,
                    note.pitchCents,
                    false,
                )?.takeIf { isAvailableCached(it.eventId) }
              } else {
                SoundBankManifest.stableToRuntime(note.instrument)?.let { runtime ->
                  SoundBankManifest.resolveInstrument(runtime, note.pitchCents, false)?.takeIf { isAvailableCached(it.eventId) }
                }
              }
            } else {
              null
            }
    playable?.let {
      PreviewSoundPlayer.playId(
          it.eventId,
          (note.volume / 100f).coerceIn(0f, 1f),
          it.pitch,
          it.seed,
          note.pan,
      )
    }
  }

  private fun durationMs(): Int {
    // Cache: notes are append-only and sorted by time, so max is near end. Only recompute when size or last id changes.
    if (notes.size != cachedDurationNotesSize || notes.lastOrNull()?.id != cachedDurationMaxId) {
      cachedDurationMs = notes.maxOfOrNull { it.time + it.duration }?.coerceAtLeast(1) ?: 1
      cachedDurationNotesSize = notes.size
      cachedDurationMaxId = notes.lastOrNull()?.id ?: -1
    }
    return cachedDurationMs
  }
  private fun visibleMarks(): List<GridMark> {
    val span = visibleSpan()
    if (cachedVisibleMarksOffset != horizontalOffset || cachedVisibleMarksSpan != span || cachedVisibleMarksSize != gridMarks.size) {
      cachedVisibleMarks = gridMarks.filter { it.timeMs in horizontalOffset..horizontalOffset + span }
      cachedVisibleMarksOffset = horizontalOffset
      cachedVisibleMarksSpan = span
      cachedVisibleMarksSize = gridMarks.size
    }
    return cachedVisibleMarks
  }
  private fun expandedEvents() = EditorAutomation.expand(notes, RetriggerProfile(), emptyMap(), tempoMarks = tempoMarks, ppq = ppq)
  private fun migrateLegacyReleaseScopes(global: RetriggerProfile, perPart: Map<Int, RetriggerProfile>) {
    notes.forEach { note ->
      if (note.retriggerOverride == null) {
        val inherited = perPart[note.part] ?: global.takeUnless { it == RetriggerProfile() }
        if (inherited != null) note.retriggerOverride = inherited.normalized()
      }
    }
    globalRetrigger = RetriggerProfile()
    partRetriggers.clear()
  }
  private fun lowerBound(events: List<RenderedNoteEvent>, timeMs: Int): Int {
    var low = 0; var high = events.size
    while (low < high) { val middle = (low + high) / 2; if (events[middle].time < timeMs) low = middle + 1 else high = middle }
    return low
  }
  private fun togglePlayback() {
    if (playing) { pausePlayback(); return }
    if (notes.isEmpty()) { state = t("Load a MIDI file before playback", "再生前にMIDIファイルを読み込んでください"); return }
    try { playbackEvents = expandedEvents() } catch (error: IllegalArgumentException) { state = t("Playback preparation failed: ${error.message}", "再生準備に失敗しました: ${error.message}"); return }
    if (playheadMs >= durationMs()) playheadMs = 0
    playbackStartMs = playheadMs; visualPlayheadMs = playheadMs.toFloat(); playbackStartedAtNanos = System.nanoTime(); nextPlaybackIndex = lowerBound(playbackEvents, playheadMs); playing = true
    state = t("Playing from ${formatTime(playheadMs)}", "${formatTime(playheadMs)}から再生中")
  }
  private fun pausePlayback() { if (playing) updatePlaybackPosition(); visualPlayheadMs = playheadMs.toFloat(); playing = false; state = t("Paused at ${formatTime(playheadMs)}", "${formatTime(playheadMs)}で一時停止") }
  private fun rewind() { playing = false; playheadMs = 0; visualPlayheadMs = 0f; playbackStartMs = 0; nextPlaybackIndex = 0; horizontalOffset = 0; state = t("Returned to the start", "先頭へ戻りました") }
  private fun seek(timeMs: Int) {
    playheadMs = timeMs.coerceIn(0, durationMs()); visualPlayheadMs = playheadMs.toFloat(); playbackStartMs = playheadMs; playbackStartedAtNanos = System.nanoTime(); nextPlaybackIndex = lowerBound(playbackEvents, playheadMs)
  }
  private fun updatePlaybackPosition() {
    playheadMs = editorPlaybackPositionMs(playbackStartMs, playbackStartedAtNanos, System.nanoTime(), durationMs())
  }
  override fun tick() {
    super.tick()
    pollFontRebuild()
    if (!playing) return
    updatePlaybackPosition()
    var played = 0
    while (nextPlaybackIndex < playbackEvents.size && playbackEvents[nextPlaybackIndex].time <= playheadMs) {
      if (played >= 64) break
      val event = playbackEvents[nextPlaybackIndex]
      nextPlaybackIndex += 1
      if (event.volume == 0) continue
      previewEvent(event)
      played += 1
    }
    if (playheadMs >= durationMs()) { playing = false; state = t("Playback finished", "再生が完了しました") }
  }

  /**
   * Windows JIS IME toggle scancodes. Only keys GLFW cannot identify (UNKNOWN) are passed
   * through, so other platforms are unaffected: there these scancodes belong to named keys.
   */
  private val imeToggleScancodes = setOf(0x29, 0x70, 0x79, 0x7B, 0x3A) // Zenkaku/Hankaku, Kana, Henkan, Muhenkan, EISU

  override fun keyPressed(input: KeyInput): Boolean {
    if (imeComposing && imguiConfigured && ImGui.getIO().wantTextInput) return true
    if (imguiConfigured && ImGui.getIO().wantTextInput &&
        input.key() == GLFW.GLFW_KEY_UNKNOWN && input.scancode() in imeToggleScancodes) {
      // OS-level IME toggles must reach the game/OS. Swallowing them here leaves IME permanently
      // off, so only half-width input ever works.
      return super.keyPressed(input)
    }
    val modifiers = currentKeyModifiers()
    capturingBinding?.let { action ->
      when (input.key()) {
        GLFW.GLFW_KEY_ESCAPE -> state = t("Key binding cancelled", "キー設定をキャンセルしました")
        GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
          settings = settings.copy(keymap = settings.keymap.with(action, EditorKeyStroke.UNBOUND)); saveSettings()
          state = t("${actionName(action)} is unbound", "${actionName(action)}の割り当てを解除しました")
        }
        GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> return true
        else -> {
          settings = settings.copy(keymap = settings.keymap.with(action, EditorKeyStroke(input.key(), modifiers.control, modifiers.shift, modifiers.alt))); saveSettings()
          state = t("Bound ${actionName(action)}", "${actionName(action)}のキーを設定しました")
        }
      }
      capturingBinding = null
      return true
    }
    if (imguiConfigured && ImGui.getIO().wantTextInput) return true
    if (focused is TextFieldWidget) return super.keyPressed(input)
    settings.keymap.matching(input.key(), modifiers.control, modifiers.shift, modifiers.alt)?.let { return executeAction(it) }
    return if (input.key() == GLFW.GLFW_KEY_ESCAPE && playing) { pausePlayback(); true } else super.keyPressed(input)
  }

  /** Uncommitted IME composition, rendered as an overlay since imgui-java has no IME API. */
  private var imePreeditText: String = ""
  private var imePreeditCaret: Int = 0

  override fun preeditUpdated(input: PreeditEvent?): Boolean {
    imeComposing = input?.fullText()?.isNotEmpty() == true
    imePreeditText = if (imeComposing) input?.fullText().orEmpty() else ""
    imePreeditCaret = input?.caretPosition() ?: 0
    return imguiConfigured && ImGui.getIO().wantTextInput || super.preeditUpdated(input)
  }

  override fun charTyped(input: CharacterEvent): Boolean {
    if (imguiConfigured && ImGui.getIO().wantTextInput) {
      // Single delivery: the GLFW backend already forwards every committed char to ImGui.
      // The old manual >=0x80 forward duplicated CJK input (あ→ああ).
      imeComposing = false
      imePreeditText = ""
      return true
    }
    if (input.codepoint() >= 0x80) imeComposing = false
    return super.charTyped(input)
  }

  /** Last IME-capable input rect, for the overlay and the OS candidate-window anchor. */
  private var lastImeX1 = 0f
  private var lastImeY1 = 0f
  private var lastImeX2 = 0f
  private var lastImeY2 = 0f
  private var lastImeHasRect = false

  /**
   * Per-frame IME classification. Only the song title and part name accept Japanese;
   * every other text field is half-width alphanumeric only, enforced at the OS level.
   */
  private var frameImeSeen = false
  private var frameImeAllowed = false
  private var imeOsEnabled = true

  /** Call immediately after a half-width-only input; title/part-name use the overlay instead. */
  private fun markAsciiField() {
    if (ImGui.isItemActive()) frameImeSeen = true
  }

  /**
   * Configured confirm stroke (Settings > Keymap > CONFIRM) on the just-edited field.
   * Scalar inputs (inputInt) forbid EnterReturnsTrue by Dear ImGui assert, so the stroke
   * is detected explicitly instead. Call immediately after the input call, while it is
   * still the last item. Never fires during IME composition.
   */
  private fun confirmPressed(): Boolean {
    if (!ImGui.isItemFocused() || imeComposing) return false
    val stroke = settings.keymap[EditorAction.CONFIRM]
    if (stroke.key < 0) return false
    val modifiers = currentKeyModifiers()
    if (modifiers.control != stroke.control || modifiers.shift != stroke.shift || modifiers.alt != stroke.alt) return false
    // An ENTER binding covers both main and keypad Enter, matching the old behavior.
    if (stroke.key == GLFW.GLFW_KEY_ENTER) {
      return ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)
    }
    return glfwToImGuiKey(stroke.key)?.let { ImGui.isKeyPressed(it) } == true
  }

  /** Minimal GLFW → ImGuiKey translation for the confirm stroke. Unmapped keys never match. */
  private fun glfwToImGuiKey(key: Int): Int? = when (key) {
    GLFW.GLFW_KEY_TAB -> ImGuiKey.Tab
    GLFW.GLFW_KEY_SPACE -> ImGuiKey.Space
    GLFW.GLFW_KEY_ENTER -> ImGuiKey.Enter
    GLFW.GLFW_KEY_KP_ENTER -> ImGuiKey.KeypadEnter
    GLFW.GLFW_KEY_ESCAPE -> ImGuiKey.Escape
    GLFW.GLFW_KEY_BACKSPACE -> ImGuiKey.Backspace
    GLFW.GLFW_KEY_DELETE -> ImGuiKey.Delete
    GLFW.GLFW_KEY_HOME -> ImGuiKey.Home
    GLFW.GLFW_KEY_END -> ImGuiKey.End
    in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> ImGuiKey.A + (key - GLFW.GLFW_KEY_A)
    in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> ImGuiKey._0 + (key - GLFW.GLFW_KEY_0)
    in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F12 -> ImGuiKey.F1 + (key - GLFW.GLFW_KEY_F1)
    else -> null
  }

  /** Applies the frame classification to the OS IME. State-change only, never per-frame spam. */
  private fun syncOsIme() {
    if (!frameImeSeen) return
    val want = frameImeAllowed
    if (want == imeOsEnabled) return
    imeOsEnabled = want
    runCatching {
      if (want) Minecraft.getInstance().textInputManager().startTextInput()
      else Minecraft.getInstance().textInputManager().stopTextInput()
    }
  }

  /**
   * Shows uncommitted IME composition next to the active text field (binding has no IME
   * API). Draws into the window draw-list so layout is untouched: no sameLine item,
   * no push of following widgets.
   */
  private fun renderImePreeditOverlay() {
    if (!ImGui.isItemActive()) return
    // Title/part-name fields allow IME: mark the frame accordingly.
    frameImeSeen = true
    frameImeAllowed = true
    lastImeX1 = ImGui.getItemRectMinX()
    lastImeY1 = ImGui.getItemRectMinY()
    lastImeX2 = ImGui.getItemRectMaxX()
    lastImeY2 = ImGui.getItemRectMaxY()
    lastImeHasRect = true
    if (!imeComposing || imePreeditText.isEmpty()) return
    val label = "IME: $imePreeditText"
    val pad = 4f
    val x0 = lastImeX2 + 6f
    val y0 = lastImeY1 - 1f
    val w = ImGui.calcTextSizeX(label) + pad * 2
    val h = ImGui.getFrameHeight()
    val draw = ImGui.getWindowDrawList()
    draw.addRectFilled(x0, y0, x0 + w, y0 + h, ImColor.rgba(20, 24, 32, 220))
    draw.addText(x0 + pad, y0 + 2f, ImColor.rgb(255, 217, 102), label)
  }

  private fun currentKeyModifiers(): KeyModifiers {
    val handle = client?.window?.handle ?: 0L
    fun down(left: Int, right: Int) = handle != 0L && (GLFW.glfwGetKey(handle, left) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, right) == GLFW.GLFW_PRESS)
    return KeyModifiers(down(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL), down(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT), down(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT))
  }

  private fun executeAction(action: EditorAction): Boolean {
    when (action) {
      EditorAction.PLAY_PAUSE -> togglePlayback()
      EditorAction.REWIND -> rewind()
      EditorAction.FIT -> { fitTimeline(); fitPitchRange() }
      EditorAction.ZOOM_IN -> zoomTimelineCentered(.8)
      EditorAction.ZOOM_OUT -> zoomTimelineCentered(1.25)
      EditorAction.COPY -> { EditorClipboard.copy(selectedNotes()); state = t("Copied ${selectedIds.size} notes", "${selectedIds.size}音をコピーしました") }
      EditorAction.CUT -> { EditorClipboard.copy(selectedNotes()); deleteSelected() }
      EditorAction.PASTE -> pasteClipboard()
      EditorAction.DUPLICATE -> duplicateSelected()
      EditorAction.UNDO -> history.undo(currentHistory())?.let(::restoreHistory)
      EditorAction.REDO -> history.redo(currentHistory())?.let(::restoreHistory)
      EditorAction.DELETE -> deleteSelected()
      EditorAction.SELECT_ALL -> { selectedIds.clear(); selectedIds += notesInCurrentView().map { it.id }; state=t("Selected ${selectedIds.size} notes", "${selectedIds.size}音を選択しました") }
      EditorAction.SNAP_CYCLE -> cycleSnap()
      EditorAction.FOLLOW_TOGGLE -> { followPlayback = !followPlayback; state=t("Follow ${if(followPlayback) "enabled" else "disabled"}", "追従を${if(followPlayback) "有効" else "無効"}にしました") }
      EditorAction.SETTINGS -> settingsOpen = !settingsOpen
      EditorAction.PREVIOUS_PART -> switchPart(-1)
      EditorAction.NEXT_PART -> switchPart(1)
      EditorAction.ALL_PARTS -> allPartsView = true
      EditorAction.PREVIEW -> preview()
      EditorAction.NEW_PART -> createPartFromSelection()
      EditorAction.LOAD_SELECTED -> loadSelectedSource()
      EditorAction.UPLOAD_DRAFT -> exportAndUpload()
      EditorAction.REFRESH_LIBRARY -> refreshMidiLibrary()
      EditorAction.OPEN_MIDI_FOLDER -> openMidiFolder()
      // CONFIRM is polled in-frame via confirmPressed() while a field is focused; it is
      // deliberately not a global hotkey, so global dispatch is a no-op.
      EditorAction.CONFIRM -> Unit
    }
    return true
  }

  private fun exportAndUpload() {
    try {
      applySelected(); require(notes.isNotEmpty()) { "Add at least one note" }
      val events = expandedEvents(); require(events.size <= 100_000) { "OYMI upload limit is 100,000 expanded notes" }
      val bytes = encode(events); UploadClient.upload(bytes)
    } catch (error: Exception) { state = t("Upload preparation failed: ${error.message ?: "invalid data"}", "送信準備に失敗しました: ${error.message ?: "不正なデータ"}") }
  }
  private fun currentHistory() = EditorHistory.State(notes.map { it.copy() }, selectedIds.toSet(), notes.getOrNull(selected)?.id, songTitle, bpm, parts.toList(), activePart, tempoControls.map { it.copy() }, globalRetrigger, partRetriggers.toMap())
  private fun rememberHistory() = history.push(currentHistory())
  private fun restoreHistory(value: EditorHistory.State) {
    val tempoLayoutChanged = !EditorHistory.hasSameTempoLayout(tempoControls, value.tempos)
    notes.clear(); notes += value.notes.map { it.copy() }; selectedIds.clear(); selectedIds += value.selected
    songTitle=value.title; bpm=value.bpm; parts.clear(); parts += value.parts.mapIndexed(::normalizePartLabel)
    activePart=value.activePart.coerceIn(0,parts.lastIndex.coerceAtLeast(0)); tempoControls.clear(); tempoControls += value.tempos
    selectedTempoPointId = tempoControls.firstOrNull()?.id; selectedTempoPointIds.clear(); selectedTempoPointId?.let { selectedTempoPointIds += it }
    migrateLegacyReleaseScopes(value.globalRetrigger, value.partRetriggers)
    // Note edits must not recompile an identical tempo map: rounding that map again visibly moved
    // bar lines even though the undo entry did not contain a tempo edit.
    if (tempoLayoutChanged) applyTempoControls(retime = false)
    sortNotesAndResolvePrimary(value.primary); choose(selected, false, ensureVisible = false)
    titleField.text=songTitle; bpmField.text=bpm.toString(); syncImGuiProject(); syncPartControls()
    state = t("History restored", "履歴を復元しました")
  }
  private fun pasteClipboard() { val entries = EditorClipboard.entries(); if (entries.isEmpty()) return; rememberHistory(); val copies = entries.map { EditorNote(playheadMs+it.time,it.duration,it.instrument,it.pitch,it.volume,it.pan,EditorSession.nextStableId(),it.part,it.sourceTrack,it.sourceChannel,-1L,-1L,it.retriggerOverride,it.customSound,it.customSoundPattern,it.pitchCents) }; copies.forEach { note -> note.sourceTick=EditorAutomation.tickAtTime(note.time,tempoMarks,ppq); note.sourceDurationTicks=(EditorAutomation.tickAtTime(note.time+note.duration,tempoMarks,ppq)-note.sourceTick).coerceAtLeast(1) }; notes += copies; selectedIds.clear(); selectedIds += copies.map { it.id }; sortNotesAndResolvePrimary(copies.first().id); choose(selected,false); state=t("Pasted ${copies.size} notes", "${copies.size}音を貼り付けました") }
  private fun duplicateSelected() {
    val source=selectedNotes(); if(source.isEmpty()) return
    rememberHistory(); val offset=(gridMarks.zipWithNext().map { it.second.timeMs-it.first.timeMs }.filter { it>0 }.minOrNull()?:125)
    val copies=source.map { it.copy(time=it.time+offset,id=EditorSession.nextStableId()) }; notes+=copies; selectedIds.clear(); selectedIds+=copies.map{it.id}; sortNotesAndResolvePrimary(copies.first().id); choose(selected,false)
    state=t("Duplicated ${copies.size} notes", "${copies.size}音を複製しました")
  }

  private fun encode(orderedInput: List<RenderedNoteEvent> = expandedEvents()): ByteArray {
    val ordered = orderedInput.sortedWith(compareBy<RenderedNoteEvent> { it.time }.thenBy { it.instrument }.thenBy { it.pitch }); val duration = ordered.maxOf { it.time }
    val custom = ordered.mapIndexedNotNull { index, note -> note.customSound?.let { sound -> Triple(index, sound, note.customSoundPattern ?: 1) } }
    val needsPitchCents = ordered.any { it.pitchCents % 100 != 0 || it.pitchCents !in 0..2400 }
    val version = if (needsPitchCents) 4 else if (custom.isEmpty()) 1 else 3
    val customJson = if (custom.isEmpty()) "" else custom.joinToString(prefix = ",\"customSounds\":{", postfix = "}") { (index, sound, pattern) ->
      "\"$index\":{\"event\":${json(sound)},\"pattern\":$pattern}"
    }
    val metadata = "{\"format\":\"oyasai-midi-import\",\"version\":$version,\"song\":{\"title\":${json(songTitle)},\"displayBpm\":$bpm}$customJson}".toByteArray(Charsets.UTF_8)
    return ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out ->
      out.writeInt(0x4F594D49); out.writeShort(version); out.writeShort(0); out.writeInt(metadata.size); out.writeInt(ordered.size); out.writeInt(duration); out.write(metadata)
      ordered.forEach { note ->
        out.writeInt(note.time); out.writeByte(note.instrument)
        if (version == 4) out.writeShort(note.pitchCents) else out.writeByte(NoteBlockPitch.foldForVanilla(note.pitch))
        out.writeByte(note.volume); out.writeByte(note.pan)
      }
    }; bytes.toByteArray() }
  }

  private val gson = Gson()
  private fun json(text: String): String = gson.toJson(text)
  private fun currentSnapshot() = EditorSnapshot(
      notes.map { it.copy() }, selectedIds.toSet(), selected, songTitle, bpm, horizontalOffset, viewSpanMs,
      activePart, parts.toList(), ppq, beatsPerBar, beatUnit, pitchMin, visiblePitchCount, snapDivisor,
      followPlayback, playheadMs, allPartsView, tempoMarks.toList(), signatureMarks.toList(), gridMarks.toList(),
      tempoControls.map { it.copy() }, RetriggerProfile(), emptyMap(),
  )

  private fun ensureDirectories() = EditorWorkspace.requireDirectories()
  private fun openMidiFolder() {
    EditorWorkspace.openDirectory(midiDirectory)
        .onSuccess { state = t("Opened MIDI folder", "MIDIフォルダーを開きました") }
        .onFailure { error -> state = t("Could not open MIDI folder: ${error.message ?: "no folder opener"}", "MIDIフォルダーを開けませんでした: ${error.message ?: "フォルダー起動機能がありません"}") }
  }
  private fun openNbsFolder() {
    EditorWorkspace.openDirectory(nbsDirectory)
        .onSuccess { state = t("Opened NBS folder", "NBSフォルダーを開きました") }
        .onFailure { error -> state = t("Could not open NBS folder: ${error.message ?: "no folder opener"}", "NBSフォルダーを開けませんでした: ${error.message ?: "フォルダー起動機能がありません"}") }
  }
  private fun openSaveFolder() {
    EditorWorkspace.openDirectory(saveDirectory)
        .onSuccess { state = t("Opened project folder", "保存フォルダーを開きました") }
        .onFailure { error -> state = t("Could not open project folder: ${error.message ?: "no folder opener"}", "保存フォルダーを開けませんでした: ${error.message ?: "フォルダー起動機能がありません"}") }
  }
  private fun refreshLibraries() {
    try {
      ensureDirectories()
      midiFiles = Files.list(midiDirectory).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().let { name -> name.endsWith(".mid") || name.endsWith(".midi") } }.sorted().toList() }
      nbsFiles = Files.list(nbsDirectory).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".nbs") }.sorted().toList() }
      saveFiles = Files.list(saveDirectory).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".ommt") }.sorted().toList() }
      libraryScroll = libraryScroll.coerceIn(0, (midiFiles.size - 1).coerceAtLeast(0))
    } catch (error: Exception) {
      midiFiles = emptyList(); nbsFiles = emptyList(); saveFiles = emptyList()
      state = t("Could not initialize OMMT folders: ${error.message ?: "file system error"}", "OMMTフォルダーを準備できませんでした: ${error.message ?: "ファイルシステムエラー"}")
    }
  }
  private fun refreshMidiLibrary() = refreshLibraries()

  private fun saveProject() {
    try {
      applySelected(); require(notes.isNotEmpty()) { t("Add at least one note before saving", "保存前に1音以上追加してください") }
      val bytes = EditorProjectCodec.encode(currentSnapshot())
      val target = currentProjectFile ?: uniqueProjectPath(songTitle)
      atomicWrite(target, bytes)
      currentProjectFile = target; selectedSave = target; refreshLibraries()
      state = t("Saved ${target.fileName} (${notes.size} notes)", "${target.fileName}に${notes.size}音を保存しました")
    } catch (error: Exception) {
      state = t("Project save failed: ${error.message ?: "invalid state"}", "編集データの保存に失敗しました: ${error.message ?: "不正な状態"}")
    }
  }

  private fun loadProject(path: Path) {
    try {
      require(Files.isRegularFile(path) && path.fileName.toString().lowercase().endsWith(".ommt")) { "Select an .ommt project" }
      require(Files.size(path) <= 64L * 1024 * 1024 + 20L) { "OMMT project exceeds 64 MiB" }
      val saved = EditorProjectCodec.decode(Files.readAllBytes(path))
      saved.notes.forEach { note -> note.customSound?.let { sound ->
        val definition = supportedCustomSound(sound) ?: throw IllegalArgumentException("Unsupported Minecraft sound: $sound")
        require(note.customSoundPattern in 1..definition.patterns) { "Unsupported sound pattern for $sound" }
      } }
      pausePlayback(); EditorSession.replace(); history.clear()
      notes.clear(); notes += saved.notes.map { it.copy() }; selectedIds.clear(); selectedIds += saved.selectedIds
      selected=saved.selected; songTitle=saved.title; bpm=saved.bpm; horizontalOffset=saved.offset; viewSpanMs=saved.span
      activePart=saved.part; allPartsView=saved.allPartsView; parts.clear(); parts += saved.parts.mapIndexed(::normalizePartLabel)
      nbsPartHierarchy = emptyList()
      ppq=saved.ppq; beatsPerBar=saved.beats; beatUnit=saved.unit; pitchMin=saved.pitchMin; visiblePitchCount=saved.visiblePitches
      snapDivisor=saved.snapDivisor; followPlayback=saved.followPlayback; playheadMs=saved.playheadMs; visualPlayheadMs=playheadMs.toFloat()
      tempoMarks=saved.tempos; signatureMarks=saved.signatures; gridMarks=saved.grid; tempoControls.clear(); tempoControls += saved.tempoControls
      selectedTempoPointId = tempoControls.firstOrNull()?.id; selectedTempoPointIds.clear(); selectedTempoPointId?.let { selectedTempoPointIds += it }
      migrateLegacyReleaseScopes(saved.globalRetrigger, saved.partRetriggers)
      sortNotesAndResolvePrimary(notes.getOrNull(selected)?.id); clampPitchViewport()
      titleField.text=songTitle; bpmField.text=bpm.toString(); syncImGuiProject(); choose(selected, replaceSelection=false, ensureVisible=false); syncPartControls()
      selectedMidi=null; selectedNbs=null; selectedSave=path; currentProjectFile=path
      state=t("Loaded ${path.fileName} (${notes.size} notes)", "${path.fileName}から${notes.size}音を読み込みました")
    } catch (error: Exception) {
      state=t("Project load failed: ${error.message ?: "invalid project"}", "編集データの読み込みに失敗しました: ${error.message ?: "不正なファイル"}")
    }
  }

  private fun uniqueProjectPath(title: String): Path {
    val base = title.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).ifBlank { "Untitled" }
    var candidate = saveDirectory.resolve("$base.ommt")
    var suffix = 2
    while (Files.exists(candidate)) { candidate = saveDirectory.resolve("$base-$suffix.ommt"); suffix++ }
    return candidate
  }

  private fun atomicWrite(target: Path, bytes: ByteArray) {
    Files.createDirectories(target.parent)
    val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.write(temp, bytes)
    try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
  }
  private fun editorLeft() = libraryWidth() + 8
  private fun libraryWidth() = if (settings.showLibrary) EditorLayout(width,height).libraryWidth else 0
  private fun settingsLeft() = (width - 300).coerceAtLeast(editorLeft())
  private fun saveSettings() { EditorSettingsStore.save(settings.copy(lastTool = tool)) }
  private fun cycleGridDensity() {
    settings = settings.copy(gridDensity = when (settings.gridDensity) { "AUTO" -> "SPARSE"; "SPARSE" -> "NORMAL"; "NORMAL" -> "DENSE"; else -> "AUTO" })
  }
  private fun cycleSnap() { snapDivisor = when (snapDivisor) { 4 -> 8; 8 -> 16; 16 -> 32; 32 -> 64; 64 -> 0; else -> 4 }; state=t("Snap ${if(snapDivisor==0) "off" else "1/$snapDivisor"}", "スナップ: ${if(snapDivisor==0) "オフ" else "1/$snapDivisor"}") }
  private fun switchPart(direction: Int) { if(parts.isEmpty()) return; activePart=Math.floorMod(activePart+direction,parts.size); partStripPage=activePart/PART_TABS_PER_PAGE; allPartsView=false; syncPartControls(); state=t("Editing ${parts[activePart]}", "${parts[activePart]}を編集中") }
  private fun cycleTool() { tool = when (tool) { EditorTool.SELECT -> EditorTool.DRAW; EditorTool.DRAW -> EditorTool.PAN; EditorTool.PAN -> EditorTool.SELECT } }

  /** The visible editor domain includes every imported MIDI pitch while vanilla output stays 0..24. */
  private fun pitchDomainMin() = min(NoteBlockPitch.VANILLA_MIN, notes.minOfOrNull { it.pitch } ?: NoteBlockPitch.VANILLA_MIN)
    .coerceAtLeast(NoteBlockPitch.DISPLAY_MIN)
  private fun pitchDomainMax() = max(NoteBlockPitch.VANILLA_MAX, notes.maxOfOrNull { it.pitch } ?: NoteBlockPitch.VANILLA_MAX)
    .coerceAtMost(NoteBlockPitch.DISPLAY_MAX)
  private fun pitchDomainSize() = pitchDomainMax() - pitchDomainMin() + 1
  private fun clampPitchViewport() {
    val size = pitchDomainSize().coerceAtLeast(1)
    visiblePitchCount = visiblePitchCount.coerceIn(min(5, size), size)
    pitchMin = pitchMin.coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1)
  }
  private fun fitPitchRange() {
    val domainMin = pitchDomainMin(); val domainMax = pitchDomainMax(); val domainSize = domainMax - domainMin + 1
    // FIT is allowed to use sub-five-pixel rows: its purpose is to reveal the entire imported
    // source-pitch domain. Users can immediately zoom vertically for detailed editing.
    visiblePitchCount = domainSize
    pitchMin = domainMin
    clampPitchViewport()
  }
  private fun handleSettingsClick(x: Int, y: Int): Boolean {
    if (!settingsOpen || !inRect(x, y, settingsLeft(), 38, width - 12 - settingsLeft(), 192)) return false
    val row = (y - 76) / 20
    settings = when (row) {
      0 -> settings.copy(showLibrary = !settings.showLibrary)
      1 -> settings.copy(showInspector = !settings.showInspector)
      2 -> settings.copy(showAutomation = !settings.showAutomation)
      3 -> settings.copy(showOtherParts = !settings.showOtherParts)
      5 -> settings.copy(compactToolbar = !settings.compactToolbar)
      6 -> settings.copy(followLead = (settings.followLead + 5).let { if (it > 70) 20 else it })
      else -> settings
    }
    when (row) { 4 -> cycleGridDensity(); 7 -> cycleTool() }
    applyPanelVisibility()
    saveSettings(); state = t("Editor settings saved", "エディター設定を保存しました"); return true
  }
  private fun applyPanelVisibility() {
    if (!::titleField.isInitialized) return
    val inspector = settings.showInspector
    val inspectorFields = listOf(timeField, durationField, instrumentField, pitchField, volumeField, panField)
    inspectorFields.forEach { it.visible = inspector }
    // A collapsed inspector cannot retain an invisible focus target that might commit on a later click.
    if (!inspector && focused in inspectorFields) {
      inspectorFields.forEach { it.setFocused(false) }
      setFocused(null)
      // setFocused(false) stops OS IME as a side effect; ImGui still owns text input.
      runCatching { Minecraft.getInstance().textInputManager().startTextInput() }
    }
  }
  private fun visibleSpan() = viewSpanMs.coerceAtLeast(2_000)
  private fun rollTop() = if (settings.showInspector) 204 else 100
  private fun noteTop() = rollTop() + 24
  private fun laneAreaHeight() = if (!settings.showAutomation) 0 else ((height - noteTop() - 64) * 0.18).roundToInt().coerceAtLeast(42)
  private fun rollBottom() = height - 64 - laneAreaHeight()
  private fun laneHeight() = (laneAreaHeight() / 2).coerceAtLeast(14)
  private fun laneTop(index: Int) = rollBottom() + 5 + index * laneHeight()
  private fun keyboardLeft() = editorLeft() + 12
  private fun plotLeft() = keyboardLeft() + 40
  private fun plotRight() = width - 12
  private fun timeToX(time: Int) = plotLeft() + ((time - horizontalOffset).toFloat() / visibleSpan() * (plotRight() - plotLeft())).roundToInt()
  private fun xToTime(x: Double) = (horizontalOffset + ((x - plotLeft()) / (plotRight() - plotLeft()).coerceAtLeast(1) * visibleSpan())).roundToInt()
  private fun pitchToY(pitch: Int): Int = rollBottom() - ((pitch - pitchMin + 1) * (rollBottom() - noteTop()) / visiblePitchCount.toFloat()).roundToInt()
  private fun pitchAt(y: Double): Int = (pitchMin + ((rollBottom() - y) / (rollBottom() - noteTop()).coerceAtLeast(1) * visiblePitchCount).toInt()).coerceIn(pitchDomainMin(), pitchDomainMax())
  private fun rowHeight() = ((rollBottom() - noteTop()) / visiblePitchCount.toFloat()).coerceAtLeast(2f)
  private fun formatTime(timeMs: Int) = "%d:%02d.%03d".format(timeMs / 60_000, timeMs / 1_000 % 60, timeMs % 1_000)
  private fun fitTimeline() { horizontalOffset = 0; viewSpanMs = (durationMs() + 1_000).coerceAtLeast(2_000); state = t("Timeline fitted to the song", "曲全体を表示しました") }
  private fun zoomTimeline(multiplier: Double, anchorX: Double) {
    followPlayback = false
    val oldSpan = visibleSpan(); val maximum = (durationMs() + 10_000).coerceAtLeast(30_000)
    val newSpan = (oldSpan * multiplier).roundToInt().coerceIn(2_000, maximum)
    val fraction = ((anchorX - plotLeft()) / (plotRight() - plotLeft()).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val anchorTime = horizontalOffset + (oldSpan * fraction).roundToInt()
    viewSpanMs = newSpan
    horizontalOffset = (anchorTime - (newSpan * fraction).roundToInt()).coerceIn(0, durationMs().coerceAtLeast(newSpan) - newSpan)
  }
  private fun zoomTimelineCentered(multiplier: Double) {
    followPlayback=false; val oldSpan=visibleSpan(); val maximum=(durationMs()+10_000).coerceAtLeast(30_000); val newSpan=(oldSpan*multiplier).roundToInt().coerceIn(2_000,maximum); val center=horizontalOffset+oldSpan/2
    viewSpanMs=newSpan; horizontalOffset=(center-newSpan/2).coerceIn(0,durationMs().coerceAtLeast(newSpan)-newSpan)
  }

  private fun snap(time: Int): Int {
    if (snapDivisor <= 0) return time.coerceAtLeast(0)
    if (gridMarks.isEmpty()) return time.coerceAtLeast(0)
    // GridMark is stored at 1/64 resolution. A musical-bar boundary is always eligible, while
    // coarser settings select every Nth fine subdivision. Search outward from a binary-search
    // insertion point so dragging a large MIDI does not scan and allocate the complete grid on
    // every mouse event.
    val stride = (64 / snapDivisor).coerceAtLeast(1)
    fun eligible(mark: GridMark) = mark.isBar || mark.subdivision % stride == 0
    var low = 0
    var high = gridMarks.size
    while (low < high) {
      val middle = (low + high) ushr 1
      if (gridMarks[middle].timeMs < time) low = middle + 1 else high = middle
    }
    var left = low - 1
    while (left >= 0 && !eligible(gridMarks[left])) left--
    var right = low
    while (right < gridMarks.size && !eligible(gridMarks[right])) right++
    val leftMark = gridMarks.getOrNull(left)
    val rightMark = gridMarks.getOrNull(right)
    return when {
      leftMark == null -> rightMark?.timeMs ?: time.coerceAtLeast(0)
      rightMark == null -> leftMark.timeMs
      kotlin.math.abs(leftMark.timeMs - time) <= kotlin.math.abs(rightMark.timeMs - time) -> leftMark.timeMs
      else -> rightMark.timeMs
    }
  }

  /** Commits the tempo BPM/curve buffers to the selected point (APPLY_TEMPO button or Enter). */
  private fun applyTempoPoint() {
    val selectedPoint = tempoControls.firstOrNull { it.id == selectedTempoPointId } ?: return
    rememberHistory()
    selectedPoint.bpm = imTempoBpm.get().coerceIn(1, 60_000)
    selectedPoint.curve = AutomationCurve.entries[imTempoCurve.get().coerceIn(0, AutomationCurve.entries.lastIndex)]
    applyTempoControls()
  }

  private fun applyTempoControls(retime: Boolean = true) {
    if (tempoControls.isEmpty()) tempoControls += TempoControlPoint(0, bpm.coerceAtLeast(1))
    val old = tempoMarks
    val compiled = EditorAutomation.compileTempo(tempoControls, ppq)
    if (retime) EditorAutomation.retimeNotes(notes, old, compiled, ppq)
    tempoMarks = compiled
    gridMarks = gridMarks.map { it.copy(timeMs = EditorAutomation.timeAtTick(it.tick, tempoMarks, ppq)) }.sortedBy { it.tick }
    bpm = tempoControls.minByOrNull { it.tick }?.bpm?.coerceIn(1, 60_000) ?: bpm
    imBpm.set(bpm); if (::bpmField.isInitialized) bpmField.text = bpm.toString()
    sortNotesAndResolvePrimary()
  }

  private fun setBaseBpm(value: Int) {
    val normalized = value.coerceIn(1, 60_000)
    val first = tempoControls.minByOrNull { it.tick }
    if (first == null) tempoControls += TempoControlPoint(0, normalized) else { first.tick = 0; first.bpm = normalized }
    applyTempoControls()
  }
  private fun selectedNotes() = notes.filter { it.id in selectedIds }
  private fun notesInCurrentView() = if (allPartsView) notes else notes.filter { it.part == activePart }
  /** Notes are sorted by start time after every time-changing edit; scan only the visible slice. */
  private fun indexedNotesInVisibleTimeline(): List<IndexedValue<EditorNote>> {
    if (notes.isEmpty()) return emptyList()
    val earliestStart = (horizontalOffset - 60_000).coerceAtLeast(0)
    val latestStart = horizontalOffset + visibleSpan()
    var low = 0
    var high = notes.size
    while (low < high) {
      val middle = (low + high) ushr 1
      if (notes[middle].time < earliestStart) low = middle + 1 else high = middle
    }
    val visible = ArrayList<IndexedValue<EditorNote>>()
    var index = low
    while (index < notes.size && notes[index].time <= latestStart) {
      val note = notes[index]
      if (note.time + note.duration >= horizontalOffset && (allPartsView || note.part == activePart)) visible += IndexedValue(index, note)
      index++
    }
    return visible
  }
  private fun moveSelectionToPart(part: Int, recordHistory: Boolean = true) {
    val targets = selectedNotes()
    if (targets.isEmpty() || part !in parts.indices) {
      state = t("Select notes and an existing part first", "移動する音と既存パートを選択してください")
      return
    }
    if (targets.all { it.part == part }) {
      activePart = part; allPartsView = false; syncPartControls()
      state = t("The selected notes are already in ${parts[part]}", "選択音はすでに${parts[part]}にあります")
      return
    }
    if (recordHistory) rememberHistory()
    targets.forEach { it.part = part }
    activePart = part; allPartsView = false; syncPartControls()
    state = t("Moved ${targets.size} notes to ${parts[part]}", "${targets.size}音を${parts[part]}へ移動しました")
  }
  private fun createPartFromSelection() {
    if (selectedIds.isEmpty()) return
    val before = currentHistory()
    parts += t("Part ${parts.size + 1}", "パート ${parts.size + 1}"); moveSelectionToPart(parts.lastIndex, recordHistory = false)
    syncPartControls()
    history.push(before)
  }

  private fun renameActivePart() {
    if (activePart !in parts.indices) return
    val name = imPartName.get().replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
    if (name.isBlank()) {
      state = t("Part name cannot be empty", "パート名を空にはできません")
      return
    }
    if (parts[activePart] == name) return
    rememberHistory(); parts[activePart] = name; syncPartControls()
    state = t("Renamed part to $name", "パート名を「$name」に変更しました")
  }

  private fun adjustSelectedPitch(delta: Int) {
    val targets = selectedNotes()
    if (targets.isEmpty()) return
    val minimumDelta = NoteBlockPitch.DISPLAY_MIN - targets.minOf { it.pitch }
    val maximumDelta = NoteBlockPitch.DISPLAY_MAX - targets.maxOf { it.pitch }
    val applied = delta.coerceIn(minimumDelta, maximumDelta)
    if (applied == 0) return
    rememberHistory(); targets.forEach { it.pitch += applied; it.pitchCents += applied * 100 }
    imPitch.set(notes.getOrNull(selected)?.pitch ?: targets.first().pitch)
    imPitchCents.set(pitchOffset(notes.getOrNull(selected) ?: targets.first()))
    clampPitchViewport()
    state = t("Moved ${targets.size} notes by ${if (applied > 0) "+" else ""}$applied semitones", "${targets.size}音を${if (applied > 0) "+" else ""}${applied}半音移動しました")
  }

  /**
   * Detune fine-tune: offset from the placed pitch, in cents. The placed pitch is
   * never touched; only the absolute -5400..7300 clamp applies per note.
   */
  private fun pitchOffset(note: EditorNote) = note.pitchCents - note.pitch * 100

  private fun adjustSelectedPitchCents(deltaCents: Int) {
    val targets = selectedNotes()
    if (targets.isEmpty()) return
    val minimumDelta = targets.maxOf { -5400 - it.pitchCents }
    val maximumDelta = targets.minOf { 7300 - it.pitchCents }
    val applied = deltaCents.coerceIn(minimumDelta, maximumDelta)
    if (applied == 0) return
    rememberHistory()
    targets.forEach { it.pitchCents += applied }
    val primary = notes.getOrNull(selected) ?: targets.first()
    imPitch.set(primary.pitch)
    imPitchCents.set(pitchOffset(primary))
    clampPitchViewport()
    state = t("Moved ${targets.size} notes by ${if (applied > 0) "+" else ""}$applied cents", "${targets.size}音を${if (applied > 0) "+" else ""}${applied}セント移動しました")
  }

  private fun resetNativeCursor() {
    runCatching { ImGui.setMouseCursor(ImGuiMouseCursor.Arrow) }
    runCatching { MinecraftClient.getInstance().window.handle.takeIf { it != 0L }?.let { GLFW.glfwSetCursor(it, 0L) } }
  }

  private fun defaultDurationAt(time: Int): Int {
    if (snapDivisor <= 0) return 125
    val stride = (64 / snapDivisor).coerceAtLeast(1)
    val next = gridMarks.firstOrNull { mark -> mark.timeMs > time && (mark.isBar || mark.subdivision % stride == 0) }
    return ((next?.timeMs ?: (time + 125)) - time).coerceIn(1, 60_000)
  }

  private fun addNoteAt(timeInput: Int, pitch: Int) {
    val time = snap(timeInput)
    val part = activePart.coerceIn(0, parts.lastIndex.coerceAtLeast(0))
    val instrument = notes.firstOrNull { it.part == part }?.instrument ?: notes.getOrNull(selected)?.instrument ?: 0
    val duration = defaultDurationAt(time)
    val startTick = EditorAutomation.tickAtTime(time, tempoMarks, ppq)
    val endTick = EditorAutomation.tickAtTime(time + duration, tempoMarks, ppq)
    rememberHistory()
    val added = EditorNote(time, duration, instrument, pitch, 100, 0, part = part, sourceTick = startTick, sourceDurationTicks = (endTick - startTick).coerceAtLeast(1))
    notes += added
    selectedIds.clear(); selectedIds += added.id
    sortNotesAndResolvePrimary(added.id); choose(selected, false)
    state = t("Added note at ${formatTime(time)}", "${formatTime(time)}にノートを追加しました")
  }

  private fun updateVisualPlayback() {
    val now = System.nanoTime()
    val elapsed = if (lastVisualRenderNanos == 0L) 0.0 else ((now - lastVisualRenderNanos) / 1_000_000_000.0).coerceIn(0.0, 0.25)
    lastVisualRenderNanos = now
    visualPlayheadMs = if (playing) editorPlaybackPositionMs(playbackStartMs, playbackStartedAtNanos, now, durationMs()).toFloat() else playheadMs.toFloat()
    if (!playing || !followPlayback) return
    val target = (visualPlayheadMs - visibleSpan() * 0.45f).coerceAtLeast(0f)
    val alpha = (1.0 - kotlin.math.exp(-6.0 * elapsed)).toFloat()
    horizontalOffset = (horizontalOffset + (target - horizontalOffset) * alpha).roundToInt()
      .coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan())
  }

  override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
    if (externalUiActive) return true
    val x = click.x().roundToInt(); val y = click.y().roundToInt()
    if (focused is TextFieldWidget && !overTextField(x, y)) { applySelected(); unfocusFields() }
    if (click.button() == 0 && handleSettingsClick(x, y)) return true
    if (contextNoteId != null) {
      if (click.button() == 0 && activateContext(x, y)) return true
      contextNoteId = null
      return true
    }
    if (click.button() == 0 && x >= plotRight() - 6 && y in noteTop()..rollBottom()) { verticalScrollbar = true; setVerticalFrom(y); return true }
    if (click.button() == 0 && y in (rollBottom() - 6)..rollBottom() && x >= plotLeft()) { horizontalScrollbar = true; setHorizontalFrom(x); return true }
    if (click.button() == 0) {
      if (settings.showLibrary && inRect(x, y, 12, height - 62, (libraryWidth() - 30) / 2, 26)) { refreshMidiLibrary(); state = t("MIDI library refreshed", "MIDIライブラリを更新しました"); return true }
      if (settings.showLibrary && inRect(x, y, 18 + (libraryWidth() - 30) / 2, height - 62, (libraryWidth() - 30) / 2, 26)) { openMidiFolder(); return true }
      if (inRect(x, y, editorLeft() + 296, 58, 108, 22)) { loadSelectedSource(); return true }
      if (inRect(x, y, width - 48, 6, 36, 22)) { settingsOpen = !settingsOpen; saveSettings(); return true }
      if (settings.showInspector && inRect(x, y, editorLeft() + 396, 128, 58, 22)) { applySelected(); return true }
      if (settings.showInspector && inRect(x, y, editorLeft() + 460, 128, 42, 22)) { rememberHistory(); val added = EditorNote(playheadMs, 200, 0, 12, 100, 0); notes += added; sortNotesAndResolvePrimary(added.id); choose(selected); state = t("Added note at ${formatTime(playheadMs)}", "${formatTime(playheadMs)}にノートを追加しました"); return true }
      if (settings.showInspector && inRect(x, y, editorLeft() + 508, 128, 60, 22)) { deleteSelected(); return true }
      if (settings.showInspector && inRect(x, y, editorLeft() + 574, 128, 70, 22)) { preview(); return true }
      if (inRect(x, y, editorLeft() + 16, 168, 42, 24)) { rewind(); return true }
      if (inRect(x, y, editorLeft() + 64, 168, 62, 24)) { togglePlayback(); return true }
      if (inRect(x, y, editorLeft() + 132, 168, 70, 24)) { followPlayback = !followPlayback; state = t("Follow ${if (followPlayback) "enabled" else "disabled"}", "追従を${if (followPlayback) "有効" else "無効"}にしました"); return true }
      if (inRect(x, y, editorLeft() + 208, 168, 46, 24)) { fitTimeline(); return true }
      if (inRect(x, y, editorLeft() + 260, 168, 30, 24)) { zoomTimeline(1.25, plotLeft().toDouble() + (plotRight() - plotLeft()) / 2); return true }
      if (inRect(x, y, editorLeft() + 296, 168, 30, 24)) { zoomTimeline(0.8, plotLeft().toDouble() + (plotRight() - plotLeft()) / 2); return true }
      if (inRect(x, y, width - 172, height - 54, 160, 34)) { exportAndUpload(); return true }
      if (inRect(x, y, editorLeft() + 334, 168, 48, 24)) { snapDivisor = when (snapDivisor) { 4 -> 8; 8 -> 16; 16 -> 32; 32 -> 64; 64 -> 0; else -> 4 }; state = t("Snap ${if (snapDivisor == 0) "off" else "1/$snapDivisor"}", "スナップ: ${if (snapDivisor == 0) "オフ" else "1/$snapDivisor"}"); return true }
      if (inRect(x, y, editorLeft() + 388, 168, 78, 24)) { createPartFromSelection(); return true }
      if (inRect(x, y, editorLeft() + 472, 168, 70, 24)) { if (parts.size > 1) moveSelectionToPart((activePart + 1) % parts.size); return true }
      val row = (y - 72) / 23 + libraryScroll
      if (settings.showLibrary && x in 12 until libraryWidth() - 12 && y >= 72 && y < height - 76 && row in midiFiles.indices) { selectedMidi = midiFiles[row]; selectedNbs = null; state = t("Selected ${selectedMidi!!.fileName}", "${selectedMidi!!.fileName}を選択しました"); if (doubled) loadMidi(); return true }
      if (x >= plotLeft() && y in laneTop(0)..(laneTop(1) + laneHeight() - 2)) {
        if (!settings.showAutomation) return true
        if (!laneGesture) { rememberHistory(); laneGesture = true }
        val lane = ((y - laneTop(0)) / laneHeight()).coerceIn(0, 1); val value = ((laneTop(lane) + laneHeight() - 2 - y) * 100 / (laneHeight() - 2).coerceAtLeast(1)).coerceIn(0, 100)
        selectedNotes().forEach { if (lane == 0) it.volume = value else it.pan = (value * 2 - 100).coerceIn(-100, 100) }
        state = if (lane == 0) t("Volume set to $value for ${selectedIds.size} notes", "${selectedIds.size}音の音量を $value に変更しました") else t("Pan set for ${selectedIds.size} notes", "${selectedIds.size}音の定位を変更しました")
        return true
      }
      if (y in rollTop() until noteTop() && x >= plotLeft()) { seek(xToTime(click.x())); followPlayback = false; state = t("Seek ${formatTime(playheadMs)}", "${formatTime(playheadMs)}へ移動しました"); return true }
      if (x in keyboardLeft() until plotLeft() && y in noteTop() until rollBottom()) { previewNote(EditorNote(0, 200, notes.getOrNull(selected)?.instrument ?: 0, pitchAt(click.y()), 100, 0)); return true }
    }
    if (click.button() == 1 && click.x() >= plotLeft() && click.y() >= noteTop() && click.y() <= rollBottom()) {
      val target = notes.firstOrNull { note -> note.pitch in pitchMin..(pitchMin + visiblePitchCount - 1).coerceAtMost(24) && click.x() in timeToX(note.time).toDouble()..timeToX(note.time + note.duration).coerceAtLeast(timeToX(note.time) + 5).toDouble() && kotlin.math.abs(pitchToY(note.pitch) - click.y()) <= rowHeight() }
      if (target != null) { if (target.id !in selectedIds) { selectedIds.clear(); selectedIds += target.id }; contextNoteId = target.id; contextX = x; contextY = y; return true }
      panning = true; panOriginX = x; return true
    }
    if (click.button() == 0 && click.x() >= plotLeft() && click.y() >= noteTop() && click.y() <= rollBottom()) {
      val nearest = notes.withIndex().filter {
        if (it.value.pitch !in pitchMin..(pitchMin + visiblePitchCount - 1).coerceAtMost(24)) false else {
          val start = timeToX(it.value.time); val end = timeToX(it.value.time + it.value.duration).coerceAtLeast(start + 5)
          click.x() in (start - 2).toDouble()..(end + 2).toDouble() && kotlin.math.abs(pitchToY(it.value.pitch) - click.y()) <= rowHeight() / 2 + 2
        }
      }.minByOrNull { kotlin.math.abs(timeToX(it.value.time) - click.x()) }
      val handle = client?.window?.handle ?: 0L
      val ctrl = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
      val shift = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS)
      // Shift drag is a marquee gesture even when it starts above a note.
      if (shift) { selectionStart = snap(xToTime(click.x())) to pitchAt(click.y()); selectionEnd = selectionStart; return true }
      if (nearest != null) {
        if (ctrl) selectedIds += nearest.value.id else if (!shift) { selectedIds.clear(); selectedIds += nearest.value.id }
        selected = nearest.index; preview(); rememberHistory(); draggingNotes = true; dragMouseTime = xToTime(click.x()); dragMousePitch = pitchAt(click.y()); dragOriginTime = snap(dragMouseTime); dragOriginPitch = dragMousePitch; dragBase = selectedNotes().associate { it.id to (it.time to it.pitch) }; return true
      }
      selectionStart = snap(xToTime(click.x())) to pitchAt(click.y()); selectionEnd = selectionStart; return true
    }
    return super.mouseClicked(click, doubled)
  }

  private fun overTextField(x: Int, y: Int): Boolean =
    (x in editorLeft() + 16 until editorLeft() + 206 && y in 58 until 80) ||
            (x in editorLeft() + 218 until editorLeft() + 280 && y in 58 until 80) ||
            (settings.showInspector && y in 128 until 150 && x in editorLeft() + 16 until editorLeft() + 384)
  private fun unfocusFields() {
    listOf(titleField, bpmField, timeField, durationField, instrumentField, pitchField, volumeField, panField).forEach { it.setFocused(false) }
    setFocused(null)
  }

  private fun setHorizontalFrom(x: Int) {
    val total = (durationMs() + 1).coerceAtLeast(visibleSpan()); val fraction = ((x - plotLeft()).toDouble() / (plotRight() - plotLeft()).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    horizontalOffset = ((total - visibleSpan()) * fraction).roundToInt().coerceAtLeast(0); followPlayback = false
  }
  private fun setVerticalFrom(y: Int) {
    val fraction = ((y - noteTop()).toDouble() / (rollBottom() - noteTop()).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    pitchMin = ((25 - visiblePitchCount) * fraction).roundToInt().coerceIn(0, 25 - visiblePitchCount)
  }
  private fun activateContext(x: Int, y: Int): Boolean {
    val entries = contextEntries(); val rows = entries.size; val left = contextX.coerceIn(plotLeft(), width - 146); val top = contextY.coerceIn(noteTop(), height - rows * 18 - 4)
    if (x !in left..(left + 142) || y !in top..(top + rows * 18)) return false
    val entry = entries.getOrNull((y - top) / 18) ?: return false
    entry.part?.let { moveSelectionToPart(it); contextNoteId = null; return true }
    when (entry.command) {
      "previous" -> { contextPartOffset = (contextPartOffset - 5).coerceAtLeast(0); return true }
      "next" -> { contextPartOffset = (contextPartOffset + 5).coerceAtMost(((parts.size - 1) / 5) * 5); return true }
      "new" -> createPartFromSelection()
      "duplicate" -> {
        rememberHistory()
        val copies = selectedNotes().map { it.copy(time = snap(it.time + 120), id = EditorSession.nextStableId()) }
        notes += copies; selectedIds.clear(); selectedIds += copies.map { it.id }; sortNotesAndResolvePrimary(copies.firstOrNull()?.id); state = t("Duplicated ${copies.size} notes", "${copies.size}音を複製しました")
      }
      "delete" -> { rememberHistory(); notes.removeIf { it.id in selectedIds }; selectedIds.clear(); sortNotesAndResolvePrimary(); choose(selected); state = t("Deleted selected notes", "選択したノートを削除しました") }
    }
    contextNoteId = null; return true
  }

  override fun mouseDragged(click: Click, offsetX: Double, offsetY: Double): Boolean {
    if (externalUiActive) return true
    if (horizontalScrollbar) { setHorizontalFrom(click.x().roundToInt()); return true }
    if (verticalScrollbar) { setVerticalFrom(click.y().roundToInt()); return true }
    if (panning) { horizontalOffset = (horizontalOffset - offsetX / (plotRight() - plotLeft()).coerceAtLeast(1) * visibleSpan()).roundToInt().coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan()); followPlayback = false; return true }
    if (selectionStart != null) { selectionEnd = snap(xToTime(click.x())) to pitchAt(click.y()); return true }
    if (settings.showAutomation && click.x() >= plotLeft() && click.y().roundToInt() in laneTop(0)..(laneTop(1) + laneHeight() - 2) && selectedIds.isNotEmpty()) {
      val y = click.y().roundToInt(); val lane = ((y - laneTop(0)) / laneHeight()).coerceIn(0, 1); val value = ((laneTop(lane) + laneHeight() - 2 - y) * 100 / (laneHeight() - 2).coerceAtLeast(1)).coerceIn(0, 100)
      selectedNotes().forEach { if (lane == 0) it.volume = value else it.pan = value * 2 - 100 }; return true
    }
    if (draggingNotes) {
      val handle = client?.window?.handle ?: 0L; val pitchFixed = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS)
      val deltaTime = snap(xToTime(click.x())) - dragOriginTime; var deltaPitch = if (pitchFixed) 0 else pitchAt(click.y()) - dragMousePitch
      val base = dragBase.values; val minTime = base.minOfOrNull { it.first } ?: 0; val minPitch = base.minOfOrNull { it.second } ?: 0; val maxPitch = base.maxOfOrNull { it.second } ?: 24
      val clampedTime = deltaTime.coerceAtLeast(-minTime); deltaPitch = deltaPitch.coerceIn(-minPitch, 24-maxPitch)
      selectedNotes().forEach { note -> dragBase[note.id]?.let { baseNote ->
        note.time = baseNote.first + clampedTime
        note.pitch = baseNote.second + deltaPitch
        note.pitchCents = ((dragBaseCents[note.id] ?: baseNote.second * 100) + deltaPitch * 100).coerceIn(-5400, 7300)
      } }; return true
    }
    return super.mouseDragged(click, offsetX, offsetY)
  }

  override fun mouseReleased(click: Click): Boolean {
    if (externalUiActive) return true
    if (selectionStart != null) {
      val (t1, p1) = selectionStart!!; val (t2, p2) = selectionEnd!!
      val handle = client?.window?.handle ?: 0L; val ctrl = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
      if (!ctrl) selectedIds.clear(); notes.filter { EditorSelection.intersectsMarquee(it.time, it.duration, it.pitch, t1, t2, p1, p2) }.forEach { selectedIds += it.id }
      state = t("Selected ${selectedIds.size} notes", "${selectedIds.size}音を選択しました"); selectionStart = null; selectionEnd = null
    }
    if (draggingNotes) sortNotesAndResolvePrimary()
    draggingNotes = false; laneGesture = false; panning = false; horizontalScrollbar = false; verticalScrollbar = false
    return super.mouseReleased(click)
  }

  override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (externalUiActive) return true
    if (settings.showLibrary && mouseX < libraryWidth() && mouseY >= 72 && mouseY < height - 76) { libraryScroll = (libraryScroll - verticalAmount.roundToInt()).coerceIn(0, (midiFiles.size - 1).coerceAtLeast(0)); return true }
    if (mouseY >= rollTop() && mouseY <= rollBottom()) {
      val handle = client?.window?.handle ?: 0L
      val ctrl = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
      val alt = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS)
      val shift = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS)
      if (ctrl) zoomTimeline(if (verticalAmount > 0) 0.8 else 1.25, mouseX)
      else if (shift) { visiblePitchCount = (visiblePitchCount + if (verticalAmount > 0) -1 else 1).coerceIn(5, 25); pitchMin = pitchMin.coerceIn(0, 25 - visiblePitchCount); followPlayback = false }
      else if (alt) { pitchMin = (pitchMin - verticalAmount.roundToInt()).coerceIn(0, 25 - visiblePitchCount); followPlayback = false }
      else { val movement = ((horizontalAmount.takeIf { it != 0.0 } ?: verticalAmount) * visibleSpan() * 0.08).roundToInt(); horizontalOffset = (horizontalOffset - movement).coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan()); followPlayback = false }
      return true
    }
    return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
  }

  override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
    // Fabric GUI ImGui renders after Minecraft's extracted GUI pass. Keep this Screen as a dark
    // capture surface and let ImGui own every visible editor widget.
    context.fill(0, 0, width, height, 0xFF10131A.toInt())
  }

  override fun render(io: ImGuiIO) {
    // In Minecraft 26.2 the visible editor is rendered through Fabric GUI ImGui's callback.
    // Advance the visual clock here so the playhead is refreshed every rendered frame.
    updateVisualPlayback()
    configureImGui(io)
    if (imguiAppliedStyle != settings.style) applyImGuiTheme()
    if (clearImGuiInputOnFirstFrame) {
      io.clearEventsQueue(); io.clearInputKeys(); io.clearInputMouse(); clearImGuiInputOnFirstFrame = false
    }
    updateImGuiScale(io)
    frameImeSeen = false
    frameImeAllowed = false
    ImGui.dockSpaceOverViewport()
    renderImGuiTransport(io)
    if (settings.showLibrary) renderImGuiLibrary(io)
    if (settings.showInspector) renderImGuiInspector(io)
    renderImGuiPianoRoll(io)
    if (settings.showAutomation) renderImGuiAutomationWindows(io)
    if (settingsOpen) renderImGuiSettings(io)
    updateImeCandidateRect()
    syncOsIme()
  }

  /**
   * Anchors the OS IME candidate window to the active IME-capable field.
   * ImGui coords are display pixels; TextInputManager takes GUI-scaled coords.
   */
  private fun updateImeCandidateRect() {
    if (!lastImeHasRect || !imeComposing) return
    val guiScale = runCatching { Minecraft.getInstance().window.guiScale }.getOrDefault(1).coerceAtLeast(1)
    val x1 = (lastImeX1 / guiScale).roundToInt().coerceAtLeast(0)
    val y1 = (lastImeY1 / guiScale).roundToInt().coerceAtLeast(0)
    val x2 = (lastImeX2 / guiScale).roundToInt().coerceAtLeast(x1 + 20)
    val y2 = (lastImeY2 / guiScale).roundToInt().coerceAtLeast(y1 + 12)
    runCatching { Minecraft.getInstance().textInputManager().setTextInputArea(x1, y1, x2, y2) }
  }

  private fun configureImGui(io: ImGuiIO) {
    if (imguiConfigured) return
    io.addConfigFlags(ImGuiConfigFlags.DockingEnable)
    io.setIniFilename(FabricLoader.getInstance().configDir.resolve("ommt-imgui-layout.ini").toString())
    ImGui.styleColorsDark()
    imguiConfigured = true
    applyImGuiTheme()
  }

  private fun applyImGuiTheme() {
    val style = ImGui.getStyle()
    val tokens = settings.style.normalized()
    fun setColor(slot: Int, color: Int, alphaMultiplier: Float = 1f) {
      val rgba = colorFloats(color)
      style.setColor(slot, rgba[0], rgba[1], rgba[2], (rgba[3] * alphaMultiplier).coerceIn(0f, 1f))
    }
    setColor(ImGuiCol.Text, tokens.textColor)
    setColor(ImGuiCol.TextDisabled, tokens.disabledTextColor)
    setColor(ImGuiCol.WindowBg, tokens.windowBackgroundColor)
    setColor(ImGuiCol.ChildBg, tokens.panelBackgroundColor)
    setColor(ImGuiCol.PopupBg, tokens.popupBackgroundColor)
    setColor(ImGuiCol.TitleBg, tokens.inactiveTitleBackgroundColor)
    setColor(ImGuiCol.TitleBgActive, tokens.titleBackgroundColor)
    setColor(ImGuiCol.TitleBgCollapsed, tokens.inactiveTitleBackgroundColor)
    setColor(ImGuiCol.CheckMark, tokens.checkMarkColor)
    setColor(ImGuiCol.Border, tokens.borderColor)
    setColor(ImGuiCol.Separator, tokens.borderColor)
    setColor(ImGuiCol.FrameBg, tokens.frameColor)
    setColor(ImGuiCol.FrameBgHovered, tokens.frameHoveredColor)
    setColor(ImGuiCol.FrameBgActive, tokens.headerColor)
    setColor(ImGuiCol.Header, tokens.headerColor, .72f)
    setColor(ImGuiCol.HeaderHovered, tokens.headerColor, .9f)
    setColor(ImGuiCol.HeaderActive, tokens.accentColor)
    setColor(ImGuiCol.Button, tokens.buttonColor)
    setColor(ImGuiCol.ButtonHovered, tokens.frameHoveredColor)
    setColor(ImGuiCol.ButtonActive, tokens.accentColor)
    setColor(ImGuiCol.Tab, tokens.dockedTabColor)
    setColor(ImGuiCol.TabHovered, tokens.dockedTabHoveredColor)
    setColor(ImGuiCol.TabSelected, tokens.dockedTabActiveColor)
    setColor(ImGuiCol.TabDimmed, tokens.dockedTabUnfocusedColor)
    setColor(ImGuiCol.TabSelectedOverline, tokens.dockedTabSelectedOverlineColor)
    setColor(ImGuiCol.TabDimmedSelected, tokens.dockedTabDimmedSelectedColor)
    setColor(ImGuiCol.TabDimmedSelectedOverline, tokens.dockedTabDimmedSelectedOverlineColor)
    setColor(ImGuiCol.ScrollbarBg, tokens.panelBackgroundColor)
    setColor(ImGuiCol.ScrollbarGrab, tokens.scrollbarGrabColor)
    setColor(ImGuiCol.ScrollbarGrabHovered, tokens.scrollbarGrabHoveredColor)
    setColor(ImGuiCol.ScrollbarGrabActive, tokens.scrollbarGrabActiveColor)
    setColor(ImGuiCol.ResizeGrip, tokens.accentColor, .30f)
    setColor(ImGuiCol.ResizeGripHovered, tokens.accentColor, .65f)
    setColor(ImGuiCol.ResizeGripActive, tokens.accentColor)
    style.setWindowPadding(tokens.windowPaddingX.toFloat(), tokens.windowPaddingY.toFloat())
    style.setFramePadding(tokens.framePaddingX.toFloat(), tokens.framePaddingY.toFloat())
    style.setItemSpacing(tokens.itemSpacingX.toFloat(), tokens.itemSpacingY.toFloat())
    val rounding = tokens.rounding.toFloat()
    style.setWindowRounding(rounding); style.setChildRounding(rounding); style.setPopupRounding(rounding)
    style.setFrameRounding(rounding); style.setTabRounding(rounding); style.setGrabRounding((rounding - 1f).coerceAtLeast(0f))
    style.setScrollbarRounding(rounding); style.setScrollbarSize(tokens.scrollbarSize.toFloat())
    val border = tokens.borderSize.toFloat()
    style.setWindowBorderSize(border); style.setChildBorderSize(border); style.setPopupBorderSize(border); style.setFrameBorderSize(border)
    style.setSeparatorSize(max(1f, border))
    imguiAppliedStyle = settings.style
  }

  private fun colorFloats(color: Int) = floatArrayOf(
    ((color ushr 16) and 255) / 255f,
    ((color ushr 8) and 255) / 255f,
    (color and 255) / 255f,
    ((color ushr 24) and 255) / 255f,
  )

  /** Per-window title-bar colors. Push before ImGui.begin, pop after the matching ImGui.end. */
  private fun pushHeaderColors(color: Int) {
    val c = colorFloats(color)
    ImGui.pushStyleColor(ImGuiCol.TitleBg, c[0], c[1], c[2], c[3])
    ImGui.pushStyleColor(ImGuiCol.TitleBgActive, c[0], c[1], c[2], c[3])
    ImGui.pushStyleColor(ImGuiCol.TitleBgCollapsed, c[0], c[1], c[2], c[3])
  }

  private fun colorFromFloats(rgba: FloatArray): Int {
    fun channel(index: Int) = (rgba[index].coerceIn(0f, 1f) * 255f).roundToInt()
    return (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
  }

  private fun drawColor(color: Int): Int = ImColor.rgba(
    (color ushr 16) and 255, (color ushr 8) and 255, color and 255, (color ushr 24) and 255,
  )

  private fun shadedDrawColor(color: Int, factor: Float): Int = ImColor.rgba(
    (((color ushr 16) and 255) * factor).roundToInt().coerceIn(0, 255),
    (((color ushr 8) and 255) * factor).roundToInt().coerceIn(0, 255),
    ((color and 255) * factor).roundToInt().coerceIn(0, 255),
    (color ushr 24) and 255,
  )

  /** UI scale the font atlas was rasterized for. Fonts render at display size while equal. */
  private var atlasRasterScale = 1f

  private fun updateImGuiScale(io: ImGuiIO) {
    val target = (settings.uiScalePercent / 100f).coerceIn(.75f, 1.5f)
    // Text size combines the UI scale with the independent text-size setting.
    val textTarget = target * (settings.textScalePercent / 100f).coerceIn(.75f, 1.5f)
    // Prefer rasterizing at display size over bilinear-upscaling a fixed atlas.
    if (abs(textTarget - atlasRasterScale) > .001f) {
      OyasaiImGuiService.requestRebuild(OyasaiImGuiService.BASE_SIZE_PX * textTarget)
    }
    // ImGuiStyle.scaleAllSizes() rounds several border metrics. Calling it repeatedly while a
    // slider is active can reduce Separator thickness to zero and trigger a native ImGui assert.
    // FontGlobalScale is frame-safe; docked windows themselves remain freely resizable.
    if (abs(textTarget - io.fontGlobalScale) > .001f) io.fontGlobalScale = textTarget
    imguiAppliedScale = textTarget
  }

  /** Runs pending font-atlas rebuilds on the client thread, outside the ImGui frame lock. */
  private fun pollFontRebuild() {
    val sizePx = OyasaiImGuiService.pollRebuild(ImGui.getIO()) ?: return
    atlasRasterScale = sizePx / OyasaiImGuiService.BASE_SIZE_PX
    // Atlas now matches display size: drop the upscale factor for crisp text.
    ImGui.getIO().fontGlobalScale = 1f
    imguiAppliedScale = 1f
  }

  private fun renderImGuiTransport(io: ImGuiIO) {
    ImGui.setNextWindowPos(0f, 0f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(io.displaySizeX, 112f, ImGuiCond.FirstUseEver)
    pushHeaderColors(settings.style.workspaceHeaderColor)
    if (ImGui.begin(windowTitle("OMMT - MIDI WORKSPACE", "OMMT - MIDIワークスペース", "OMMT  •  MIDI WORKSPACE"))) {
      ImGui.setNextItemWidth(280f); if (ImGui.inputText("${t("Song", "曲名")}###Song", imTitle)) { songTitle = imTitle.get().trim().take(120).ifBlank { "Untitled song" }; titleField.text = songTitle }; renderImePreeditOverlay()
      ImGui.sameLine(); ImGui.setNextItemWidth(140f); if (ImGui.inputInt("BPM###BPM", imBpm, 1, 10)) { setBaseBpm(imBpm.get()) }; markAsciiField()
      val selectedAction = when {
        selectedSave != null && selectedMidi == null && selectedNbs == null -> t("LOAD SAVE", "保存読込")
        selectedNbs != null && selectedMidi == null -> t("LOAD NBS", "NBS読込")
        else -> t("LOAD MIDI", "MIDI読込")
      }
      ImGui.sameLine(); if (ImGui.button("$selectedAction###LOAD_SELECTED")) {
        selectedSave?.takeIf { selectedMidi == null && selectedNbs == null }?.let(::loadProject) ?: loadSelectedSource()
      }
      ImGui.sameLine(); if (ImGui.button("${t("SETTINGS", "設定")}###SETTINGS")) settingsOpen = !settingsOpen
      ImGui.textDisabled(t("TRANSPORT", "再生")); ImGui.sameLine()
      if (ImGui.button("|◀###HOME")) rewind(); ImGui.sameLine()
      if (ImGui.button("${if (playing) "⏹" else "▶"}###PLAY")) togglePlayback(); ImGui.sameLine()
      if (ImGui.button("${t("FOLLOW", "追従")} ${if(followPlayback) "ON" else "OFF"}###FOLLOW")) followPlayback = !followPlayback; ImGui.sameLine()
      if (ImGui.button("${t("FIT", "全体表示")}###FIT")) { fitTimeline(); fitPitchRange() }; ImGui.sameLine()
      if (ImGui.button("-###ZOOM_OUT")) zoomTimelineCentered(1.25); ImGui.sameLine()
      if (ImGui.button("+###ZOOM_IN")) zoomTimelineCentered(.8); ImGui.sameLine()
      if (ImGui.button("${t("SNAP", "スナップ")} ${if(snapDivisor==0) "OFF" else "1/$snapDivisor"}###SNAP")) cycleSnap()
      ImGui.sameLine(); if (ImGui.button("${t("MAX VOL", "音量正規化")}###NORMALIZE_VELOCITY")) normalizeVelocities()
      ImGui.sameLine(); if (ImGui.button("${t("SAVE PROJECT", "編集を保存")}###SAVE_PROJECT")) saveProject()
      ImGui.sameLine(); if (ImGui.button("${t("UPLOAD DRAFT", "下書き送信")}###UPLOAD")) exportAndUpload()
      ImGui.sameLine(); ImGui.text("${formatTime(visualPlayheadMs.roundToInt())} / ${formatTime(durationMs())}")
      if (UploadClient.isVisible()) {
        val progress = UploadClient.progress()
        ImGui.textColored(.73f, .91f, .41f, 1f, localizedUploadStatus(UploadClient.status()))
        ImGui.sameLine(); ImGui.progressBar(progress.percent / 100f, 240f, 0f, "${localizedProgressPhase(progress.phase)} ${progress.percent}%")
      }
      ImGui.sameLine(); ImGui.textDisabled(state)
    }
    ImGui.end()
    ImGui.popStyleColor(3)
  }

  private fun renderImGuiLibrary(io: ImGuiIO) {
    ImGui.setNextWindowPos(0f, 118f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(max(220f, io.displaySizeX * .16f), max(260f, io.displaySizeY - 118f), ImGuiCond.FirstUseEver)
    pushHeaderColors(settings.style.libraryHeaderColor)
    if (ImGui.begin(windowTitle("OMMT LIBRARY", "OMMTライブラリ", "MIDI LIBRARY"))) {
      if (ImGui.beginTabBar("##OMMT_LIBRARY_TABS")) {
        if (ImGui.beginTabItem("MIDI###MIDI_FILES")) {
          ImGui.textDisabled("OMMT/midi | ${midiFiles.size} ${t("files", "ファイル")}")
          if (ImGui.button("${t("REFRESH", "更新")}###REFRESH_MIDI")) refreshLibraries(); ImGui.sameLine()
          if (ImGui.button("${t("OPEN FOLDER", "フォルダーを開く")}###OPEN_MIDI_FOLDER")) openMidiFolder()
          ImGui.spacing()
          midiFiles.forEach { path ->
            val chosen = path == selectedMidi
            if (ImGui.selectable(path.fileName.toString(), chosen)) { selectedMidi = path; selectedNbs = null; state = t("Selected ${path.fileName}", "${path.fileName}を選択しました") }
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) { selectedMidi = path; selectedNbs = null; loadMidi() }
          }
          if (midiFiles.isEmpty()) ImGui.textDisabled(t("Place .mid or .midi files in OMMT/midi", "OMMT/midiに.midまたは.midiを入れてください"))
          ImGui.endTabItem()
        }
        if (ImGui.beginTabItem("NBS###NBS_FILES")) {
          ImGui.textDisabled("OMMT/nbs | ${nbsFiles.size} ${t("files", "ファイル")}")
          if (ImGui.button("${t("REFRESH", "更新")}###REFRESH_NBS")) refreshLibraries(); ImGui.sameLine()
          if (ImGui.button("${t("OPEN FOLDER", "フォルダーを開く")}###OPEN_NBS_FOLDER")) openNbsFolder()
          ImGui.spacing()
          nbsFiles.forEach { path ->
            val chosen = path == selectedNbs
            if (ImGui.selectable(path.fileName.toString(), chosen)) { selectedNbs = path; selectedMidi = null; state = t("Selected ${path.fileName}", "${path.fileName}を選択しました") }
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) { selectedNbs = path; selectedMidi = null; loadNbs() }
          }
          if (nbsFiles.isEmpty()) ImGui.textDisabled(t("Place .nbs files in OMMT/nbs", "OMMT/nbsに.nbsを入れてください"))
          selectedNbs?.let { if (ImGui.button("${t("LOAD SELECTED NBS", "選択したNBSを開く")}###LOAD_NBS")) loadNbs() }
          ImGui.endTabItem()
        }
        if (ImGui.beginTabItem("${t("SAVE", "保存")}###PROJECT_SAVES")) {
          ImGui.textDisabled("OMMT/saves | ${saveFiles.size} ${t("projects", "ファイル")}")
          if (ImGui.button("${t("REFRESH", "更新")}###REFRESH_SAVES")) refreshLibraries(); ImGui.sameLine()
          if (ImGui.button("${t("OPEN FOLDER", "フォルダーを開く")}###OPEN_SAVE_FOLDER")) openSaveFolder()
          ImGui.spacing()
          saveFiles.forEach { path ->
            val chosen = path == selectedSave
            if (ImGui.selectable(path.fileName.toString(), chosen)) { selectedSave = path; selectedMidi = null; selectedNbs = null; state = t("Selected ${path.fileName}", "${path.fileName}を選択しました") }
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) loadProject(path)
          }
          if (saveFiles.isEmpty()) ImGui.textDisabled(t("Saved editing projects appear here", "保存した編集データがここに表示されます"))
          selectedSave?.let { if (ImGui.button("${t("LOAD SELECTED PROJECT", "選択した編集データを開く")}###LOAD_PROJECT")) loadProject(it) }
          ImGui.endTabItem()
        }
        ImGui.endTabBar()
      }
    }
    ImGui.end()
    ImGui.popStyleColor(3)
  }

  private fun renderImGuiInspector(io: ImGuiIO) {
    ImGui.setNextWindowPos(max(0f, io.displaySizeX - 330f), 118f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(350f, 520f, ImGuiCond.FirstUseEver)
    pushHeaderColors(settings.style.inspectorHeaderColor)
    if (ImGui.begin(windowTitle("NOTE INSPECTOR", "ノートインスペクター"))) {
      val picked = selectedNotes()
      ImGui.text("${picked.size} ${t("selected", "音選択中")}${if (picked.size > 1) t(" | relative edit from primary values", " | 代表音から相対編集") else ""}")
      ImGui.text(t("Time (ms)", "開始 (ms)")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f); ImGui.inputInt("##Time", imTime); if (confirmPressed()) applySelected(); markAsciiField()
      ImGui.text(t("Length", "長さ")); ImGui.sameLine(); ImGui.setNextItemWidth(118f)
      ImGui.combo("##LengthUnit", imDurationUnit, releaseUnitLabels(durationDivisors))
      if (imDurationUnit.get() == 0) {
        ImGui.sameLine(); ImGui.setNextItemWidth(-1f); ImGui.inputInt("##Length", imDuration); if (confirmPressed()) applySelected(); markAsciiField()
      }
      ImGui.text(t("Instrument", "楽器")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f)
      if (ImGui.combo("##Instrument", imInstrument, NoteBlockInstruments.labels(japanese)) && imInstrument.get() != NoteBlockInstruments.OTHER_INDEX) {
        imCustomSound.set("")
        imCustomSoundPattern.set(1)
      }
      if (imInstrument.get() == NoteBlockInstruments.OTHER_INDEX) {
        ImGui.text(t("Sound search", "サウンド検索")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f)
        // Sound IDs are lowercase ASCII (minecraft:...); drop CJK at input so search never breaks.
        if (ImGui.inputText("##CustomSoundSearch", imCustomSound, ImGuiInputTextFlags.CallbackCharFilter, asciiCharFilter)) Unit; if (confirmPressed()) applySelected(); markAsciiField()
        val search = imCustomSound.get().trim().lowercase()
        val matches = supportedCustomSounds.asSequence().filter { search.isBlank() || it.id.contains(search) }.take(200).toList()
        ImGui.text(t("Minecraft sound", "Minecraftサウンド")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f)
        val selectedDefinition = supportedCustomSound(search)
        val preview = selectedDefinition?.id ?: t("Choose from ${supportedCustomSounds.size} server-compatible sounds", "サーバー互換の${supportedCustomSounds.size}音から選択")
        if (ImGui.beginCombo("##CustomSoundPicker", preview)) {
          matches.forEach { definition ->
            if (ImGui.selectable("${definition.id} (${definition.patterns})", definition.id == search)) {
              imCustomSound.set(definition.id)
              imCustomSoundPattern.set(1)
            }
          }
          if (matches.isEmpty()) ImGui.textDisabled(t("No matching sound", "一致するサウンドはありません"))
          ImGui.endCombo()
        }
        selectedDefinition?.let { definition ->
          imCustomSoundPattern.set(imCustomSoundPattern.get().coerceIn(1, definition.patterns))
          ImGui.text(t("Fixed pattern", "固定パターン")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f)
          val patternIndex = ImInt(imCustomSoundPattern.get() - 1)
          if (ImGui.combo(
                  "##CustomSoundPattern",
                  patternIndex,
                  Array(definition.patterns) { index -> "${index + 1} / ${definition.patterns}" },
              )) imCustomSoundPattern.set(patternIndex.get() + 1)
          ImGui.textDisabled(t("The server converts this pattern to a deterministic playback seed", "サーバー側でこのパターンを固定再生seedへ変換します"))
        }
        ImGui.textDisabled(t("Minecraft 26.2 trumpet sounds are available", "Minecraft 26.2のラッパ音源を利用できます"))
      }
      ImGui.text(t("Source pitch", "元の音高"))
      val pitchButtonWidth = max(36f, ImGui.calcTextSizeX("+12") + settings.style.framePaddingX * 2f + 4f)
      val pitchGap = settings.style.itemSpacingX.toFloat()
      val pitchValueWidth = (ImGui.getContentRegionAvailX() - pitchButtonWidth * 4f - pitchGap * 4f - 2f).coerceAtLeast(48f)
      ImGui.setNextItemWidth(pitchValueWidth); ImGui.inputInt("##Pitch", imPitch, 0, 0); if (confirmPressed()) applySelected(); markAsciiField()
      ImGui.sameLine(); if (ImGui.button("-12##PITCH_MINUS_12", pitchButtonWidth, 0f)) adjustSelectedPitch(-12)
      ImGui.sameLine(); if (ImGui.button("-1##PITCH_MINUS_1", pitchButtonWidth, 0f)) adjustSelectedPitch(-1)
      ImGui.sameLine(); if (ImGui.button("+1##PITCH_PLUS_1", pitchButtonWidth, 0f)) adjustSelectedPitch(1)
      ImGui.sameLine(); if (ImGui.button("+12##PITCH_PLUS_12", pitchButtonWidth, 0f)) adjustSelectedPitch(12)
      ImGui.text(t("Volume", "音量")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f); ImGui.inputInt("##Volume", imVolume); if (confirmPressed()) applySelected(); markAsciiField()
      ImGui.text(t("Pan", "定位")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f); ImGui.inputInt("##Pan", imPan); if (confirmPressed()) applySelected(); markAsciiField()
      ImGui.text(t("Detune (cents)", "デチューン（セント）")); ImGui.sameLine()
      val centsButtonWidth = max(36f, ImGui.calcTextSizeX("+") + settings.style.framePaddingX * 2f + 4f)
      val centsValueWidth = (ImGui.getContentRegionAvailX() - centsButtonWidth * 2f - settings.style.itemSpacingX * 2f - 2f).coerceAtLeast(48f)
      ImGui.setNextItemWidth(centsValueWidth)
      ImGui.inputInt("##PitchCents", imPitchCents, 0, 0); if (confirmPressed()) applySelected(); markAsciiField()
      ImGui.sameLine(); if (ImGui.button("-##PITCHCENTS_MINUS", centsButtonWidth, 0f)) adjustSelectedPitchCents(-10)
      ImGui.sameLine(); if (ImGui.button("+##PITCHCENTS_PLUS", centsButtonWidth, 0f)) adjustSelectedPitchCents(10)
      ImGui.textDisabled(t("Detune offsets from the placed pitch; absolute -5400..7300 cents apply", "配置音からの差分です。絶対-5400..7300セントの範囲で有効です"))
      if (ImGui.button("${t("APPLY", "適用")}###APPLY")) applySelected(); ImGui.sameLine()
      if (ImGui.button("${t("DELETE", "削除")}###DELETE")) deleteSelected()
      if (ImGui.button("${t("NEW PART FROM SELECTION", "選択音からパート作成")}###NEW_PART")) createPartFromSelection()
      if (ImGui.button("${t("MOVE TO EXISTING PART", "既存パートへ移動")}###MOVE_EXISTING")) moveSelectionToPart(imMovePart.get())
      ImGui.text(t("Move selected to", "選択音の移動先")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f)
      ImGui.combo("##MOVE_EXISTING_PART", imMovePart, parts.toTypedArray())
      ImGui.separator()
      ImGui.text(t("Active part name", "現在のパート名")); ImGui.sameLine(); ImGui.setNextItemWidth(-1f); ImGui.inputText("##PART_NAME", imPartName); renderImePreeditOverlay()
      if (ImGui.button("${t("RENAME ACTIVE PART", "パート名を変更")}###RENAME_PART")) renameActivePart()
      ImGui.spacing()
      if (nbsPartHierarchy.isNotEmpty()) {
        ImGui.textDisabled(t("NBS instruments and layers", "NBSの楽器とレイヤー"))
        nbsPartHierarchy.forEach { group ->
          if (ImGui.collapsingHeader("${group.label} (${group.leaves.size})###NBS_GROUP_${group.sourceInstrument}")) {
            group.leaves.forEach { leaf ->
              val index = leaf.partIndex
              val partColorRgba = partColorFloats(index)
              ImGui.textColored(partColorRgba[0], partColorRgba[1], partColorRgba[2], 1f, "#")
              ImGui.sameLine()
              val layerLabel = "${t("Layer", "レイヤー")} ${leaf.sourceLayer + 1}: ${parts.getOrElse(index) { "" }}"
              if (ImGui.selectable("$layerLabel###NBS_PART_$index", !allPartsView && index == activePart)) {
                activePart=index; partStripPage=index/PART_TABS_PER_PAGE; allPartsView=false; syncPartControls()
              }
            }
          }
        }
      } else {
        val inspectorPageCount = ((parts.size + PART_TABS_PER_PAGE - 1) / PART_TABS_PER_PAGE).coerceAtLeast(1)
        partStripPage = partStripPage.coerceIn(0, inspectorPageCount - 1)
        if (ImGui.smallButton("<###INSPECTOR_PART_PREV") && partStripPage > 0) partStripPage--
        ImGui.sameLine(); ImGui.textDisabled("${partStripPage + 1} / $inspectorPageCount")
        ImGui.sameLine(); if (ImGui.smallButton(">###INSPECTOR_PART_NEXT") && partStripPage + 1 < inspectorPageCount) partStripPage++
        val inspectorStart = partStripPage * PART_TABS_PER_PAGE
        val inspectorEnd = min(parts.size, inspectorStart + PART_TABS_PER_PAGE)
        for (index in inspectorStart until inspectorEnd) {
          val name = parts[index]
          val partColorRgba = partColorFloats(index)
          ImGui.textColored(partColorRgba[0], partColorRgba[1], partColorRgba[2], 1f, "#")
          ImGui.sameLine()
          if (ImGui.selectable("${t("Part", "パート")} ${index + 1}: $name", !allPartsView && index == activePart)) { activePart=index; partStripPage=index/PART_TABS_PER_PAGE; allPartsView=false; syncPartControls() }
        }
      }
    }
    ImGui.end()
    ImGui.popStyleColor(3)
  }

  private fun wheelAction(io: ImGuiIO) = when {
    io.keyCtrl -> settings.wheelControl
    io.keyShift -> settings.wheelShift
    io.keyAlt -> settings.wheelAlt
    else -> settings.wheelPlain
  }
  private fun modifierActive(modifier: GestureModifier, io: ImGuiIO) = when(modifier) {
    GestureModifier.NONE -> !io.keyCtrl && !io.keyShift && !io.keyAlt
    GestureModifier.SHIFT -> io.keyShift
    GestureModifier.CONTROL -> io.keyCtrl
    GestureModifier.ALT -> io.keyAlt
  }
  private fun panMouseButton() = if(settings.panMouseButton==PanMouseButton.MIDDLE) ImGuiMouseButton.Middle else ImGuiMouseButton.Right
  private fun localizedProgressPhase(phase:String)=if(!japanese) phase else when(phase){"CHECKING"->"確認中";"PREPARING"->"準備中";"UPLOADING"->"送信中";"VERIFYING"->"検証中";"IMPORTING"->"登録中";"DONE"->"完了";"ERROR"->"エラー";else->phase}
  private fun localizedUploadStatus(value: String): String {
    if (!japanese) return value
    return when {
      value == "OyasaiMusic upload not checked" -> "OyasaiMusicへの送信は未確認です"
      value == "Checking OyasaiMusic upload..." -> "OyasaiMusicへの送信可否を確認中..."
      value == "OyasaiMusic upload is unavailable" -> "このサーバーではOyasaiMusicへ送信できません"
      value == "OyasaiMusic upload ready" -> "OyasaiMusicへ送信できます"
      value == "Waiting for OyasaiMusic upload capability..." -> "OyasaiMusicの送信機能を確認中..."
      value == "Importing on OyasaiMusic..." -> "OyasaiMusicへ登録中..."
      value == "Retrying legacy OyasaiMusic upload..." -> "旧方式で送信を再試行中..."
      value.startsWith("Uploading ") -> value.replace("Uploading ", "送信中: ").replace(" command chunks...", "分割...")
      value.startsWith("Imported as private draft #") -> value.replace("Imported as private draft #", "非公開の下書きとして登録しました #")
      value.startsWith("Upload failed:") -> value.replace("Upload failed:", "送信に失敗しました:")
      value.startsWith("Upload cancelled:") -> value.replace("Upload cancelled:", "送信を中止しました:")
      value.startsWith("Upload rejected:") -> value.replace("Upload rejected:", "送信を拒否しました:")
      value == "OYMI must be at most 1 MiB" -> "送信データは1 MiB以下にしてください"
      value == "Compressed OYMI exceeds 60,000 bytes" -> "圧縮後のデータが60,000バイトを超えています"
      value == "Encoded OYMI exceeds 80,000 characters" -> "符号化後のデータが80,000文字を超えています"
      value.contains("too many") -> "送信の分割数が上限を超えています"
      value.contains("transport limits") -> "送信コマンドが通信上限を超えています"
      else -> value
    }
  }

  private fun renderImGuiPianoRoll(io: ImGuiIO) {
    val defaultLeft = if (settings.showLibrary) max(220f, io.displaySizeX * .16f) else 0f
    val defaultRight = if (settings.showInspector) 350f else 0f
    val defaultBottom = if (settings.showAutomation) (io.displaySizeY * .30f).coerceIn(240f, 420f) else 0f
    ImGui.setNextWindowPos(defaultLeft, 118f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(max(420f, io.displaySizeX - defaultLeft - defaultRight), max(260f, io.displaySizeY - 118f - defaultBottom), ImGuiCond.FirstUseEver)
    if (!ImGui.begin(windowTitle("PIANO ROLL", "ピアノロール"))) { ImGui.end(); return }

    // Keep the potentially very wide part list inside its own scrolling region. If these
    // buttons contribute to the piano-roll window's content width, ImGui treats Shift+wheel as
    // horizontal scrolling of the whole editor and moves the canvas itself off-screen.
    // One button row plus a dedicated 6px+padding scrollbar; neither dimension is allowed to
    // contribute to the piano-roll canvas window's own horizontal content extent.
    val partStyle = ImGui.getStyle()
    val partStripHeight = ImGui.getFrameHeight() + partStyle.windowPaddingY * 2f + partStyle.scrollbarSize + 4f
    ImGui.setNextWindowContentSize(0f, ImGui.getFrameHeight())
    if (ImGui.beginChild("##ommt-part-strip", 0f, partStripHeight, true, ImGuiWindowFlags.HorizontalScrollbar or ImGuiWindowFlags.AlwaysHorizontalScrollbar)) {
      val partCounts = IntArray(parts.size)
      notes.forEach { note -> if (note.part in partCounts.indices) partCounts[note.part]++ }
      val pageCount = ((parts.size + PART_TABS_PER_PAGE - 1) / PART_TABS_PER_PAGE).coerceAtLeast(1)
      partStripPage = partStripPage.coerceIn(0, pageCount - 1)
      if(ImGui.button("${t("ALL", "全体")} (${notes.size})###ALL_PARTS")) allPartsView=true
      ImGui.sameLine(); if (ImGui.smallButton("<###PART_PAGE_PREV") && partStripPage > 0) partStripPage--
      ImGui.sameLine(); ImGui.textDisabled("${partStripPage + 1}/$pageCount")
      ImGui.sameLine(); if (ImGui.smallButton(">###PART_PAGE_NEXT") && partStripPage + 1 < pageCount) partStripPage++
      val partStart = partStripPage * PART_TABS_PER_PAGE
      val partEnd = min(parts.size, partStart + PART_TABS_PER_PAGE)
      for (index in partStart until partEnd) {
        val name = parts[index]
        ImGui.sameLine()
        val partColorRgba = partColorFloats(index)
        val isActivePart = !allPartsView && index == activePart
        ImGui.pushStyleColor(ImGuiCol.Button, partColorRgba[0], partColorRgba[1], partColorRgba[2], if (isActivePart) 0.85f else 0.30f)
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, partColorRgba[0], partColorRgba[1], partColorRgba[2], 0.6f)
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, partColorRgba[0], partColorRgba[1], partColorRgba[2], 0.9f)
        if (ImGui.button("${index+1}: $name (${partCounts[index]})##part$index")) { activePart=index; allPartsView=false; syncPartControls() }
        ImGui.popStyleColor(3)
      }
    }
    ImGui.endChild()
    ImGui.textDisabled("${if(allPartsView)t("All-parts overview", "全パート表示") else t("Editing ${parts.getOrElse(activePart){"Part"}}", "${parts.getOrElse(activePart){"パート"}}を編集中")} | ${selectedIds.size} ${t("selected", "選択")} | ${t("Gestures are configurable in Settings > Keymap", "操作は設定 > キーマップで変更できます")}")
    val canvasX = ImGui.getCursorScreenPosX(); val canvasY = ImGui.getCursorScreenPosY()
    val canvasWidth = ImGui.getContentRegionAvailX().coerceAtLeast(240f)
    val canvasHeight = ImGui.getContentRegionAvailY().coerceAtLeast(170f)
    ImGui.invisibleButton("##ommt-piano-canvas", canvasWidth, canvasHeight)
    val hovered = ImGui.isItemHovered()
    val draw = ImGui.getWindowDrawList()
    val keyboardWidth = TIMELINE_AXIS_WIDTH; val rulerHeight = 28f; val scrollbar = TIMELINE_SCROLLBAR_WIDTH
    val plotLeft = canvasX + keyboardWidth; val plotRight = canvasX + canvasWidth - scrollbar
    val notesTop = canvasY + rulerHeight; val notesBottom = canvasY + canvasHeight - scrollbar
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f); val noteHeight = (notesBottom - notesTop).coerceAtLeast(1f)
    clampPitchViewport()
    val rowHeight = noteHeight / visiblePitchCount.coerceAtLeast(1)
    fun xAt(time: Int) = plotLeft + (time - horizontalOffset).toFloat() / visibleSpan() * plotWidth
    fun timeAt(x: Float) = (horizontalOffset + ((x - plotLeft) / plotWidth).coerceIn(0f, 1f) * visibleSpan()).roundToInt()
    fun yAt(pitch: Int) = notesBottom - (pitch - pitchMin + 1) * rowHeight
    fun pitchAtCanvas(y: Float) = (pitchMin + ((notesBottom - y) / noteHeight * visiblePitchCount).toInt()).coerceIn(pitchDomainMin(), pitchDomainMax())
    val visibleTimelineNotes = indexedNotesInVisibleTimeline()
    // At a fit-to-song zoom, tens of thousands of starts may resolve to the same screen pixel.
    // Drawing/hit-testing all of them cannot reveal more information, so keep one representative
    // per pitch and adaptive horizontal bucket. Marquee selection still uses the full visible set.
    val interactiveTimelineNotes = if (visibleTimelineNotes.size <= MAX_VISIBLE_NOTE_COMMANDS) visibleTimelineNotes else {
      val bucketWidth = ceil(visibleTimelineNotes.size.toDouble() / MAX_VISIBLE_NOTE_COMMANDS).toFloat().coerceAtLeast(1f)
      val representatives = LinkedHashMap<Long, IndexedValue<EditorNote>>(MAX_VISIBLE_NOTE_COMMANDS)
      visibleTimelineNotes.forEach { indexed ->
        val note = indexed.value
        val bucket = ((xAt(note.time) - plotLeft) / bucketWidth).roundToInt()
        val key = ((note.pitch - NoteBlockPitch.DISPLAY_MIN).toLong() shl 32) or (bucket.toLong() and 0xffffffffL)
        val previous = representatives[key]
        if (previous == null || note.id in selectedIds || note.duration > previous.value.duration) representatives[key] = indexed
      }
      val collapsed = representatives.values.toList()
      if (collapsed.size <= MAX_VISIBLE_NOTE_COMMANDS) collapsed else {
        val selectedRepresentatives = collapsed.filter { it.value.id in selectedIds }
        selectedRepresentatives + collapsed.asSequence().filter { it.value.id !in selectedIds }
            .take((MAX_VISIBLE_NOTE_COMMANDS - selectedRepresentatives.size).coerceAtLeast(0)).toList()
      }
    }

    // Mouse position is hoisted: drag continuations below run outside the hover gate so
    // leaving the window keeps operating instead of freezing mid-gesture.
    val mouseX = io.mousePosX; val mouseY = io.mousePosY
    if (hovered) {
      val wheel = if (io.mouseWheelH != 0f) io.mouseWheelH else io.mouseWheel
      if (wheel != 0f) {
        when (wheelAction(io)) {
          WheelAction.TIME_ZOOM -> {
            val oldSpan = visibleSpan(); val fraction = ((mouseX - plotLeft) / plotWidth).coerceIn(0f, 1f)
            val anchor = horizontalOffset + (oldSpan * fraction).roundToInt()
            viewSpanMs = (oldSpan * if (wheel > 0) .8 else 1.25).roundToInt().coerceIn(2_000, (durationMs() + 10_000).coerceAtLeast(30_000))
            horizontalOffset = (anchor - (visibleSpan() * fraction).roundToInt()).coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan())
          }
          WheelAction.PITCH_ZOOM -> {
            val anchorPitch = pitchAtCanvas(mouseY); val fraction = ((notesBottom - mouseY) / noteHeight).coerceIn(0f, 1f)
            visiblePitchCount = (visiblePitchCount + if (wheel > 0) -2 else 2).coerceIn(5, pitchDomainSize())
            pitchMin = (anchorPitch - (visiblePitchCount * fraction).roundToInt()).coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1)
          }
          // Match ordinary viewport scrolling: the wheel moves the visible content, not the
          // abstract pitch value. This direction was verified against the user's in-game report.
          WheelAction.PITCH_SCROLL -> { pitchMin = (pitchMin + wheel.roundToInt()).coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1) }
          WheelAction.TIMELINE_SCROLL -> {
            val movement = (wheel * visibleSpan() * .08f).roundToInt()
            horizontalOffset = (horizontalOffset - movement).coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan())
          }
          WheelAction.NONE -> Unit
        }
        followPlayback = false
      }

      val visibleRange = pitchMin until pitchMin + visiblePitchCount
      val edgeHit = if(mouseX in plotLeft..plotRight && mouseY in notesTop..notesBottom) interactiveTimelineNotes.filter { indexed ->
        val note=indexed.value
        (allPartsView||note.part==activePart)&&note.pitch in visibleRange&&abs((yAt(note.pitch)+rowHeight/2f)-mouseY)<=rowHeight/2f+3f
      }.minByOrNull { indexed -> abs(max(xAt(indexed.value.time + indexed.value.duration),xAt(indexed.value.time)+6f)-mouseX) }?.takeIf { indexed ->
        abs(max(xAt(indexed.value.time + indexed.value.duration),xAt(indexed.value.time)+6f)-mouseX)<=7f
      } else null
      if(edgeHit!=null)ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)

      val noteHit = if(mouseX in plotLeft..plotRight && mouseY in notesTop..notesBottom) edgeHit ?: interactiveTimelineNotes.filter { indexed ->
        val note = indexed.value
        (allPartsView || note.part == activePart) && note.pitch in visibleRange && mouseX >= xAt(note.time) - 3f && mouseX <= max(xAt(note.time + note.duration), xAt(note.time) + 6f) + 3f && abs((yAt(note.pitch) + rowHeight / 2f) - mouseY) <= rowHeight / 2f + 3f
      }.minByOrNull { abs(xAt(it.value.time) - mouseX) } else null
      // Ctrl+left adds only on empty roll space. Clicking an existing note retains the normal
      // selection/additive-selection behavior, so creation can never hide an accidental overlap.
      val ctrlLeftAdd = io.keyCtrl && ImGui.isMouseClicked(ImGuiMouseButton.Left) && noteHit == null && mouseX in plotLeft..plotRight && mouseY in notesTop..notesBottom
      if(ctrlLeftAdd) addNoteAt(timeAt(mouseX),pitchAtCanvas(mouseY))
      val panButton=panMouseButton()
      if (ImGui.isMouseClicked(panButton)) {
        imguiRightPanning = true; imguiPanStartX = mouseX; imguiPanStartOffset = horizontalOffset
      }
      if (imguiRightPanning && ImGui.isMouseDown(panButton)) {
        horizontalOffset = (imguiPanStartOffset + ((imguiPanStartX - mouseX) / plotWidth * visibleSpan()).roundToInt())
          .coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan())
        followPlayback = false
      }
      if (ImGui.isMouseReleased(panButton)) imguiRightPanning = false

      if (ImGui.isMouseClicked(ImGuiMouseButton.Left) && !ctrlLeftAdd) {
        when {
          mouseX >= plotRight && mouseY in notesTop..notesBottom -> {
            verticalScrollbar = true
            val fraction = ((mouseY - notesTop) / noteHeight).coerceIn(0f, 1f)
            pitchMin = (pitchDomainMin() + ((pitchDomainSize() - visiblePitchCount) * fraction).roundToInt())
              .coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1)
          }
          mouseY >= notesBottom && mouseX in plotLeft..plotRight -> {
            horizontalScrollbar = true
            val fraction = ((mouseX - plotLeft) / plotWidth).coerceIn(0f, 1f)
            val maximum = durationMs().coerceAtLeast(visibleSpan()) - visibleSpan()
            horizontalOffset = (maximum * fraction).roundToInt().coerceAtLeast(0)
          }
          mouseY in canvasY..notesTop && mouseX in plotLeft..plotRight -> { seek(timeAt(mouseX)); followPlayback = false }
          mouseX in canvasX..plotLeft && mouseY in notesTop..notesBottom -> previewNote(EditorNote(0, 200, notes.getOrNull(selected)?.instrument ?: 0, pitchAtCanvas(mouseY), 100, 0))
          mouseX in plotLeft..plotRight && mouseY in notesTop..notesBottom -> {
            val hit = noteHit
            val rangeModifier=modifierActive(settings.rangeSelectionModifier,io)
            if (rangeModifier) {
              selectionStart = snap(timeAt(mouseX)) to pitchAtCanvas(mouseY); selectionEnd = selectionStart
            } else if(hit != null) {
              if (modifierActive(settings.additiveSelectionModifier,io)) selectedIds += hit.value.id else if (hit.value.id !in selectedIds) { selectedIds.clear(); selectedIds += hit.value.id }
              selected = hit.index; syncImGuiInspector(); noteDragStartX=mouseX; noteDragStartY=mouseY
              val noteEnd=max(xAt(hit.value.time+hit.value.duration),xAt(hit.value.time)+6f)
              if(abs(noteEnd-mouseX)<=7f){
                noteResizeArmed=true;resizeNoteId=hit.value.id;resizeStartX=mouseX
              }else{
                noteDragArmed = true;dragMouseTime = timeAt(mouseX); dragMousePitch = pitchAtCanvas(mouseY); dragOriginTime = hit.value.time; dragOriginPitch = hit.value.pitch
                dragBase = selectedNotes().associate { it.id to (it.time to it.pitch) }
                dragBaseCents = selectedNotes().associate { it.id to it.pitchCents }
              }
            } else {
              selectedIds.clear(); syncImGuiInspector()
            }
          }
        }
      }

    }

      // Drag continuations intentionally run outside the hover gate above: the cursor may
      // leave the window mid-gesture while the operation keeps tracking it.
      if (verticalScrollbar && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        val fraction = ((mouseY - notesTop) / noteHeight).coerceIn(0f, 1f)
        pitchMin = (pitchDomainMin() + ((pitchDomainSize() - visiblePitchCount) * fraction).roundToInt()).coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1)
      } else if (horizontalScrollbar && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        val maximum = durationMs().coerceAtLeast(visibleSpan()) - visibleSpan()
        horizontalOffset = (maximum * ((mouseX - plotLeft) / plotWidth).coerceIn(0f, 1f)).roundToInt().coerceAtLeast(0)
      } else if (selectionStart != null && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        selectionEnd = snap(timeAt(mouseX)) to pitchAtCanvas(mouseY)
      } else if (noteResizeArmed && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        if(abs(mouseX-resizeStartX)>=4f){rememberHistory();noteResizeArmed=false;resizingNote=true}
      } else if (resizingNote && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        resizeNoteId?.let{id->notes.firstOrNull{it.id==id}?.let{note->
          val snappedEnd=snap(timeAt(mouseX)).coerceAtLeast(note.time+1)
          note.duration=(snappedEnd-note.time).coerceIn(1,60_000)
          imDuration.set(note.duration)
        }}
      } else if (noteDragArmed && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        val distance=kotlin.math.sqrt((mouseX-noteDragStartX)*(mouseX-noteDragStartX)+(mouseY-noteDragStartY)*(mouseY-noteDragStartY))
        if(distance>=4f){ rememberHistory(); noteDragArmed=false; draggingNotes=true }
      } else if (draggingNotes && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        val intendedPrimary = dragOriginTime + timeAt(mouseX) - dragMouseTime
        val deltaTime = snap(intendedPrimary) - dragOriginTime
        val deltaPitch = (pitchAtCanvas(mouseY) - dragMousePitch).coerceIn(
          NoteBlockPitch.DISPLAY_MIN - (dragBase.values.minOfOrNull { it.second } ?: NoteBlockPitch.DISPLAY_MIN),
          NoteBlockPitch.DISPLAY_MAX - (dragBase.values.maxOfOrNull { it.second } ?: NoteBlockPitch.DISPLAY_MAX),
        )
        val minTime = dragBase.values.minOfOrNull { it.first } ?: 0
        selectedNotes().forEach { note -> dragBase[note.id]?.let { base -> note.time = base.first + deltaTime.coerceAtLeast(-minTime); note.pitch = base.second + deltaPitch; note.pitchCents = (dragBaseCents[note.id] ?: base.second * 100) + deltaPitch * 100 } }
      }

      // Edge auto-scroll while any piano-roll drag is active. The selection marquee
      // uses coerced mappers, so its data rect is inherently window-clamped.
      if ((draggingNotes || resizingNote || selectionStart != null || horizontalScrollbar || verticalScrollbar) && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        edgeScrollDelta(io, plotLeft, notesTop, plotRight, notesBottom)?.let { (dxPx, dyPx) ->
          if (dxPx != 0f) {
            val maximum = (durationMs().coerceAtLeast(visibleSpan()) - visibleSpan()).coerceAtLeast(0)
            horizontalOffset = (horizontalOffset + dxPx / plotWidth * visibleSpan()).roundToInt().coerceIn(0, maximum)
            followPlayback = false
          }
          if (dyPx != 0f) {
            pitchMin = (pitchMin - dyPx / rowHeight).roundToInt().coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1)
          }
        }
      }

      if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
        selectionStart?.let { start -> selectionEnd?.let { end ->
          if (!modifierActive(settings.additiveSelectionModifier,io)) selectedIds.clear()
          // Full-data scan: notes scrolled out of view by edge auto-scroll must stay selected.
          notes.asSequence().filter { (allPartsView || it.part == activePart) && EditorSelection.intersectsMarquee(it.time, it.duration, it.pitch, start.first, end.first, start.second, end.second) }.forEach { selectedIds += it.id }
          selectedIds.firstOrNull()?.let { id -> selected = notes.indexOfFirst { it.id == id }.coerceAtLeast(0); syncImGuiInspector() }
          state = t("Selected ${selectedIds.size} notes", "${selectedIds.size}音を選択しました")
        } }
        if (draggingNotes) {
          selectedNotes().forEach { note -> note.sourceTick=EditorAutomation.tickAtTime(note.time,tempoMarks,ppq);note.sourceDurationTicks=(EditorAutomation.tickAtTime(note.time+note.duration,tempoMarks,ppq)-note.sourceTick).coerceAtLeast(1) }
          sortNotesAndResolvePrimary()
        }
        if(resizingNote){resizeNoteId?.let{id->notes.firstOrNull{it.id==id}?.let{note->note.sourceTick=EditorAutomation.tickAtTime(note.time,tempoMarks,ppq);note.sourceDurationTicks=(EditorAutomation.tickAtTime(note.time+note.duration,tempoMarks,ppq)-note.sourceTick).coerceAtLeast(1)}};syncImGuiInspector()}
        // A press without a drag is selection plus a single local audition. Drag and resize
        // paths deliberately do not call preview, avoiding a burst of notes while editing.
        if ((noteDragArmed || noteResizeArmed) && !draggingNotes && !resizingNote) notes.getOrNull(selected)?.let(::previewNote)
        selectionStart = null; selectionEnd = null; noteDragArmed=false; draggingNotes = false; noteResizeArmed=false;resizingNote=false;resizeNoteId=null;horizontalScrollbar = false; verticalScrollbar = false
      }

    draw.pushClipRect(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, true)
    draw.addRectFilled(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, drawColor(settings.style.pianoRollColor))
    draw.addRectFilled(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, drawColor(settings.style.pianoRollColor))
    draw.addRectFilled(canvasX, canvasY, plotLeft, notesTop, drawColor(settings.style.panelBackgroundColor))
    draw.addText(canvasX + 10f, canvasY + 7f, drawColor(settings.style.disabledTextColor), "KEY")
    val blackKeys = setOf(1, 3, 6, 8, 10)
    for (pitch in pitchMin until pitchMin + visiblePitchCount) {
      val y = yAt(pitch); val midi = NoteBlockPitch.toMidiKey(pitch); val midiClass = Math.floorMod(midi, 12); val black = midiClass in blackKeys
      val outsideVanilla = pitch !in NoteBlockPitch.VANILLA_MIN..NoteBlockPitch.VANILLA_MAX
      draw.addRectFilled(plotLeft, y, plotRight, y + rowHeight, shadedDrawColor(settings.style.pianoRollColor, if (black) .88f else 1.12f))
      draw.addRectFilled(canvasX, y, plotLeft, y + rowHeight, if (black) ImColor.rgb(37, 43, 53) else ImColor.rgb(225, 229, 232))
      if (outsideVanilla) {
        draw.addRectFilled(plotLeft, y, plotRight, y + rowHeight, drawColor(settings.style.outOfRangeColor))
        draw.addRectFilled(canvasX, y, plotLeft, y + rowHeight, drawColor(settings.style.outOfRangeColor))
        draw.addRectFilled(canvasX, y, canvasX + 5f, y + rowHeight, drawColor(settings.style.accentColor))
      }
      if (pitch == NoteBlockPitch.VANILLA_MIN || pitch == NoteBlockPitch.VANILLA_MAX + 1) draw.addLine(canvasX, y + rowHeight, plotRight, y + rowHeight, drawColor(settings.style.accentColor), 1.5f)
      draw.addLine(plotLeft, y, plotRight, y, drawColor(settings.style.gridColor))
      // y is the top of a key row. C and F# boundaries belong below those rows, at B/C and F/F#.
      val keyBoundary = midiClass == 0 || midiClass == 6
      if (keyBoundary) draw.addLine(plotLeft, y + rowHeight, plotRight, y + rowHeight, drawColor(settings.style.accentColor), 1f)
      if (midiClass == 0 && rowHeight >= 7f) draw.addText(canvasX + 5f, y + 1f, if (black) ImColor.rgb(234, 240, 248) else ImColor.rgb(40, 46, 55), "C${midi / 12 - 1}")
    }

    draw.addRectFilled(plotLeft, canvasY, plotRight, notesTop, drawColor(settings.style.frameColor))
    val visibleMarks = visibleMarks()
    val barMarks = visibleMarks.filter { it.isBar }
    val barSpacing = barMarks.zipWithNext().map { xAt(it.second.timeMs) - xAt(it.first.timeMs) }.filter { it > 0f }.minOrNull() ?: plotWidth
    var barStride = 1
    while (barSpacing * barStride < 56f && barStride < 128) barStride *= 2
    val density = when (settings.gridDensity) { "SPARSE" -> 1.6f; "DENSE" -> .7f; "NORMAL" -> 1f; else -> 1f }
    val quarterMarks = visibleMarks.filter { it.isBar || it.subdivision == 0 }
    val quarterSpacing = quarterMarks.zipWithNext().map { xAt(it.second.timeMs) - xAt(it.first.timeMs) }.filter { it > 0f }.minOrNull() ?: barSpacing
    // Explicit LOD ladder: bar only, then 1/4, 1/8, 1/16, 1/32 and finally 1/64.
    // This avoids the previous all-or-nothing jump caused by testing adjacent 1/64 pixels.
    val minimumMinorSpacing = 8f * density
    val renderDivisor = listOf(4, 8, 16, 32, 64).lastOrNull { divisor -> quarterSpacing * 4f / divisor >= minimumMinorSpacing } ?: 0
    val renderStride = if (renderDivisor == 0) Int.MAX_VALUE else (64 / renderDivisor).coerceAtLeast(1)
    var previousLabelRight = plotLeft - 1f
    visibleMarks.forEach { mark ->
      val x = xAt(mark.timeMs)
      val strideBar = (mark.bar - 1) % barStride == 0
      val drawLine = mark.isBar || (renderDivisor > 0 && mark.subdivision % renderStride == 0)
      if (drawLine) {
        val color = when { mark.isBar -> shadedDrawColor(settings.style.gridColor, 1.65f); mark.isBeat -> shadedDrawColor(settings.style.gridColor, 1.30f); else -> drawColor(settings.style.gridColor) }
        // The ruler is a dedicated opaque band: grid lines begin below it and cannot cross labels.
        draw.addLine(x, notesTop, x, notesBottom, color)
        if (mark.isBar) draw.addLine(x, notesTop - 5f, x, notesTop, color)
      }
      if (mark.isBar && strideBar) {
        val label = mark.bar.toString(); val labelWidth = ImGui.calcTextSizeX(label)
        val labelX = (x + 4f).coerceIn(plotLeft + 3f, plotRight - labelWidth - 3f)
        if (labelX >= previousLabelRight && labelX + labelWidth <= plotRight - 2f) {
          draw.addRectFilled(labelX - 2f, canvasY + 3f, labelX + labelWidth + 2f, notesTop - 3f, drawColor(settings.style.frameColor))
          draw.addText(labelX, canvasY + 6f, drawColor(settings.style.disabledTextColor), label)
          previousLabelRight = labelX + labelWidth + 12f
        }
      }
    }

    val visibleRange = pitchMin until pitchMin + visiblePitchCount
    interactiveTimelineNotes.forEach { indexed ->
      val note = indexed.value
      if (note.pitch in visibleRange && note.time + note.duration >= horizontalOffset && note.time <= horizontalOffset + visibleSpan()) {
        val start = xAt(note.time).coerceAtLeast(plotLeft); val end = max(start + 5f, xAt(note.time + note.duration).coerceAtMost(plotRight))
        val y = yAt(note.pitch) + 2f; val selectedNote = note.id in selectedIds
        val base = if (selectedNote) ImColor.rgb(185, 231, 105) else if (allPartsView || note.part == activePart) partColor(note.part, 255) else partColor(note.part, if (settings.showOtherParts) 72 else 0)
        if (allPartsView || (base ushr 24) != 0 || note.part == activePart || selectedNote) {
          val noteBottom = max(y + 3f, y + rowHeight - 3f)
          draw.addRectFilled(start, y, end, noteBottom, base, 2f)
          if(selectedNote){
            val outline=drawColor(settings.style.textColor)
            draw.addLine(start,y,end,y,outline,1.5f);draw.addLine(start,noteBottom,end,noteBottom,outline,1.5f)
            draw.addLine(start,y,start,noteBottom,outline,1.5f);draw.addLine(end,y,end,noteBottom,outline,2f)
          }
        }
      }
    }

    selectionStart?.let { start -> selectionEnd?.let { end ->
      val x1 = xAt(start.first).coerceIn(plotLeft, plotRight); val x2 = xAt(end.first).coerceIn(plotLeft, plotRight)
      val y1 = yAt(start.second).coerceIn(notesTop, notesBottom); val y2 = yAt(end.second).coerceIn(notesTop, notesBottom)
      draw.addRectFilled(min(x1, x2), min(y1, y2), max(x1, x2), (max(y1, y2) + rowHeight).coerceAtMost(notesBottom), ImColor.rgba(59, 140, 203, 64))
      draw.addRect(min(x1, x2), min(y1, y2), max(x1, x2), (max(y1, y2) + rowHeight).coerceAtMost(notesBottom), ImColor.rgb(185, 231, 105))
    } }
    val head = visualPlayheadMs.roundToInt()
    if (head in horizontalOffset..horizontalOffset + visibleSpan()) draw.addLine(xAt(head), canvasY, xAt(head), notesBottom, ImColor.rgb(185, 231, 105), 2f)

    val total = durationMs().coerceAtLeast(visibleSpan()); val horizontalThumb = (plotWidth * visibleSpan() / total).coerceAtLeast(18f)
    val horizontalX = plotLeft + (plotWidth - horizontalThumb) * horizontalOffset / (total - visibleSpan()).coerceAtLeast(1)
    draw.addRectFilled(plotLeft, notesBottom, plotRight, canvasY + canvasHeight, ImColor.rgb(42, 51, 64)); draw.addRectFilled(horizontalX, notesBottom, horizontalX + horizontalThumb, canvasY + canvasHeight, ImColor.rgb(101, 117, 138))
    val domainSize = pitchDomainSize(); val verticalThumb = (noteHeight * visiblePitchCount / domainSize).coerceAtLeast(14f)
    val verticalY = notesTop + (noteHeight - verticalThumb) * (pitchMin - pitchDomainMin()) / (domainSize - visiblePitchCount).coerceAtLeast(1)
    draw.addRectFilled(plotRight, notesTop, canvasX + canvasWidth, notesBottom, ImColor.rgb(42, 51, 64)); draw.addRectFilled(plotRight, verticalY, canvasX + canvasWidth, verticalY + verticalThumb, ImColor.rgb(101, 117, 138))
    draw.addRect(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, ImColor.rgb(92, 100, 115))
    draw.popClipRect()
    ImGui.end()
  }

  /** Part colors deliberately avoid the lime selection/playhead accent. */
  private val PART_COLOR_PALETTE = intArrayOf(
    0x79C7FF, 0xF7C66B, 0x74C69D, 0xD990FF,
    0xFF9B71, 0xA8DADC, 0xF4A261, 0xE78FB3,
    0x98AFC7, 0x61C0BF, 0xA7A4FF, 0xC79A6B,
    0x75A7FF, 0xD98291, 0xB7C56B, 0x8DD3C7,
  )

  private fun partColor(part: Int, alpha: Int): Int {
    val rgb = PART_COLOR_PALETTE[Math.floorMod(part, PART_COLOR_PALETTE.size)]
    return ImColor.rgba((rgb ushr 16) and 255, (rgb ushr 8) and 255, rgb and 255, alpha.coerceIn(0, 255))
  }

  private fun partColorFloats(part: Int): FloatArray {
    val rgb = PART_COLOR_PALETTE[Math.floorMod(part, PART_COLOR_PALETTE.size)]
    return floatArrayOf(((rgb ushr 16) and 255) / 255f, ((rgb ushr 8) and 255) / 255f, (rgb and 255) / 255f, 1f)
  }

  private fun releaseProfileForSelection(): RetriggerProfile =
      selectedNotes().firstOrNull()?.retriggerOverride ?: RetriggerProfile()

  private fun syncReleaseControls() {
    val profile = releaseProfileForSelection().normalized()
    imReleaseEnabled = profile.enabled; imReleaseThreshold.set(profile.thresholdMs); imReleaseInterval.set(profile.intervalMs)
    imReleaseStart.set(profile.startVolumePercent); imReleaseEnd.set(profile.endVolumePercent); imReleaseCurve.set(profile.curve.ordinal)
    imReleaseThresholdUnit.set(releaseThresholdDivisors.indexOf(profile.thresholdDivisor).coerceAtLeast(0))
    imReleaseIntervalUnit.set(releaseIntervalDivisors.indexOf(profile.intervalDivisor).coerceAtLeast(0))
    releaseDraftPoints.clear(); releaseDraftPoints += profile.middlePoints
    selectedReleasePoint = selectedReleasePoint.coerceIn(0, releaseDraftPoints.size + 1)
  }

  private fun releaseUnitLabels(divisors: IntArray) = divisors.map { divisor ->
    if (divisor == 0) t("Milliseconds", "ミリ秒") else "1/$divisor"
  }.toTypedArray()

  private fun releaseProfileFromControls() = RetriggerProfile(
      enabled = imReleaseEnabled,
      thresholdMs = imReleaseThreshold.get(),
      intervalMs = imReleaseInterval.get(),
      startVolumePercent = imReleaseStart.get(),
      endVolumePercent = imReleaseEnd.get(),
      curve = AutomationCurve.entries[imReleaseCurve.get().coerceIn(0, AutomationCurve.entries.lastIndex)],
      thresholdDivisor = releaseThresholdDivisors[imReleaseThresholdUnit.get().coerceIn(0, releaseThresholdDivisors.lastIndex)],
      intervalDivisor = releaseIntervalDivisors[imReleaseIntervalUnit.get().coerceIn(0, releaseIntervalDivisors.lastIndex)],
      middlePoints = releaseDraftPoints.toList(),
  ).normalized()

  private fun applyReleaseControls(clearOverride: Boolean = false) {
    val targets = selectedNotes()
    if (targets.isEmpty()) {
      state = t("Select one or more notes before editing pseudo-release", "疑似リリースを編集する音を選択してください")
      return
    }
    val before = currentHistory()
    val profile = releaseProfileFromControls()
    targets.forEach { it.retriggerOverride = if (clearOverride) null else profile }
    if (before != currentHistory()) history.push(before)
    syncReleaseControls()
    state = if (clearOverride) t("Cleared pseudo-release from ${targets.size} selected notes", "選択した${targets.size}音の疑似リリースを解除しました") else t("Applied pseudo-release to ${targets.size} selected notes", "選択した${targets.size}音に疑似リリースを適用しました")
  }

  private fun addReleaseMiddlePoint() {
    if (releaseDraftPoints.size >= 2) return
    val profile = releaseProfileFromControls()
    val positions = (listOf(0) + releaseDraftPoints.map { it.positionPercent } + 100).sorted()
    val gap = positions.zipWithNext().maxByOrNull { it.second - it.first } ?: (0 to 100)
    val position = (gap.first + gap.second) / 2
    val volume = EditorAutomation.releaseEnvelope(profile, position / 100.0).roundToInt().coerceIn(0, 100)
    releaseDraftPoints += ReleaseControlPoint(position, volume)
    releaseDraftPoints.sortBy { it.positionPercent }
    selectedReleasePoint = releaseDraftPoints.indexOfFirst { it.positionPercent == position } + 1
  }

  private fun deleteSelectedReleaseMiddlePoint() {
    val middleIndex = selectedReleasePoint - 1
    if (middleIndex !in releaseDraftPoints.indices) return
    releaseDraftPoints.removeAt(middleIndex)
    selectedReleasePoint = selectedReleasePoint.coerceIn(0, releaseDraftPoints.size + 1)
  }

  private fun renderReleaseControls() {
    ImGui.text("${t("Selected notes", "選択音")}: ${selectedIds.size}")
    ImGui.sameLine()
    if (ImGui.checkbox("${t("Enabled", "有効")}###RELEASE_ENABLED", imReleaseEnabled)) imReleaseEnabled=!imReleaseEnabled

    ImGui.setNextItemWidth(150f)
    ImGui.combo("${t("Long-note threshold", "連打判定")}###RELEASE_THRESHOLD_UNIT", imReleaseThresholdUnit, releaseUnitLabels(releaseThresholdDivisors))
    if (imReleaseThresholdUnit.get() == 0) { ImGui.sameLine(); ImGui.setNextItemWidth(150f); ImGui.inputInt("ms###RELEASE_THRESHOLD", imReleaseThreshold, 25, 100); if (confirmPressed()) applyReleaseControls(); markAsciiField() }
    ImGui.setNextItemWidth(150f)
    ImGui.combo("${t("Repeat interval", "連打間隔")}###RELEASE_INTERVAL_UNIT", imReleaseIntervalUnit, releaseUnitLabels(releaseIntervalDivisors))
    if (imReleaseIntervalUnit.get() == 0) { ImGui.sameLine(); ImGui.setNextItemWidth(150f); ImGui.inputInt("ms###RELEASE_INTERVAL", imReleaseInterval, 5, 25); if (confirmPressed()) applyReleaseControls(); markAsciiField() }

    ImGui.setNextItemWidth(130f); ImGui.inputInt("${t("Start %", "開始音量 %")}###RELEASE_START", imReleaseStart, 5, 10); if (confirmPressed()) applyReleaseControls(); markAsciiField()
    ImGui.sameLine(); ImGui.setNextItemWidth(130f); ImGui.inputInt("${t("End %", "終了音量 %")}###RELEASE_END", imReleaseEnd, 5, 10); if (confirmPressed()) applyReleaseControls(); markAsciiField()
    imReleaseStart.set(imReleaseStart.get().coerceIn(0,100)); imReleaseEnd.set(imReleaseEnd.get().coerceIn(0,100))
    ImGui.setNextItemWidth(145f); ImGui.combo("${t("Curve", "曲線")}###RELEASE_CURVE", imReleaseCurve, AutomationCurve.entries.map { it.name }.toTypedArray())
    ImGui.sameLine(); if (ImGui.button("${t("ADD MIDDLE", "中間点を追加")}###ADD_RELEASE_POINT")) addReleaseMiddlePoint()
    ImGui.sameLine(); if (ImGui.button("${t("APPLY", "適用")}###APPLY_RELEASE")) applyReleaseControls()
    if (selectedReleasePoint in 1..releaseDraftPoints.size) { if (ImGui.button("${t("DELETE POINT", "選択点を削除")}###DELETE_RELEASE_POINT")) deleteSelectedReleaseMiddlePoint() }
    ImGui.sameLine(); if (ImGui.button("${t("CLEAR SELECTED", "選択音から解除")}###CLEAR_RELEASE")) applyReleaseControls(clearOverride=true)
    ImGui.spacing()
    ImGui.textWrapped(t("Pseudo-release is stored only on the selected notes. Drag the end points vertically; up to two middle points move horizontally and vertically.", "疑似リリースは選択音だけに保存されます。開始・終止点は上下、中間点は最大2個まで上下左右にドラッグできます。"))
  }

  private fun renderReleaseGraph(io: ImGuiIO, requestedSize: Float) {
    val graphSize = requestedSize.coerceIn(180f, 360f)
    val x0=ImGui.getCursorScreenPosX(); val y0=ImGui.getCursorScreenPosY()
    ImGui.invisibleButton("##release-envelope",graphSize,graphSize)
    val hovered=ImGui.isItemHovered(); val draw=ImGui.getWindowDrawList(); val profile=releaseProfileFromControls()
    draw.addRectFilled(x0,y0,x0+graphSize,y0+graphSize,ImColor.rgb(14,20,27))
    for (line in 0..4) {
      val offset=graphSize*line/4f
      draw.addLine(x0+offset,y0,x0+offset,y0+graphSize,ImColor.rgba(67,82,98,100))
      draw.addLine(x0,y0+offset,x0+graphSize,y0+offset,ImColor.rgba(67,82,98,100))
    }
    draw.addText(x0+5f,y0+4f,ImColor.rgb(141,152,169),"100%")
    draw.addText(x0+5f,y0+graphSize/2f-7f,ImColor.rgb(141,152,169),"50%")
    draw.addText(x0+5f,y0+graphSize-18f,ImColor.rgb(141,152,169),"0%")
    var previousX=x0; var previousY=y0+graphSize-(EditorAutomation.releaseEnvelope(profile,0.0)/100.0*graphSize).toFloat()
    for(step in 1..128){
      val fraction=step/128.0; val x=x0+graphSize*step/128f
      val y=y0+graphSize-(EditorAutomation.releaseEnvelope(profile,fraction)/100.0*graphSize).toFloat()
      draw.addLine(previousX,previousY,x,y,ImColor.rgb(102,217,166),2f);previousX=x;previousY=y
    }
    val points = buildList { add(ReleaseControlPoint(0,imReleaseStart.get())); addAll(releaseDraftPoints); add(ReleaseControlPoint(100,imReleaseEnd.get())) }
    fun pointX(point:ReleaseControlPoint)=x0+graphSize*point.positionPercent/100f
    fun pointY(point:ReleaseControlPoint)=y0+graphSize-(point.volumePercent.coerceIn(0,100)/100f)*graphSize
    points.forEachIndexed { index,point ->
      val color=if(index==selectedReleasePoint)ImColor.rgb(185,231,105)else ImColor.rgb(102,217,166)
      draw.addCircleFilled(pointX(point),pointY(point),if(index==selectedReleasePoint)7f else 5f,color)
      val label="${point.positionPercent}% / ${point.volumePercent}%"
      val labelX=(pointX(point)+7f).coerceAtMost(x0+graphSize-ImGui.calcTextSize(label).x-4f)
      val labelY=(pointY(point)-18f).coerceIn(y0+3f,y0+graphSize-18f)
      draw.addText(labelX,labelY,color,label)
    }
    if(hovered&&ImGui.isMouseClicked(ImGuiMouseButton.Left)){
      val hit=points.indices.minByOrNull{index->kotlin.math.hypot((pointX(points[index])-io.mousePosX).toDouble(),(pointY(points[index])-io.mousePosY).toDouble())}
      if(hit!=null&&kotlin.math.hypot((pointX(points[hit])-io.mousePosX).toDouble(),(pointY(points[hit])-io.mousePosY).toDouble())<=12){selectedReleasePoint=hit;draggingReleasePoint=true}
    }
    if(draggingReleasePoint&&ImGui.isMouseDown(ImGuiMouseButton.Left)){
      val volume=((y0+graphSize-io.mousePosY)/graphSize*100f).roundToInt().coerceIn(0,100)
      when(selectedReleasePoint){
        0->imReleaseStart.set(volume)
        releaseDraftPoints.size+1->imReleaseEnd.set(volume)
        else->{
          val index=selectedReleasePoint-1
          if(index in releaseDraftPoints.indices){
            val minimum=if(index==0)1 else releaseDraftPoints[index-1].positionPercent+1
            val maximum=if(index==releaseDraftPoints.lastIndex)99 else releaseDraftPoints[index+1].positionPercent-1
            val position=((io.mousePosX-x0)/graphSize*100f).roundToInt().coerceIn(minimum,maximum)
            releaseDraftPoints[index]=ReleaseControlPoint(position,volume)
          }
        }
      }
    }
    if(ImGui.isMouseReleased(ImGuiMouseButton.Left))draggingReleasePoint=false
    val selectedPoint=points.getOrNull(selectedReleasePoint)
    if(selectedPoint!=null)ImGui.textDisabled(t("Point ${selectedReleasePoint+1}: ${selectedPoint.positionPercent}% / ${selectedPoint.volumePercent}% volume", "点 ${selectedReleasePoint+1}: 位置 ${selectedPoint.positionPercent}% / 音量 ${selectedPoint.volumePercent}%"))
    draw.addRect(x0,y0,x0+graphSize,y0+graphSize,ImColor.rgb(67,82,98))
  }

  private fun renderReleaseAutomation(io: ImGuiIO) {
    val available = ImGui.getContentRegionAvailX().coerceAtLeast(180f)
    val availableHeight = ImGui.getContentRegionAvailY().coerceAtLeast(190f)
    val sideBySide = available >= 660f
    if (sideBySide) {
      val graphSize = minOf(340f, (available * .38f).coerceAtLeast(240f), (availableHeight - 28f).coerceAtLeast(180f))
      val graphColumnWidth = graphSize + 20f
      val controlsWidth = (available - graphColumnWidth - 16f).coerceAtLeast(360f)
      ImGui.beginChild("##release-controls", controlsWidth, availableHeight, false)
      renderReleaseControls()
      ImGui.endChild()
      ImGui.sameLine()
      ImGui.beginChild("##release-graph", graphColumnWidth, availableHeight, false)
      renderReleaseGraph(io, graphSize)
      ImGui.endChild()
    } else {
      renderReleaseControls()
      renderReleaseGraph(io, min(340f, available))
    }
  }

  private fun renderImGuiAutomationWindows(io: ImGuiIO) {
    val dockId = ImGui.getID("##OMMT_BOTTOM_AUTOMATION_DOCK")
    if (!automationDockInitialized) {
      if (ImGuiInternal.dockBuilderGetNode(dockId) == null) {
        ImGuiInternal.dockBuilderAddNode(dockId)
        val left = if (settings.showLibrary) max(220f, io.displaySizeX * .16f) else 0f
        val right = if (settings.showInspector) 350f else 0f
        val dockHeight = (io.displaySizeY * .30f).coerceIn(240f, 420f)
        ImGuiInternal.dockBuilderSetNodePos(dockId, left, io.displaySizeY - dockHeight)
        ImGuiInternal.dockBuilderSetNodeSize(dockId, (io.displaySizeX - left - right).coerceAtLeast(420f), dockHeight)
        // Dock Volume last because ImGui activates the last added tab on a fresh node.
        AutomationLane.entries.reversed().forEach { lane ->
          ImGuiInternal.dockBuilderDockWindow(automationWindowTitle(lane), dockId)
        }
        ImGuiInternal.dockBuilderFinish(dockId)
      }
      automationDockInitialized = true
    }
    AutomationLane.entries.forEach { lane -> renderImGuiAutomationWindow(io, lane, dockId) }
    if (focusDefaultAutomation) {
      ImGui.setWindowFocus(automationWindowTitle(AutomationLane.VOLUME))
      automationLane = AutomationLane.VOLUME
      focusDefaultAutomation = false
    }
  }

  private fun automationWindowTitle(lane: AutomationLane) = when (lane) {
    AutomationLane.VOLUME -> windowTitle("VOLUME", "音量", "OMMT_VOLUME")
    AutomationLane.PAN -> windowTitle("PAN", "定位", "OMMT_PAN")
    AutomationLane.TEMPO -> windowTitle("TEMPO", "テンポ", "OMMT_TEMPO")
    AutomationLane.RELEASE -> windowTitle("RELEASE", "リリース", "OMMT_RELEASE")
  }

  private fun renderImGuiAutomationWindow(io: ImGuiIO, lane: AutomationLane, dockId: Int) {
    val left = if (settings.showLibrary) max(220f, io.displaySizeX * .16f) else 0f
    val right = if (settings.showInspector) 350f else 0f
    val dockHeight = (io.displaySizeY * .30f).coerceIn(240f, 420f)
    ImGuiInternal.setNextWindowDockID(dockId, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowPos(left, io.displaySizeY - dockHeight, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize((io.displaySizeX - left - right).coerceAtLeast(420f), dockHeight, ImGuiCond.FirstUseEver)
    if (!ImGui.begin(automationWindowTitle(lane))) { ImGui.end(); return }
    if (automationLane != lane && lane == AutomationLane.RELEASE) syncReleaseControls()
    automationLane = lane
      ImGui.textDisabled(t("Timeline is shared with the piano roll", "横位置はピアノロールと連動します"))
      if (automationLane == AutomationLane.RELEASE) { renderReleaseAutomation(io); ImGui.end(); return }
      val canvasX=ImGui.getCursorScreenPosX(); val canvasY=ImGui.getCursorScreenPosY()
      val canvasWidth=ImGui.getContentRegionAvailX().coerceAtLeast(180f)
      val canvasHeight=(ImGui.getContentRegionAvailY() - if (automationLane==AutomationLane.TEMPO) 38f else 0f).coerceAtLeast(90f)
      ImGui.invisibleButton("##automation-canvas",canvasWidth,canvasHeight)
      val draw=ImGui.getWindowDrawList()
      // Match the piano-roll canvas exactly: 58 px is its keyboard column and 8 px is its right
      // scrollbar. Sharing only horizontalOffset/span was insufficient because the drawable widths
      // had different origins, which visibly shifted every automation point to the left.
      val axisWidth=TIMELINE_AXIS_WIDTH; val scrollbarWidth=TIMELINE_SCROLLBAR_WIDTH
      val graphLeft=canvasX+axisWidth; val graphRight=canvasX+canvasWidth-scrollbarWidth
      val graphWidth=(graphRight-graphLeft).coerceAtLeast(1f)
      val bottom=canvasY+canvasHeight-10f; val top=canvasY+10f
      val hovered=ImGui.isItemHovered() && io.mousePosX in graphLeft..graphRight && io.mousePosY in canvasY..(canvasY+canvasHeight)
      fun xAt(time:Int)=graphLeft+(time-horizontalOffset).toFloat()/visibleSpan()*graphWidth
      fun valueY(note:EditorNote)=when(automationLane){AutomationLane.VOLUME->bottom-(note.volume/100f)*(bottom-top);AutomationLane.PAN->bottom-((note.pan+100)/200f)*(bottom-top);else->bottom}
      fun axisLabel(text:String,y:Float){draw.addText(canvasX+5f,(y-7f).coerceIn(canvasY+2f,canvasY+canvasHeight-17f),ImColor.rgb(141,152,169),text)}
      val tempoAxisMax=max(300,(tempoControls.maxOfOrNull{it.bpm}?:120)+20)
      draw.addRectFilled(canvasX,canvasY,canvasX+canvasWidth,canvasY+canvasHeight,drawColor(settings.style.pianoRollColor))
      draw.addRectFilled(canvasX,canvasY,graphLeft,canvasY+canvasHeight,drawColor(settings.style.panelBackgroundColor))
      draw.addRectFilled(graphRight,canvasY,canvasX+canvasWidth,canvasY+canvasHeight,drawColor(settings.style.panelBackgroundColor))
      when(automationLane){
        AutomationLane.VOLUME->{axisLabel("100",top);axisLabel("0",bottom);draw.addLine(graphLeft,bottom,graphRight,bottom,drawColor(settings.style.borderColor))}
        AutomationLane.PAN->{axisLabel("+100",top);axisLabel("0",(top+bottom)/2f);axisLabel("-100",bottom);draw.addLine(graphLeft,(top+bottom)/2f,graphRight,(top+bottom)/2f,drawColor(settings.style.borderColor))}
        AutomationLane.TEMPO->{axisLabel(tempoAxisMax.toString(),top);axisLabel("0",bottom)}
        else->Unit
      }
      draw.pushClipRect(graphLeft,canvasY,graphRight,canvasY+canvasHeight,true)
      val visibleGrid=visibleMarks()
      val beatXs=visibleGrid.filter{it.isBeat}.map{mark->xAt(mark.timeMs)}
      val showBeats=beatXs.zipWithNext().map{it.second-it.first}.filter{it>0f}.minOrNull()?.let{it>=12f}?:false
      visibleGrid.filter{it.isBar||(showBeats&&it.isBeat)}.forEach{mark->
        val color=if(mark.isBar)ImColor.rgba(91,109,132,150)else ImColor.rgba(67,82,98,90)
        draw.addLine(xAt(mark.timeMs),canvasY,xAt(mark.timeMs),canvasY+canvasHeight,color)
      }
      if (automationLane == AutomationLane.TEMPO) {
        run {
          val minBpm=0;val maxBpm=tempoAxisMax
          fun tempoX(point:TempoControlPoint)=xAt(EditorAutomation.timeAtTick(point.tick,tempoMarks,ppq))
          fun tempoY(point:TempoControlPoint)=bottom-(point.bpm-minBpm).toFloat()/(maxBpm-minBpm).coerceAtLeast(1)*(bottom-top)
          val ordered=tempoControls.sortedBy{it.tick}
          ordered.firstOrNull()?.let { first -> draw.addLine(graphLeft,tempoY(first),tempoX(first),tempoY(first),ImColor.rgb(244,162,97),2f) }
          ordered.zipWithNext().forEach{(a,b)->
            val ax=tempoX(a);val bx=tempoX(b);val ay=tempoY(a);val by=tempoY(b)
            if(b.curve==AutomationCurve.STEP){
              draw.addLine(ax,ay,bx,ay,ImColor.rgb(244,162,97),2f)
              draw.addLine(bx,ay,bx,by,ImColor.rgb(244,162,97),2f)
            }else{
              var px=ax;var py=ay
              for(step in 1..64){
                val f=step/64.0;val shaped=if(b.curve==AutomationCurve.LINEAR)f else f*f*(3.0-2.0*f)
                val bpmValue=a.bpm+(b.bpm-a.bpm)*shaped;val x=ax+(bx-ax)*step/64f
                val y=bottom-((bpmValue-minBpm)/(maxBpm-minBpm).toDouble()*(bottom-top)).toFloat()
                draw.addLine(px,py,x,y,ImColor.rgb(244,162,97),2f);px=x;py=y
              }
            }
          }
          ordered.lastOrNull()?.let { last -> draw.addLine(tempoX(last),tempoY(last),graphRight,tempoY(last),ImColor.rgb(244,162,97),2f) }
          val visiblePoints=ordered.filter{tempoX(it) in graphLeft..graphRight}
          val markerPoints=mutableListOf<TempoControlPoint>();var lastMarkerX=Float.NEGATIVE_INFINITY
          visiblePoints.forEach{point->val x=tempoX(point);if(point.id in selectedTempoPointIds||x-lastMarkerX>=8f){markerPoints+=point;lastMarkerX=x}}
          var lastTempoLabelX=Float.NEGATIVE_INFINITY
          markerPoints.forEach{point->
            val x=tempoX(point);val y=tempoY(point);val color=if(point.id in selectedTempoPointIds)ImColor.rgb(185,231,105)else ImColor.rgb(244,162,97)
            draw.addCircleFilled(x,y,if(point.id==selectedTempoPointId)5f else 3.5f,color)
            if(point.id in selectedTempoPointIds||x-lastTempoLabelX>=48f){draw.addText((x+7f).coerceAtMost(graphRight-38f),(y-18f).coerceAtLeast(top),color,point.bpm.toString());lastTempoLabelX=x}
          }
          // Marquee helpers share the lane's coerced mappers, so the rect is window-clamped.
          fun tempoTimeAt(x:Float)=(horizontalOffset+((x-graphLeft)/graphWidth).coerceIn(0f,1f)*visibleSpan()).roundToInt()
          fun tempoTickAt(x:Float)=EditorAutomation.tickAtTime(snap(tempoTimeAt(x)),tempoMarks,ppq)
          fun tempoBpmAt(y:Float)=(minBpm+((bottom-y)/(bottom-top)).coerceIn(0f,1f)*(maxBpm-minBpm)).roundToInt()
          fun marqueeTempoX(tick:Long)=xAt(EditorAutomation.timeAtTick(tick,tempoMarks,ppq))
          fun marqueeTempoY(bpm:Int)=bottom-(bpm-minBpm).toFloat()/(maxBpm-minBpm).coerceAtLeast(1)*(bottom-top)
          if(hovered&&ImGui.isMouseClicked(ImGuiMouseButton.Left)){val hit=markerPoints.minByOrNull{point->kotlin.math.hypot((tempoX(point)-io.mousePosX).toDouble(),(tempoY(point)-io.mousePosY).toDouble())}?.takeIf{point->kotlin.math.hypot((tempoX(point)-io.mousePosX).toDouble(),(tempoY(point)-io.mousePosY).toDouble())<=10};if(hit!=null){rememberHistory();selectedTempoPointId=hit.id;selectedTempoPointIds.clear();selectedTempoPointIds+=hit.id;automationDragId=hit.id;imTempoBpm.set(hit.bpm);imTempoCurve.set(hit.curve.ordinal)}else{tempoMarqueeStart=tempoTickAt(io.mousePosX) to tempoBpmAt(io.mousePosY);tempoMarqueeEnd=tempoMarqueeStart}}
          automationDragId?.let{id->tempoControls.firstOrNull{it.id==id}?.let{point->if(ImGui.isMouseDown(ImGuiMouseButton.Left)){
            val oldBpm=point.bpm;val oldTick=point.tick
            point.bpm=(minBpm+((bottom-io.mousePosY)/(bottom-top)).coerceIn(0f,1f)*(maxBpm-minBpm)).roundToInt();imTempoBpm.set(point.bpm)
            val dragOrder=tempoControls.sortedBy{it.tick};val index=dragOrder.indexOfFirst{it.id==point.id}
            if(index>0){
              val proposedTime=(horizontalOffset+((io.mousePosX-graphLeft)/graphWidth).coerceIn(0f,1f)*visibleSpan()).roundToInt()
              val proposedTick=EditorAutomation.tickAtTime(snap(proposedTime),tempoMarks,ppq)
              val minimum=dragOrder[index-1].tick+1;val maximum=dragOrder.getOrNull(index+1)?.tick?.minus(1)?:Long.MAX_VALUE
              point.tick=proposedTick.coerceIn(minimum,maximum)
            }
            // Group drag: marquee-selected points follow by the same deltas, each clamped
            // to its own non-selected neighbors so order is preserved.
            val bpmDelta=point.bpm-oldBpm;val tickDelta=point.tick-oldTick
            if((bpmDelta!=0||tickDelta!=0L)&&selectedTempoPointIds.size>1){
              val order=tempoControls.sortedBy{it.tick}
              selectedTempoPointIds.forEach{otherId->if(otherId!=id)tempoControls.firstOrNull{it.id==otherId}?.let{other->
                other.bpm=(other.bpm+bpmDelta).coerceIn(1,60_000)
                if(tickDelta!=0L){
                  val below=order.filter{it.tick<other.tick&&it.id !in selectedTempoPointIds}.maxOfOrNull{it.tick}
                  val above=order.filter{it.tick>other.tick&&it.id !in selectedTempoPointIds}.minOfOrNull{it.tick}
                  other.tick=(other.tick+tickDelta).coerceIn((below?.plus(1))?:0L,(above?.minus(1))?:Long.MAX_VALUE)
                }
              }}
            }
          }}}
          tempoMarqueeStart?.let{if(ImGui.isMouseDown(ImGuiMouseButton.Left)){tempoMarqueeEnd=tempoTickAt(io.mousePosX) to tempoBpmAt(io.mousePosY)}}
          tempoMarqueeStart?.let{s->tempoMarqueeEnd?.let{e->
            val t1=minOf(s.first,e.first);val t2=maxOf(s.first,e.first);val v1=minOf(s.second,e.second);val v2=maxOf(s.second,e.second)
            val x1=marqueeTempoX(t1).coerceIn(graphLeft,graphRight);val x2=marqueeTempoX(t2).coerceIn(graphLeft,graphRight)
            val y1=marqueeTempoY(v1).coerceIn(canvasY,canvasY+canvasHeight);val y2=marqueeTempoY(v2).coerceIn(canvasY,canvasY+canvasHeight)
            draw.addRectFilled(minOf(x1,x2),minOf(y1,y2),maxOf(x1,x2),maxOf(y1,y2),ImColor.rgba(59,140,203,64))
            draw.addRect(minOf(x1,x2),minOf(y1,y2),maxOf(x1,x2),maxOf(y1,y2),ImColor.rgb(185,231,105))
          }}
          if(ImGui.isMouseReleased(ImGuiMouseButton.Left)){
            tempoMarqueeStart?.let{s->tempoMarqueeEnd?.let{e->
              val t1=minOf(s.first,e.first);val t2=maxOf(s.first,e.first);val v1=minOf(s.second,e.second);val v2=maxOf(s.second,e.second)
              val found=tempoControls.filter{it.tick in t1..t2&&it.bpm in v1..v2}
              if(!modifierActive(settings.additiveSelectionModifier,io))selectedTempoPointIds.clear()
              found.forEach{selectedTempoPointIds+=it.id}
              val primary=selectedTempoPointId?.takeIf{it in selectedTempoPointIds}?:found.firstOrNull()?.id
              selectedTempoPointId=primary
              primary?.let{tempoControls.firstOrNull{point->point.id==it}?.let{imTempoBpm.set(it.bpm);imTempoCurve.set(it.curve.ordinal)}}
              state=t("Selected ${selectedTempoPointIds.size} tempo points","${selectedTempoPointIds.size}個のテンポ点を選択しました")
            }}
            tempoMarqueeStart=null;tempoMarqueeEnd=null
            if(automationDragId!=null){automationDragId=null;applyTempoControls();state=t("Tempo envelope updated","テンポカーブを更新しました")}
          }
          if((automationDragId!=null||tempoMarqueeStart!=null)&&ImGui.isMouseDown(ImGuiMouseButton.Left)){
            edgeScrollDelta(io,graphLeft,canvasY,graphRight,canvasY+canvasHeight)?.let{(dxPx,dyPx)->
              if(dxPx!=0f){
                val maximum=(durationMs().coerceAtLeast(visibleSpan())-visibleSpan()).coerceAtLeast(0)
                horizontalOffset=(horizontalOffset+dxPx/graphWidth*visibleSpan()).roundToInt().coerceIn(0,maximum)
                followPlayback=false
              }
            }
          }
        }
        draw.popClipRect();draw.addRect(graphLeft,canvasY,graphRight,canvasY+canvasHeight,ImColor.rgb(67,82,98))
        val selectedPoint=tempoControls.firstOrNull{it.id==selectedTempoPointId}
        if(ImGui.button("${t("ADD AT PLAYHEAD", "再生位置に追加")}###ADD_TEMPO")){rememberHistory();val tick=EditorAutomation.tickAtTime(playheadMs,tempoMarks,ppq);val existing=tempoControls.firstOrNull{it.tick==tick};val point=existing?:TempoControlPoint(tick,bpm,AutomationCurve.LINEAR).also{tempoControls+=it};selectedTempoPointId=point.id;selectedTempoPointIds.clear();selectedTempoPointIds+=point.id;imTempoBpm.set(point.bpm);imTempoCurve.set(point.curve.ordinal);applyTempoControls()}
        ImGui.sameLine();ImGui.setNextItemWidth(120f);ImGui.inputInt("BPM###TEMPO_BPM",imTempoBpm,1,10);if(confirmPressed()) applyTempoPoint();markAsciiField();ImGui.sameLine();ImGui.setNextItemWidth(140f);ImGui.combo("${t("Curve", "曲線")}###TEMPO_CURVE",imTempoCurve,AutomationCurve.entries.map{it.name}.toTypedArray())
        ImGui.sameLine();if(ImGui.button("${t("APPLY", "適用")}###APPLY_TEMPO")) applyTempoPoint()
        ImGui.sameLine();if(ImGui.button("${t("DELETE", "削除")}###DELETE_TEMPO")){val removable=tempoControls.filter{it.id in selectedTempoPointIds};if(removable.isNotEmpty()&&tempoControls.size-removable.size>=1){rememberHistory();tempoControls.removeAll(removable.toSet());selectedTempoPointIds.retainAll(tempoControls.map{it.id}.toSet());selectedTempoPointId=selectedTempoPointId?.takeIf{id->tempoControls.any{it.id==id}}?:tempoControls.firstOrNull()?.id;selectedTempoPointId?.let{if(it !in selectedTempoPointIds){selectedTempoPointIds.clear();selectedTempoPointIds+=it}};applyTempoControls()}}
        ImGui.end(); return
      }
      val visibleCandidates=indexedNotesInVisibleTimeline().asSequence().map{it.value}.filter{it.time in horizontalOffset..horizontalOffset+visibleSpan()}.toList()
      // Dense automation columns are visually indistinguishable at an overview zoom. Preserve
      // the minimum, maximum and selected values for each 2px column instead of issuing tens of
      // thousands of overlapping draw commands.
      val visible=if(visibleCandidates.size<=MAX_VISIBLE_NOTE_COMMANDS)visibleCandidates else visibleCandidates
        .groupBy{((xAt(it.time)-graphLeft)/2f).roundToInt()}
        .values.flatMap{column->
          val valueOf:(EditorNote)->Int=if(automationLane==AutomationLane.VOLUME)({it.volume})else({it.pan})
          buildList{column.minByOrNull(valueOf)?.let(::add);column.maxByOrNull(valueOf)?.let(::add);column.filter{it.id in selectedIds}.forEach(::add)}
        }.distinctBy{it.id}
      val displayX=mutableMapOf<Long,Float>();visible.groupBy{(xAt(it.time)/5f).roundToInt()}.values.forEach{group->val sorted=group.sortedBy{it.id};val step=if(sorted.size<=1)0f else min(4f,12f/(sorted.size-1));sorted.forEachIndexed{index,note->displayX[note.id]=xAt(note.time)+(index-(sorted.size-1)/2f)*step}}
      var lastLabelX=Float.NEGATIVE_INFINITY
      visible.sortedBy{it.time}.forEach { note ->
        val x=displayX[note.id]?:xAt(note.time); val y=valueY(note); val color=if(note.id in selectedIds)ImColor.rgb(185,231,105)else partColor(note.part,210)
        draw.addLine(x,bottom,x,y,color,3f); draw.addRectFilled(x-3f,y-3f,x+3f,y+3f,color)
        if(note.id in selectedIds||x-lastLabelX>=34f){
          val value=if(automationLane==AutomationLane.VOLUME)note.volume else note.pan
          draw.addText((x+5f).coerceAtMost(graphRight-32f),(y-17f).coerceAtLeast(top),color,value.toString())
          if(note.id !in selectedIds)lastLabelX=x
        }
      }
      // Marquee helpers share the lane's coerced mappers, so the rect is inherently window-clamped.
      fun laneTimeAt(x:Float)=(horizontalOffset+((x-graphLeft)/graphWidth).coerceIn(0f,1f)*visibleSpan()).roundToInt()
      fun laneValueAt(y:Float)=if(automationLane==AutomationLane.VOLUME)(((bottom-y)/(bottom-top)).coerceIn(0f,1f)*100).roundToInt() else (((bottom-y)/(bottom-top)).coerceIn(0f,1f)*200-100).roundToInt()
      fun laneValueOf(note:EditorNote)=if(automationLane==AutomationLane.VOLUME)note.volume else note.pan
      fun laneY(value:Int)=if(automationLane==AutomationLane.VOLUME)bottom-(value/100f)*(bottom-top) else bottom-((value+100)/200f)*(bottom-top)
      if(hovered&&ImGui.isMouseClicked(ImGuiMouseButton.Left)){
        val hit=visible.minByOrNull{kotlin.math.hypot(((displayX[it.id]?:xAt(it.time))-io.mousePosX).toDouble(),(valueY(it)-io.mousePosY).toDouble())}?.takeIf{kotlin.math.hypot(((displayX[it.id]?:xAt(it.time))-io.mousePosX).toDouble(),(valueY(it)-io.mousePosY).toDouble())<=10}
        if(hit!=null){rememberHistory();automationDragId=hit.id;if(!modifierActive(settings.additiveSelectionModifier,io)&&hit.id !in selectedIds){selectedIds.clear()};selectedIds+=hit.id;selected=notes.indexOf(hit);automationDragBase=selectedNotes().associate{it.id to if(automationLane==AutomationLane.VOLUME)it.volume else it.pan};automationDragStartValue=if(automationLane==AutomationLane.VOLUME)hit.volume else hit.pan;syncImGuiInspector()}
        else{laneMarqueeStart=snap(laneTimeAt(io.mousePosX)) to laneValueAt(io.mousePosY);laneMarqueeEnd=laneMarqueeStart}
      }
      automationDragId?.let{if(ImGui.isMouseDown(ImGuiMouseButton.Left)){val fraction=((bottom-io.mousePosY)/(bottom-top)).coerceIn(0f,1f);val target=if(automationLane==AutomationLane.VOLUME)(fraction*100).roundToInt()else(fraction*200-100).roundToInt();val delta=target-automationDragStartValue;selectedNotes().forEach{note->automationDragBase[note.id]?.let{base->if(automationLane==AutomationLane.VOLUME)note.volume=(base+delta).coerceIn(0,100)else note.pan=(base+delta).coerceIn(-100,100)}};syncImGuiInspector()}}
      laneMarqueeStart?.let{if(ImGui.isMouseDown(ImGuiMouseButton.Left)){laneMarqueeEnd=snap(laneTimeAt(io.mousePosX)) to laneValueAt(io.mousePosY)}}
      laneMarqueeStart?.let{s->laneMarqueeEnd?.let{e->
        val t1=minOf(s.first,e.first);val t2=maxOf(s.first,e.first);val v1=minOf(s.second,e.second);val v2=maxOf(s.second,e.second)
        val x1=xAt(t1).coerceIn(graphLeft,graphRight);val x2=xAt(t2).coerceIn(graphLeft,graphRight)
        val y1=laneY(v1).coerceIn(canvasY,canvasY+canvasHeight);val y2=laneY(v2).coerceIn(canvasY,canvasY+canvasHeight)
        draw.addRectFilled(minOf(x1,x2),minOf(y1,y2),maxOf(x1,x2),maxOf(y1,y2),ImColor.rgba(59,140,203,64))
        draw.addRect(minOf(x1,x2),minOf(y1,y2),maxOf(x1,x2),maxOf(y1,y2),ImColor.rgb(185,231,105))
      }}
      if(ImGui.isMouseReleased(ImGuiMouseButton.Left)){
        automationDragId=null;automationDragBase=emptyMap()
        laneMarqueeStart?.let{s->laneMarqueeEnd?.let{e->
          val t1=minOf(s.first,e.first);val t2=maxOf(s.first,e.first);val v1=minOf(s.second,e.second);val v2=maxOf(s.second,e.second)
          if(!modifierActive(settings.additiveSelectionModifier,io))selectedIds.clear()
          notes.asSequence().filter{(allPartsView||it.part==activePart)&&it.time in t1..t2&&laneValueOf(it) in v1..v2}.forEach{selectedIds+=it.id}
          selectedIds.firstOrNull()?.let{id->selected=notes.indexOfFirst{it.id==id}.coerceAtLeast(0);syncImGuiInspector()}
          state=t("Selected ${selectedIds.size} notes","${selectedIds.size}音を選択しました")
        }}
        laneMarqueeStart=null;laneMarqueeEnd=null
      }
      if((automationDragId!=null||laneMarqueeStart!=null)&&ImGui.isMouseDown(ImGuiMouseButton.Left)){
        edgeScrollDelta(io,graphLeft,canvasY,graphRight,canvasY+canvasHeight)?.let{(dxPx,dyPx)->
          if(dxPx!=0f){
            val maximum=(durationMs().coerceAtLeast(visibleSpan())-visibleSpan()).coerceAtLeast(0)
            horizontalOffset=(horizontalOffset+dxPx/graphWidth*visibleSpan()).roundToInt().coerceIn(0,maximum)
            followPlayback=false
          }
        }
      }
      draw.popClipRect();draw.addRect(graphLeft,canvasY,graphRight,canvasY+canvasHeight,ImColor.rgb(67,82,98))
    ImGui.end()
  }

  private fun renderImGuiSettings(io: ImGuiIO) {
    ImGui.setNextWindowPos(max(10f, io.displaySizeX - 720f), 72f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(700f, min(760f, io.displaySizeY - 90f).coerceAtLeast(520f), ImGuiCond.FirstUseEver)
    var changed = false
    val settingsVisible = ImBoolean(true)
    // The settings window never docks: it is a floating dialog. Users with a persisted
    // DockId need LAYOUT > RESTORE DEFAULT LAYOUT (or ini deletion) once to undock.
    if (ImGui.begin(windowTitle("EDITOR SETTINGS", "エディター設定"), settingsVisible, ImGuiWindowFlags.NoDocking)) {
      if(ImGui.button("${t("GENERAL", "一般")}###GENERAL")) settingsPage=SettingsPage.GENERAL
      ImGui.sameLine(); if(ImGui.button("${t("STYLE", "配色")}###STYLE")) settingsPage=SettingsPage.STYLE
      ImGui.sameLine(); if(ImGui.button("${t("LAYOUT", "レイアウト")}###LAYOUT")) settingsPage=SettingsPage.LAYOUT
      ImGui.sameLine(); if(ImGui.button("${t("KEYMAP", "キーマップ")}###KEYMAP")) settingsPage=SettingsPage.KEYMAP
      ImGui.sameLine(); if(ImGui.button("${t("SHARE", "共有")}###SHARE")) settingsPage=SettingsPage.SHARE
      ImGui.spacing()
      when(settingsPage) {
        SettingsPage.GENERAL -> {
          val scale = intArrayOf(settings.uiScalePercent)
          if (ImGui.sliderInt("${t("OMMT UI scale", "OMMT UIスケール")}###uiScale", scale, 75, 150, "%d%%")) { settings = settings.copy(uiScalePercent = (scale[0] / 5 * 5).coerceIn(75, 150)); changed = true }
          ImGui.textDisabled(t("Font-safe scale; independent from Minecraft GUI Scale", "MinecraftのGUIサイズとは独立した安全な表示倍率です"))
          val textScale = intArrayOf(settings.textScalePercent)
          if (ImGui.sliderInt("${t("Text size", "テキストサイズ")}###textScale", textScale, 75, 150, "%d%%")) { settings = settings.copy(textScalePercent = (textScale[0] / 5 * 5).coerceIn(75, 150)); changed = true }; markAsciiField()
          ImGui.textDisabled(t("Text-only size; combined with the UI scale above", "文字だけの大きさです。上のUIスケールと掛け合わせます"))
          if (ImGui.button("${t("Grid", "グリッド")}: ${settings.gridDensity}###GRID")) { cycleGridDensity(); changed = true }
          if (ImGui.checkbox("${t("Compact toolbar", "コンパクトツールバー")}###compactToolbar", settings.compactToolbar)) { settings = settings.copy(compactToolbar = !settings.compactToolbar); changed = true }
          val lead = intArrayOf(settings.followLead)
          if (ImGui.sliderInt("${t("Follow lead", "追従開始位置")}###FOLLOW_LEAD", lead, 20, 70, "%d%%")) { settings = settings.copy(followLead = lead[0].coerceIn(20,70)); changed = true }
          ImGui.spacing()
          ImGui.textWrapped(t("Use Style for live color and spacing editing, Layout for panel placement, and Keymap for all editor gestures.", "配色と余白は「配色」、パネル配置は「レイアウト」、編集操作は「キーマップ」から変更できます。"))
        }
        SettingsPage.STYLE -> if (renderStyleSettings()) changed = true
        SettingsPage.LAYOUT -> if (renderLayoutSettings(io)) changed = true
        SettingsPage.KEYMAP -> {
          capturingBinding?.let{ImGui.textColored(.73f,.91f,.41f,1f,t("Press a key combination; Backspace clears; Esc cancels", "割り当てるキーを押す / Backspaceで解除 / Escで中止"))}
          EditorAction.entries.forEach{action->ImGui.text(actionName(action));ImGui.sameLine(220f);if(ImGui.button("${settings.keymap[action].encode()}##key_${action.name}"))capturingBinding=action}
          ImGui.spacing();ImGui.text(t("Mouse wheel", "マウスホイール"))
          fun wheelRow(label:String,value:WheelAction,set:(WheelAction)->Unit){ImGui.text(label);ImGui.sameLine(220f);if(ImGui.button("${t(value.english,value.japanese)}##wheel_$label")){set(WheelAction.entries[(value.ordinal+1)%WheelAction.entries.size]);changed=true}}
          wheelRow(t("No modifier", "修飾キーなし"),settings.wheelPlain){settings=settings.copy(wheelPlain=it)}
          wheelRow("Shift",settings.wheelShift){settings=settings.copy(wheelShift=it)}
          wheelRow("Ctrl",settings.wheelControl){settings=settings.copy(wheelControl=it)}
          wheelRow("Alt",settings.wheelAlt){settings=settings.copy(wheelAlt=it)}
          fun modifierRow(label:String,value:GestureModifier,set:(GestureModifier)->Unit){ImGui.text(label);ImGui.sameLine(220f);if(ImGui.button("${t(value.english,value.japanese)}##modifier_$label")){set(GestureModifier.entries[(value.ordinal+1)%GestureModifier.entries.size]);changed=true}}
          modifierRow(t("Range selection", "範囲選択"),settings.rangeSelectionModifier){settings=settings.copy(rangeSelectionModifier=it)}
          modifierRow(t("Add to selection", "選択に追加"),settings.additiveSelectionModifier){settings=settings.copy(additiveSelectionModifier=it)}
          ImGui.text(t("Timeline pan", "タイムラインパン"));ImGui.sameLine(220f);if(ImGui.button("${t(settings.panMouseButton.english,settings.panMouseButton.japanese)}###PAN_BUTTON")){settings=settings.copy(panMouseButton=if(settings.panMouseButton==PanMouseButton.RIGHT)PanMouseButton.MIDDLE else PanMouseButton.RIGHT);changed=true}
        }
        SettingsPage.SHARE -> {
          ImGui.textWrapped(t("This page only shares settings. Edit colors on Style and arrange windows on Layout before exporting. Songs are never included.", "ここは設定の共有専用です。配色は「配色」、配置は「レイアウト」で編集してからエクスポートします。楽曲データは含まれません。"))
          // Share blobs are URL-safe Base64: drop non-ASCII at keystroke (paste still validated by decode).
          ImGui.inputTextMultiline("##OMMT_SETTINGS_TEXT", imSettingsText, -1f, 300f, ImGuiInputTextFlags.CallbackCharFilter, asciiCharFilter); markAsciiField()
          if (ImGui.button("${t("EXPORT", "エクスポート")}###EXPORT_SETTINGS")) {
            runCatching { EditorSettingsStore.exportText(settings.copy(lastTool=tool)) }.onSuccess { imSettingsText.set(it); state=t("Settings exported to text", "設定を文字列へエクスポートしました") }.onFailure { state=t("Settings export failed: ${it.message}", "設定のエクスポートに失敗しました: ${it.message}") }
          }
          ImGui.sameLine(); if (ImGui.button("${t("COPY", "コピー")}###COPY_SETTINGS")) { GLFW.glfwSetClipboardString(MinecraftClient.getInstance().window.handle, imSettingsText.get()); state=t("Settings text copied", "設定文字列をコピーしました") }
          ImGui.sameLine(); if (ImGui.button("${t("PASTE", "貼り付け")}###PASTE_SETTINGS")) { imSettingsText.set(GLFW.glfwGetClipboardString(MinecraftClient.getInstance().window.handle).orEmpty().take(1024*1024)) }
          ImGui.sameLine(); if (ImGui.button("${t("IMPORT", "インポート")}###IMPORT_SETTINGS")) {
            runCatching { EditorSettingsStore.importText(imSettingsText.get()) }.onSuccess { imported -> settings=imported; tool=imported.lastTool; applyPanelVisibility(); imguiAppliedStyle=null; automationDockInitialized=false; focusDefaultAutomation=true; ImGui.loadIniSettingsFromDisk(EditorSettingsStore.layoutPath.toString()); state=t("Settings and layout imported", "設定とレイアウトをインポートしました") }.onFailure { state=t("Settings import failed: ${it.message}", "設定のインポートに失敗しました: ${it.message}") }
          }
        }
      }
    }
    ImGui.end()
    if (!settingsVisible.get()) settingsOpen = false
    if (changed) { applyPanelVisibility(); saveSettings(); state = t("Editor settings saved", "エディター設定を保存しました") }
  }

  private fun renderStyleSettings(): Boolean {
    var changed = false
    val themeIndex = ImInt(settings.theme.ordinal)
    ImGui.setNextItemWidth(280f)
    if (ImGui.combo(t("Base style", "基本スタイル") + "###THEME", themeIndex, EditorTheme.entries.map { t(it.english,it.japanese) }.toTypedArray())) {
      val theme = EditorTheme.entries[themeIndex.get().coerceIn(0, EditorTheme.entries.lastIndex)]
      settings = settings.copy(theme = theme, style = EditorStylePresets.forTheme(theme))
      imguiAppliedStyle = null
      changed = true
    }
    ImGui.sameLine()
    val customized = settings.style != EditorStylePresets.forTheme(settings.theme)
    ImGui.textDisabled(if (customized) t("Customized", "カスタム済み") else t("Preset values", "プリセット値"))
    if (ImGui.button("${t("RESET TO BASE STYLE", "基本スタイルへ戻す")}###RESET_STYLE")) {
      settings = settings.copy(style = EditorStylePresets.forTheme(settings.theme))
      imguiAppliedStyle = null
      changed = true
    }
    ImGui.separator()

    ImGui.beginChild("##style-controls", -1f, -1f, true)
    if (ImGui.beginTabBar("##STYLE_EDITOR_TABS")) {
      if (ImGui.beginTabItem(t("COLORS", "色"))) {
        fun colorRow(english: String, japanese: String, value: Int, update: (EditorStyle, Int) -> EditorStyle) {
          val rgba = colorFloats(value)
          ImGui.text(t(english, japanese))
          ImGui.setNextItemWidth(-1f)
          if (ImGui.colorEdit4("##style_$english", rgba)) {
            settings = settings.copy(style = update(settings.style, colorFromFloats(rgba)))
            imguiAppliedStyle = null
            changed = true
          }
        }
        val style = settings.style
        colorRow("Text", "文字", style.textColor) { current, value -> current.copy(textColor = value) }
        colorRow("Disabled text", "補助文字", style.disabledTextColor) { current, value -> current.copy(disabledTextColor = value) }
        colorRow("Window background", "ウィンドウ背景", style.windowBackgroundColor) { current, value -> current.copy(windowBackgroundColor = value) }
        colorRow("Panel background", "パネル背景", style.panelBackgroundColor) { current, value -> current.copy(panelBackgroundColor = value) }
        colorRow("Popup background", "ポップアップ背景", style.popupBackgroundColor) { current, value -> current.copy(popupBackgroundColor = value) }
        colorRow("Active title", "選択中のタイトル", style.titleBackgroundColor) { current, value -> current.copy(titleBackgroundColor = value) }
        colorRow("Library header", "ライブラリ見出し", style.libraryHeaderColor) { current, value -> current.copy(libraryHeaderColor = value) }
        colorRow("Workspace header", "ワークスペース見出し", style.workspaceHeaderColor) { current, value -> current.copy(workspaceHeaderColor = value) }
        colorRow("Inspector header", "インスペクター見出し", style.inspectorHeaderColor) { current, value -> current.copy(inspectorHeaderColor = value) }
        colorRow("Borders", "境界線", style.borderColor) { current, value -> current.copy(borderColor = value) }
        colorRow("Input frame", "入力欄", style.frameColor) { current, value -> current.copy(frameColor = value) }
        colorRow("Hovered frame", "ホバー中の入力欄", style.frameHoveredColor) { current, value -> current.copy(frameHoveredColor = value) }
        colorRow("Headers", "見出し", style.headerColor) { current, value -> current.copy(headerColor = value) }
        colorRow("Buttons", "ボタン", style.buttonColor) { current, value -> current.copy(buttonColor = value) }
        colorRow("Accent", "アクセント", style.accentColor) { current, value -> current.copy(accentColor = value) }
        colorRow("Inactive title", "非選択タイトル", style.inactiveTitleBackgroundColor) { current, value -> current.copy(inactiveTitleBackgroundColor = value) }
        colorRow("Check mark", "チェックマーク", style.checkMarkColor) { current, value -> current.copy(checkMarkColor = value) }
        colorRow("Scrollbar thumb", "スクロールバー", style.scrollbarGrabColor) { current, value -> current.copy(scrollbarGrabColor = value) }
        colorRow("Hovered thumb", "ホバー中のスクロールバー", style.scrollbarGrabHoveredColor) { current, value -> current.copy(scrollbarGrabHoveredColor = value) }
        colorRow("Active thumb", "操作中のスクロールバー", style.scrollbarGrabActiveColor) { current, value -> current.copy(scrollbarGrabActiveColor = value) }
        colorRow("Docked tab", "ドックタブ", style.dockedTabColor) { current, value -> current.copy(dockedTabColor = value) }
        colorRow("Hovered tab", "ホバー中のドックタブ", style.dockedTabHoveredColor) { current, value -> current.copy(dockedTabHoveredColor = value) }
        colorRow("Active tab", "選択中のドックタブ", style.dockedTabActiveColor) { current, value -> current.copy(dockedTabActiveColor = value) }
        colorRow("Unfocused tab", "非選択のドックタブ", style.dockedTabUnfocusedColor) { current, value -> current.copy(dockedTabUnfocusedColor = value) }
        colorRow("Selected overline", "選択タブの上線", style.dockedTabSelectedOverlineColor) { current, value -> current.copy(dockedTabSelectedOverlineColor = value) }
        colorRow("Dimmed selected tab", "非フォーカス時の選択タブ", style.dockedTabDimmedSelectedColor) { current, value -> current.copy(dockedTabDimmedSelectedColor = value) }
        colorRow("Dimmed selected overline", "非フォーカス時の選択タブ上線", style.dockedTabDimmedSelectedOverlineColor) { current, value -> current.copy(dockedTabDimmedSelectedOverlineColor = value) }
        colorRow("Piano-roll background", "ピアノロール背景", style.pianoRollColor) { current, value -> current.copy(pianoRollColor = value) }
        colorRow("Grid lines", "グリッド線", style.gridColor) { current, value -> current.copy(gridColor = value) }
        colorRow("Outside vanilla range", "バニラ音域外", style.outOfRangeColor) { current, value -> current.copy(outOfRangeColor = value) }
        ImGui.endTabItem()
      }
      if (ImGui.beginTabItem(t("SIZES", "サイズ"))) {
        fun metric(english: String, japanese: String, value: Int, minimum: Int, maximum: Int, update: (EditorStyle, Int) -> EditorStyle) {
          val holder = intArrayOf(value)
          ImGui.text(t(english, japanese))
          ImGui.setNextItemWidth(-1f)
          if (ImGui.sliderInt("##metric_$english", holder, minimum, maximum)) {
            settings = settings.copy(style = update(settings.style, holder[0]).normalized())
            imguiAppliedStyle = null
            changed = true
          }
        }
        val style = settings.style
        metric("Window horizontal padding", "ウィンドウ左右余白", style.windowPaddingX, 2, 24) { current, value -> current.copy(windowPaddingX = value) }
        metric("Window vertical padding", "ウィンドウ上下余白", style.windowPaddingY, 2, 24) { current, value -> current.copy(windowPaddingY = value) }
        metric("Input horizontal padding", "入力欄左右余白", style.framePaddingX, 2, 20) { current, value -> current.copy(framePaddingX = value) }
        metric("Input vertical padding", "入力欄上下余白", style.framePaddingY, 2, 16) { current, value -> current.copy(framePaddingY = value) }
        metric("Horizontal item spacing", "項目の左右間隔", style.itemSpacingX, 1, 20) { current, value -> current.copy(itemSpacingX = value) }
        metric("Vertical item spacing", "項目の上下間隔", style.itemSpacingY, 1, 20) { current, value -> current.copy(itemSpacingY = value) }
        metric("Corner rounding", "角の丸み", style.rounding, 0, 12) { current, value -> current.copy(rounding = value) }
        metric("Scrollbar width", "スクロールバー幅", style.scrollbarSize, 8, 24) { current, value -> current.copy(scrollbarSize = value) }
        metric("Border width", "境界線の幅", style.borderSize, 0, 2) { current, value -> current.copy(borderSize = value) }
        ImGui.endTabItem()
      }
      ImGui.endTabBar()
    }
    ImGui.endChild()
    return changed
  }

  private fun renderLayoutSettings(io: ImGuiIO): Boolean {
    var changed = false
    if (ImGui.checkbox("${t("MIDI Library", "MIDIライブラリ")}###showLibrary", settings.showLibrary)) { settings = settings.copy(showLibrary = !settings.showLibrary); changed = true }
    if (ImGui.checkbox("${t("Note Inspector", "ノートインスペクター")}###showInspector", settings.showInspector)) { settings = settings.copy(showInspector = !settings.showInspector); changed = true }
    if (ImGui.checkbox("${t("Bottom editor panels", "下部編集パネル")}###showAutomation", settings.showAutomation)) { settings = settings.copy(showAutomation = !settings.showAutomation); changed = true; focusDefaultAutomation = true }
    if (ImGui.checkbox("${t("Ghost other parts", "他パートを半透明表示")}###showOtherParts", settings.showOtherParts)) { settings = settings.copy(showOtherParts = !settings.showOtherParts); changed = true }
    ImGui.separator()
    ImGui.textWrapped(t("Move a window by dragging its title. Drag a window onto another title to create tabs, or onto a docking guide to split the workspace. Resize from window edges. The layout is saved independently from Minecraft GUI Scale.", "タイトルをドラッグしてウィンドウを移動できます。別タイトルへ重ねるとタブ化し、ドッキングガイドへ重ねると画面を分割できます。端をドラッグするとサイズを変更できます。配置はMinecraftのGUIサイズとは別に保存されます。"))
    if (ImGui.button("${t("SAVE CURRENT LAYOUT", "現在の配置を保存")}###SAVE_LAYOUT")) {
      ImGui.saveIniSettingsToDisk(EditorSettingsStore.layoutPath.toString())
      state = t("Current window layout saved", "現在のウィンドウ配置を保存しました")
    }
    ImGui.sameLine()
    if (ImGui.button("${t("RESTORE DEFAULT LAYOUT", "標準配置に戻す")}###RESET_LAYOUT")) resetImGuiLayout(io)
    return changed
  }

  private fun resetImGuiLayout(io: ImGuiIO) {
    val left = if (settings.showLibrary) max(220f, io.displaySizeX * .16f) else 0f
    val right = if (settings.showInspector) 350f else 0f
    val bottom = if (settings.showAutomation) (io.displaySizeY * .30f).coerceIn(240f, 420f) else 0f
    ImGui.setWindowPos(windowTitle("OMMT - MIDI WORKSPACE", "OMMT - MIDIワークスペース", "OMMT  •  MIDI WORKSPACE"), 0f, 0f)
    ImGui.setWindowSize(windowTitle("OMMT - MIDI WORKSPACE", "OMMT - MIDIワークスペース", "OMMT  •  MIDI WORKSPACE"), io.displaySizeX, 112f)
    ImGui.setWindowPos(windowTitle("OMMT LIBRARY", "OMMTライブラリ", "MIDI LIBRARY"), 0f, 118f)
    ImGui.setWindowSize(windowTitle("OMMT LIBRARY", "OMMTライブラリ", "MIDI LIBRARY"), max(220f, io.displaySizeX * .16f), max(260f, io.displaySizeY - 118f))
    ImGui.setWindowPos(windowTitle("NOTE INSPECTOR", "ノートインスペクター"), io.displaySizeX - 350f, 118f)
    ImGui.setWindowSize(windowTitle("NOTE INSPECTOR", "ノートインスペクター"), 350f, max(260f, io.displaySizeY - 118f))
    ImGui.setWindowPos(windowTitle("PIANO ROLL", "ピアノロール"), left, 118f)
    ImGui.setWindowSize(windowTitle("PIANO ROLL", "ピアノロール"), max(420f, io.displaySizeX - left - right), max(260f, io.displaySizeY - 118f - bottom))
    val dockId = ImGui.getID("##OMMT_BOTTOM_AUTOMATION_DOCK")
    if (ImGuiInternal.dockBuilderGetNode(dockId) != null) ImGuiInternal.dockBuilderRemoveNode(dockId)
    automationDockInitialized = false
    focusDefaultAutomation = true
    ImGui.saveIniSettingsToDisk(EditorSettingsStore.layoutPath.toString())
    state = t("Default window layout restored", "標準のウィンドウ配置へ戻しました")
  }

  private fun drawSettings(context: DrawContext) {
    val left=settingsLeft(); val top=38; context.fill(left,top,width-12,top+192,0xEE171C25.toInt()); context.fill(left,top,width-12,top+1,0xFFB9E769.toInt())
    context.drawTextWithShadow(textRenderer,"EDITOR SETTINGS",left+10,top+10,0xFFEAF0F8.toInt())
    val rows = listOf(
      "Library: ${if (settings.showLibrary) "shown" else "hidden"}",
      "Inspector: ${if (settings.showInspector) "shown" else "hidden"}",
      "Automation: ${if (settings.showAutomation) "shown" else "hidden"}",
      "Other parts: ${if (settings.showOtherParts) "shown" else "hidden"}",
      "Grid density: ${settings.gridDensity}",
      "Compact toolbar: ${if (settings.compactToolbar) "on" else "off"}",
      "Follow lead: ${settings.followLead}%",
      "Tool: $tool"
    )
    rows.forEachIndexed { index, text -> context.drawTextWithShadow(textRenderer, text, left+10, top+38+index*20, 0xFFBFC7D5.toInt()) }
    context.drawTextWithShadow(textRenderer,"Click a row to change it. O key: Minecraft Controls.",left+10,top+178,0xFF778295.toInt())
  }

  private fun inRect(x: Int, y: Int, left: Int, top: Int, rectWidth: Int, rectHeight: Int) = x in left until left + rectWidth && y in top until top + rectHeight
  private fun drawChrome(context: DrawContext) {
    context.fill(0, 0, width, height, 0xFF10131A.toInt())
    context.fill(0, 0, width, 32, 0xFF181D27.toInt())
    context.fill(0, 32, libraryWidth(), height, 0xFF171C25.toInt())
    context.fill(libraryWidth(), 32, libraryWidth() + 1, height, 0xFF3A4352.toInt())
    context.fill(editorLeft(), 32, width - 12, 92, 0xFF171C25.toInt())
    if (settings.showInspector) context.fill(editorLeft(), 100, width - 12, 158, 0xFF171C25.toInt())
    context.fill(editorLeft(), 164, width - 12, 198, 0xFF151A22.toInt())
    drawInputFrame(context, editorLeft() + 16, 58, 190, 22)
    drawInputFrame(context, editorLeft() + 218, 58, 62, 22)
    if (settings.showInspector) {
      drawInputFrame(context, editorLeft() + 16, 128, 66, 22)
      drawInputFrame(context, editorLeft() + 90, 128, 66, 22)
      drawInputFrame(context, editorLeft() + 164, 128, 48, 22)
      drawInputFrame(context, editorLeft() + 220, 128, 48, 22)
      drawInputFrame(context, editorLeft() + 276, 128, 48, 22)
      drawInputFrame(context, editorLeft() + 332, 128, 52, 22)
    }
    drawControl(context, editorLeft() + 296, 58, 108, 22, "", 0xFFB9E769.toInt())
    if (settings.showInspector) {
      drawControl(context, editorLeft() + 396, 128, 58, 22, "APPLY", 0xFF3B526A.toInt())
      drawControl(context, editorLeft() + 460, 128, 42, 22, "+", 0xFF293241.toInt())
      drawControl(context, editorLeft() + 508, 128, 60, 22, "DELETE", 0xFF3A2631.toInt())
      drawControl(context, editorLeft() + 574, 128, 70, 22, "PREVIEW", 0xFF293241.toInt())
    }
    drawControl(context, editorLeft() + 16, 168, 42, 24, "|◀", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 64, 168, 62, 24, if (playing) "PAUSE" else "PLAY", 0xFF4667A8.toInt())
    drawControl(context, editorLeft() + 132, 168, 70, 24, if (followPlayback) "FOLLOW ✓" else "FOLLOW", if (followPlayback) 0xFF40552C.toInt() else 0xFF293241.toInt())
    drawControl(context, editorLeft() + 208, 168, 46, 24, "FIT", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 260, 168, 30, 24, "−", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 296, 168, 30, 24, "+", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 334, 168, 48, 24, if (snapDivisor == 0) "SNAP OFF" else "SNAP 1/$snapDivisor", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 388, 168, 78, 24, "NEW PART", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 472, 168, 70, 24, "PART ${activePart + 1}", 0xFF293241.toInt())
    if (settings.showLibrary) {
      drawControl(context, 12, height - 62, (libraryWidth() - 30) / 2, 26, "REFRESH", 0xFF293241.toInt())
      drawControl(context, 18 + (libraryWidth() - 30) / 2, height - 62, (libraryWidth() - 30) / 2, 26, "OPEN FOLDER", 0xFF3B526A.toInt())
      context.drawTextWithShadow(textRenderer, "OMMT/midi", 14, height - 25, 0xFF778295.toInt())
    }
    drawControl(context, width - 172, height - 54, 160, 34, "", 0xFFB9E769.toInt())
    val rows = ((height - 158) / 23).coerceAtLeast(1)
    if (settings.showLibrary) midiFiles.drop(libraryScroll).take(rows).forEachIndexed { local, path ->
      val y = 72 + local * 23; val selected = path == selectedMidi
      context.fill(12, y, libraryWidth() - 12, y + 20, if (selected) 0xFF34465A.toInt() else 0xFF202734.toInt())
      context.drawTextWithShadow(textRenderer, ellipsize(path.fileName.toString(), libraryWidth() - 46), 19, y + 6, if (selected) 0xFFB9E769.toInt() else 0xFFD8DEE8.toInt())
    }
    if (settings.showLibrary && midiFiles.isEmpty()) {
      context.drawTextWithShadow(textRenderer, "No MIDI files yet", 18, 76, 0xFF8D98A9.toInt())
      context.drawTextWithShadow(textRenderer, "Place .mid/.midi files in:", 18, 94, 0xFF8D98A9.toInt())
      context.drawTextWithShadow(textRenderer, "OMMT/midi", 18, 108, 0xFFB9E769.toInt())
    }
  }

  private fun drawInputFrame(context: DrawContext, x: Int, y: Int, fieldWidth: Int, fieldHeight: Int) {
    context.fill(x, y, x + fieldWidth, y + fieldHeight, 0xFF0D1118.toInt())
    context.fill(x, y, x + fieldWidth, y + 1, 0xFF4B5668.toInt())
    context.fill(x, y + fieldHeight - 1, x + fieldWidth, y + fieldHeight, 0xFF303A49.toInt())
  }

  private fun ellipsize(value: String, maximumWidth: Int): String {
    if (textRenderer.getWidth(value) <= maximumWidth) return value
    val suffix = "…"; val builder = StringBuilder()
    for (character in value) {
      if (textRenderer.getWidth(builder.toString() + character + suffix) > maximumWidth) break
      builder.append(character)
    }
    return builder.toString() + suffix
  }

  private fun drawControl(context: DrawContext, x: Int, y: Int, controlWidth: Int, controlHeight: Int, label: String, color: Int) {
    context.fill(x, y, x + controlWidth, y + controlHeight, color)
    context.fill(x, y, x + controlWidth, y + 1, 0xFF65758A.toInt())
    val rendered = if (settings.compactToolbar) when (label) { "DELETE" -> "DEL"; "PREVIEW" -> "▶"; "FOLLOW ✓" -> "F✓"; "FOLLOW" -> "F"; "PAUSE" -> "Ⅱ"; "PLAY" -> "▶"; "NEW PART" -> "+P"; else -> label } else label
    if (rendered.isNotBlank()) context.drawCenteredTextWithShadow(textRenderer, EditorLayout(width,height).visible(rendered), x + controlWidth / 2, y + (controlHeight - 8) / 2, 0xFFEAF0F8.toInt())
  }

  private fun drawPianoRoll(context: DrawContext) {
    val top = rollTop(); val notesTop = noteTop(); val bottom = rollBottom(); val left = keyboardLeft(); val plotLeft = plotLeft(); val right = plotRight()
    context.fill(left, top, right, bottom, 0xA0101218.toInt())
    context.fill(left, top, plotLeft, notesTop, 0xFF202631.toInt())
    context.drawCenteredTextWithShadow(textRenderer, "KEY", left + (plotLeft - left) / 2, top + 8, 0xFF778295.toInt())
    val blackKeys = setOf(1, 3, 6, 8, 10)
    for (pitch in pitchMin..(pitchMin + visiblePitchCount - 1).coerceAtMost(24)) {
      val y = pitchToY(pitch); val nextY = pitchToY(pitch - 1).coerceAtMost(bottom); val midiClass = (pitch + 54) % 12; val black = midiClass in blackKeys
      context.fill(plotLeft, y, right, nextY, if (black) 0xFF121720.toInt() else 0xFF171C25.toInt())
      context.fill(left, y, plotLeft, nextY, if (black) 0xFF252B35.toInt() else 0xFFE1E5E8.toInt())
      if (black) context.fill(left, y, left + 26, nextY, 0xFF242A34.toInt())
      context.fill(plotLeft, y, right, y + 1, 0xFF2D3440.toInt())
      if (midiClass == 0 || midiClass == 6) context.fill(plotLeft, nextY - 1, right, nextY, 0xFF607842.toInt())
      if (midiClass == 0) context.drawTextWithShadow(textRenderer, "C${(pitch + 54) / 12 - 1}", left + 3, y + 2, if (black) 0xFFDAF2AB.toInt() else 0xFF30343A.toInt())
    }
    val span = visibleSpan()
    context.fill(plotLeft, top, right, notesTop, 0xFF1D232D.toInt())
    var previousLabelRight = plotLeft
    gridMarks.asSequence().filter { it.timeMs in horizontalOffset..horizontalOffset + span }.forEach { mark ->
      val x = timeToX(mark.timeMs); val color = if (mark.isBar) 0xFF515C6D.toInt() else if (mark.isBeat) 0xFF3D4858.toInt() else 0xFF303744.toInt()
      val next = gridMarks.firstOrNull { it.tick > mark.tick }?.timeMs ?: mark.timeMs + 1
      val spacing = (timeToX(next) - x).coerceAtLeast(1)
      val density = when (settings.gridDensity) { "SPARSE" -> 1.7; "DENSE" -> .6; "NORMAL" -> 1.0; else -> 1.0 }
      val draw = mark.isBar || (mark.isBeat && spacing >= (10*density)) || (!mark.isBeat && spacing >= (7*density))
      if (draw && (mark.isBar || spacing >= 4)) context.fill(x, top, x + 1, bottom, color)
      if ((mark.isBar || (mark.isBeat && spacing >= (10*density))) && x >= previousLabelRight) { val label=formatRuler(mark); context.drawTextWithShadow(textRenderer,label,x+3,top+8,0xFFAAB3C2.toInt()); previousLabelRight=x+3+textRenderer.getWidth(label)+8 }
    }
    val visiblePitches = pitchMin..(pitchMin + visiblePitchCount - 1).coerceAtMost(24)
    notes.forEach { note -> if (note.pitch in visiblePitches && note.time + note.duration >= horizontalOffset && note.time <= horizontalOffset + span) {
      val startX = timeToX(note.time).coerceAtLeast(plotLeft); val endX = timeToX(note.time + note.duration).coerceAtMost(right).coerceAtLeast(startX + 5)
      val y = pitchToY(note.pitch); val height = rowHeight().roundToInt().coerceAtLeast(4); val color = if (note.id in selectedIds) 0xFFB9E769.toInt() else if (note.part == activePart) instrumentColor(note.instrument) else 0x664B5668
      context.fill(startX, y + 2, endX, (y + height - 1).coerceAtMost(bottom), color)
      if (note.id in selectedIds) { context.fill(startX, y + 1, endX, y + 2, 0xFFFFFFFF.toInt()); context.fill(startX, y + height - 1, endX, y + height, 0xFF718D3F.toInt()) }
    } }
    selectionStart?.let { start -> selectionEnd?.let { end ->
      val x1 = timeToX(start.first).coerceIn(plotLeft, right); val x2 = timeToX(end.first).coerceIn(plotLeft, right)
      val y1 = pitchToY(start.second).coerceIn(noteTop(), bottom); val y2 = pitchToY(end.second).coerceIn(noteTop(), bottom)
      val leftSelection = minOf(x1, x2); val rightSelection = maxOf(x1, x2); val topSelection = minOf(y1, y2); val bottomSelection = maxOf(y1, y2) + rowHeight().roundToInt()
      context.fill(leftSelection, topSelection, rightSelection + 1, bottomSelection, 0x443B8CCB)
      context.fill(leftSelection, topSelection, rightSelection + 1, topSelection + 1, 0xFFB9E769.toInt())
      context.fill(leftSelection, bottomSelection - 1, rightSelection + 1, bottomSelection, 0xFFB9E769.toInt())
      context.fill(leftSelection, topSelection, leftSelection + 1, bottomSelection, 0xFFB9E769.toInt())
      context.fill(rightSelection, topSelection, rightSelection + 1, bottomSelection, 0xFFB9E769.toInt())
    } }
    val visualHead = visualPlayheadMs.roundToInt()
    if (visualHead in horizontalOffset..horizontalOffset + span) { val x = timeToX(visualHead); context.fill(x, top, x + 2, bottom, 0xFFB9E769.toInt()); context.fill(x - 4, top, x + 6, top + 4, 0xFFB9E769.toInt()) }
    context.fill(left, top, right, top + 1, 0xFF5C6473.toInt())
    context.fill(left, bottom - 1, right, bottom, 0xFF5C6473.toInt())
    context.fill(left, top, left + 1, bottom, 0xFF5C6473.toInt())
    context.fill(right - 1, top, right, bottom, 0xFF5C6473.toInt())
  }

  private fun formatRuler(mark: GridMark): String = if (mark.isBar) "${mark.bar}" else "${mark.bar}.${mark.beat}"

  private fun drawAutomation(context: DrawContext) {
    val labels = arrayOf("VOLUME", "PAN")
    for (lane in 0..1) {
      val top = laneTop(lane); context.fill(plotLeft(), top, plotRight(), top + laneHeight() - 2, 0xFF171C25.toInt()); context.fill(plotLeft(), top + laneHeight() - 2, plotRight(), top + laneHeight() - 1, 0xFF2A3340.toInt())
      context.drawTextWithShadow(textRenderer, labels[lane], keyboardLeft(), top + 3, 0xFF8D98A9.toInt())
      selectedNotes().forEach { note ->
        val value = if (lane == 0) note.volume else (note.pan + 100) / 2
        val x = timeToX(note.time).coerceIn(plotLeft(), plotRight() - 2); val y = top + laneHeight() - 3 - (value * (laneHeight() - 4) / 100)
        context.fill(x, y, x + 2, top + laneHeight() - 2, 0xFFB9E769.toInt())
      }
    }
    context.drawTextWithShadow(textRenderer, "Volume = OYMI volume", keyboardLeft(), laneTop(1) + laneHeight() + 1, 0xFF778295.toInt())
  }

  private fun drawScrollbars(context: DrawContext) {
    val track = 0xFF2A3340.toInt(); val thumb = 0xFF65758A.toInt()
    // Roll and lanes share horizontalOffset/visibleSpan, so this is the single horizontal thumb.
    val total = (durationMs() + 1).coerceAtLeast(visibleSpan()); val trackWidth = plotRight() - plotLeft()
    val thumbWidth = (trackWidth.toLong() * visibleSpan() / total).toInt().coerceIn(18, trackWidth)
    val thumbX = plotLeft() + ((trackWidth - thumbWidth).toLong() * horizontalOffset / (total - visibleSpan()).coerceAtLeast(1)).toInt()
    context.fill(plotLeft(), rollBottom() - 6, plotRight(), rollBottom(), track); context.fill(thumbX, rollBottom() - 6, thumbX + thumbWidth, rollBottom(), thumb)
    val verticalHeight = rollBottom() - noteTop(); val visible = visiblePitchCount; val vThumb = (verticalHeight * visible / 25).coerceAtLeast(12)
    val vY = noteTop() + ((verticalHeight - vThumb) * pitchMin / (25 - visible).coerceAtLeast(1))
    context.fill(plotRight() - 6, noteTop(), plotRight(), rollBottom(), track); context.fill(plotRight() - 6, vY, plotRight(), vY + vThumb, thumb)
  }

  private data class ContextEntry(val label: String, val part: Int? = null, val command: String? = null)
  private fun contextEntries(): List<ContextEntry> {
    val start = contextPartOffset.coerceIn(0, ((parts.size - 1) / 5) * 5)
    return buildList {
      parts.drop(start).take(5).forEachIndexed { offset, name -> add(ContextEntry("PART ${start + offset + 1}: $name", part = start + offset)) }
      if (start > 0) add(ContextEntry("‹ PREVIOUS PARTS", command = "previous"))
      if (start + 5 < parts.size) add(ContextEntry("NEXT PARTS ›", command = "next"))
      add(ContextEntry("NEW PART", command = "new")); add(ContextEntry("DUPLICATE", command = "duplicate")); add(ContextEntry("DELETE", command = "delete"))
    }
  }
  private fun drawContextMenu(context: DrawContext) {
    val noteId = contextNoteId ?: return; if (notes.none { it.id == noteId }) { contextNoteId = null; return }
    val entries = contextEntries(); val rows = entries.size; val menuWidth = 142; val left = contextX.coerceIn(plotLeft(), width - menuWidth - 4); val top = contextY.coerceIn(noteTop(), height - rows * 18 - 4)
    context.fill(left, top, left + menuWidth, top + rows * 18, 0xFF171C25.toInt()); context.fill(left, top, left + menuWidth, top + 1, 0xFFB9E769.toInt())
    entries.forEachIndexed { index, entry -> context.drawTextWithShadow(textRenderer, ellipsize(entry.label, menuWidth - 8), left + 4, top + 5 + index * 18, 0xFFEAF0F8.toInt()) }
  }

  override fun removed() {
    try {
      // Closing must remain safe even if an unfinished inspector value cannot be committed.
      runCatching { applySelected() }
      pausePlayback()
      editorSession.save(currentSnapshot())
      EditorSettingsStore.save(settings.copy(lastTool = tool))
      runCatching { ImGui.getIO().clearEventsQueue(); ImGui.getIO().clearInputKeys(); ImGui.getIO().clearInputMouse() }
      runCatching { Minecraft.getInstance().textInputManager().stopTextInput() }
    } finally {
      resetNativeCursor()
      super.removed()
    }
  }

  private fun instrumentColor(instrument: Int): Int = intArrayOf(0xFF79C7FF.toInt(), 0xFF74C69D.toInt(), 0xFFFF9B71.toInt(), 0xFFF7C66B.toInt(), 0xFFD8D8D8.toInt(), 0xFF98C1D9.toInt(), 0xFFFFD166.toInt(), 0xFFE9A66F.toInt(), 0xFFA8DADC.toInt(), 0xFFF4A261.toInt(), 0xFFB8C0CC.toInt(), 0xFFC5A46D.toInt(), 0xFFD97745.toInt(), 0xFF66D9A6.toInt(), 0xFFE9C46A.toInt(), 0xFFF6E58D.toInt())[instrument.coerceIn(0, 15)]
  override fun shouldPause() = false
}
