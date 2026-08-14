package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import java.nio.file.Files
import java.nio.file.Path
import cn.enaium.fabric.imgui.ImGuiRenderable
import imgui.ImColor
import imgui.ImDrawList
import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImInt
import imgui.type.ImString
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import com.github.sahyuya.oyasaimusicmiditranslator.NoteBlockPitch

/**
 * Local, client-only MIDI editor. The stored notes deliberately use the same stable instrument IDs
 * and limits as OYMI v1, so exporting does not depend on a server round trip.
 */
class OyasaiEditorScreen(private val editorSession: EditorSession = EditorSession) : Screen(Text.literal("OMMT MIDI editor")), ImGuiRenderable {
  private data class ChannelState(var program: Int = 0, var volume: Int = 127, var expression: Int = 127, var pan: Int = 64)
  private data class TimedEvent(val tick: Long, val track: Int, val order: Int, val event: MidiEvent)
  private enum class AutomationLane { VOLUME, PAN }
  private enum class SettingsPage { GENERAL, KEYMAP }

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
  private var signatureMarks = listOf(SignatureMark(0, 4, 4))
  private var gridMarks = listOf(GridMark(0, 0, 1, 1, 0, true, true))
  private var snapDivisor = 4
  private var selectionStart: Pair<Int, Int>? = null
  private var selectionEnd: Pair<Int, Int>? = null
  private var draggingNotes = false
  private var dragOriginTime = 0
  private var dragOriginPitch = 0
  private var dragMouseTime = 0
  private var dragMousePitch = 0
  private var dragBase = emptyMap<Long, Pair<Int, Int>>()
  private var noteDragArmed = false
  private var noteDragStartX = 0f
  private var noteDragStartY = 0f
  private val history get() = EditorSession.history
  private var panOriginX = 0
  private var panning = false
  private var horizontalScrollbar = false
  private var verticalScrollbar = false
  private var contextNoteId: Long? = null
  private var contextX = 0
  private var contextY = 0
  private var contextPartOffset = 0
  private var songTitle = "Untitled song"
  private var bpm = 120
  private var horizontalOffset = 0
  private var viewSpanMs = 30_000
  private var playheadMs = 0
  private var playbackStartMs = 0
  private var playbackStartedAt = 0L
  private var visualPlayheadMs = 0f
  private var lastVisualRenderNanos = 0L
  private var nextPlaybackIndex = 0
  private var playing = false
  private var followPlayback = true
  private var state = "Select a MIDI file from the library"
  private var settings = EditorSettingsStore.load()
  private var tool = settings.lastTool
  private var settingsOpen = false
  private var settingsPage = SettingsPage.GENERAL
  private var capturingBinding: EditorAction? = null
  private var automationLane = AutomationLane.VOLUME
  private var automationDragId: Long? = null
  /** A lane change is a gesture, just like a note drag: retain one undo state, not one per sample. */
  private var laneGesture = false
  private val midiDirectory: Path by lazy { MinecraftClient.getInstance().runDirectory.toPath().resolve("OMMT").resolve("midi") }
  private var midiFiles: List<Path> = emptyList()
  private var selectedMidi: Path? = null
  private var libraryScroll = 0
  private lateinit var titleField: TextFieldWidget
  private lateinit var bpmField: TextFieldWidget
  private lateinit var timeField: TextFieldWidget
  private lateinit var durationField: TextFieldWidget
  private lateinit var instrumentField: TextFieldWidget
  private lateinit var pitchField: TextFieldWidget
  private lateinit var volumeField: TextFieldWidget
  private lateinit var panField: TextFieldWidget
  private val imTitle = ImString(121)
  private val imBpm = ImInt(120)
  private val imTime = ImInt()
  private val imDuration = ImInt()
  private val imInstrument = ImInt()
  private val imPitch = ImInt()
  private val imVolume = ImInt()
  private val imPan = ImInt()
  private var imguiConfigured = false
  private var imguiAppliedScale = 1f
  private var imguiRightPanning = false
  private var imguiPanStartOffset = 0
  private var imguiPanStartX = 0f
  private var externalUiActive = true
  private data class KeyModifiers(val control: Boolean, val shift: Boolean, val alt: Boolean)

  private val japanese get() = MinecraftClient.getInstance().languageManager.language.lowercase().startsWith("ja_")
  private fun t(english: String, japaneseText: String) = if (japanese) japaneseText else english
  private fun windowTitle(english: String, japaneseText: String) = "${t(english, japaneseText)}###$english"
  private fun actionName(action: EditorAction) = t(action.english, action.japanese)


  override fun init() {
    ensureDirectories(); refreshMidiLibrary()
    if (state == "Select a MIDI file from the library") state = t("Select a MIDI file from the library", "ライブラリからMIDIファイルを選択してください")
    UploadClient.setEncodingPreference(settings.uploadEncoding)
    val restored = editorSession.restore()
    restored?.let { saved ->
      notes.clear(); notes += saved.notes.map { it.copy() }; selectedIds.clear(); selectedIds += saved.selectedIds
      selected = saved.selected; songTitle = saved.title; bpm = saved.bpm; horizontalOffset = saved.offset; viewSpanMs = saved.span
      activePart = saved.part; allPartsView = saved.allPartsView; parts.clear(); parts += saved.parts; ppq = saved.ppq; beatsPerBar = saved.beats; beatUnit = saved.unit; pitchMin = saved.pitchMin; visiblePitchCount = saved.visiblePitches; snapDivisor = saved.snapDivisor; followPlayback = saved.followPlayback; playheadMs = saved.playheadMs; visualPlayheadMs = playheadMs.toFloat(); tempoMarks = saved.tempos; signatureMarks = saved.signatures; gridMarks = saved.grid
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
    // The old widgets remain as a validated value bridge while the new external UI is active.
    // They must never render or retain focus behind ImGui windows.
    allLegacyFields().forEach { it.visible = false; it.setFocused(false) }
    setFocused(null)
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
    imTime.set(note.time); imDuration.set(note.duration); imInstrument.set(note.instrument)
    imPitch.set(note.pitch); imVolume.set(note.volume); imPan.set(note.pan)
  }

  private fun copyImGuiValuesToValidatedFields() {
    titleField.text = imTitle.get(); bpmField.text = imBpm.get().toString()
    timeField.text = imTime.get().toString(); durationField.text = imDuration.get().toString()
    instrumentField.text = imInstrument.get().toString(); pitchField.text = imPitch.get().toString()
    volumeField.text = imVolume.get().toString(); panField.text = imPan.get().toString()
  }

  private fun choose(index: Int, replaceSelection: Boolean = true) {
    selected = index.coerceIn(0, (notes.size - 1).coerceAtLeast(0))
    notes.getOrNull(selected)?.let { note ->
      if (replaceSelection) { selectedIds.clear(); selectedIds += note.id }
      timeField.text = note.time.toString(); durationField.text = note.duration.toString(); instrumentField.text = note.instrument.toString()
      pitchField.text = note.pitch.toString(); volumeField.text = note.volume.toString(); panField.text = note.pan.toString()
    }
    keepSelectedVisible()
    syncImGuiInspector()
  }

  private fun keepSelectedVisible() {
    val time = notes.getOrNull(selected)?.time ?: return
    val span = visibleSpan()
    if (time < horizontalOffset) horizontalOffset = time
    if (time > horizontalOffset + span) horizontalOffset = (time - span / 2).coerceAtLeast(0)
  }

  private fun applySelected() {
    if (::titleField.isInitialized) copyImGuiValuesToValidatedFields()
    val note = notes.getOrNull(selected) ?: return
    val before = currentHistory()
    note.time = timeField.text.toIntOrNull()?.coerceAtLeast(0) ?: note.time
    note.duration = durationField.text.toIntOrNull()?.coerceIn(1, 60_000) ?: note.duration
    note.instrument = instrumentField.text.toIntOrNull()?.coerceIn(0, 15) ?: note.instrument
    note.pitch = pitchField.text.toIntOrNull()?.coerceIn(NoteBlockPitch.DISPLAY_MIN, NoteBlockPitch.DISPLAY_MAX) ?: note.pitch
    note.volume = volumeField.text.toIntOrNull()?.coerceIn(0, 100) ?: note.volume
    note.pan = panField.text.toIntOrNull()?.coerceIn(-100, 100) ?: note.pan
    songTitle = titleField.text.trim().take(120).ifBlank { "Untitled song" }
    bpm = bpmField.text.toIntOrNull()?.coerceIn(1, 60_000) ?: bpm
    sortNotesAndResolvePrimary(note.id)
    if (before != currentHistory()) history.push(before)
    choose(selected, replaceSelection = false); state = t("Edited note ${selected + 1}/${notes.size}", "ノート ${selected + 1}/${notes.size} を編集しました")
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
      val sequence = Files.newInputStream(input).use(MidiSystem::getSequence)
      require(sequence.divisionType == Sequence.PPQ) { "SMPTE MIDI is unsupported; export as PPQ MIDI" }
      notes.clear(); notes += parseSequence(sequence)
      require(notes.size <= 1_000_000) { "This MIDI has more than 1,000,000 playable notes" }
      notes.sortWith(compareBy<EditorNote> { it.time }.thenBy { it.id })
      EditorSession.replace(); parts.clear(); parts += "Part 1"; activePart = 0; allPartsView = true; contextPartOffset = 0; selectedIds.clear(); fitPitchRange(); bpm = 120; beatsPerBar = 4; beatUnit = 4; snapDivisor = 4; playheadMs = 0; visualPlayheadMs = 0f; horizontalOffset = 0
      ppq = sequence.resolution
      val timing = buildTiming(sequence, notes.maxOfOrNull { it.time + it.duration } ?: 0)
      tempoMarks = timing.first; signatureMarks = timing.second; gridMarks = timing.third
      bpm = (60_000_000 / tempoMarks.first().microsPerQuarter).coerceIn(1, 60_000)
      beatsPerBar = signatureMarks.first().numerator; beatUnit = signatureMarks.first().denominator
      songTitle = midiTitle(sequence).ifBlank { input.fileName.toString().substringBeforeLast('.') }.take(120)
      titleField.text = songTitle; bpmField.text = bpm.toString(); selectedIds.clear(); choose(0); rewind(); fitTimeline()
      syncImGuiProject(); syncImGuiInspector()
      state = t("Loaded ${notes.size} notes; source pitch is shown and playback is octave-folded", "${notes.size}音を読み込みました。元の音高を表示し、再生時だけ音域内へ折り返します")
    } catch (error: Exception) { state = t("MIDI import failed: ${error.message ?: "invalid file"}", "MIDIの読み込みに失敗しました: ${error.message ?: "不正なファイル"}") }
  }

  private fun midiTitle(sequence: Sequence): String = sequence.tracks.asSequence().flatMap { track ->
    (0 until track.size()).asSequence().map { track.get(it).message }
  }.filterIsInstance<MetaMessage>().firstOrNull { it.type == 0x03 }?.data?.toString(Charsets.UTF_8)?.trim().orEmpty()

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
      // Calculate every tick directly from the signature segment origin. Repeatedly adding
      // floor(PPQ/8) drifts for PPQ values such as 100; round(k * PPQ / 8) does not.
      fun roundedRatio(index: Long, numerator: Long, denominator: Long): Long =
          (index * numerator + denominator / 2) / denominator
      val subdivisionOffsets = linkedSetOf<Long>()
      var subdivisionIndex = 0L
      while (true) {
        val offset = roundedRatio(subdivisionIndex, sequence.resolution.toLong(), 8)
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
      segmentTicks.filter { it <= endTick }.sorted().forEach { gridTick ->
        val offset = gridTick - signature.tick; val isBar = offset in barOffsets; val isBeat = offset in beatOffsets
        if (isBar && gridTick != signature.tick) bar++
        val currentBeat = (beatOffsets.count { it <= offset } - 1).coerceAtLeast(0)
        val beat = currentBeat % signature.numerator + 1
        val subdivision = (subdivisionOffsets.count { it <= offset } - 1).coerceAtLeast(0) % 8
        grid += GridMark(gridTick, timeAt(gridTick), bar, beat, subdivision, isBar, isBeat)
      }
      // A signature boundary is always a major grid point, even when PPQ is not divisible by 4.
      if (grid.lastOrNull()?.tick != endTick && endTick == songEndTick) grid += GridMark(endTick, timeAt(endTick), bar, signature.numerator, 0, false, true)
      if (index + 1 < signatures.size) bar++
    }
    // Unfinished MIDI notes can extend the audible song past a sparse sequence tick length.
    var finalTick = songEndTick
    while (grid.lastOrNull()?.timeMs ?: 0 < lastNoteMs) {
      finalTick += (sequence.resolution / 8).coerceAtLeast(1); grid += GridMark(finalTick, timeAt(finalTick), bar, 1, 0, false, false)
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
    val converted = mutableListOf<EditorNote>()
    val active = mutableMapOf<String, ArrayDeque<EditorNote>>()
    events.forEach { timed ->
      val message = timed.event.message as? ShortMessage ?: return@forEach
      val state = states[timed.track][message.channel]
      when (message.command) {
        ShortMessage.PROGRAM_CHANGE -> state.program = message.data1
        ShortMessage.CONTROL_CHANGE -> when (message.data1) { 7 -> state.volume = message.data2; 10 -> state.pan = message.data2; 11 -> state.expression = message.data2; 121 -> { state.volume = 127; state.expression = 127; state.pan = 64 } }
        ShortMessage.NOTE_ON -> if (message.data2 > 0) {
          val note = convertedNote(millisecondsAt(timed.tick), message, state)
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
    if (queue.isEmpty()) active.remove(key)
  }

  private fun convertedNote(time: Int, message: ShortMessage, state: ChannelState): EditorNote {
    val drum = message.channel == 9
    val instrument = if (drum) drumInstrument(message.data1) else gmInstrument(state.program)
    val pitch = if (drum) drumPitch(message.data1) else NoteBlockPitch.fromMidiKey(message.data1)
    val volume = ((message.data2 / 127.0) * (state.volume / 127.0) * (state.expression / 127.0) * 100).roundToInt().coerceIn(0, 100)
    val pan = (((state.pan - 64) / 63.0) * 100).roundToInt().coerceIn(-100, 100)
    return EditorNote(time, 120, instrument, pitch, volume, pan)
  }

  private fun gmInstrument(program: Int): Int = when (program.coerceIn(0, 127)) { in 0..7 -> 0; in 8..15 -> if (program in 9..10) 6 else if (program == 14) 8 else 9; in 16..23 -> if (program >= 19) 14 else 0; in 24..31 -> if (program >= 28) 14 else 7; in 32..39 -> 1; in 40..55 -> if (program >= 48) 15 else 7; in 56..63 -> if (program >= 60) 11 else 12; in 64..79 -> 5; in 80..87 -> 13; in 88..95 -> if (program >= 92) 8 else 15; in 96..103 -> if (program % 2 == 0) 13 else 8; in 104..111 -> if (program <= 107) 14 else 12; in 112..119 -> if (program <= 115) 6 else 11; else -> if (program >= 126) 4 else 13 }
  private fun drumInstrument(midi: Int): Int = when (midi) { 35, 36 -> 2; in 37..40, in 60..66 -> 3; 56 -> 11; 67, 68, 80, 81 -> 6; else -> 4 }
  private fun drumPitch(midi: Int): Int = when (midi) { 35 -> 8; 36 -> 11; in 37..40 -> 10 + (midi - 37) * 2; 42, 44 -> 8; 46 -> 14; 49, 51, 52, 54, 55, 57, 59 -> 20; 56 -> 12; 67, 80 -> 9; 68, 81 -> 16; in 60..66 -> midi - 53; 75, 76 -> 15; 77 -> 7; else -> 12 }.coerceIn(0, 24)

  private fun preview() { notes.getOrNull(selected)?.let(::previewNote) }

  private fun previewNote(note: EditorNote) {
    val sound = when (note.instrument) {
      1 -> SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()
      2 -> SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value()
      3 -> SoundEvents.BLOCK_NOTE_BLOCK_SNARE.value()
      4 -> SoundEvents.BLOCK_NOTE_BLOCK_HAT.value()
      5 -> SoundEvents.BLOCK_NOTE_BLOCK_FLUTE.value()
      6 -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value()
      7 -> SoundEvents.BLOCK_NOTE_BLOCK_GUITAR.value()
      8 -> SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value()
      9 -> SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE.value()
      10 -> SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE.value()
      11 -> SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL.value()
      12 -> SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO.value()
      13 -> SoundEvents.BLOCK_NOTE_BLOCK_BIT.value()
      14 -> SoundEvents.BLOCK_NOTE_BLOCK_BANJO.value()
      15 -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()
      else -> SoundEvents.BLOCK_NOTE_BLOCK_HARP.value()
    }
    val playablePitch = NoteBlockPitch.foldForVanilla(note.pitch)
    client?.player?.playSound(sound, (note.volume / 100f).coerceIn(0f, 1f), 2.0.pow((playablePitch - 12) / 12.0).toFloat())
  }

  private fun durationMs() = notes.maxOfOrNull { it.time + it.duration }?.coerceAtLeast(1) ?: 1
  private fun lowerBound(timeMs: Int): Int {
    var low = 0; var high = notes.size
    while (low < high) { val middle = (low + high) / 2; if (notes[middle].time < timeMs) low = middle + 1 else high = middle }
    return low
  }
  private fun togglePlayback() {
    if (playing) { pausePlayback(); return }
    if (notes.isEmpty()) { state = t("Load a MIDI file before playback", "再生前にMIDIファイルを読み込んでください"); return }
    if (playheadMs >= durationMs()) playheadMs = 0
    playbackStartMs = playheadMs; visualPlayheadMs = playheadMs.toFloat(); playbackStartedAt = System.currentTimeMillis(); nextPlaybackIndex = lowerBound(playheadMs); playing = true
    state = t("Playing from ${formatTime(playheadMs)}", "${formatTime(playheadMs)}から再生中")
  }
  private fun pausePlayback() { if (playing) updatePlaybackPosition(); visualPlayheadMs = playheadMs.toFloat(); playing = false; state = t("Paused at ${formatTime(playheadMs)}", "${formatTime(playheadMs)}で一時停止") }
  private fun rewind() { playing = false; playheadMs = 0; playbackStartMs = 0; nextPlaybackIndex = 0; horizontalOffset = 0; state = t("Returned to the start", "先頭へ戻りました") }
  private fun seek(timeMs: Int) {
    playheadMs = timeMs.coerceIn(0, durationMs()); visualPlayheadMs = playheadMs.toFloat(); playbackStartMs = playheadMs; playbackStartedAt = System.currentTimeMillis(); nextPlaybackIndex = lowerBound(playheadMs)
  }
  private fun updatePlaybackPosition() {
    playheadMs = (playbackStartMs + (System.currentTimeMillis() - playbackStartedAt).toInt()).coerceAtMost(durationMs())
  }
  override fun tick() {
    super.tick(); if (!playing) return
    updatePlaybackPosition()
    var played = 0
    while (nextPlaybackIndex < notes.size && notes[nextPlaybackIndex].time <= playheadMs) {
      if (played < 64) previewNote(notes[nextPlaybackIndex])
      nextPlaybackIndex += 1; played += 1
    }
    if (playheadMs >= durationMs()) { playing = false; state = t("Playback finished", "再生が完了しました") }
  }

  override fun keyPressed(input: KeyInput): Boolean {
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
      EditorAction.LOAD_SELECTED -> loadMidi()
      EditorAction.UPLOAD_DRAFT -> exportAndUpload()
      EditorAction.REFRESH_LIBRARY -> refreshMidiLibrary()
      EditorAction.OPEN_MIDI_FOLDER -> openMidiFolder()
    }
    return true
  }

  private fun exportAndUpload() {
    try {
      applySelected(); require(notes.isNotEmpty()) { "Add at least one note" }; require(notes.size <= 100_000) { "OYMI upload limit is 100,000 notes" }
      val bytes = encode(); UploadClient.upload(bytes)
      state = t("Prepared ${notes.size} notes for server upload; ${UploadClient.status()}", "${notes.size}音をサーバー送信用に準備しました")
    } catch (error: Exception) { state = t("Upload preparation failed: ${error.message ?: "invalid data"}", "送信準備に失敗しました: ${error.message ?: "不正なデータ"}") }
  }
  private fun currentHistory() = EditorHistory.State(notes.map { it.copy() }, selectedIds.toSet(), notes.getOrNull(selected)?.id, songTitle, bpm, parts.toList(), activePart)
  private fun rememberHistory() = history.push(currentHistory())
  private fun restoreHistory(value: EditorHistory.State) { notes.clear(); notes += value.notes.map { it.copy() }; selectedIds.clear(); selectedIds += value.selected; songTitle=value.title; bpm=value.bpm; parts.clear(); parts+=value.parts; activePart=value.activePart.coerceIn(0,parts.lastIndex.coerceAtLeast(0)); sortNotesAndResolvePrimary(value.primary); choose(selected, false); titleField.text=songTitle; bpmField.text=bpm.toString(); state = t("History restored", "履歴を復元しました") }
  private fun pasteClipboard() { val entries = EditorClipboard.entries(); if (entries.isEmpty()) return; rememberHistory(); val copies = entries.map { EditorNote(playheadMs+it.time,it.duration,it.instrument,it.pitch,it.volume,it.pan,EditorSession.nextStableId(),it.part) }; notes += copies; selectedIds.clear(); selectedIds += copies.map { it.id }; sortNotesAndResolvePrimary(copies.first().id); choose(selected,false); state=t("Pasted ${copies.size} notes", "${copies.size}音を貼り付けました") }
  private fun duplicateSelected() {
    val source=selectedNotes(); if(source.isEmpty()) return
    rememberHistory(); val offset=(gridMarks.zipWithNext().map { it.second.timeMs-it.first.timeMs }.filter { it>0 }.minOrNull()?:125)
    val copies=source.map { it.copy(time=it.time+offset,id=EditorSession.nextStableId()) }; notes+=copies; selectedIds.clear(); selectedIds+=copies.map{it.id}; sortNotesAndResolvePrimary(copies.first().id); choose(selected,false)
    state=t("Duplicated ${copies.size} notes", "${copies.size}音を複製しました")
  }

  private fun encode(): ByteArray {
    val metadata = "{\"format\":\"oyasai-midi-import\",\"version\":1,\"song\":{\"title\":${json(songTitle)},\"displayBpm\":$bpm}}".toByteArray(Charsets.UTF_8)
    val ordered = notes.sortedWith(compareBy<EditorNote> { it.time }.thenBy { it.id }); val duration = ordered.maxOf { it.time }
    return ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out ->
      out.writeInt(0x4F594D49); out.writeShort(1); out.writeShort(0); out.writeInt(metadata.size); out.writeInt(ordered.size); out.writeInt(duration); out.write(metadata)
      ordered.forEach { note -> out.writeInt(note.time); out.writeByte(note.instrument); out.writeByte(NoteBlockPitch.foldForVanilla(note.pitch)); out.writeByte(note.volume); out.writeByte(note.pan) }
    }; bytes.toByteArray() }
  }

  private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
  private fun ensureDirectories() { Files.createDirectories(midiDirectory) }
  private fun openMidiFolder() {
    try { ensureDirectories(); Util.getOperatingSystem().open(midiDirectory); state = t("Opened MIDI folder", "MIDIフォルダーを開きました") }
    catch (error: Exception) { state = t("Could not open MIDI folder: ${error.message ?: "unsupported system"}", "MIDIフォルダーを開けませんでした: ${error.message ?: "未対応の環境"}") }
  }
  private fun refreshMidiLibrary() {
    midiFiles = Files.list(midiDirectory).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().let { name -> name.endsWith(".mid") || name.endsWith(".midi") } }.sorted().toList() }
    libraryScroll = libraryScroll.coerceIn(0, (midiFiles.size - 1).coerceAtLeast(0))
  }
  private fun editorLeft() = libraryWidth() + 8
  private fun libraryWidth() = if (settings.showLibrary) EditorLayout(width,height).libraryWidth else 0
  private fun settingsLeft() = (width - 300).coerceAtLeast(editorLeft())
  private fun saveSettings() { EditorSettingsStore.save(settings.copy(lastTool = tool)) }
  private fun cycleGridDensity() {
    settings = settings.copy(gridDensity = when (settings.gridDensity) { "AUTO" -> "SPARSE"; "SPARSE" -> "NORMAL"; "NORMAL" -> "DENSE"; else -> "AUTO" })
  }
  private fun cycleEncoding() {
    settings = settings.copy(uploadEncoding = when (settings.uploadEncoding) { "AUTO" -> "U15"; "U15" -> "BASE64"; else -> "AUTO" })
    UploadClient.setEncodingPreference(settings.uploadEncoding)
  }
  private fun cycleSnap() { snapDivisor = when (snapDivisor) { 4 -> 8; 8 -> 16; 16 -> 0; else -> 4 }; state=t("Snap ${if(snapDivisor==0) "off" else "1/$snapDivisor"}", "スナップ: ${if(snapDivisor==0) "オフ" else "1/$snapDivisor"}") }
  private fun switchPart(direction: Int) { if(parts.isEmpty()) return; activePart=Math.floorMod(activePart+direction,parts.size); allPartsView=false; state=t("Editing ${parts[activePart]}", "${parts[activePart]}を編集中") }
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
    val maximumVisible = (((rollBottom() - noteTop()).coerceAtLeast(120)) / 5).coerceIn(25, 64)
    visiblePitchCount = domainSize.coerceIn(min(25, domainSize), min(maximumVisible, domainSize))
    val center = notes.map { it.pitch }.sorted().let { sorted -> sorted.getOrNull(sorted.size / 2) ?: 12 }
    pitchMin = (center - visiblePitchCount / 2).coerceIn(domainMin, domainMax - visiblePitchCount + 1)
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
    when (row) { 4 -> cycleGridDensity(); 7 -> cycleTool(); 8 -> cycleEncoding() }
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
    // MIDI signature changes reset the musical grid. Do not use absolute tick modulo, otherwise a
    // change inserted off-grid makes every following snap candidate disappear. Match the same
    // rounded rational positions used to create the 1/32 grid: for PPQ=101, 1/8 includes tick 51
    // (`round(101 * 4 / 8)`), rather than the truncated-modulo tick 50.
    val candidates = gridMarks.filter { mark ->
      val signature = signatureMarks.lastOrNull { it.tick <= mark.tick } ?: SignatureMark(0, 4, 4)
      val relativeTick = mark.tick - signature.tick
      val numerator = ppq.toLong() * 4L
      val denominator = snapDivisor.toLong()
      if (relativeTick < 0L) false else {
        val estimate = relativeTick * denominator / numerator
        // Search the only nearby integer indices which can round to this grid tick. This keeps
        // exact equality with roundedRatio(k, PPQ*4, snapDivisor) without materializing duplicates.
        ((estimate - 2L)..(estimate + 2L)).any { index ->
          index >= 0L && (index * numerator + denominator / 2L) / denominator == relativeTick
        }
      }
    }
    val marks = candidates.ifEmpty { gridMarks }
    return marks.minByOrNull { kotlin.math.abs(it.timeMs - time) }?.timeMs ?: time.coerceAtLeast(0)
  }
  private fun selectedNotes() = notes.filter { it.id in selectedIds }
  private fun notesInCurrentView() = if (allPartsView) notes else notes.filter { it.part == activePart }
  private fun moveSelectionToPart(part: Int, recordHistory: Boolean = true) {
    if (recordHistory) rememberHistory()
    selectedNotes().forEach { it.part = part }; activePart = part; allPartsView=false; state = t("Moved ${selectedIds.size} notes to ${parts[part]}", "${selectedIds.size}音を${parts[part]}へ移動しました")
  }
  private fun createPartFromSelection() {
    if (selectedIds.isEmpty()) return
    val before = currentHistory()
    parts += "Part ${parts.size + 1}"; moveSelectionToPart(parts.lastIndex, recordHistory = false)
    history.push(before)
  }

  private fun updateVisualPlayback() {
    val now = System.nanoTime()
    val elapsed = if (lastVisualRenderNanos == 0L) 0.0 else ((now - lastVisualRenderNanos) / 1_000_000_000.0).coerceIn(0.0, 0.25)
    lastVisualRenderNanos = now
    visualPlayheadMs = if (playing) (playbackStartMs + (System.currentTimeMillis() - playbackStartedAt).toInt()).coerceAtMost(durationMs()).toFloat() else playheadMs.toFloat()
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
      if (inRect(x, y, editorLeft() + 296, 58, 108, 22)) { loadMidi(); return true }
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
      if (inRect(x, y, editorLeft() + 334, 168, 48, 24)) { snapDivisor = when (snapDivisor) { 4 -> 8; 8 -> 16; 16 -> 0; else -> 4 }; state = t("Snap ${if (snapDivisor == 0) "off" else "1/$snapDivisor"}", "スナップ: ${if (snapDivisor == 0) "オフ" else "1/$snapDivisor"}"); return true }
      if (inRect(x, y, editorLeft() + 388, 168, 78, 24)) { createPartFromSelection(); return true }
      if (inRect(x, y, editorLeft() + 472, 168, 70, 24)) { if (parts.size > 1) moveSelectionToPart((activePart + 1) % parts.size); return true }
      val row = (y - 72) / 23 + libraryScroll
      if (settings.showLibrary && x in 12 until libraryWidth() - 12 && y >= 72 && y < height - 76 && row in midiFiles.indices) { selectedMidi = midiFiles[row]; state = t("Selected ${selectedMidi!!.fileName}", "${selectedMidi!!.fileName}を選択しました"); if (doubled) loadMidi(); return true }
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
      selectedNotes().forEach { note -> dragBase[note.id]?.let { baseNote -> note.time = baseNote.first + clampedTime; note.pitch = baseNote.second + deltaPitch } }; return true
    }
    return super.mouseDragged(click, offsetX, offsetY)
  }

  override fun mouseReleased(click: Click): Boolean {
    if (externalUiActive) return true
    if (selectionStart != null) {
      val (t1, p1) = selectionStart!!; val (t2, p2) = selectionEnd!!
      val handle = client?.window?.handle ?: 0L; val ctrl = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
      if (!ctrl) selectedIds.clear(); notes.filter { it.time + it.duration >= minOf(t1, t2) && it.time <= maxOf(t1, t2) && it.pitch in minOf(p1, p2)..maxOf(p1, p2) }.forEach { selectedIds += it.id }
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
    updateVisualPlayback()
    // Fabric GUI ImGui renders after Minecraft's extracted GUI pass. Keep this Screen as a dark
    // capture surface and let ImGui own every visible editor widget.
    context.fill(0, 0, width, height, 0xFF10131A.toInt())
  }

  override fun render(io: ImGuiIO) {
    configureImGui(io)
    updateImGuiScale(io)
    ImGui.dockSpaceOverViewport()
    renderImGuiTransport(io)
    if (settings.showLibrary) renderImGuiLibrary(io)
    if (settings.showInspector) renderImGuiInspector(io)
    renderImGuiPianoRoll(io)
    if (settings.showAutomation) renderImGuiAutomation(io)
    if (settingsOpen) renderImGuiSettings(io)
  }

  private fun configureImGui(io: ImGuiIO) {
    if (imguiConfigured) return
    io.addConfigFlags(ImGuiConfigFlags.DockingEnable)
    io.setIniFilename(FabricLoader.getInstance().configDir.resolve("ommt-imgui-layout.ini").toString())
    ImGui.styleColorsDark()
    ImGui.getStyle().apply {
      setWindowRounding(2f); setChildRounding(2f); setFrameRounding(2f); setTabRounding(2f)
      setWindowPadding(8f, 8f); setFramePadding(8f, 5f)
    }
    imguiConfigured = true
  }

  private fun updateImGuiScale(io: ImGuiIO) {
    val target = (settings.uiScalePercent / 100f).coerceIn(.75f, 1.5f)
    // ImGuiStyle.scaleAllSizes() rounds several border metrics. Calling it repeatedly while a
    // slider is active can reduce Separator thickness to zero and trigger a native ImGui assert.
    // FontGlobalScale is frame-safe; docked windows themselves remain freely resizable.
    if (abs(target - io.fontGlobalScale) > .001f) io.fontGlobalScale = target
    imguiAppliedScale = target
  }

  private fun renderImGuiTransport(io: ImGuiIO) {
    ImGui.setNextWindowPos(0f, 0f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(io.displaySizeX, 112f, ImGuiCond.FirstUseEver)
    if (ImGui.begin(windowTitle("OMMT  •  MIDI WORKSPACE", "OMMT ・ MIDIワークスペース"))) {
      ImGui.setNextItemWidth(280f); if (ImGui.inputText("${t("Song", "曲名")}###Song", imTitle)) { songTitle = imTitle.get().trim().take(120).ifBlank { "Untitled song" }; titleField.text = songTitle }
      ImGui.sameLine(); ImGui.setNextItemWidth(90f); if (ImGui.inputInt("BPM###BPM", imBpm, 1, 10)) { bpm = imBpm.get().coerceIn(1, 60_000); imBpm.set(bpm); bpmField.text = bpm.toString() }
      ImGui.sameLine(); if (ImGui.button("${t("LOAD MIDI", "MIDI読込")}###LOAD_SELECTED")) loadMidi()
      ImGui.sameLine(); if (ImGui.button("${t("SETTINGS", "設定")}###SETTINGS")) settingsOpen = !settingsOpen
      if (ImGui.button("|<  ${t("Home", "先頭")}###HOME")) rewind(); ImGui.sameLine()
      if (ImGui.button("${if (playing) t("PAUSE", "一時停止") else t("PLAY", "再生")}###PLAY")) togglePlayback(); ImGui.sameLine()
      if (ImGui.button("${t("FOLLOW", "追従")} ${if(followPlayback) "ON" else "OFF"}###FOLLOW")) followPlayback = !followPlayback; ImGui.sameLine()
      if (ImGui.button("${t("FIT", "全体表示")}###FIT")) { fitTimeline(); fitPitchRange() }; ImGui.sameLine()
      if (ImGui.button("−###ZOOM_OUT")) zoomTimelineCentered(1.25); ImGui.sameLine()
      if (ImGui.button("+###ZOOM_IN")) zoomTimelineCentered(.8); ImGui.sameLine()
      if (ImGui.button("${t("SNAP", "スナップ")} ${if(snapDivisor==0) "OFF" else "1/$snapDivisor"}###SNAP")) cycleSnap()
      ImGui.sameLine(); if (ImGui.button("${t("NEW PART", "パート作成")}###NEW_PART")) createPartFromSelection()
      ImGui.sameLine(); if (ImGui.button("${t("UPLOAD DRAFT", "下書き送信")}###UPLOAD")) exportAndUpload()
      ImGui.sameLine(); ImGui.text("${formatTime(playheadMs)} / ${formatTime(durationMs())}")
      val progress = UploadClient.progress()
      ImGui.textColored(.73f, .91f, .41f, 1f, localizedUploadStatus(UploadClient.status()))
      ImGui.sameLine(); ImGui.progressBar(progress.percent / 100f, 240f, 0f, "${localizedProgressPhase(progress.phase)} ${progress.percent}%")
      ImGui.sameLine(); ImGui.textDisabled(state)
    }
    ImGui.end()
  }

  private fun renderImGuiLibrary(io: ImGuiIO) {
    ImGui.setNextWindowPos(0f, 118f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(max(220f, io.displaySizeX * .16f), max(260f, io.displaySizeY - 118f), ImGuiCond.FirstUseEver)
    if (ImGui.begin(windowTitle("MIDI LIBRARY", "MIDIライブラリ"))) {
      ImGui.textDisabled("OMMT/midi  •  ${midiFiles.size} ${t("files", "ファイル")}")
      if (ImGui.button("${t("REFRESH", "更新")}###REFRESH")) refreshMidiLibrary(); ImGui.sameLine()
      if (ImGui.button("${t("OPEN FOLDER", "フォルダーを開く")}###OPEN_FOLDER")) openMidiFolder()
      ImGui.spacing()
      midiFiles.forEach { path ->
        val chosen = path == selectedMidi
        if (ImGui.selectable(path.fileName.toString(), chosen)) { selectedMidi = path; state = t("Selected ${path.fileName}", "${path.fileName}を選択しました") }
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) { selectedMidi = path; loadMidi() }
      }
      if (midiFiles.isEmpty()) ImGui.textDisabled(t("Place .mid or .midi files in OMMT/midi", "OMMT/midiに.midまたは.midiを入れてください"))
    }
    ImGui.end()
  }

  private fun renderImGuiInspector(io: ImGuiIO) {
    ImGui.setNextWindowPos(max(0f, io.displaySizeX - 330f), 118f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(330f, 300f, ImGuiCond.FirstUseEver)
    if (ImGui.begin(windowTitle("NOTE INSPECTOR", "ノートインスペクター"))) {
      val picked = selectedNotes()
      ImGui.text("${picked.size} ${t("selected", "音選択中")}${if (picked.size > 1) t("  •  primary values below", " ・ 代表音の値") else ""}")
      ImGui.setNextItemWidth(145f); ImGui.inputInt("${t("Time (ms)", "開始 (ms)")}###Time", imTime); ImGui.sameLine(); ImGui.setNextItemWidth(120f); ImGui.inputInt("${t("Length", "長さ")}###Length", imDuration)
      ImGui.setNextItemWidth(145f); ImGui.inputInt("${t("Instrument", "楽器")}###Instrument", imInstrument); ImGui.sameLine(); ImGui.setNextItemWidth(120f); ImGui.inputInt("${t("Source pitch", "元の音高")}###Pitch", imPitch)
      ImGui.setNextItemWidth(145f); ImGui.inputInt("${t("Volume", "音量")}###Volume", imVolume); ImGui.sameLine(); ImGui.setNextItemWidth(120f); ImGui.inputInt("${t("Pan", "定位")}###Pan", imPan)
      ImGui.textDisabled(t("Outside 0..24 is shown; sound/export octave-folds into vanilla range", "0..24の範囲外も表示し、再生・送信時だけバニラ音域へ折り返します"))
      if (ImGui.button("${t("APPLY", "適用")}###APPLY")) applySelected(); ImGui.sameLine()
      if (ImGui.button("${t("PREVIEW", "試聴")}###PREVIEW")) preview(); ImGui.sameLine()
      if (ImGui.button("${t("DELETE", "削除")}###DELETE")) deleteSelected()
      ImGui.spacing()
      parts.forEachIndexed { index, name ->
        if (ImGui.selectable("${t("Part", "パート")} ${index + 1}: $name", !allPartsView && index == activePart)) { activePart=index; allPartsView=false }
        if (selectedIds.isNotEmpty()) { ImGui.sameLine(); if(ImGui.smallButton("${t("MOVE", "移動")}##move$index")) moveSelectionToPart(index) }
      }
    }
    ImGui.end()
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
      value == "Checking OyasaiMusic upload…" -> "OyasaiMusicへの送信可否を確認中…"
      value == "OyasaiMusic upload is unavailable" -> "このサーバーではOyasaiMusicへ送信できません"
      value == "OyasaiMusic upload ready" -> "OyasaiMusicへ送信できます"
      value == "Waiting for OyasaiMusic upload capability…" -> "OyasaiMusicの送信機能を確認中…"
      value == "Importing on OyasaiMusic…" -> "OyasaiMusicへ登録中…"
      value == "Retrying legacy OyasaiMusic upload…" -> "旧方式で送信を再試行中…"
      value.startsWith("Uploading ") -> value.replace("Uploading ", "送信中: ").replace(" command chunks…", "分割…")
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
    val defaultRight = if (settings.showInspector) 330f else 0f
    val defaultBottom = if (settings.showAutomation) 190f else 0f
    ImGui.setNextWindowPos(defaultLeft, 118f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(max(420f, io.displaySizeX - defaultLeft - defaultRight), max(260f, io.displaySizeY - 118f - defaultBottom), ImGuiCond.FirstUseEver)
    if (!ImGui.begin(windowTitle("PIANO ROLL", "ピアノロール"))) { ImGui.end(); return }

    if(ImGui.button("${t("ALL", "全体")} (${notes.size})###ALL_PARTS")) allPartsView=true
    parts.forEachIndexed { index,name -> ImGui.sameLine(); if(ImGui.button("${index+1}: $name (${notes.count{it.part==index}})##part$index")){activePart=index;allPartsView=false} }
    ImGui.textDisabled("${if(allPartsView)t("All-parts overview", "全パート表示") else t("Editing ${parts.getOrElse(activePart){"Part"}}", "${parts.getOrElse(activePart){"パート"}}を編集中")}  •  ${selectedIds.size} ${t("selected", "選択")}  •  ${t("Gestures are configurable in Settings > Keymap", "操作は設定 > キーマップで変更できます")}")
    val canvasX = ImGui.getCursorScreenPosX(); val canvasY = ImGui.getCursorScreenPosY()
    val canvasWidth = ImGui.getContentRegionAvailX().coerceAtLeast(240f)
    val canvasHeight = ImGui.getContentRegionAvailY().coerceAtLeast(170f)
    ImGui.invisibleButton("##ommt-piano-canvas", canvasWidth, canvasHeight)
    val hovered = ImGui.isItemHovered()
    val draw = ImGui.getWindowDrawList()
    val keyboardWidth = 58f; val rulerHeight = 28f; val scrollbar = 8f
    val plotLeft = canvasX + keyboardWidth; val plotRight = canvasX + canvasWidth - scrollbar
    val notesTop = canvasY + rulerHeight; val notesBottom = canvasY + canvasHeight - scrollbar
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f); val noteHeight = (notesBottom - notesTop).coerceAtLeast(1f)
    clampPitchViewport()
    val rowHeight = noteHeight / visiblePitchCount.coerceAtLeast(1)
    fun xAt(time: Int) = plotLeft + (time - horizontalOffset).toFloat() / visibleSpan() * plotWidth
    fun timeAt(x: Float) = (horizontalOffset + ((x - plotLeft) / plotWidth).coerceIn(0f, 1f) * visibleSpan()).roundToInt()
    fun yAt(pitch: Int) = notesBottom - (pitch - pitchMin + 1) * rowHeight
    fun pitchAtCanvas(y: Float) = (pitchMin + ((notesBottom - y) / noteHeight * visiblePitchCount).toInt()).coerceIn(pitchDomainMin(), pitchDomainMax())

    if (hovered) {
      val mouseX = io.mousePosX; val mouseY = io.mousePosY
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
          WheelAction.PITCH_SCROLL -> { pitchMin = (pitchMin - wheel.roundToInt()).coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1) }
          WheelAction.TIMELINE_SCROLL -> {
            val movement = (wheel * visibleSpan() * .08f).roundToInt()
            horizontalOffset = (horizontalOffset - movement).coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan())
          }
          WheelAction.NONE -> Unit
        }
        followPlayback = false
      }

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

      if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
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
            val visibleRange = pitchMin until pitchMin + visiblePitchCount
            val hit = notes.withIndex().filter { indexed ->
              val note = indexed.value
              (allPartsView || note.part == activePart) && note.pitch in visibleRange && mouseX >= xAt(note.time) - 3f && mouseX <= max(xAt(note.time + note.duration), xAt(note.time) + 6f) + 3f && abs((yAt(note.pitch) + rowHeight / 2f) - mouseY) <= rowHeight / 2f + 3f
            }.minByOrNull { abs(xAt(it.value.time) - mouseX) }
            val rangeModifier=modifierActive(settings.rangeSelectionModifier,io)
            if (rangeModifier) {
              selectionStart = snap(timeAt(mouseX)) to pitchAtCanvas(mouseY); selectionEnd = selectionStart
            } else if(hit != null) {
              if (modifierActive(settings.additiveSelectionModifier,io)) selectedIds += hit.value.id else if (hit.value.id !in selectedIds) { selectedIds.clear(); selectedIds += hit.value.id }
              selected = hit.index; syncImGuiInspector(); noteDragArmed = true; noteDragStartX=mouseX; noteDragStartY=mouseY
              dragMouseTime = timeAt(mouseX); dragMousePitch = pitchAtCanvas(mouseY); dragOriginTime = hit.value.time; dragOriginPitch = hit.value.pitch
              dragBase = selectedNotes().associate { it.id to (it.time to it.pitch) }
            } else {
              selectedIds.clear(); syncImGuiInspector()
            }
          }
        }
      }

      if (verticalScrollbar && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        val fraction = ((mouseY - notesTop) / noteHeight).coerceIn(0f, 1f)
        pitchMin = (pitchDomainMin() + ((pitchDomainSize() - visiblePitchCount) * fraction).roundToInt()).coerceIn(pitchDomainMin(), pitchDomainMax() - visiblePitchCount + 1)
      } else if (horizontalScrollbar && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        val maximum = durationMs().coerceAtLeast(visibleSpan()) - visibleSpan()
        horizontalOffset = (maximum * ((mouseX - plotLeft) / plotWidth).coerceIn(0f, 1f)).roundToInt().coerceAtLeast(0)
      } else if (selectionStart != null && ImGui.isMouseDown(ImGuiMouseButton.Left)) {
        selectionEnd = snap(timeAt(mouseX)) to pitchAtCanvas(mouseY)
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
        selectedNotes().forEach { note -> dragBase[note.id]?.let { base -> note.time = base.first + deltaTime.coerceAtLeast(-minTime); note.pitch = base.second + deltaPitch } }
      }

      if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
        selectionStart?.let { start -> selectionEnd?.let { end ->
          if (!modifierActive(settings.additiveSelectionModifier,io)) selectedIds.clear()
          notesInCurrentView().filter { it.time + it.duration >= min(start.first, end.first) && it.time <= max(start.first, end.first) && it.pitch in min(start.second, end.second)..max(start.second, end.second) }.forEach { selectedIds += it.id }
          selectedIds.firstOrNull()?.let { id -> selected = notes.indexOfFirst { it.id == id }.coerceAtLeast(0); syncImGuiInspector() }
          state = t("Selected ${selectedIds.size} notes", "${selectedIds.size}音を選択しました")
        } }
        if (draggingNotes) sortNotesAndResolvePrimary()
        selectionStart = null; selectionEnd = null; noteDragArmed=false; draggingNotes = false; horizontalScrollbar = false; verticalScrollbar = false
      }
    }

    draw.pushClipRect(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, true)
    draw.addRectFilled(canvasX, canvasY, canvasX + canvasWidth, canvasY + canvasHeight, ImColor.rgb(16, 19, 26))
    draw.addRectFilled(canvasX, canvasY, plotLeft, notesTop, ImColor.rgb(30, 37, 48))
    draw.addText(canvasX + 10f, canvasY + 7f, ImColor.rgb(141, 152, 169), "KEY")
    val blackKeys = setOf(1, 3, 6, 8, 10)
    for (pitch in pitchMin until pitchMin + visiblePitchCount) {
      val y = yAt(pitch); val midi = NoteBlockPitch.toMidiKey(pitch); val midiClass = Math.floorMod(midi, 12); val black = midiClass in blackKeys
      val outsideVanilla = pitch !in NoteBlockPitch.VANILLA_MIN..NoteBlockPitch.VANILLA_MAX
      draw.addRectFilled(plotLeft, y, plotRight, y + rowHeight, if (black) ImColor.rgb(18, 23, 32) else ImColor.rgb(23, 28, 37))
      draw.addRectFilled(canvasX, y, plotLeft, y + rowHeight, if (black) ImColor.rgb(37, 43, 53) else ImColor.rgb(225, 229, 232))
      if (outsideVanilla) {
        draw.addRectFilled(plotLeft, y, plotRight, y + rowHeight, ImColor.rgba(87, 112, 139, 34))
        draw.addRectFilled(canvasX, y, canvasX + 4f, y + rowHeight, ImColor.rgb(91, 109, 132))
      }
      if (pitch == NoteBlockPitch.VANILLA_MIN || pitch == NoteBlockPitch.VANILLA_MAX + 1) draw.addLine(canvasX, y, plotRight, y, ImColor.rgb(112, 137, 164), 1.5f)
      val octaveLine = midiClass == 0
      draw.addLine(plotLeft, y, plotRight, y, if (octaveLine) ImColor.rgb(76, 94, 61) else ImColor.rgb(45, 52, 64))
      if (octaveLine && rowHeight >= 7f) draw.addText(canvasX + 5f, y + 1f, if (black) ImColor.rgb(234, 240, 248) else ImColor.rgb(40, 46, 55), "C${midi / 12 - 1}")
    }

    draw.addRectFilled(plotLeft, canvasY, plotRight, notesTop, ImColor.rgb(29, 35, 45))
    val visibleMarks = gridMarks.filter { it.timeMs in horizontalOffset..horizontalOffset + visibleSpan() }
    val barMarks = visibleMarks.filter { it.isBar }
    val barSpacing = barMarks.zipWithNext().map { xAt(it.second.timeMs) - xAt(it.first.timeMs) }.filter { it > 0f }.minOrNull() ?: plotWidth
    var barStride = 1
    while (barSpacing * barStride < 56f && barStride < 128) barStride *= 2
    val density = when (settings.gridDensity) { "SPARSE" -> 1.6f; "DENSE" -> .7f; "NORMAL" -> 1f; else -> 1f }
    var previousLabelRight = plotLeft - 1f
    visibleMarks.forEachIndexed { index, mark ->
      val x = xAt(mark.timeMs); val nextX = visibleMarks.getOrNull(index + 1)?.let { xAt(it.timeMs) } ?: plotRight
      val spacing = (nextX - x).coerceAtLeast(0f)
      val strideBar = (mark.bar - 1) % barStride == 0
      val drawLine = when { mark.isBar -> barSpacing >= 4f || strideBar; mark.isBeat -> spacing >= 14f * density; else -> spacing >= 8f * density }
      if (drawLine) {
        val color = when { mark.isBar -> ImColor.rgb(81, 92, 109); mark.isBeat -> ImColor.rgb(61, 72, 88); else -> ImColor.rgb(48, 55, 68) }
        // The ruler is a dedicated opaque band: grid lines begin below it and cannot cross labels.
        draw.addLine(x, notesTop, x, notesBottom, color)
        if (mark.isBar) draw.addLine(x, notesTop - 5f, x, notesTop, color)
      }
      if (mark.isBar && strideBar) {
        val label = mark.bar.toString(); val labelWidth = ImGui.calcTextSizeX(label)
        val labelX = (x + 4f).coerceIn(plotLeft + 3f, plotRight - labelWidth - 3f)
        if (labelX >= previousLabelRight && labelX + labelWidth <= plotRight - 2f) {
          draw.addRectFilled(labelX - 2f, canvasY + 3f, labelX + labelWidth + 2f, notesTop - 3f, ImColor.rgb(29, 35, 45))
          draw.addText(labelX, canvasY + 6f, ImColor.rgb(180, 190, 204), label)
          previousLabelRight = labelX + labelWidth + 12f
        }
      }
    }

    val visibleRange = pitchMin until pitchMin + visiblePitchCount
    notes.forEach { note ->
      if (note.pitch in visibleRange && note.time + note.duration >= horizontalOffset && note.time <= horizontalOffset + visibleSpan()) {
        val start = xAt(note.time).coerceAtLeast(plotLeft); val end = max(start + 5f, xAt(note.time + note.duration).coerceAtMost(plotRight))
        val y = yAt(note.pitch) + 2f; val selectedNote = note.id in selectedIds
        val base = if (selectedNote) ImColor.rgb(185, 231, 105) else if (allPartsView || note.part == activePart) imguiInstrumentColor(note.instrument, 255) else imguiInstrumentColor(note.instrument, if (settings.showOtherParts) 72 else 0)
        if (allPartsView || (base ushr 24) != 0 || note.part == activePart || selectedNote) draw.addRectFilled(start, y, end, max(y + 3f, y + rowHeight - 3f), base, 2f)
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

  private fun imguiInstrumentColor(instrument: Int, alpha: Int): Int {
    val rgb = intArrayOf(0x79C7FF,0x74C69D,0xFF9B71,0xF7C66B,0xD8D8D8,0x98C1D9,0xFFD166,0xE9A66F,0xA8DADC,0xF4A261,0xB8C0CC,0xC5A46D,0xD97745,0x66D9A6,0xE9C46A,0xF6E58D)[instrument.coerceIn(0,15)]
    return ImColor.rgba(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255, alpha.coerceIn(0,255))
  }

  private fun renderImGuiAutomation(io: ImGuiIO) {
    ImGui.setNextWindowPos(max(220f, io.displaySizeX * .16f), max(420f, io.displaySizeY - 190f), ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(max(420f, io.displaySizeX * .55f), 180f, ImGuiCond.FirstUseEver)
    if (ImGui.begin(windowTitle("AUTOMATION  •  VOLUME / PAN", "オートメーション ・ 音量 / 定位"))) {
      if(ImGui.button("${t("VOLUME", "音量")}###LANE_VOLUME")) automationLane=AutomationLane.VOLUME
      ImGui.sameLine(); if(ImGui.button("${t("PAN", "定位")}###LANE_PAN")) automationLane=AutomationLane.PAN
      ImGui.sameLine(); ImGui.textDisabled(t("Drag each note bar; timeline scroll is shared with the piano roll", "各音のバーをドラッグ。横位置はピアノロールと連動します"))
      val x0=ImGui.getCursorScreenPosX(); val y0=ImGui.getCursorScreenPosY(); val w=ImGui.getContentRegionAvailX().coerceAtLeast(180f); val h=ImGui.getContentRegionAvailY().coerceAtLeast(70f)
      ImGui.invisibleButton("##automation-canvas",w,h); val hovered=ImGui.isItemHovered(); val draw=ImGui.getWindowDrawList(); val bottom=y0+h-8f; val top=y0+8f
      fun xAt(time:Int)=x0+(time-horizontalOffset).toFloat()/visibleSpan()*w
      fun valueY(note:EditorNote)=when(automationLane){AutomationLane.VOLUME->bottom-(note.volume/100f)*(bottom-top);AutomationLane.PAN->bottom-((note.pan+100)/200f)*(bottom-top)}
      draw.addRectFilled(x0,y0,x0+w,y0+h,ImColor.rgb(14,20,27)); draw.addLine(x0,if(automationLane==AutomationLane.PAN)(top+bottom)/2f else bottom,x0+w,if(automationLane==AutomationLane.PAN)(top+bottom)/2f else bottom,ImColor.rgb(67,82,98))
      val visible=notesInCurrentView().filter{it.time in horizontalOffset..horizontalOffset+visibleSpan()}
      visible.forEach { note -> val x=xAt(note.time); val y=valueY(note); val color=if(note.id in selectedIds)ImColor.rgb(185,231,105)else imguiInstrumentColor(note.instrument,210); draw.addLine(x,bottom,x,y,color,3f); draw.addRectFilled(x-3f,y-3f,x+3f,y+3f,color) }
      if(hovered&&ImGui.isMouseClicked(ImGuiMouseButton.Left)){
        val hit=visible.minByOrNull{abs(xAt(it.time)-io.mousePosX)}?.takeIf{abs(xAt(it.time)-io.mousePosX)<=8f}
        if(hit!=null){rememberHistory();automationDragId=hit.id;if(!modifierActive(settings.additiveSelectionModifier,io)){selectedIds.clear()};selectedIds+=hit.id;selected=notes.indexOf(hit);syncImGuiInspector()}
      }
      automationDragId?.let{id->if(ImGui.isMouseDown(ImGuiMouseButton.Left)){notes.firstOrNull{it.id==id}?.let{note->val fraction=((bottom-io.mousePosY)/(bottom-top)).coerceIn(0f,1f);if(automationLane==AutomationLane.VOLUME)note.volume=(fraction*100).roundToInt()else note.pan=(fraction*200-100).roundToInt();syncImGuiInspector()}}}
      if(ImGui.isMouseReleased(ImGuiMouseButton.Left))automationDragId=null
      draw.addRect(x0,y0,x0+w,y0+h,ImColor.rgb(67,82,98))
    }
    ImGui.end()
  }

  private fun renderImGuiSettings(io: ImGuiIO) {
    ImGui.setNextWindowPos(max(10f, io.displaySizeX - 360f), 118f, ImGuiCond.FirstUseEver)
    ImGui.setNextWindowSize(420f, 640f, ImGuiCond.FirstUseEver)
    var changed = false
    if (ImGui.begin(windowTitle("EDITOR SETTINGS", "エディター設定"))) {
      if(ImGui.button("${t("GENERAL", "一般")}###GENERAL"))settingsPage=SettingsPage.GENERAL
      ImGui.sameLine();if(ImGui.button("${t("KEYMAP", "キーマップ")}###KEYMAP"))settingsPage=SettingsPage.KEYMAP
      ImGui.spacing()
      if(settingsPage==SettingsPage.GENERAL){
        if (ImGui.checkbox("${t("MIDI Library", "MIDIライブラリ")}###showLibrary", settings.showLibrary)) { settings = settings.copy(showLibrary = !settings.showLibrary); changed = true }
        if (ImGui.checkbox("${t("Note Inspector", "ノートインスペクター")}###showInspector", settings.showInspector)) { settings = settings.copy(showInspector = !settings.showInspector); changed = true }
        if (ImGui.checkbox("${t("Automation", "オートメーション")}###showAutomation", settings.showAutomation)) { settings = settings.copy(showAutomation = !settings.showAutomation); changed = true }
        if (ImGui.checkbox("${t("Ghost other parts", "他パートを半透明表示")}###showOtherParts", settings.showOtherParts)) { settings = settings.copy(showOtherParts = !settings.showOtherParts); changed = true }
        val scale = intArrayOf(settings.uiScalePercent)
        if (ImGui.sliderInt("${t("OMMT UI scale", "OMMT UIスケール")}###uiScale", scale, 75, 150, "%d%%")) { settings = settings.copy(uiScalePercent = (scale[0] / 5 * 5).coerceIn(75, 150)); changed = true }
        ImGui.textDisabled(t("Font-safe scale; independent from Minecraft GUI Scale", "MinecraftのGUIサイズとは独立した安全な表示倍率です"))
        if (ImGui.button("${t("Grid", "グリッド")}: ${settings.gridDensity}###GRID")) { cycleGridDensity(); changed = true }
        ImGui.sameLine(); if (ImGui.button("${t("Encoding", "送信形式")}: ${settings.uploadEncoding}###ENCODING")) { cycleEncoding(); changed = true }
        ImGui.textWrapped(t("Windows can be moved, resized, tabbed and docked. The supplied Studio One-like layout is the first-run default.", "ウィンドウは移動・リサイズ・タブ化・ドッキングできます。添付のStudio One風配置が初回起動時の標準です。"))
      }else{
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
      if (ImGui.button("${t("CLOSE", "閉じる")}###CLOSE_SETTINGS")) settingsOpen = false
    }
    ImGui.end()
    if (changed) { saveSettings(); state = t("Editor settings saved", "エディター設定を保存しました") }
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
      "Tool: $tool",
      "Upload encoding: ${settings.uploadEncoding}"
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
      if (black) context.fill(left, y, left + 26, nextY, if (pitch % 12 == 0) 0xFF637D36.toInt() else 0xFF242A34.toInt())
      val lineColor = if (pitch % 12 == 0) 0xFF607842.toInt() else 0xFF2D3440.toInt()
      context.fill(plotLeft, y, right, y + 1, lineColor)
      if (pitch % 12 == 0 || pitch == 24) context.drawTextWithShadow(textRenderer, "F♯${3 + pitch / 12}", left + 3, y + 2, if (black) 0xFFDAF2AB.toInt() else 0xFF30343A.toInt())
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
    applySelected()
    pausePlayback()
    editorSession.save(EditorSnapshot(notes.map { it.copy() }, selectedIds.toSet(), selected, songTitle, bpm, horizontalOffset, viewSpanMs, activePart, parts.toList(), ppq, beatsPerBar, beatUnit, pitchMin, visiblePitchCount, snapDivisor, followPlayback, playheadMs, allPartsView, tempoMarks, signatureMarks, gridMarks))
    EditorSettingsStore.save(settings.copy(lastTool = tool))
    super.removed()
  }

  private fun instrumentColor(instrument: Int): Int = intArrayOf(0xFF79C7FF.toInt(), 0xFF74C69D.toInt(), 0xFFFF9B71.toInt(), 0xFFF7C66B.toInt(), 0xFFD8D8D8.toInt(), 0xFF98C1D9.toInt(), 0xFFFFD166.toInt(), 0xFFE9A66F.toInt(), 0xFFA8DADC.toInt(), 0xFFF4A261.toInt(), 0xFFB8C0CC.toInt(), 0xFFC5A46D.toInt(), 0xFFD97745.toInt(), 0xFF66D9A6.toInt(), 0xFFE9C46A.toInt(), 0xFFF6E58D.toInt())[instrument.coerceIn(0, 15)]
  override fun shouldPause() = false
}
