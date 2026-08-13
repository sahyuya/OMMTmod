package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.pow
import kotlin.math.roundToInt
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

/**
 * Local, client-only MIDI editor. The stored notes deliberately use the same stable instrument IDs
 * and limits as OYMI v1, so exporting does not depend on a server round trip.
 */
class OyasaiEditorScreen : Screen(Text.literal("OMMT MIDI editor")) {
  private data class Note(var time: Int, var duration: Int, var instrument: Int, var pitch: Int, var volume: Int, var pan: Int)
  private data class ChannelState(var program: Int = 0, var volume: Int = 127, var expression: Int = 127, var pan: Int = 64)
  private data class TimedEvent(val tick: Long, val track: Int, val order: Int, val event: MidiEvent)
  private data class TempoPoint(val tick: Long, val microsAtTick: Double, val microsPerQuarter: Int)

  private val notes = mutableListOf<Note>()
  private var selected = 0
  private var songTitle = "Untitled song"
  private var bpm = 120
  private var horizontalOffset = 0
  private var viewSpanMs = 30_000
  private var playheadMs = 0
  private var playbackStartMs = 0
  private var playbackStartedAt = 0L
  private var nextPlaybackIndex = 0
  private var playing = false
  private var followPlayback = true
  private var state = "Select a MIDI file from the library"
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

  override fun init() {
    ensureDirectories(); refreshMidiLibrary()
    titleField = field(editorLeft() + 16, 58, 190, "Song title").also { it.text = songTitle; it.setMaxLength(120) }
    bpmField = field(editorLeft() + 218, 58, 62, "BPM").also { it.text = bpm.toString(); it.setMaxLength(5) }
    timeField = field(editorLeft() + 16, 128, 66, "ms")
    durationField = field(editorLeft() + 90, 128, 66, "length")
    instrumentField = field(editorLeft() + 164, 128, 48, "inst")
    pitchField = field(editorLeft() + 220, 128, 48, "pitch")
    volumeField = field(editorLeft() + 276, 128, 48, "vol")
    panField = field(editorLeft() + 332, 128, 52, "pan")
    choose(selected)
  }

  private fun field(x: Int, y: Int, fieldWidth: Int, hint: String) =
      TextFieldWidget(textRenderer, x, y, fieldWidth, 22, Text.literal(hint)).also {
        it.setDrawsBackground(false)
        it.setTextShadow(false)
        it.setEditableColor(0xFFEAF0F8.toInt())
        addDrawableChild(it)
      }

  private fun choose(index: Int) {
    selected = index.coerceIn(0, (notes.size - 1).coerceAtLeast(0))
    notes.getOrNull(selected)?.let { note ->
      timeField.text = note.time.toString(); durationField.text = note.duration.toString(); instrumentField.text = note.instrument.toString()
      pitchField.text = note.pitch.toString(); volumeField.text = note.volume.toString(); panField.text = note.pan.toString()
    }
    keepSelectedVisible()
  }

  private fun keepSelectedVisible() {
    val time = notes.getOrNull(selected)?.time ?: return
    val span = visibleSpan()
    if (time < horizontalOffset) horizontalOffset = time
    if (time > horizontalOffset + span) horizontalOffset = (time - span / 2).coerceAtLeast(0)
  }

  private fun applySelected() {
    val note = notes.getOrNull(selected) ?: return
    note.time = timeField.text.toIntOrNull()?.coerceAtLeast(0) ?: note.time
    note.duration = durationField.text.toIntOrNull()?.coerceIn(1, 60_000) ?: note.duration
    note.instrument = instrumentField.text.toIntOrNull()?.coerceIn(0, 15) ?: note.instrument
    note.pitch = pitchField.text.toIntOrNull()?.coerceIn(0, 24) ?: note.pitch
    note.volume = volumeField.text.toIntOrNull()?.coerceIn(0, 100) ?: note.volume
    note.pan = panField.text.toIntOrNull()?.coerceIn(-100, 100) ?: note.pan
    songTitle = titleField.text.trim().take(120).ifBlank { "Untitled song" }
    bpm = bpmField.text.toIntOrNull()?.coerceIn(1, 60_000) ?: bpm
    notes.sortBy { it.time }; selected = notes.indexOf(note).coerceAtLeast(0)
    choose(selected); state = "Edited note ${selected + 1}/${notes.size}"
  }

  private fun deleteSelected() {
    if (notes.isEmpty()) return
    notes.removeAt(selected.coerceIn(0, notes.lastIndex))
    choose(selected.coerceAtMost(notes.lastIndex)); state = "Deleted note"
  }

  private fun loadMidi() {
    try {
      val input = selectedMidi ?: throw IllegalStateException("Select a .mid or .midi file in the MIDI library")
      val sequence = Files.newInputStream(input).use(MidiSystem::getSequence)
      require(sequence.divisionType == Sequence.PPQ) { "SMPTE MIDI is unsupported; export as PPQ MIDI" }
      notes.clear(); notes += parseSequence(sequence)
      require(notes.size <= 1_000_000) { "This MIDI has more than 1,000,000 playable notes" }
      notes.sortBy { it.time }
      songTitle = midiTitle(sequence).ifBlank { input.fileName.toString().substringBeforeLast('.') }.take(120)
      titleField.text = songTitle; choose(0); rewind(); fitTimeline()
      state = "Loaded ${notes.size} notes with tempo, instrument, volume and pan conversion"
    } catch (error: Exception) { state = "MIDI import failed: ${error.message ?: "invalid file"}" }
  }

  private fun midiTitle(sequence: Sequence): String = sequence.tracks.asSequence().flatMap { track ->
    (0 until track.size()).asSequence().map { track.get(it).message }
  }.filterIsInstance<MetaMessage>().firstOrNull { it.type == 0x03 }?.data?.toString(Charsets.UTF_8)?.trim().orEmpty()

  private fun parseSequence(sequence: Sequence): List<Note> {
    val events = sequence.tracks.flatMapIndexed { trackIndex, track ->
      (0 until track.size()).map { index -> TimedEvent(track.get(index).tick, trackIndex, index, track.get(index)) }
    }.sortedWith(compareBy<TimedEvent> { it.tick }.thenBy { it.track }.thenBy { it.order })
    val rawTempos = events.mapNotNull { timed -> (timed.event.message as? MetaMessage)?.takeIf { it.type == 0x51 && it.data.size == 3 }?.let {
      timed.tick to ((it.data[0].toInt() and 255 shl 16) or (it.data[1].toInt() and 255 shl 8) or (it.data[2].toInt() and 255))
    } }.toMutableList()
    if (rawTempos.none { it.first == 0L }) rawTempos.add(0, 0L to 500_000)
    val tempos = mutableListOf<TempoPoint>()
    var tempoTick = 0L; var tempoMicros = 0.0; var tempoValue = 500_000
    rawTempos.sortedBy { it.first }.forEach { (tick, value) ->
      tempoMicros += (tick - tempoTick) * tempoValue.toDouble() / sequence.resolution
      tempoTick = tick; tempoValue = value
      if (tempos.lastOrNull()?.tick == tick) tempos[tempos.lastIndex] = TempoPoint(tick, tempoMicros, value)
      else tempos += TempoPoint(tick, tempoMicros, value)
    }
    fun millisecondsAt(target: Long): Int {
      var low = 0; var high = tempos.lastIndex
      while (low < high) { val middle = (low + high + 1) / 2; if (tempos[middle].tick <= target) low = middle else high = middle - 1 }
      val point = tempos[low]
      val micros = point.microsAtTick + (target - point.tick) * point.microsPerQuarter.toDouble() / sequence.resolution
      return (micros / 1000.0).roundToInt().coerceAtLeast(0)
    }
    val states = Array(sequence.tracks.size) { Array(16) { ChannelState() } }
    val converted = mutableListOf<Note>()
    val active = mutableMapOf<String, ArrayDeque<Note>>()
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

  private fun finishNote(active: MutableMap<String, ArrayDeque<Note>>, timed: TimedEvent, message: ShortMessage, endMs: Int) {
    val key = "${timed.track}:${message.channel}:${message.data1}"
    val queue = active[key] ?: return
    val note = queue.pollFirst() ?: return
    note.duration = (endMs - note.time).coerceIn(1, 60_000)
    if (queue.isEmpty()) active.remove(key)
  }

  private fun convertedNote(time: Int, message: ShortMessage, state: ChannelState): Note {
    val drum = message.channel == 9
    val instrument = if (drum) drumInstrument(message.data1) else gmInstrument(state.program)
    val pitch = if (drum) drumPitch(message.data1) else foldPitch(message.data1 - 54)
    val volume = ((message.data2 / 127.0) * (state.volume / 127.0) * (state.expression / 127.0) * 100).roundToInt().coerceIn(0, 100)
    val pan = (((state.pan - 64) / 63.0) * 100).roundToInt().coerceIn(-100, 100)
    return Note(time, 120, instrument, pitch, volume, pan)
  }

  private fun foldPitch(value: Int): Int { var pitch = value; while (pitch < 0) pitch += 12; while (pitch > 24) pitch -= 12; return pitch }
  private fun gmInstrument(program: Int): Int = when (program.coerceIn(0, 127)) { in 0..7 -> 0; in 8..15 -> if (program in 9..10) 6 else if (program == 14) 8 else 9; in 16..23 -> if (program >= 19) 14 else 0; in 24..31 -> if (program >= 28) 14 else 7; in 32..39 -> 1; in 40..55 -> if (program >= 48) 15 else 7; in 56..63 -> if (program >= 60) 11 else 12; in 64..79 -> 5; in 80..87 -> 13; in 88..95 -> if (program >= 92) 8 else 15; in 96..103 -> if (program % 2 == 0) 13 else 8; in 104..111 -> if (program <= 107) 14 else 12; in 112..119 -> if (program <= 115) 6 else 11; else -> if (program >= 126) 4 else 13 }
  private fun drumInstrument(midi: Int): Int = when (midi) { 35, 36 -> 2; in 37..40, in 60..66 -> 3; 56 -> 11; 67, 68, 80, 81 -> 6; else -> 4 }
  private fun drumPitch(midi: Int): Int = when (midi) { 35 -> 8; 36 -> 11; in 37..40 -> 10 + (midi - 37) * 2; 42, 44 -> 8; 46 -> 14; 49, 51, 52, 54, 55, 57, 59 -> 20; 56 -> 12; 67, 80 -> 9; 68, 81 -> 16; in 60..66 -> midi - 53; 75, 76 -> 15; 77 -> 7; else -> 12 }.coerceIn(0, 24)

  private fun preview() { notes.getOrNull(selected)?.let(::previewNote) }

  private fun previewNote(note: Note) {
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
    client?.player?.playSound(sound, (note.volume / 100f).coerceIn(0f, 1f), 2.0.pow((note.pitch - 12) / 12.0).toFloat())
  }

  private fun durationMs() = notes.maxOfOrNull { it.time + it.duration }?.coerceAtLeast(1) ?: 1
  private fun lowerBound(timeMs: Int): Int {
    var low = 0; var high = notes.size
    while (low < high) { val middle = (low + high) / 2; if (notes[middle].time < timeMs) low = middle + 1 else high = middle }
    return low
  }
  private fun togglePlayback() {
    if (playing) { pausePlayback(); return }
    if (notes.isEmpty()) { state = "Load a MIDI file before playback"; return }
    if (playheadMs >= durationMs()) playheadMs = 0
    playbackStartMs = playheadMs; playbackStartedAt = System.currentTimeMillis(); nextPlaybackIndex = lowerBound(playheadMs); playing = true
    state = "Playing from ${formatTime(playheadMs)}"
  }
  private fun pausePlayback() { if (playing) updatePlaybackPosition(); playing = false; state = "Paused at ${formatTime(playheadMs)}" }
  private fun rewind() { playing = false; playheadMs = 0; playbackStartMs = 0; nextPlaybackIndex = 0; horizontalOffset = 0; state = "Returned to the start" }
  private fun seek(timeMs: Int) {
    playheadMs = timeMs.coerceIn(0, durationMs()); playbackStartMs = playheadMs; playbackStartedAt = System.currentTimeMillis(); nextPlaybackIndex = lowerBound(playheadMs)
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
    if (followPlayback && playheadMs > horizontalOffset + visibleSpan() * 3 / 4) horizontalOffset = (playheadMs - visibleSpan() / 2).coerceAtLeast(0)
    if (playheadMs >= durationMs()) { playing = false; state = "Playback finished" }
  }

  override fun keyPressed(input: KeyInput): Boolean {
    if (focused is TextFieldWidget) return super.keyPressed(input)
    return when (input.key()) {
      GLFW.GLFW_KEY_SPACE -> { togglePlayback(); true }
      GLFW.GLFW_KEY_HOME -> { rewind(); true }
      GLFW.GLFW_KEY_ESCAPE -> if (playing) { pausePlayback(); true } else super.keyPressed(input)
      else -> super.keyPressed(input)
    }
  }

  private fun exportAndUpload() {
    try {
      applySelected(); require(notes.isNotEmpty()) { "Add at least one note" }; require(notes.size <= 1_000_000) { "Too many notes" }
      val bytes = encode(); UploadClient.upload(bytes)
      state = "Prepared ${notes.size} notes for server upload; ${UploadClient.status()}"
    } catch (error: Exception) { state = "Upload preparation failed: ${error.message ?: "invalid data"}" }
  }

  private fun encode(): ByteArray {
    val metadata = "{\"format\":\"oyasai-midi-import\",\"version\":1,\"song\":{\"title\":${json(songTitle)},\"displayBpm\":$bpm}}".toByteArray(Charsets.UTF_8)
    val ordered = notes.sortedBy { it.time }; val duration = ordered.maxOf { it.time }
    return ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out ->
      out.writeInt(0x4F594D49); out.writeShort(1); out.writeShort(0); out.writeInt(metadata.size); out.writeInt(ordered.size); out.writeInt(duration); out.write(metadata)
      ordered.forEach { note -> out.writeInt(note.time); out.writeByte(note.instrument); out.writeByte(note.pitch); out.writeByte(note.volume); out.writeByte(note.pan) }
    }; bytes.toByteArray() }
  }

  private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
  private fun ensureDirectories() { Files.createDirectories(midiDirectory) }
  private fun openMidiFolder() {
    try { ensureDirectories(); Util.getOperatingSystem().open(midiDirectory); state = "Opened MIDI folder" }
    catch (error: Exception) { state = "Could not open MIDI folder: ${error.message ?: "unsupported system"}" }
  }
  private fun refreshMidiLibrary() {
    midiFiles = Files.list(midiDirectory).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().let { name -> name.endsWith(".mid") || name.endsWith(".midi") } }.sorted().toList() }
    libraryScroll = libraryScroll.coerceIn(0, (midiFiles.size - 1).coerceAtLeast(0))
  }
  private fun editorLeft() = libraryWidth() + 8
  private fun libraryWidth() = (width / 5).coerceIn(210, 300)
  private fun visibleSpan() = viewSpanMs.coerceAtLeast(2_000)
  private fun rollTop() = 204
  private fun noteTop() = rollTop() + 24
  private fun rollBottom() = height - 76
  private fun keyboardLeft() = editorLeft() + 12
  private fun plotLeft() = keyboardLeft() + 56
  private fun plotRight() = width - 12
  private fun timeToX(time: Int) = plotLeft() + ((time - horizontalOffset).toFloat() / visibleSpan() * (plotRight() - plotLeft())).roundToInt()
  private fun xToTime(x: Double) = (horizontalOffset + ((x - plotLeft()) / (plotRight() - plotLeft()).coerceAtLeast(1) * visibleSpan())).roundToInt()
  private fun pitchToY(pitch: Int): Int = rollBottom() - ((pitch + 1) * (rollBottom() - noteTop()) / 25f).roundToInt()
  private fun pitchAt(y: Double): Int = (((rollBottom() - y) / (rollBottom() - noteTop()).coerceAtLeast(1) * 25).toInt()).coerceIn(0, 24)
  private fun rowHeight() = ((rollBottom() - noteTop()) / 25f).coerceAtLeast(2f)
  private fun formatTime(timeMs: Int) = "%d:%02d.%03d".format(timeMs / 60_000, timeMs / 1_000 % 60, timeMs % 1_000)
  private fun fitTimeline() { horizontalOffset = 0; viewSpanMs = (durationMs() + 1_000).coerceAtLeast(2_000); state = "Timeline fitted to the song" }
  private fun zoomTimeline(multiplier: Double, anchorX: Double) {
    val oldSpan = visibleSpan(); val maximum = (durationMs() + 10_000).coerceAtLeast(30_000)
    val newSpan = (oldSpan * multiplier).roundToInt().coerceIn(2_000, maximum)
    val fraction = ((anchorX - plotLeft()) / (plotRight() - plotLeft()).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val anchorTime = horizontalOffset + (oldSpan * fraction).roundToInt()
    viewSpanMs = newSpan
    horizontalOffset = (anchorTime - (newSpan * fraction).roundToInt()).coerceIn(0, durationMs().coerceAtLeast(newSpan) - newSpan)
  }

  override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
    val x = click.x().roundToInt(); val y = click.y().roundToInt()
    if (click.button() == 0) {
      if (inRect(x, y, 12, height - 62, (libraryWidth() - 30) / 2, 26)) { refreshMidiLibrary(); state = "MIDI library refreshed"; return true }
      if (inRect(x, y, 18 + (libraryWidth() - 30) / 2, height - 62, (libraryWidth() - 30) / 2, 26)) { openMidiFolder(); return true }
      if (inRect(x, y, editorLeft() + 296, 58, 108, 22)) { loadMidi(); return true }
      if (inRect(x, y, editorLeft() + 396, 128, 58, 22)) { applySelected(); return true }
      if (inRect(x, y, editorLeft() + 460, 128, 42, 22)) { notes += Note(playheadMs, 200, 0, 12, 100, 0); notes.sortBy { it.time }; choose(notes.indexOfFirst { it.time == playheadMs }); state = "Added note at ${formatTime(playheadMs)}"; return true }
      if (inRect(x, y, editorLeft() + 508, 128, 60, 22)) { deleteSelected(); return true }
      if (inRect(x, y, editorLeft() + 574, 128, 70, 22)) { preview(); return true }
      if (inRect(x, y, editorLeft() + 16, 168, 42, 24)) { rewind(); return true }
      if (inRect(x, y, editorLeft() + 64, 168, 62, 24)) { togglePlayback(); return true }
      if (inRect(x, y, editorLeft() + 132, 168, 70, 24)) { followPlayback = !followPlayback; state = "Follow ${if (followPlayback) "enabled" else "disabled"}"; return true }
      if (inRect(x, y, editorLeft() + 208, 168, 46, 24)) { fitTimeline(); return true }
      if (inRect(x, y, editorLeft() + 260, 168, 30, 24)) { zoomTimeline(1.25, plotLeft().toDouble() + (plotRight() - plotLeft()) / 2); return true }
      if (inRect(x, y, editorLeft() + 296, 168, 30, 24)) { zoomTimeline(0.8, plotLeft().toDouble() + (plotRight() - plotLeft()) / 2); return true }
      if (inRect(x, y, width - 172, height - 54, 160, 34)) { exportAndUpload(); return true }
      val row = (y - 72) / 23 + libraryScroll
      if (x in 12 until libraryWidth() - 12 && y >= 72 && y < height - 76 && row in midiFiles.indices) { selectedMidi = midiFiles[row]; state = "Selected ${selectedMidi!!.fileName}"; if (doubled) loadMidi(); return true }
      if (y in rollTop() until noteTop() && x >= plotLeft()) { seek(xToTime(click.x())); state = "Seek ${formatTime(playheadMs)}"; return true }
      if (x in keyboardLeft() until plotLeft() && y in noteTop() until rollBottom()) { previewNote(Note(0, 200, notes.getOrNull(selected)?.instrument ?: 0, pitchAt(click.y()), 100, 0)); return true }
    }
    if (click.button() == 0 && click.y() >= noteTop() && click.y() <= rollBottom()) {
      val nearest = notes.withIndex().filter {
        val start = timeToX(it.value.time); val end = timeToX(it.value.time + it.value.duration).coerceAtLeast(start + 5)
        click.x() in (start - 2).toDouble()..(end + 2).toDouble() && kotlin.math.abs(pitchToY(it.value.pitch) - click.y()) <= rowHeight() / 2 + 2
      }.minByOrNull { kotlin.math.abs(timeToX(it.value.time) - click.x()) }
      if (nearest != null) { choose(nearest.index); preview(); return true }
    }
    return super.mouseClicked(click, doubled)
  }

  override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (mouseX < libraryWidth() && mouseY >= 72 && mouseY < height - 76) { libraryScroll = (libraryScroll - verticalAmount.roundToInt()).coerceIn(0, (midiFiles.size - 1).coerceAtLeast(0)); return true }
    if (mouseY >= rollTop() && mouseY <= rollBottom()) {
      val handle = client?.window?.handle ?: 0L
      val ctrl = handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
      if (ctrl) zoomTimeline(if (verticalAmount > 0) 0.8 else 1.25, mouseX)
      else horizontalOffset = (horizontalOffset - (verticalAmount * visibleSpan() / 8).roundToInt()).coerceIn(0, durationMs().coerceAtLeast(visibleSpan()) - visibleSpan())
      return true
    }
    return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
  }

  override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
    drawChrome(context)
    super.render(context, mouseX, mouseY, delta)
    context.drawTextWithShadow(textRenderer, "OMMT  •  MIDI WORKSPACE", 14, 13, 0xFFB9E769.toInt())
    context.drawTextWithShadow(textRenderer, "MIDI LIBRARY", 14, 45, 0xFFBFC7D5.toInt())
    context.drawTextWithShadow(textRenderer, "${midiFiles.size} files  •  double-click to load", 14, 58, 0xFF778295.toInt())
    context.drawTextWithShadow(textRenderer, "PROJECT / IMPORT", editorLeft() + 16, 38, 0xFF8D98A9.toInt())
    context.drawTextWithShadow(textRenderer, "SONG NAME", editorLeft() + 16, 48, 0xFF8D98A9.toInt())
    context.drawTextWithShadow(textRenderer, "BPM", editorLeft() + 218, 48, 0xFF8D98A9.toInt())
    context.drawCenteredTextWithShadow(textRenderer, "LOAD SELECTED", editorLeft() + 350, 65, 0xFF10151E.toInt())
    context.drawTextWithShadow(textRenderer, "NOTE INSPECTOR", editorLeft() + 16, 106, 0xFFBFC7D5.toInt())
    context.drawTextWithShadow(textRenderer, "TIME", editorLeft() + 16, 118, 0xFF778295.toInt())
    context.drawTextWithShadow(textRenderer, "LENGTH", editorLeft() + 90, 118, 0xFF778295.toInt())
    context.drawTextWithShadow(textRenderer, "INST", editorLeft() + 164, 118, 0xFF778295.toInt())
    context.drawTextWithShadow(textRenderer, "KEY", editorLeft() + 220, 118, 0xFF778295.toInt())
    context.drawTextWithShadow(textRenderer, "VOL", editorLeft() + 276, 118, 0xFF778295.toInt())
    context.drawTextWithShadow(textRenderer, "PAN", editorLeft() + 332, 118, 0xFF778295.toInt())
    context.drawTextWithShadow(textRenderer, "${formatTime(playheadMs)} / ${formatTime(durationMs())}", editorLeft() + 340, 176, 0xFFBFC7D5.toInt())
    context.drawTextWithShadow(textRenderer, "Selected ${selected + 1}/${notes.size}  •  Space: play/pause  •  Home: rewind  •  Ctrl+wheel: zoom", editorLeft() + 500, 176, 0xFF778295.toInt())
    drawPianoRoll(context)
    context.drawTextWithShadow(textRenderer, ellipsize(state, width - editorLeft() - 220), editorLeft() + 16, height - 54, 0xFFFFD166.toInt())
    context.drawTextWithShadow(textRenderer, ellipsize(UploadClient.status(), width - editorLeft() - 220), editorLeft() + 16, height - 38, 0xFF86D46A.toInt())
    context.drawCenteredTextWithShadow(textRenderer, "UPLOAD DRAFT", width - 92, height - 42, 0xFF10151E.toInt())
  }

  private fun inRect(x: Int, y: Int, left: Int, top: Int, rectWidth: Int, rectHeight: Int) = x in left until left + rectWidth && y in top until top + rectHeight
  private fun drawChrome(context: DrawContext) {
    context.fill(0, 0, width, height, 0xFF10131A.toInt())
    context.fill(0, 0, width, 32, 0xFF181D27.toInt())
    context.fill(0, 32, libraryWidth(), height, 0xFF171C25.toInt())
    context.fill(libraryWidth(), 32, libraryWidth() + 1, height, 0xFF3A4352.toInt())
    context.fill(editorLeft(), 32, width - 12, 92, 0xFF171C25.toInt())
    context.fill(editorLeft(), 100, width - 12, 158, 0xFF171C25.toInt())
    context.fill(editorLeft(), 164, width - 12, 198, 0xFF151A22.toInt())
    drawInputFrame(context, editorLeft() + 16, 58, 190, 22)
    drawInputFrame(context, editorLeft() + 218, 58, 62, 22)
    drawInputFrame(context, editorLeft() + 16, 128, 66, 22)
    drawInputFrame(context, editorLeft() + 90, 128, 66, 22)
    drawInputFrame(context, editorLeft() + 164, 128, 48, 22)
    drawInputFrame(context, editorLeft() + 220, 128, 48, 22)
    drawInputFrame(context, editorLeft() + 276, 128, 48, 22)
    drawInputFrame(context, editorLeft() + 332, 128, 52, 22)
    drawControl(context, editorLeft() + 296, 58, 108, 22, "", 0xFFB9E769.toInt())
    drawControl(context, editorLeft() + 396, 128, 58, 22, "APPLY", 0xFF3B526A.toInt())
    drawControl(context, editorLeft() + 460, 128, 42, 22, "+", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 508, 128, 60, 22, "DELETE", 0xFF3A2631.toInt())
    drawControl(context, editorLeft() + 574, 128, 70, 22, "PREVIEW", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 16, 168, 42, 24, "|◀", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 64, 168, 62, 24, if (playing) "PAUSE" else "PLAY", 0xFF4667A8.toInt())
    drawControl(context, editorLeft() + 132, 168, 70, 24, if (followPlayback) "FOLLOW ✓" else "FOLLOW", if (followPlayback) 0xFF40552C.toInt() else 0xFF293241.toInt())
    drawControl(context, editorLeft() + 208, 168, 46, 24, "FIT", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 260, 168, 30, 24, "−", 0xFF293241.toInt())
    drawControl(context, editorLeft() + 296, 168, 30, 24, "+", 0xFF293241.toInt())
    drawControl(context, 12, height - 62, (libraryWidth() - 30) / 2, 26, "REFRESH", 0xFF293241.toInt())
    drawControl(context, 18 + (libraryWidth() - 30) / 2, height - 62, (libraryWidth() - 30) / 2, 26, "OPEN FOLDER", 0xFF3B526A.toInt())
    context.drawTextWithShadow(textRenderer, "OMMT/midi", 14, height - 25, 0xFF778295.toInt())
    drawControl(context, width - 172, height - 54, 160, 34, "", 0xFFB9E769.toInt())
    val rows = ((height - 158) / 23).coerceAtLeast(1)
    midiFiles.drop(libraryScroll).take(rows).forEachIndexed { local, path ->
      val y = 72 + local * 23; val selected = path == selectedMidi
      context.fill(12, y, libraryWidth() - 12, y + 20, if (selected) 0xFF34465A.toInt() else 0xFF202734.toInt())
      context.drawTextWithShadow(textRenderer, ellipsize(path.fileName.toString(), libraryWidth() - 46), 19, y + 6, if (selected) 0xFFB9E769.toInt() else 0xFFD8DEE8.toInt())
    }
    if (midiFiles.isEmpty()) {
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
    if (label.isNotBlank()) context.drawCenteredTextWithShadow(textRenderer, label, x + controlWidth / 2, y + (controlHeight - 8) / 2, 0xFFEAF0F8.toInt())
  }

  private fun drawPianoRoll(context: DrawContext) {
    val top = rollTop(); val notesTop = noteTop(); val bottom = rollBottom(); val left = keyboardLeft(); val plotLeft = plotLeft(); val right = plotRight()
    context.fill(left, top, right, bottom, 0xA0101218.toInt())
    context.fill(left, top, plotLeft, notesTop, 0xFF202631.toInt())
    context.drawCenteredTextWithShadow(textRenderer, "KEY", left + (plotLeft - left) / 2, top + 8, 0xFF778295.toInt())
    val blackKeys = setOf(1, 3, 6, 8, 10)
    for (pitch in 0..24) {
      val y = pitchToY(pitch); val nextY = pitchToY(pitch - 1).coerceAtMost(bottom); val midiClass = (pitch + 54) % 12; val black = midiClass in blackKeys
      context.fill(plotLeft, y, right, nextY, if (black) 0xFF121720.toInt() else 0xFF171C25.toInt())
      context.fill(left, y, plotLeft, nextY, if (black) 0xFF252B35.toInt() else 0xFFE1E5E8.toInt())
      if (black) context.fill(left, y, left + 34, nextY, if (pitch % 12 == 0) 0xFF637D36.toInt() else 0xFF242A34.toInt())
      val lineColor = if (pitch % 12 == 0) 0xFF607842.toInt() else 0xFF2D3440.toInt()
      context.fill(plotLeft, y, right, y + 1, lineColor)
      if (pitch % 12 == 0 || pitch == 24) context.drawTextWithShadow(textRenderer, "F♯${3 + pitch / 12}", left + 4, y + 2, if (black) 0xFFDAF2AB.toInt() else 0xFF30343A.toInt())
    }
    val span = visibleSpan(); val step = when { span <= 10_000 -> 500; span <= 60_000 -> 2_000; else -> 10_000 }
    context.fill(plotLeft, top, right, notesTop, 0xFF1D232D.toInt())
    for (time in (horizontalOffset / step) * step..horizontalOffset + span step step) {
      val x = timeToX(time); context.fill(x, top, x + 1, bottom, if (time % (step * 4) == 0) 0xFF515C6D.toInt() else 0xFF303744.toInt())
      context.drawTextWithShadow(textRenderer, formatRuler(time), x + 3, top + 8, 0xFFAAB3C2.toInt())
    }
    notes.forEachIndexed { index, note -> if (note.time + note.duration >= horizontalOffset && note.time <= horizontalOffset + span) {
      val startX = timeToX(note.time).coerceAtLeast(plotLeft); val endX = timeToX(note.time + note.duration).coerceAtMost(right).coerceAtLeast(startX + 5)
      val y = pitchToY(note.pitch); val height = rowHeight().roundToInt().coerceAtLeast(4); val color = if (index == selected) 0xFFFFD166.toInt() else instrumentColor(note.instrument)
      context.fill(startX, y + 2, endX, (y + height - 1).coerceAtMost(bottom), color)
      if (index == selected) { context.fill(startX, y + 1, endX, y + 2, 0xFFFFFFFF.toInt()); context.fill(startX, y + height - 1, endX, y + height, 0xFFB87918.toInt()) }
    } }
    if (playheadMs in horizontalOffset..horizontalOffset + span) { val x = timeToX(playheadMs); context.fill(x, top, x + 2, bottom, 0xFFFF5A66.toInt()); context.fill(x - 4, top, x + 6, top + 4, 0xFFFF5A66.toInt()) }
    context.fill(left, top, right, top + 1, 0xFF5C6473.toInt())
    context.fill(left, bottom - 1, right, bottom, 0xFF5C6473.toInt())
    context.fill(left, top, left + 1, bottom, 0xFF5C6473.toInt())
    context.fill(right - 1, top, right, bottom, 0xFF5C6473.toInt())
  }

  private fun formatRuler(timeMs: Int) = if (visibleSpan() <= 10_000) "%.1fs".format(timeMs / 1000.0) else "${timeMs / 1000}s"

  private fun instrumentColor(instrument: Int): Int = intArrayOf(0xFFB9E769.toInt(), 0xFF74C69D.toInt(), 0xFFFF9B71.toInt(), 0xFFF7C66B.toInt(), 0xFFD8D8D8.toInt(), 0xFF79C7FF.toInt(), 0xFFFFD166.toInt(), 0xFFE9A66F.toInt(), 0xFFA8DADC.toInt(), 0xFFF4A261.toInt(), 0xFFB8C0CC.toInt(), 0xFFC5A46D.toInt(), 0xFFD97745.toInt(), 0xFF66D9A6.toInt(), 0xFFE9C46A.toInt(), 0xFFF6E58D.toInt())[instrument.coerceIn(0, 15)]
  override fun shouldPause() = false
}
