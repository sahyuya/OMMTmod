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
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorKeyboard
import com.github.sahyuya.oyasaimusicmiditranslator.client.EditorPresetStore
import com.github.sahyuya.oyasaimusicmiditranslator.client.MusicVoicePolicy
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
    editor304CompatibilityAndSearch()
    verifyMuteSolo()
    editorKeyboardPresetsAndVoices()
    densePlaybackBatchDrainage()
    unicode15RoundTripAndThreeByteAlphabet(); compactCanonicalRoundTripAndMalformedRejection(); packetBoundaryUsesRawBytes(); playbackWireGoldenAndState(); editorTempoAndRetriggerModel(); editorHistoryTempoIsolation(); editorProjectAndSettingsRoundTrip(); selectionAndMusicalLength(); nbsAndMidiImport(); midiTempoAndSustainImport(); japaneseTitleRoundTrip(); bufferedPlaybackDoesNotStartEarly(); editorPlaybackClockAdvancesAndClamps()
    args.forEach { rawPath ->
      val path = Path.of(rawPath)
      val files = if (Files.isDirectory(path)) Files.list(path).use { stream -> stream.filter(Files::isRegularFile).sorted().toList() } else listOf(path)
      files.forEach(::verifyImportFile)
    }
    println("UploadV2CodecVerification: PASS")
  }
  private fun editor304CompatibilityAndSearch() {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/project-v2.ommt")).use { it.readBytes() }
    val old = EditorProjectCodec.decode(bytes)
    check(old.title == "Legacy v2 日本語" && old.notes.single().pitchCents == 1225)
    check(old.partDisplayMode == com.github.sahyuya.oyasaimusicmiditranslator.client.PartDisplayMode.AUTO && old.importedPartGroups.isEmpty())
    val group = com.github.sahyuya.oyasaimusicmiditranslator.client.NbsPartGroup(2, "Harp 日本語", listOf(com.github.sahyuya.oyasaimusicmiditranslator.client.NbsPartLeaf(0, 1)))
    for (mode in com.github.sahyuya.oyasaimusicmiditranslator.client.PartDisplayMode.entries) {
      val value = old.copy(partDisplayMode = mode, importedPartGroups = listOf(group))
      val encoded = EditorProjectCodec.encode(value)
      check(encoded[5].toInt() == 3)
      val decoded = EditorProjectCodec.decode(encoded)
      check(decoded.partDisplayMode == mode && decoded.importedPartGroups == listOf(group))
      check(decoded.notes.single().copy(id = old.notes.single().id) == old.notes.single())
      com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSession.save(decoded)
      val restored = checkNotNull(com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSession.restore())
      check(restored.partDisplayMode == mode && restored.importedPartGroups == decoded.importedPartGroups)
      rejects { EditorProjectCodec.decode(encoded.copyOf(encoded.size - 1)) }
      rejects { EditorProjectCodec.decode(encoded.copyOf().also { it[5] = 99 }) }
    }
    com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSession.replace()
    rejects { EditorProjectCodec.encode(old.copy(importedPartGroups = listOf(group, group))) }
    rejects { EditorProjectCodec.encode(old.copy(importedPartGroups = listOf(group.copy(leaves = listOf(com.github.sahyuya.oyasaimusicmiditranslator.client.NbsPartLeaf(1, 0)))))) }
    val ids = listOf("minecraft:zombie", "minecraft:entity.zombie.ambient", "minecraft:zombie_villager", "minecraft:music.menu")
    val search = com.github.sahyuya.oyasaimusicmiditranslator.client.SoundBrowserSearch
    check(search.rank(ids, "") == ids.sorted())
    check(search.rank(ids, " ZOMBIE ").take(2) == listOf("minecraft:zombie", "minecraft:zombie_villager"))
    check(search.rank(ids, "zombie ambient") == listOf("minecraft:entity.zombie.ambient"))
    check(search.rank(ids, "unknown").isEmpty())
    check(search.rank((0..5000).map { "minecraft:test.$it" }, "").size == 5001)
    // Recompute gzip/CRC so invalid v3 fields reach structural validation, not checksum rejection.
    fun withExtension(edit: (java.nio.ByteBuffer, Int) -> Unit): ByteArray {
      val encoded = EditorProjectCodec.encode(old.copy(importedPartGroups = listOf(group)))
      val raw = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(encoded, 20, encoded.size - 20)).use { it.readBytes() }
      val oldSize = java.nio.ByteBuffer.wrap(bytes).getInt(8)
      edit(java.nio.ByteBuffer.wrap(raw), oldSize)
      val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw) } }.toByteArray()
      return ByteArrayOutputStream().also { output -> DataOutputStream(output).use { out ->
        out.writeInt(0x4f4d4d54); out.writeShort(3); out.writeShort(1); out.writeInt(raw.size); out.writeInt(compressed.size)
        out.writeInt(java.util.zip.CRC32().apply { update(raw) }.value.toInt()); out.write(compressed)
      } }.toByteArray()
    }
    rejects { EditorProjectCodec.decode(withExtension { raw, at -> raw.put(at, 127.toByte()) }) }
    rejects { EditorProjectCodec.decode(withExtension { raw, at -> raw.putInt(at + 1, 4097) }) }
    rejects { EditorProjectCodec.decode(withExtension { raw, at -> raw.putInt(at + 5, 256) }) }
    rejects { EditorProjectCodec.decode(withExtension { raw, _ -> raw.putInt(raw.capacity() - 8, 99) }) }
    println("Editor304: PASS (v2 golden, all display modes, groups/session, invalid extension, ranked/full sound search)")
  }

  private fun densePlaybackBatchDrainage() {
    val times = IntArray(1025) { if (it < 513) 0 else 1000 }
    var cursor = 0
    val counts = IntArray(times.size)
    repeat(3) {
      val end = com.github.sahyuya.oyasaimusicmiditranslator.client.playbackBatchEnd(cursor, times.size, 0) { times[it] }
      check(end - cursor <= 256)
      while (cursor < end) counts[cursor++]++
    }
    check(cursor == 513) // No early dispatch of the final chord.
    check(com.github.sahyuya.oyasaimusicmiditranslator.client.playbackBatchEnd(cursor, times.size, 999) { times[it] } == cursor)
    repeat(3) {
      val end = com.github.sahyuya.oyasaimusicmiditranslator.client.playbackBatchEnd(cursor, times.size, 1000) { times[it] }
      while (cursor < end) counts[cursor++]++
    }
    check(cursor == times.size && counts.all { it == 1 })
    check(com.github.sahyuya.oyasaimusicmiditranslator.client.playbackBatchEnd(cursor, times.size, Int.MAX_VALUE) { times[it] } == cursor)
  }
  private fun verifyMuteSolo() {
    val state = com.github.sahyuya.oyasaimusicmiditranslator.client.PartAuditionState()
    fun audible() = (0..3).filter(state::audible)
    check(audible() == listOf(0, 1, 2, 3))
    state.toggleMute(listOf(0)); state.toggleSolo(listOf(1))
    check(audible() == listOf(1))
    state.toggleSolo(listOf(1)); check(audible() == listOf(1, 2, 3))
    state.reset(); state.toggleSolo(listOf(1, 2, 3)); check(audible() == listOf(1, 2, 3))
    state.toggleSolo(listOf(1)); state.toggleMute(listOf(2)); check(audible() == listOf(3))
    state.toggleMute(listOf(3)); check(audible().isEmpty()) // Muted solos still gate non-solos.
    state.reset(); state.toggleMute(listOf(0, 1, 2)); state.toggleMute(listOf(1))
    check(audible() == listOf(1, 3)) // Individual operation overrides group.
    state.toggleMute(listOf(0, 1, 2)); check(audible() == listOf(3)) // Mixed -> all enabled.
    state.toggleMute(listOf(0, 1, 2)); check(audible().size == 4)
    state.toggleSolo(listOf(0, 1)); state.toggleSolo(listOf(1))
    check(audible() == listOf(0))
    state.toggleSolo(listOf(0, 1)); check(audible() == listOf(0, 1))
    state.toggleSolo(listOf(0, 1)); check(audible().size == 4)
    state.toggleMute(listOf(22)); state.toggleSolo(listOf(100)); state.reset()
    check(state.audible(22) && state.audible(100))
    state.toggleSolo(listOf(100)); state.retainParts(4); check(audible().size == 4)
    state.toggleMute(listOf(0))
    check(state.noteColor(0xFF123456.toInt(), 0) == 0x3F123456)
    check(state.noteColor(0xFF123456.toInt(), 0, 100) == 0xFF123456.toInt())
    check(state.noteColor(0xFF123456.toInt(), 0, 5) == 0x0C123456)
    check(state.noteColor(0xFF123456.toInt(), 0, 0) ushr 24 == 0)
    check(!state.visible(0, 0) && state.visible(1, 0) && state.visible(0, 1))
    val viewState = com.github.sahyuya.oyasaimusicmiditranslator.client.PartAuditionState()
    check(viewState.previewAudible(1, false, false, 0))
    check(!viewState.previewAudible(1, true, false, 0))
    check(viewState.previewAudible(0, true, false, 0))
    check(viewState.previewAudible(1, true, true, 0))
    check(viewState.previewAudible(1, true, false, 1)) // Follow the selected part.
    check(!viewState.soloed(0) && !viewState.soloed(1))
    viewState.toggleMute(listOf(1))
    check(!viewState.previewAudible(1, true, false, 1))
    viewState.toggleSolo(listOf(2))
    check(!viewState.previewAudible(0, true, false, 0))
    check(viewState.previewAudible(2, true, true, 0))
    check(viewState.soloed(2) && viewState.muted(1)) // View changes never rewrite M/S.
    val overlaps = listOf(1, 0, 2, 0)
    check(state.drawOrder(overlaps) { it } == listOf(0, 0, 1, 2))
    check(state.hit(listOf(0, 1), { it }, { if (it == 0) 0.0 else 3.0 }) == 1)
    check(state.hit(listOf(0), { it }, { 0.0 }) == 0)
    check(state.hit(emptyList<Int>(), { it }, { 0.0 }) == null)
    check(state.noteColor(0xFF123456.toInt(), 0, 50) == 0x7F123456)
    check(state.noteColor(0x48123456, 0, 50) == 0x24123456)
    check(state.noteColor(0xFF123456.toInt(), 1) == 0xFF123456.toInt())
    val notes = listOf(EditorNote(0, 200, 0, 12, 80, 0, part = 0), EditorNote(10, 200, 1, 14, 90, 0, part = 1))
    val expanded = EditorAutomation.expand(notes, RetriggerProfile(), emptyMap())
    check(expanded.map { it.part }.toSet() == setOf(0, 1))
    state.toggleSolo(listOf(1))
    check(expanded.filter { state.audible(it.part) }.all { it.part == 1 })
    check(EditorAutomation.expand(notes, RetriggerProfile(), emptyMap()) == expanded)
    check(notes[0].volume == 80 && notes[1].volume == 90)
    val golden = UploadV2CodecVerification::class.java.getResourceAsStream("/settings-v6.txt")!!.bufferedReader().use { it.readText().trim() }
    val old = EditorSettingsBundleCodec.decode(golden)
    val v7 = UploadV2CodecVerification::class.java.getResourceAsStream("/settings-v7.txt")!!.bufferedReader().use { it.readText().trim() }
    check(EditorSettingsBundleCodec.decode(v7).settings.style.mutedOpacityPercent == 25)
    val v8 = UploadV2CodecVerification::class.java.getResourceAsStream("/settings-v8.txt")!!.bufferedReader().use { it.readText().trim() }
    check(!EditorSettingsBundleCodec.decode(v8).settings.auditionVisiblePartOnly)
    check(!EditorSettingsBundleCodec.decode(v7).settings.auditionVisiblePartOnly)
    check(old.settings.style.mutedOpacityPercent == 25)
    check(old.settings.version == 9 && old.layout == "[Window][V6 fixture]\nPos=10,20\n")
    check(old.settings.style.muteColor == 0xFFDC4545.toInt() && old.settings.style.soloColor == 0xFF45C86A.toInt())
    val custom = old.settings.copy(auditionVisiblePartOnly = true, style = old.settings.style.copy(muteColor = 0xFF112233.toInt(), soloColor = 0xFFABCDEF.toInt(), mutedOpacityPercent = 15))
    check(EditorSettingsBundleCodec.decode(EditorSettingsBundleCodec.encode(custom, old.layout)).settings == custom)
    val invisible = custom.copy(style = custom.style.copy(mutedOpacityPercent = 0))
    check(EditorSettingsBundleCodec.decode(EditorSettingsBundleCodec.encode(invisible, old.layout)).settings == invisible)
    val settingsStore = com.github.sahyuya.oyasaimusicmiditranslator.client.EditorSettingsStore
    val writer = settingsStore.javaClass.getDeclaredMethod("toJson", EditorSettings::class.java).apply { isAccessible = true }
    val reader = settingsStore.javaClass.getDeclaredMethod("parse", String::class.java).apply { isAccessible = true }
    val json = writer.invoke(settingsStore, custom) as String
    check((reader.invoke(settingsStore, json) as EditorSettings).style == custom.style)
    check((reader.invoke(settingsStore, json) as EditorSettings).auditionVisiblePartOnly)
    check(!(reader.invoke(settingsStore, json.replace(",\"auditionVisiblePartOnly\":true", "")) as EditorSettings).auditionVisiblePartOnly)
    check((reader.invoke(settingsStore, writer.invoke(settingsStore, invisible)) as EditorSettings).style.mutedOpacityPercent == 0)
    val oldJson = json.replace(Regex(",\"style(?:Mute|Solo)\":\"[^\"]*\""), "").replace("\"version\":7", "\"version\":6")
    val migrated = reader.invoke(settingsStore, oldJson) as EditorSettings
    check(migrated.style.muteColor == old.settings.style.muteColor && migrated.style.soloColor == old.settings.style.soloColor)
    val noOpacity = json.replace(Regex(",\"styleMutedOpacityPercent\":\\d+"), "")
    check((reader.invoke(settingsStore, noOpacity) as EditorSettings).style.mutedOpacityPercent == 25)
    val dir = Files.createTempDirectory("ommt-audition-preset-")
    try {
      val store = EditorPresetStore(dir)
      val preset = store.create("Mute solo colors", custom, old.layout)
      check(EditorSettingsBundleCodec.decode(store.read(preset.id).bundle).settings.style == custom.style)
    } finally { Files.list(dir).use { it.forEach { path -> Files.delete(path) } }; Files.delete(dir) }
    println("MuteSoloVerification: PASS (both examples, group/leaf latest edit, reset, alpha, part propagation, unchanged notes, draw/hit ordering, v6/v7/v8 golden/v9 view-audition preset)")
  }
  private fun verifyImportFile(path: Path) {
    when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
      "nbs" -> {
        val song = NbsFileCodec.decode(Files.readAllBytes(path))
        check(song.header.version in 0..6)
        val ordered = MusicVoicePolicy.onsetOrder(song.notes, { it.tick }, { it.velocity * song.layers[it.layer].volume })
        check(ordered.groupingBy { it }.eachCount() == song.notes.groupingBy { it }.eachCount())
        check(ordered.zipWithNext().all { (a, b) -> a.tick < b.tick ||
            a.tick == b.tick && a.velocity * song.layers[a.layer].volume >= b.velocity * song.layers[b.layer].volume })
        val maxChord = song.notes.groupingBy { it.tick }.eachCount().values.maxOrNull() ?: 0
        println("  onset priority PASS: all original note values preserved, maximum chord $maxChord")
        if (path.fileName.toString() == "Field of Hopes and Dreams.nbs") {
          check(song.notes.size == 82989 && song.layers.size == 238 && maxChord == 73)
        }
        // Optional real-file regression: send the brass subset through the production compact
        // codec. Generated files are test artifacts, never edits to the user's NBS originals.
        val brass = song.notes.filter { it.instrument in 16..19 && it.instrument < song.header.defaultInstruments }
        if (brass.isNotEmpty()) {
          val converted = brass.map { note ->
            val cents = NbsFileCodec.toOmmtPitchCents(note.key, note.detuneCents)
            UploadV2Codec.Note((note.tick * 1000.0 / song.header.ticksPerSecond).roundToInt(),
                NbsFileCodec.toOmmtInstrument(note.instrument, song.header.defaultInstruments)!!,
                Math.floorDiv(cents, 100), (note.velocity * song.layers[note.layer].volume / 100.0).roundToInt(),
                NbsFileCodec.effectivePanning(note.panning, song.layers[note.layer].panning), cents)
          }
          val version = UploadV2Codec.formatVersion(converted.any { it.pitchCents % 100 != 0 || it.pitchCents !in 0..2400 }, true, false)
          val metadata = "{\"format\":\"oyasai-midi-import\",\"version\":$version,\"song\":{\"title\":\"NBS brass regression\"}}".toByteArray()
          val compact = UploadV2Codec.compact(UploadV2Codec.Compact(metadata, converted.maxOf { it.time }, converted, version))
          val canonical = UploadV2Codec.reconstructOymi(compact)
          check(compact.contentEquals(UploadV2Codec.compactFromOymi(canonical)))
          val key = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 255) }
          val output = Files.createDirectories(Path.of("build", "verification", "nbs-brass"))
          Files.write(output.resolve("$key.oymc"), compact)
          Files.write(output.resolve("$key.oymi"), canonical)
          println("  brass OYMC v$version PASS: ${brass.size} notes, IDs ${converted.map { it.instrument }.distinct()}, fixture $key")
        }
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
  private fun editorKeyboardPresetsAndVoices() {
    check(EditorKeyboard.imguiKey(32) == imgui.flag.ImGuiKey.Space)
    check(EditorKeyboard.imguiKey(257) == imgui.flag.ImGuiKey.Enter)
    check(EditorKeyboard.imguiKey(335) == imgui.flag.ImGuiKey.KeypadEnter)
    check(EditorKeyboard.imguiKey(290) == imgui.flag.ImGuiKey.F1)
    check(EditorKeyboard.imguiKey(340) == null) // Modifier alone must not finish capture.
    check(EditorKeyboard.ownsShortcut(true, true, true))
    check(!EditorKeyboard.ownsShortcut(false, true, false))
    check(!EditorKeyboard.ownsShortcut(false, false, true))
    check(EditorKeyboard.ownsShortcut(false, false, false))
    for (key in listOf(32, 257, 335, 65, 90, 48, 57, 290, 313, 262, 39)) {
      val stroke = EditorKeyStroke(key, control = true, shift = true)
      check(EditorKeyStroke.parse(stroke.encode(), EditorKeyStroke.UNBOUND) == stroke)
    }
    val directory = Files.createTempDirectory("ommt-personal-presets-")
    try {
      val settings = EditorSettings().copy(keymap = EditorKeymap().with(EditorAction.PLAY_PAUSE, EditorKeyStroke(290)))
      val layout = "[Window][日本語]\nPos=120,80\nSize=640,480\n"
      val store = EditorPresetStore(directory)
      val first = store.create("夜の編集用", settings, layout)
      val reopened = EditorPresetStore(directory)
      check(reopened.list().single() == first)
      val decoded = EditorSettingsBundleCodec.decode(reopened.read(first.id).bundle)
      check(decoded.layout == layout && decoded.settings.keymap[EditorAction.PLAY_PAUSE] == EditorKeyStroke(290))
      check(decoded.settings == EditorSettingsBundleCodec.decode(EditorSettingsBundleCodec.encode(settings, layout)).settings)
      rejects { store.create("夜の編集用", settings, layout) }
      check(store.read(first.id) == first)
      val renamed = store.rename(first.id, "演奏用")
      check(renamed.bundle == first.bundle && renamed.id == first.id)
      val updated = store.update(first.id, settings, "[Window][Other]\nPos=0,0\n")
      check(updated.name == "演奏用" && updated.bundle != first.bundle)
      rejects { store.rename(first.id, "\n") }
      rejects { store.rename(first.id, "a".repeat(65)) }
      val pathName = store.create("../display name", settings, layout)
      check(Files.isRegularFile(directory.resolve("${pathName.id}.json")))
      val badId = UUID.randomUUID()
      Files.writeString(directory.resolve("$badId.json"), "{\"schemaVersion\":99}")
      rejects { store.read(badId) }
      check(store.list().size == 2)
    } finally {
      Files.list(directory).use { paths -> paths.forEach { Files.delete(it) } }
      Files.delete(directory)
    }
    check(MusicVoicePolicy.shouldReplace(0.8f, MusicVoicePolicy.priority(0.2f, 0)))
    check(!MusicVoicePolicy.shouldReplace(0.2f, MusicVoicePolicy.priority(0.8f, 0)))
    check(!MusicVoicePolicy.shouldReplace(0.8f, MusicVoicePolicy.priority(0.8f, 0)))
    check(MusicVoicePolicy.shouldReplace(0.2f, MusicVoicePolicy.priority(0.8f, 2000)))
    val original = listOf(Triple(20, 90, "later"), Triple(0, 20, "quiet"), Triple(0, 80, "lead"), Triple(0, 80, "equal"))
    val sorted = MusicVoicePolicy.onsetOrder(original, { it.first }, { it.second })
    check(sorted.map { it.third } == listOf("lead", "equal", "quiet", "later"))
    check(original.first().third == "later" && sorted.toSet() == original.toSet())
    println("Editor keyboard / personal preset persistence / voice priority: PASS")
  }
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
    val brass = (16..19).map { id -> first.copy(id=id.toLong(), instrument=id, sourceChannel=id, pitchCents=2_525) }
    val brassSaved = EditorProjectCodec.decode(EditorProjectCodec.encode(snapshot.copy(notes=brass, selectedIds=emptySet(), selected=0)))
    check(brassSaved.notes.map { it.instrument } == (16..19).toList())
    check(brassSaved.notes.map { it.sourceChannel } == (16..19).toList())
    check(brassSaved.notes.all { it.pitchCents == 2_525 })
    rejects { EditorProjectCodec.encode(snapshot.copy(notes=listOf(first.copy(instrument=20)))) }
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
    check(legacy.settings.version==9 && legacy.settings.theme==EditorTheme.MIDNIGHT_BLUE)
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
    check((16..19).map { NbsFileCodec.toOmmtInstrument(it, 20) } == listOf(16, 17, 19, 18))
    check(NbsFileCodec.toOmmtInstrument(16, 16) == null) // v5 custom ID is NOT brass.
    check(NbsFileCodec.toOmmtInstrument(20, 20) == null)
    check(NbsFileCodec.toOmmtInstrument(255, 256) == null)
    check(UploadV2Codec.formatVersion(false, true, false) == 4)
    check(UploadV2Codec.formatVersion(false, true, true) == 4)
    check(UploadV2Codec.formatVersion(false, false, false) == 1)
    check(UploadV2Codec.formatVersion(false, false, true) == 3)
    check(UploadV2Codec.formatVersion(true, false, false) == 4)
    val brassFixture = Base64.getDecoder().decode(Files.readString(Path.of("..", "docs", "interop", "fixtures", "nbs-20-instruments-oymi-v4.base64")).trim())
    check(brassFixture.contentEquals(UploadV2Codec.reconstructOymi(UploadV2Codec.compactFromOymi(brassFixture))))
    val expectedIds = listOf(0,1,2,3,4,7,5,6,8,9,10,11,12,13,14,15,16,17,19,18)
    check((0..19).map { NbsFileCodec.toOmmtInstrument(it, 20) } == expectedIds)
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
