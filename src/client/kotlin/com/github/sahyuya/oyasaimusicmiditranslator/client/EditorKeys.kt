package com.github.sahyuya.oyasaimusicmiditranslator.client

/** Stable key identifiers in saved OMMT keymaps. Values retain the pre-SDL format. */
object EditorKeys {
  const val KEY_UNKNOWN = -1
  const val KEY_SPACE = 32
  const val KEY_APOSTROPHE = 39
  const val KEY_COMMA = 44
  const val KEY_MINUS = 45
  const val KEY_PERIOD = 46
  const val KEY_SLASH = 47
  const val KEY_0 = 48
  const val KEY_1 = 49
  const val KEY_2 = 50
  const val KEY_3 = 51
  const val KEY_4 = 52
  const val KEY_5 = 53
  const val KEY_6 = 54
  const val KEY_7 = 55
  const val KEY_8 = 56
  const val KEY_9 = 57
  const val KEY_SEMICOLON = 59
  const val KEY_EQUAL = 61
  const val KEY_A = 65
  const val KEY_B = 66
  const val KEY_C = 67
  const val KEY_D = 68
  const val KEY_E = 69
  const val KEY_F = 70
  const val KEY_G = 71
  const val KEY_H = 72
  const val KEY_I = 73
  const val KEY_J = 74
  const val KEY_K = 75
  const val KEY_L = 76
  const val KEY_M = 77
  const val KEY_N = 78
  const val KEY_O = 79
  const val KEY_P = 80
  const val KEY_Q = 81
  const val KEY_R = 82
  const val KEY_S = 83
  const val KEY_T = 84
  const val KEY_U = 85
  const val KEY_V = 86
  const val KEY_W = 87
  const val KEY_X = 88
  const val KEY_Y = 89
  const val KEY_Z = 90
  const val KEY_LEFT_BRACKET = 91
  const val KEY_BACKSLASH = 92
  const val KEY_RIGHT_BRACKET = 93
  const val KEY_GRAVE_ACCENT = 96
  const val KEY_WORLD_1 = 161
  const val KEY_WORLD_2 = 162
  const val KEY_ESCAPE = 256
  const val KEY_ENTER = 257
  const val KEY_TAB = 258
  const val KEY_BACKSPACE = 259
  const val KEY_INSERT = 260
  const val KEY_DELETE = 261
  const val KEY_RIGHT = 262
  const val KEY_LEFT = 263
  const val KEY_DOWN = 264
  const val KEY_UP = 265
  const val KEY_PAGE_UP = 266
  const val KEY_PAGE_DOWN = 267
  const val KEY_HOME = 268
  const val KEY_END = 269
  const val KEY_CAPS_LOCK = 280
  const val KEY_SCROLL_LOCK = 281
  const val KEY_NUM_LOCK = 282
  const val KEY_PRINT_SCREEN = 283
  const val KEY_PAUSE = 284
  const val KEY_F1 = 290
  const val KEY_F2 = 291
  const val KEY_F3 = 292
  const val KEY_F4 = 293
  const val KEY_F5 = 294
  const val KEY_F6 = 295
  const val KEY_F7 = 296
  const val KEY_F8 = 297
  const val KEY_F9 = 298
  const val KEY_F10 = 299
  const val KEY_F11 = 300
  const val KEY_F12 = 301
  const val KEY_F13 = 302
  const val KEY_F14 = 303
  const val KEY_F15 = 304
  const val KEY_F16 = 305
  const val KEY_F17 = 306
  const val KEY_F18 = 307
  const val KEY_F19 = 308
  const val KEY_F20 = 309
  const val KEY_F21 = 310
  const val KEY_F22 = 311
  const val KEY_F23 = 312
  const val KEY_F24 = 313
  const val KEY_F25 = 314
  const val KEY_KP_0 = 320
  const val KEY_KP_1 = 321
  const val KEY_KP_2 = 322
  const val KEY_KP_3 = 323
  const val KEY_KP_4 = 324
  const val KEY_KP_5 = 325
  const val KEY_KP_6 = 326
  const val KEY_KP_7 = 327
  const val KEY_KP_8 = 328
  const val KEY_KP_9 = 329
  const val KEY_KP_DECIMAL = 330
  const val KEY_KP_DIVIDE = 331
  const val KEY_KP_MULTIPLY = 332
  const val KEY_KP_SUBTRACT = 333
  const val KEY_KP_ADD = 334
  const val KEY_KP_ENTER = 335
  const val KEY_KP_EQUAL = 336
  const val KEY_LEFT_SHIFT = 340
  const val KEY_LEFT_CONTROL = 341
  const val KEY_LEFT_ALT = 342
  const val KEY_LEFT_SUPER = 343
  const val KEY_RIGHT_SHIFT = 344
  const val KEY_RIGHT_CONTROL = 345
  const val KEY_RIGHT_ALT = 346
  const val KEY_RIGHT_SUPER = 347
  const val KEY_MENU = 348
  const val KEY_LAST = 348

  // SDL3 physical scancodes are translated at the platform boundary, never persisted.
  private val sdlToLegacy: Map<Int, Int> = buildMap {
    for (i in 0..25) put(4 + i, KEY_A + i)
    for (i in 0..8) put(30 + i, KEY_1 + i)
    put(39, KEY_0)
    for (i in 0..11) put(58 + i, KEY_F1 + i)
    for (i in 0..11) put(104 + i, KEY_F13 + i)
    for (i in 0..8) put(89 + i, KEY_KP_1 + i)
    put(40, KEY_ENTER)
    put(41, KEY_ESCAPE)
    put(42, KEY_BACKSPACE)
    put(43, KEY_TAB)
    put(44, KEY_SPACE)
    put(45, KEY_MINUS)
    put(46, KEY_EQUAL)
    put(47, KEY_LEFT_BRACKET)
    put(48, KEY_RIGHT_BRACKET)
    put(49, KEY_BACKSLASH)
    put(51, KEY_SEMICOLON)
    put(52, KEY_APOSTROPHE)
    put(53, KEY_GRAVE_ACCENT)
    put(54, KEY_COMMA)
    put(55, KEY_PERIOD)
    put(56, KEY_SLASH)
    put(57, KEY_CAPS_LOCK)
    put(70, KEY_PRINT_SCREEN)
    put(71, KEY_SCROLL_LOCK)
    put(72, KEY_PAUSE)
    put(73, KEY_INSERT)
    put(74, KEY_HOME)
    put(75, KEY_PAGE_UP)
    put(76, KEY_DELETE)
    put(77, KEY_END)
    put(78, KEY_PAGE_DOWN)
    put(79, KEY_RIGHT)
    put(80, KEY_LEFT)
    put(81, KEY_DOWN)
    put(82, KEY_UP)
    put(83, KEY_NUM_LOCK)
    put(84, KEY_KP_DIVIDE)
    put(85, KEY_KP_MULTIPLY)
    put(86, KEY_KP_SUBTRACT)
    put(87, KEY_KP_ADD)
    put(88, KEY_KP_ENTER)
    put(98, KEY_KP_0)
    put(99, KEY_KP_DECIMAL)
    put(101, KEY_MENU)
    put(103, KEY_KP_EQUAL)
    put(224, KEY_LEFT_CONTROL)
    put(225, KEY_LEFT_SHIFT)
    put(226, KEY_LEFT_ALT)
    put(227, KEY_LEFT_SUPER)
    put(228, KEY_RIGHT_CONTROL)
    put(229, KEY_RIGHT_SHIFT)
    put(230, KEY_RIGHT_ALT)
    put(231, KEY_RIGHT_SUPER)
  }
  private val legacyToSdl = sdlToLegacy.entries.associate { (scan, key) -> key to scan }

  fun fromSdlScancode(scancode: Int): Int = sdlToLegacy[scancode] ?: KEY_UNKNOWN
  fun toSdlScancode(key: Int): Int? = legacyToSdl[key]
}
