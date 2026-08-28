package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import net.minecraft.client.MinecraftClient

/** Owns every user-visible OMMT directory and opens it without relying only on AWT Desktop. */
object EditorWorkspace {
  private const val ROOT_NAME = "OMMT"
  private const val MIDI_NAME = "midi"
  private const val NBS_NAME = "nbs"
  private const val SAVES_NAME = "saves"

  @Volatile
  var initializationError: String? = null
    private set

  fun rootDirectory(): Path =
      MinecraftClient.getInstance().runDirectory.toPath().resolve(ROOT_NAME).toAbsolutePath().normalize()

  fun midiDirectory(): Path = rootDirectory().resolve(MIDI_NAME)

  fun nbsDirectory(): Path = rootDirectory().resolve(NBS_NAME)

  fun saveDirectory(): Path = rootDirectory().resolve(SAVES_NAME)

  /** Called during client initialization, before the editor screen is ever opened. */
  fun initialize(): Result<Unit> = runCatching {
    Files.createDirectories(rootDirectory())
    Files.createDirectories(midiDirectory())
    Files.createDirectories(nbsDirectory())
    Files.createDirectories(saveDirectory())
    Unit
  }.also { result -> initializationError = result.exceptionOrNull()?.message }

  fun requireDirectories() {
    initialize().getOrThrow()
  }

  fun openDirectory(directory: Path): Result<Unit> = runCatching {
    requireDirectories()
    val root = rootDirectory()
    val target = directory.toAbsolutePath().normalize()
    require(target.startsWith(root) && Files.isDirectory(target)) { "Not an OMMT directory" }

    val command = folderOpenCommand(System.getProperty("os.name").orEmpty(), target)
    val nativeOpen = command?.let { value -> runCatching { ProcessBuilder(value).start() } }
    if (nativeOpen?.isSuccess == true) return@runCatching

    val desktopOpen = runCatching {
      require(Desktop.isDesktopSupported()) { "Desktop integration is unavailable" }
      val desktop = Desktop.getDesktop()
      require(desktop.isSupported(Desktop.Action.OPEN)) { "Opening folders is unavailable" }
      desktop.open(target.toFile())
    }
    desktopOpen.getOrElse { desktopError ->
      val nativeMessage = nativeOpen?.exceptionOrNull()?.message
      throw IllegalStateException(nativeMessage ?: desktopError.message ?: "No folder opener is available", desktopError)
    }
  }

  fun folderOpenCommand(osName: String, directory: Path): List<String>? {
    val normalized = osName.lowercase(Locale.ROOT)
    return when {
      "mac" in normalized || "darwin" in normalized -> listOf("open", directory.toString())
      normalized.startsWith("windows") -> listOf("explorer.exe", directory.toString())
      "linux" in normalized || "unix" in normalized -> listOf("xdg-open", directory.toString())
      else -> null
    }
  }
}
