package com.github.sahyuya.oyasaimusicmiditranslator.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

data class EditorPreset(val id: UUID, val name: String, val bundle: String)

/** Independent, versioned snapshots. Display names are never used as paths. */
class EditorPresetStore(private val directory: Path) {
  fun list(): List<EditorPreset> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { paths -> paths.filter { it.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.json")) }
        .limit(256).map { path -> runCatching { read(UUID.fromString(path.fileName.toString().removeSuffix(".json"))) }.getOrNull() }
        .filter { it != null }.toList().filterNotNull().sortedBy { it.name.lowercase() } }
  }

  fun read(id: UUID): EditorPreset {
    val path = path(id)
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 2 * 1024 * 1024) { "Invalid preset file" }
    val json = JsonParser.parseString(Files.readString(path)).asJsonObject
    require(json.get("schemaVersion").asInt == 1) { "Unsupported preset version" }
    val bundle = json.get("bundle").asString
    EditorSettingsBundleCodec.decode(bundle)
    return EditorPreset(id, validName(json.get("name").asString), bundle)
  }

  fun create(name: String, settings: EditorSettings, layout: String): EditorPreset {
    require(list().size < 100) { "At most 100 presets can be saved" }
    return write(EditorPreset(UUID.randomUUID(), validName(name), EditorSettingsBundleCodec.encode(settings, layout)))
  }

  fun update(id: UUID, settings: EditorSettings, layout: String): EditorPreset =
      write(read(id).copy(bundle = EditorSettingsBundleCodec.encode(settings, layout)))

  fun rename(id: UUID, name: String): EditorPreset = write(read(id).copy(name = validName(name)))

  private fun write(preset: EditorPreset): EditorPreset {
    require(list().none { it.id != preset.id && it.name.equals(preset.name, ignoreCase = true) }) { "A preset with that name already exists" }
    val json = JsonObject().apply {
      addProperty("schemaVersion", 1); addProperty("name", preset.name); addProperty("bundle", preset.bundle)
    }.toString()
    require(json.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, "preset-", ".tmp")
    try {
      Files.writeString(temporary, json)
      try { Files.move(temporary, path(preset.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
      catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path(preset.id), StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
    return preset
  }

  private fun path(id: UUID) = directory.resolve("$id.json")
  private fun validName(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl)) { "Preset name must be 1–64 characters" }
  }
}
