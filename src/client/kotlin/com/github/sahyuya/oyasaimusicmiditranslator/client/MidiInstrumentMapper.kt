package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Deterministic General MIDI to note-block mapping with optional MIDI track metadata hints. */
object MidiInstrumentMapper {
  private val PROGRAMS = intArrayOf(
      // 0..7 Piano
      0, 0, 0, 0, 0, 0, 15, 0,
      // 8..15 Chromatic percussion
      6, 6, 8, 10, 9, 9, 8, 14,
      // 16..23 Organ / accordion
      15, 15, 15, 15, 15, 14, 5, 14,
      // 24..31 Guitar
      7, 7, 7, 7, 14, 7, 7, 8,
      // 32..39 Bass
      1, 1, 1, 1, 1, 1, 1, 13,
      // 40..47 Solo strings
      15, 15, 1, 1, 15, 14, 0, 2,
      // 48..55 Ensembles / voices
      15, 15, 13, 13, 0, 0, 13, 2,
      // 56..63 Brass
      12, 12, 12, 12, 12, 12, 13, 13,
      // 64..71 Reeds
      5, 5, 12, 12, 5, 5, 12, 5,
      // 72..79 Pipes
      5, 5, 5, 5, 5, 5, 5, 5,
      // 80..87 Synth leads
      13, 13, 5, 13, 14, 0, 15, 13,
      // 88..95 Synth pads
      8, 15, 13, 0, 15, 10, 8, 8,
      // 96..103 Synth effects
      8, 15, 8, 8, 6, 13, 8, 13,
      // 104..111 Ethnic
      14, 14, 14, 14, 9, 5, 15, 5,
      // 112..119 Percussive
      6, 11, 10, 9, 2, 3, 13, 4,
      // 120..127 Sound effects
      4, 5, 4, 5, 6, 4, 4, 3,
  )

  fun mapProgram(program: Int, trackHint: String = ""): Int {
    val hint = trackHint.lowercase(Locale.ROOT)
    hintedInstrument(hint)?.let { return it }
    return PROGRAMS[program.coerceIn(0, 127)]
  }

  private fun hintedInstrument(hint: String): Int? {
    if (hint.isBlank()) return null
    fun has(vararg words: String) = words.any(hint::contains)
    return when {
      has("bassoon", "fagotto", "ファゴット") -> 12
      has("bass drum", "kick", "バスドラム", "キック") -> 2
      has("snare", "スネア") -> 3
      has("hi-hat", "hihat", "cymbal", "ハイハット", "シンバル") -> 4
      has("glockenspiel", "celesta", "music box", "ベル", "グロッケン", "オルゴール") -> 6
      has("chime", "tubular", "チャイム") -> 8
      has("xylophone", "marimba", "kalimba", "木琴", "マリンバ", "カリンバ") -> 9
      has("iron xylophone", "vibraphone", "鉄琴", "ビブラフォン") -> 10
      has("cowbell", "cow bell", "カウベル") -> 11
      has("didgeridoo", "tuba", "trombone", "trumpet", "brass", "ディジュリドゥ", "チューバ", "トロンボーン", "トランペット", "ブラス") -> 12
      has("banjo", "sitar", "koto", "shamisen", "バンジョー", "シタール", "琴", "三味線") -> 14
      has("flute", "piccolo", "recorder", "whistle", "ocarina", "oboe", "clarinet", "sax", "フルート", "ピッコロ", "リコーダー", "オカリナ", "オーボエ", "クラリネット", "サックス") -> 5
      has("guitar", "ギター") -> 7
      has("contrabass", "double bass", "bass", "ベース") -> 1
      has("violin", "viola", "cello", "strings", "fiddle", "バイオリン", "ヴィオラ", "チェロ", "ストリング") -> 15
      has("synth", "square", "saw", "lead", "シンセ", "リード") -> 13
      has("piano", "keyboard", "keys", "harp", "ピアノ", "キーボード", "鍵盤", "ハープ") -> 0
      else -> null
    }
  }

  fun drumInstrument(midiKey: Int): Int = when (midiKey) {
    35, 36 -> 2
    in 37..40, in 60..66 -> 3
    56 -> 11
    67, 68, 80, 81 -> 6
    else -> 4
  }

  fun drumPitch(midiKey: Int): Int = when (midiKey) {
    35 -> 8
    36 -> 11
    in 37..40 -> 10 + (midiKey - 37) * 2
    42, 44 -> 8
    46 -> 14
    49, 51, 52, 54, 55, 57, 59 -> 20
    56 -> 12
    67, 80 -> 9
    68, 81 -> 16
    in 60..66 -> midiKey - 53
    75, 76 -> 15
    77 -> 7
    else -> 12
  }.coerceIn(0, 24)

  /** MIDI text is often UTF-8, Shift-JIS, or legacy Windows-1252; never expose replacement glyphs. */
  fun decodeText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val charsets = listOf(StandardCharsets.UTF_8, Charset.forName("windows-31j"), Charset.forName("windows-1252"))
    for (charset in charsets) {
      try {
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .replace('\u0000', ' ')
            .trim()
      } catch (_: CharacterCodingException) {
        // Try the next common MIDI metadata encoding.
      }
    }
    return bytes.joinToString(separator = "") { byte -> if ((byte.toInt() and 0xFF) in 32..126) byte.toInt().toChar().toString() else " " }.trim()
  }
}
