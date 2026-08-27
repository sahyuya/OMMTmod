package com.github.sahyuya.oyasaimusicmiditranslator.interop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.math.roundToInt
import com.github.sahyuya.oyasaimusicmiditranslator.client.AutomationCurve
import com.github.sahyuya.oyasaimusicmiditranslator.client.bufferedElapsedMillis
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorAutomation
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorAction
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorHistory
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeyStroke
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeymap
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorNote
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorProjectCodec
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSelection
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSettings
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSettingsBundleCodec
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSnapshot
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorTheme
import com.github.sahyuya.oyasaimusicmiditranslator.client.GridMark
import com.github.sahyuya.oyasaimusicmiditranslator.client.ReleaseControlPoint
import com.github.sahyuya.oyasaimusicmiditranslator.client.RetriggerProfile
import com.github.sahyuya.oyasaimusicmiditranslator.client.SignatureMark
import com.github.sahyuya.oyasaimusicmiditranslator.client.TempoControlPoint
import com.github.sahyuya.oyasaimusicmiditranslator.client.TempoMark

object UploadV2CodecVerification {
  @JvmStatic fun main(args: Array<String>) {
    unicode15RoundTripAndThreeByteAlphabet(); compactCanonicalRoundTripAndMalformedRejection(); packetBoundaryUsesRawBytes(); playbackWireGoldenAndState(); editorTempoAndRetriggerModel(); editorHistoryTempoIsolation(); editorProjectAndSettingsRoundTrip(); selectionAndMusicalLength(); bufferedPlaybackDoesNotStartEarly()
    println("UploadV2CodecVerification: PASS")
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
    rejects { PlaybackWireCodec.decode(byteArrayOf(2)+ByteArray(17)) }; rejects { PlaybackWireCodec.decode(byteArrayOf(1,8)+ByteArray(16)) }
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
    val settings = EditorSettings(theme=EditorTheme.MIDNIGHT_BLUE, uiScalePercent=125, showLibrary=false, keymap=portableKeymap)
    val text = EditorSettingsBundleCodec.encode(settings,"[Window][PIANO ROLL]\nPos=1,2\n")
    val bundle = EditorSettingsBundleCodec.decode(text)
    check(bundle.settings.theme==EditorTheme.MIDNIGHT_BLUE && bundle.settings.uiScalePercent==125 && !bundle.settings.showLibrary)
    check(bundle.layout.contains("PIANO ROLL"))
    rejects { EditorSettingsBundleCodec.decode("not-an-ommt-setting") }
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

  private fun bufferedPlaybackDoesNotStartEarly() {
    val start = 5_000_000_000L
    check(bufferedElapsedMillis(start - 1, start) == null)
    check(bufferedElapsedMillis(start, start) == 0)
    check(bufferedElapsedMillis(start + 250_000_000L, start) == 250)
  }
}
