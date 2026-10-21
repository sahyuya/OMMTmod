package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.util.Locale

/** Independent from the selected sound: a search term is never a sound event ID. */
object SoundBrowserSearch {
  fun rank(ids: List<String>, query: String): List<String> {
    val term = query.trim().lowercase(Locale.ROOT)
    if (term.isEmpty()) return ids.sorted()
    val tokens = term.split(Regex("[\\s:._/]+" )).filter { it.isNotEmpty() }
    return ids.mapNotNull { id ->
      val name = id.substringAfter(':')
      val score = when {
        id == term || name == term -> 0
        name.startsWith(term) || id.startsWith(term) -> 1
        name.split('.', '_', '/').any { it == term } -> 2
        id.contains(term) -> 3
        tokens.isNotEmpty() && tokens.all { id.contains(it) } -> 4
        else -> return@mapNotNull null
      }
      id to score
    }.sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first }).map { it.first }
  }
}
