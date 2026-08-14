package com.github.sahyuya.oyasaimusicmiditranslator

/**
 * Separates the pitch shown in the editor from the pitch sent to a vanilla note block.
 *
 * Display pitch zero is MIDI F#3 (key 54), matching the old 0..24 note-block ruler. The editor
 * retains the complete MIDI key range around that origin, while playback/export octave-fold into
 * the vanilla 0..24 range without losing the source position on the piano roll.
 */
object NoteBlockPitch {
  const val DISPLAY_MIN = -54
  const val DISPLAY_MAX = 73
  const val VANILLA_MIN = 0
  const val VANILLA_MAX = 24
  const val MIDI_ORIGIN = 54

  fun fromMidiKey(midiKey: Int): Int = midiKey.coerceIn(0, 127) - MIDI_ORIGIN

  fun toMidiKey(displayPitch: Int): Int = (displayPitch + MIDI_ORIGIN).coerceIn(0, 127)

  /** Preserves pitch class and chooses the first playable octave reached from the source. */
  fun foldForVanilla(displayPitch: Int): Int {
    var folded = displayPitch.coerceIn(DISPLAY_MIN, DISPLAY_MAX)
    while (folded < VANILLA_MIN) folded += 12
    while (folded > VANILLA_MAX) folded -= 12
    return folded
  }
}
