package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt
import com.github.sahyuya.oyasaimusicmiditranslator.client.AutomationCurve
import com.github.sahyuya.oyasaimusicmiditranslator.client.bufferedElapsedMillis
import com.github.sahyuya.oyasaimusicmiditranslator.client.editorPlaybackPositionMs
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorAutomation
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorAction
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorHistory
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorWorkspace
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeyStroke
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeymap
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorNote
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorProjectCodec
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSelection
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSettings
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSettingsBundleCodec
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorStylePresets
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSnapshot
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorTheme
import com.github.sahyuya.oyasaimusicmiditranslator.client.GridMark
import com.github.sahyuya.oyasaimusicmiditranslator.client.MidiInstrumentMapper
import com.github.sahyuya.oyasaimusicmiditranslator.client.MidiFileCodec
import com.github.sahyuya.oyasaimusicmiditranslator.client.NbsFileCodec
import com.github.sahyuya.oyasaimusicmiditranslator.client.ReleaseControlPoint
import com.github.sahyuya.oyasaimusicmiditranslator.client.RetriggerProfile
import com.github.sahyuya.oyasaimusicmiditranslator.client.SignatureMark
import com.github.sahyuya.oyasaimusicmiditranslator.client.TempoControlPoint
import com.github.sahyuya.oyasaimusicmiditranslator.client.TempoMark
import javax.sound.midi.MetaMessage
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

object UploadV2CodecVerification {
  @JvmStatic fun main(args: Array<String>) {
    unicode15RoundTripAndThreeByteAlphabet(); compactCanonicalRoundTripAndMalformedRejection(); packetBoundaryUsesRawBytes(); playbackWireGoldenAndState(); editorTempoAndRetriggerModel(); editorHistoryTempoIsolation(); editorProjectAndSettingsRoundTrip(); selectionAndMusicalLength(); nbsAndMidiImport(); midiTempoAndSustainImport(); japaneseTitleRoundTrip(); bufferedPlaybackDoesNotStartEarly(); editorPlaybackClockAdvancesAndClamps()
    args.forEach { rawPath ->
      val path = Path.of(rawPath)
      val files = if (Files.isDirectory(path)) Files.list(path).use { stream -> stream.filter(Files::isRegularFile).sorted().toList() } else listOf(path)
      files.forEach(::verifyImportFile)
    }
    println("UploadV2CodecVerification: PASS")
  }
  private fun verifyImportFile(path: Path) {
    when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
      "nbs" -> {
        val song = NbsFileCodec.decode(Files.readAllBytes(path))
        check(song.header.version in 0..6)
        println("NBS file PASS: ${path.fileName} (v${song.header.version}, ${song.notes.size} notes, ${song.layers.size} layers, ${song.customInstrumentCount} custom, ${song.normalizedValueCount} normalized)")
        if (song.customInstruments.isNotEmpty()) {
          println("  custom samples: " + song.customInstruments.take(12).joinToString { "${it.id}:${it.name}=${it.soundFile}@${it.key}${if (it.pressKey) "+key" else ""}" })
          val controls = song.customInstruments.filter { NbsFileCodec.isControlInstrument(song, it.id) }
          if (controls.isNotEmpty()) {
            println("  non-audio controls: " + controls.joinToString { control ->
              "${control.name}=${song.notes.count { it.instrument == control.id }} notes"
            })
          }
        }
      }
      "mid", "midi" -> {
        val song = MidiFileCodec.decode(Files.readAllBytes(path))
        val commonGaps = song.tempoMarks.zipWithNext().map { it.second.tick - it.first.tick }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(3).joinToString { "${it.key}x${it.value}" }
        println("MIDI file PASS: ${path.fileName} (${song.notes.size} notes, PPQ ${song.ppq}, ${song.rawTempoPointCount}->${song.tempoControls.size} tempo UI, gaps [$commonGaps], ${song.sustainExtendedNotes} sustained)")
      }
    }
  }
  private inline fun rejects(block: () -> Unit) { try { block(); error("expected rejection") } catch (_: IllegalArgumentException) { } }
  private fun oymi(): ByteArray = ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out ->
    val metadata = "{}".toByteArray(); out.writeInt(0x4f594d49); out.writeShort(1); out.writeShort(0); out.writeInt(metadata.size); out.writeInt(1); out.writeInt(120); out.write(metadata)
    out.writeInt(120); out.writeByte(0); out.writeByte(12); out.writeByte(100); out.writeByte(0)
  }; bytes.toByteArray() }

  private fun unicode15RoundTripAndThreeByteAlphabet() {
    val source = byteArrayOf(0, 1, 2, 3, 4, -1)
    val text = UploadV2Codec.unicode15(source)
    check(text.toByteArray(StandardCharsets.UTF_8).size == text.length * 3)
    check(source.contentEquals(UploadV2Codec.unicode15Decode(text, source.size)))
    rejects { UploadV2Codec.unicode15Decode("A", 1) }
    val damaged = text.dropLast(1) + '\u3401'
    rejects { UploadV2Codec.unicode15Decode(damaged, source.size) }
  }

  private fun compactCanonicalRoundTripAndMalformedRejection() {
    val canonical = oymi(); val compact = UploadV2Codec.compactFromOymi(canonical)
    check(canonical.contentEquals(UploadV2Codec.reconstructOymi(compact)))
    // This checked-in v1 vector is shared with Paper; both sides must reconstruct these exact bytes.
    val fixture = Base64.getDecoder().decode(Files.readString(Path.of("..", "docs", "interop", "fixtures", "minimal-oymi-v1.oyasai.base64")).trim())
    check(fixture.contentEquals(UploadV2Codec.reconstructOymi(UploadV2Codec.compactFromOymi(fixture))))
    val customFixture = Base64.getDecoder().decode(Files.readString(Path.of("..", "docs", "interop", "fixtures", "minimal-oymi-v2-custom.oyasai.base64")).trim())
    check(customFixture.contentEquals(UploadV2Codec.reconstructOymi(UploadV2Codec.compactFromOymi(customFixture))))
    val fixedPatternFixture = Base64.getDecoder().decode(Files.readString(Path.of("..", "docs", "interop", "fixtures", "minimal-oymi-v3-custom-pattern.oyasai.base64")).trim())
    check(fixedPatternFixture.contentEquals(UploadV2Codec.reconstructOymi(UploadV2Codec.compactFromOymi(fixedPatternFixture))))
    val pitchCentsFixture = Base64.getDecoder().decode(Files.readString(Path.of("..", "docs", "interop", "fixtures", "minimal-oymi-v4-pitch-cents.oyasai.base64")).trim())
    check(pitchCentsFixture.contentEquals(UploadV2Codec.reconstructOymi(UploadV2Codec.compactFromOymi(pitchCentsFixture))))
    rejects { UploadV2Codec.reconstructOymi(compact + byteArrayOf(0)) }
    val overlong = compact.copyOfRange(0, 5) + byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0) 
    rejects { UploadV2Codec.reconstructOymi(overlong) }
    // 0x80 0x00 is a numerically valid zero but not canonical/minimal varuint.
    rejects { UploadV2Codec.reconstructOymi(compact.copyOfRange(0, 5) + byteArrayOf(0x80.toByte(), 0) + compact.copyOfRange(6, compact.size)) }
    val tooMany = List(100_001) { UploadV2Codec.Note(it, 0, 12, 100, 0) }
    rejects { UploadV2Codec.compact(UploadV2Codec.Compact(byteArrayOf(), 100_000, tooMany)) }
  }

  private fun packetBoundaryUsesRawBytes() {
    val id = UUID(1, 2)
    val chunk = OmmtPluginWire.uploadChunk(id, 0, 1, ByteArray(OmmtPluginWire.CHUNK_BYTES))
    check(chunk.size == OmmtPluginWire.ENVELOPE_BYTES + 6 + OmmtPluginWire.CHUNK_BYTES)
    rejects { OmmtPluginWire.uploadChunk(id, 0, 1, ByteArray(OmmtPluginWire.CHUNK_BYTES + 1)) }
  }
  private fun playbackWireGoldenAndState() {
    val id=UUID(1,2)
    // All v1 types have a deterministic envelope and exact body/EOF boundary.
    (1..7).forEach { type -> val packet=PlaybackWireCodec.encode(type,id){ when(type){1->writeUTF("abcdefghijklmnopqrstuv");2-> {writeShort(1);writeInt(1);write(ByteArray(32));writeInt(0);writeByte(0);writeInt(500)};3->{writeShort(0);writeShort(1);writeShort(1);writeByte(1)};4,6->{writeInt(500);writeInt(0)};5->writeInt(0);7->writeByte(0)} }; val (actual,session,input)=PlaybackWireCodec.decode(packet);check(actual==type&&session==id); while(input.available()>0)input.readByte();check(input.available()==0); rejects{PlaybackWireCodec.decode(packet+byteArrayOf(0)).also{(_,_,i)->require(i.available()==0)}} }
    rejects { PlaybackWireCodec.decode(byteArrayOf(2)+ByteArray(17)) }; rejects { PlaybackWireCodec.decode(byteArrayOf(1,15)+ByteArray(16)) }
    // Type 10 is a connection capability notification, not a presence probe.
    val capabilities = OmmtPluginWire.envelope(
        OmmtPluginWire.PLAYBACK_SERVER_CAPABILITIES,
        UUID(0L, 0L),
    ) { writeInt(OmmtPluginWire.CAP_BRASS_NOTE_BLOCK) }
    OmmtPluginWire.input(capabilities).use { input ->
      check(input.readUnsignedByte() == OmmtPluginWire.PLAYBACK_SERVER_CAPABILITIES)
      check(input.readLong() == 0L && input.readLong() == 0L)
      check(input.readInt() == OmmtPluginWire.CAP_BRASS_NOTE_BLOCK)
      check(input.available() == 0)
    }
    val nonce = "abcdefghijklmnopqrstuv"
    val clientCapabilities = OmmtPluginWire.playbackClientCapabilities(
        nonce,
        OmmtPluginWire.CAP_OYPB_V2 or OmmtPluginWire.CAP_BANK_MANIFEST_V1,
    )
    OmmtPluginWire.input(clientCapabilities).use { input ->
      check(input.readUnsignedByte() == OmmtPluginWire.PLAYBACK_CLIENT_CAPABILITIES)
      check(input.readLong() == 0L && input.readLong() == 0L)
      check(input.readUTF() == nonce)
      check(input.readInt() == OmmtPluginWire.CAP_OYPB_V2 or OmmtPluginWire.CAP_BANK_MANIFEST_V1)
      check(input.available() == 0)
    }
    val playbackHash = ByteArray(32) { (it * 7).toByte() }
    OmmtPluginWire.input(OmmtPluginWire.playbackStartedAck(id, playbackHash, 125)).use { input ->
      check(input.readUnsignedByte() == OmmtPluginWire.PLAYBACK_STARTED_ACK)
      check(input.readLong() == id.mostSignificantBits && input.readLong() == id.leastSignificantBits)
      check(input.readNBytes(32).contentEquals(playbackHash))
      check(input.readInt() == 125)
      check(input.available() == 0)
    }
    OmmtPluginWire.input(OmmtPluginWire.playbackFailed(id, 3, 456)).use { input ->
      check(input.readUnsignedByte() == OmmtPluginWire.PLAYBACK_CLIENT_PLAYBACK_FAILED)
      check(input.readLong() == id.mostSignificantBits && input.readLong() == id.leastSignificantBits)
      check(input.readUnsignedByte() == 3)
      check(input.readInt() == 456)
      check(input.available() == 0)
    }
    // Pure ready route state: no ACK stays vanilla; exact generation/session/hash activates local;
    // reconnect/quit generation loss returns it to vanilla.
    var generation=7L; val hash="h"; var ready:Pair<Long,String>?=null; fun local()=ready?.first==generation&&ready?.second==hash
    check(!local()); ready=generation to hash; check(local()); generation++; check(!local())
  }
  private fun editorTempoAndRetriggerModel() {
    val old = listOf(TempoMark(0, 0, 500_000))
    val controls = listOf(TempoControlPoint(0, 120), TempoControlPoint(1_920, 240, AutomationCurve.SMOOTH))
    val compiled = EditorAutomation.compileTempo(controls, 480)
    check(compiled.size > 2)
    check(compiled.drop(1).dropLast(1).any { it.microsPerQuarter in 250_001..499_999 })
    val note = EditorNote(1_000, 500, 0, 12, 80, 0, sourceTick = 960, sourceDurationTicks = 480)
    EditorAutomation.retimeNotes(listOf(note), old, compiled, 480)
    check(note.time in 500..999 && note.duration in 200..500)

    val profile = RetriggerProfile(true, thresholdMs = 500, intervalMs = 200, startVolumePercent = 100, endVolumePercent = 50, curve = AutomationCurve.SMOOTH)
    val events = EditorAutomation.expand(listOf(note.copy(time = 0, duration = 1_000, volume = 80)), profile, emptyMap())
    check(events.map { it.time } == listOf(0, 200, 400, 600, 800))
    check(events.first().volume == 80 && events.last().volume == 40)
    check(events.zipWithNext().all { (a, b) -> a.volume >= b.volume })

    val shaped = profile.copy(middlePoints = listOf(ReleaseControlPoint(30, 90), ReleaseControlPoint(70, 20)))
    check(EditorAutomation.releaseEnvelope(shaped, .30).roundToInt() == 90)
    check(EditorAutomation.releaseEnvelope(shaped, .70).roundToInt() == 20)
    val clamped = shaped.copy(startVolumePercent = 180, endVolumePercent = 150, middlePoints = listOf(ReleaseControlPoint(50, 160))).normalized()
    check(clamped.startVolumePercent == 100 && clamped.endVolumePercent == 100 && clamped.middlePoints.single().volumePercent == 100)
    check(EditorAutomation.releaseEnvelope(clamped, .5).roundToInt() == 100)
    val musical = shaped.copy(thresholdDivisor = 4, intervalDivisor = 8)
    val musicalNote = note.copy(time = 0, duration = 1_000, sourceTick = 0, sourceDurationTicks = 960, volume = 50)
    val musicalEvents = EditorAutomation.expand(listOf(musicalNote), musical, emptyMap(), tempoMarks = old, ppq = 480)
    check(musicalEvents.map { it.time } == listOf(0, 250, 500, 750))
    rejects { EditorAutomation.expand(List(100_001) { note.copy(id = it.toLong()) }, RetriggerProfile(), emptyMap()) }
  }

  private fun editorHistoryTempoIsolation() {
    val tempo = listOf(TempoControlPoint(0, 120, AutomationCurve.STEP))
    check(EditorHistory.hasSameTempoLayout(tempo, tempo.map { it.copy(id = 9_999) }))
    check(!EditorHistory.hasSameTempoLayout(tempo, tempo.map { it.copy(bpm = 121) }))
    val note = EditorNote(0, 125, 0, 12, 100, 0)
    val state = EditorHistory.State(listOf(note), setOf(note.id), note.id, "Song", 120, listOf("Part 1"), 0, tempo)
    val history = EditorHistory()
    history.push(state)
    history.clear()
    check(history.undo(state) == null)
  }

  private fun editorProjectAndSettingsRoundTrip() {
    val release = RetriggerProfile(true, 500, 125, 100, 55, AutomationCurve.SMOOTH, middlePoints=listOf(ReleaseControlPoint(40, 80)))
    val first = EditorNote(250, 500, 0, 12, 90, -10, part=0, sourceTrack=1, sourceChannel=2, sourceTick=240, sourceDurationTicks=480, retriggerOverride=release)
    val second = EditorNote(1_000, 250, 0, 36, 70, 20, part=1, sourceTrack=3, sourceChannel=4, sourceTick=960, sourceDurationTicks=240, customSound="minecraft:block.note_block.harp", customSoundPattern=1)
    val snapshot = EditorSnapshot(
        notes=listOf(first,second), selectedIds=setOf(second.id), selected=1, title="Project テスト", bpm=120, offset=200, span=4_000,
        part=1, parts=listOf("Lead","Bass renamed"), ppq=480, beats=4, unit=4, pitchMin=-12, visiblePitches=49,
        snapDivisor=16, followPlayback=true, playheadMs=750, allPartsView=false,
        tempos=listOf(TempoMark(0,0,500_000)), signatures=listOf(SignatureMark(0,4,4)),
        grid=listOf(GridMark(0,0,1,1,0,true,true)), tempoControls=listOf(TempoControlPoint(0,120)),
    )
    val encoded = EditorProjectCodec.encode(snapshot)
    val decoded = EditorProjectCodec.decode(encoded)
    check(decoded.title==snapshot.title && decoded.parts==snapshot.parts && decoded.notes.size==2)
    check(decoded.notes[0].retriggerOverride==release.normalized())
    check(decoded.notes[1].customSound==second.customSound && decoded.notes[1].customSoundPattern==1)
    check(decoded.selectedIds==setOf(decoded.notes[1].id) && decoded.selected==1)
    rejects { EditorProjectCodec.decode(encoded.copyOf().also { it[0]=0 }) }
    rejects { EditorProjectCodec.decode(encoded + 0) }

    // Keep this pure verifier independent from LWJGL's native DLL. Runtime key names are covered
    // by the same codec, while UNBOUND exercises every action slot without native GLFW calls.
    val portableKeymap = EditorKeymap(EditorAction.entries.associateWith { EditorKeyStroke.UNBOUND })
    val customStyle = EditorStylePresets.forTheme(EditorTheme.MIDNIGHT_BLUE).copy(accentColor=0xFF44CC88.toInt(), rounding=9, scrollbarSize=18)
    val settings = EditorSettings(theme=EditorTheme.MIDNIGHT_BLUE, style=customStyle, uiScalePercent=125, showLibrary=false, keymap=portableKeymap)
    val text = EditorSettingsBundleCodec.encode(settings,"[Window][PIANO ROLL]\nPos=1,2\n")
    val bundle = EditorSettingsBundleCodec.decode(text)
    check(bundle.settings.theme==EditorTheme.MIDNIGHT_BLUE && bundle.settings.uiScalePercent==125 && !bundle.settings.showLibrary)
    check(bundle.settings.style==customStyle)
    check(bundle.layout.contains("PIANO ROLL"))
    val legacy = EditorSettingsBundleCodec.decode(legacyEditorSettingsV1(settings, "[Window][LEGACY]\n"))
    check(legacy.settings.version==6 && legacy.settings.theme==EditorTheme.MIDNIGHT_BLUE)
    check(legacy.settings.style==EditorStylePresets.forTheme(EditorTheme.MIDNIGHT_BLUE))
    check(legacy.layout.contains("LEGACY"))
    rejects { EditorSettingsBundleCodec.decode("not-an-ommt-setting") }
    // CONFIRM keymap action: non-default stroke round-trips through the share bundle.
    check(EditorKeyStroke.parse("ENTER", EditorKeyStroke.UNBOUND) == EditorAction.CONFIRM.default)
    check(EditorKeyStroke.parse("CTRL+ENTER", EditorKeyStroke.UNBOUND).control)
    val confirmKeymap = EditorKeymap(EditorAction.entries.associateWith { if (it == EditorAction.CONFIRM) EditorKeyStroke.parse("CTRL+ENTER", EditorKeyStroke.UNBOUND) else EditorKeyStroke.UNBOUND })
    val confirmBundle = EditorSettingsBundleCodec.decode(EditorSettingsBundleCodec.encode(settings.copy(keymap = confirmKeymap), "[Window][CONFIRM]\n"))
    check(confirmBundle.settings.keymap[EditorAction.CONFIRM] == EditorKeyStroke.parse("CTRL+ENTER", EditorKeyStroke.UNBOUND))
  }

  /** Recreates the 2.1.0 settings-bundle wire shape so the v2 decoder cannot drop compatibility. */
  private fun legacyEditorSettingsV1(settings: EditorSettings, layout: String): String {
    val bytes = ByteArrayOutputStream()
    GZIPOutputStream(bytes).use { gzip -> DataOutputStream(gzip).use { out ->
      fun writeString(value: String) { val encoded=value.toByteArray(StandardCharsets.UTF_8);out.writeInt(encoded.size);out.write(encoded) }
      out.writeInt(0x4f4d4346);out.writeShort(1)
      out.writeBoolean(settings.compactToolbar);out.writeBoolean(settings.showLibrary);out.writeBoolean(settings.showInspector);out.writeBoolean(settings.showAutomation)
      writeString(settings.gridDensity);out.writeBoolean(settings.showOtherParts);out.writeInt(settings.followLead);out.writeByte(settings.lastTool.ordinal);out.writeInt(settings.uiScalePercent);out.writeByte(settings.theme.ordinal)
      out.writeByte(settings.wheelPlain.ordinal);out.writeByte(settings.wheelShift.ordinal);out.writeByte(settings.wheelControl.ordinal);out.writeByte(settings.wheelAlt.ordinal)
      out.writeByte(settings.rangeSelectionModifier.ordinal);out.writeByte(settings.additiveSelectionModifier.ordinal);out.writeByte(settings.panMouseButton.ordinal)
      out.writeInt(EditorAction.entries.size);EditorAction.entries.forEach { action -> writeString(settings.keymap[action].encode()) }
      writeString(layout)
    } }
    return "OMMTCFG1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
  }

  private fun selectionAndMusicalLength() {
    check(!EditorSelection.intersectsMarquee(1_000,250,12,500,1_000,10,14))
    check(EditorSelection.intersectsMarquee(400,250,12,500,1_000,10,14))
    check(EditorSelection.intersectsMarquee(500,250,12,500,1_000,10,14))
    val tempo=listOf(TempoMark(0,0,500_000))
    val note=EditorNote(0,500,0,12,100,0,sourceTick=0,sourceDurationTicks=480)
    check(EditorAutomation.durationForDivision(note,4,tempo,480)==500)
    check(EditorAutomation.durationForDivision(note,64,tempo,480) in 31..32)
  }

  private fun nbsAndMidiImport() {
    val modern = nbsModern()
    val song = NbsFileCodec.decode(modern)
    check(song.header.version == 6 && song.header.defaultInstruments == 20 && song.header.songName == "NBS Test")
    check(song.header.ticksPerSecond == 10.0 && song.header.beatsPerBar == 4)
    check((song.header.ticksPerSecond * 15.0).roundToInt() == 150)
    check(song.notes.size == 3 && song.layers.size == 2 && song.customInstrumentCount == 1)
    check(song.customInstruments.single().name == "External" && song.customInstruments.single().soundFile == "external.ogg")
    check(NbsFileCodec.toOmmtPitchCents(58, 0) == 2_500) // IRIS OUT portal trigger: G5
    check(NbsFileCodec.toOmmtPitchCents(58, 0, 45) == 2_500)
    check(NbsFileCodec.toOmmtPitchCents(58, -25, 47) == 2_675)
    check(NbsFileCodec.effectivePanning(20, -40) == -10)
    check(NbsFileCodec.effectivePanning(100, 100) == 100)
    check(NbsFileCodec.toOmmtInstrument(5, song.header.defaultInstruments) == 7)
    check(NbsFileCodec.toOmmtInstrument(6, song.header.defaultInstruments) == 5)
    check(NbsFileCodec.toOmmtInstrument(7, song.header.defaultInstruments) == 6)
    check(NbsFileCodec.toOmmtInstrument(16, song.header.defaultInstruments) == null)
    check(NbsFileCodec.toMinecraftSound(16, song.header.defaultInstruments) == "minecraft:block.note_block.trumpet")
    check(NbsFileCodec.toMinecraftSound(19, song.header.defaultInstruments) == "minecraft:block.note_block.trumpet_oxidized")
    check(NbsFileCodec.toMinecraftSound(20, song.header.defaultInstruments) == null)
    check(song.notes[1].panning == 20 && song.notes[1].detuneCents == 100)
    check(NbsFileCodec.normalizedSoundPath("assets/minecraft/sounds/ambient/cave/cave1.ogg") == "ambient/cave/cave1")
    rejects { NbsFileCodec.decode(modern.copyOf(modern.size - 1)) }
    rejects { NbsFileCodec.decode(modern + 0) }
    rejects { NbsFileCodec.decode(nbsModern(firstKey = 88)) }
    rejects { NbsFileCodec.decode(nbsModern(secondInstrument = 21)) }
    rejects { NbsFileCodec.decode(nbsModern(version = 7)) }
    check(NbsFileCodec.decode(nbsModern(version = 5, defaultInstruments = 16)).header.version == 5)
    check(NbsFileCodec.decode(nbsModern(secondVelocity = 255)).normalizedValueCount == 1)

    val classic = NbsFileCodec.decode(nbsV0())
    check(classic.header.version == 0 && classic.header.defaultInstruments == 10)
    check(classic.notes.single().key == 45 && classic.notes.single().velocity == 100)

    check(MidiInstrumentMapper.mapProgram(0) == 0)
    check(MidiInstrumentMapper.mapProgram(32) == 1)
    check(MidiInstrumentMapper.mapProgram(0, "Lead Guitar") == 7)
    check(MidiInstrumentMapper.mapProgram(0, "フルート") == 5)
    check(MidiInstrumentMapper.mapProgram(70, "Bassoon") == 12)
    val shiftJis = "主旋律ギター".toByteArray(Charset.forName("windows-31j"))
    check(MidiInstrumentMapper.decodeText(shiftJis) == "主旋律ギター")
    check(EditorWorkspace.folderOpenCommand("Windows 11", Path.of("C:/OMMT/midi"))?.first() == "explorer.exe")
    check(EditorWorkspace.folderOpenCommand("Darwin", Path.of("/tmp/OMMT/midi"))?.first() == "open")
    check(EditorWorkspace.folderOpenCommand("Linux", Path.of("/tmp/OMMT/midi"))?.first() == "xdg-open")
  }

  private fun midiTempoAndSustainImport() {
    val sequence = Sequence(Sequence.PPQ, 480)
    val track = sequence.createTrack()
    fun short(command: Int, channel: Int, data1: Int, data2: Int, tick: Long) {
      track.add(javax.sound.midi.MidiEvent(ShortMessage(command, channel, data1, data2), tick))
    }
    val tempo = MetaMessage().also { it.setMessage(0x51, byteArrayOf(0x07, 0xA1.toByte(), 0x20), 3) }
    track.add(javax.sound.midi.MidiEvent(tempo, 0))
    short(ShortMessage.NOTE_ON, 0, 60, 100, 0)
    short(ShortMessage.CONTROL_CHANGE, 0, 64, 127, 120)
    short(ShortMessage.NOTE_OFF, 0, 60, 0, 240)
    short(ShortMessage.CONTROL_CHANGE, 0, 64, 0, 480)
    val imported = MidiFileCodec.decode(sequence)
    check(imported.notes.single().sourceDurationTicks == 480L)
    check(imported.notes.single().duration in 499..501)
    check(imported.sustainExtendedNotes == 1)

    val dense = (0..200).map { index ->
      val bpm = 100 + index
      TempoMark(index * 30L, index * 10, (60_000_000.0 / bpm).roundToInt())
    }
    val simplified = MidiFileCodec.simplifyTempoControls(dense, 480)
    check(simplified.size < 32) { "Dense linear tempo ramp was not simplified: ${simplified.size}" }
    check(simplified.drop(1).all { it.curve == AutomationCurve.LINEAR })
  }

  private fun nbsModern(version: Int = 6, defaultInstruments: Int = 20, firstKey: Int = 33, secondInstrument: Int = 16, secondVelocity: Int = 100): ByteArray = ByteArrayOutputStream().use { bytes ->
    fun u8(value: Int) = bytes.write(value)
    fun u16(value: Int) { u8(value); u8(value ushr 8) }
    fun i16(value: Int) = u16(value and 0xFFFF)
    fun u32(value: Int) { u16(value); u16(value ushr 16) }
    fun string(value: String) { val encoded=value.toByteArray(Charset.forName("windows-1252"));u32(encoded.size);bytes.write(encoded) }
    u16(0);u8(version);u8(defaultInstruments);u16(8);u16(2)
    string("NBS Test");string("Author");string("");string("Description")
    u16(1_000);u8(0);u8(10);u8(4);repeat(5){u32(0)};string("source.mid");u8(0);u8(0);u16(0)
    // tick 0, layer 0: NBS guitar; layer 1: v6 copper trumpet (v5: first custom instrument).
    u16(1);u16(1);u8(5);u8(firstKey);u8(80);u8(100);i16(0);u16(1);u8(secondInstrument);u8(45);u8(secondVelocity);u8(120);i16(100);u16(0)
    // tick 4, layer 0: NBS bell.
    u16(4);u16(1);u8(7);u8(57);u8(90);u8(100);i16(0);u16(0);u16(0)
    string("Lead");u8(0);u8(50);u8(100);string("Custom");u8(0);u8(100);u8(80)
    u8(1);string("External");string("external.ogg");u8(45);u8(1)
    bytes.toByteArray()
  }

  private fun nbsV0(): ByteArray = ByteArrayOutputStream().use { bytes ->
    fun u8(value: Int) = bytes.write(value)
    fun u16(value: Int) { u8(value);u8(value ushr 8) }
    fun u32(value: Int) { u16(value);u16(value ushr 16) }
    fun string(value: String) { val encoded=value.toByteArray(Charset.forName("windows-1252"));u32(encoded.size);bytes.write(encoded) }
    u16(4);u16(1);string("Classic");string("");string("");string("");u16(1_000);u8(0);u8(10);u8(4);repeat(5){u32(0)};string("")
    u16(1);u16(1);u8(0);u8(45);u16(0);u16(0)
    string("Layer 1");u8(100);u8(0)
    bytes.toByteArray()
  }

  private fun japaneseTitleRoundTrip() {
    // 120 chars max; ImString(513) holds 120 CJK (360B) and 120 emoji (480B).
    val hiragana = "あ".repeat(120)
    check(hiragana.length == 120 && hiragana.toByteArray(StandardCharsets.UTF_8).size == 360)
    val emoji = "\uD83D\uDE00".repeat(120)
    check(emoji.length == 240 && emoji.toByteArray(StandardCharsets.UTF_8).size == 480)
    // Gson escaping round-trip for .oyasai metadata (control chars, quotes, backslash).
    val tricky = "R&B \"test\" \\ あ\n\t\u0001"
    val encoded = com.google.gson.Gson().toJson(tricky)
    check(com.google.gson.JsonParser.parseString(encoded).asString == tricky)
    // NBS strings decode as UTF-8 first (OpenNBS writes UTF-8).
    check(MidiInstrumentMapper.decodeText(hiragana.toByteArray(StandardCharsets.UTF_8)) == hiragana)
    check(MidiInstrumentMapper.decodeText("Machine Love テト".toByteArray(StandardCharsets.UTF_8)) == "Machine Love テト")
  }

  private fun bufferedPlaybackDoesNotStartEarly() {
    val start = 5_000_000_000L
    check(bufferedElapsedMillis(start - 1, start) == null)
    check(bufferedElapsedMillis(start, start) == 0)
    check(bufferedElapsedMillis(start + 250_000_000L, start) == 250)
  }

  private fun editorPlaybackClockAdvancesAndClamps() {
    val startedAt = 10_000_000_000L
    check(editorPlaybackPositionMs(1_000, startedAt, startedAt, 4_000) == 1_000)
    check(editorPlaybackPositionMs(1_000, startedAt, startedAt + 250_000_000L, 4_000) == 1_250)
    check(editorPlaybackPositionMs(3_900, startedAt, startedAt + 250_000_000L, 4_000) == 4_000)
  }
}
