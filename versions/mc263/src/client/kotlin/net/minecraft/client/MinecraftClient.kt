package net.minecraft.client

import java.io.File
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component

/**
 * Small source-compatibility facade for the 1.21.11 editor sources.
 *
 * Minecraft 26 uses the official class names (`Minecraft`, `ClientPacketListener`, …). Keeping
 * those names behind this facade lets the pure editor/model stay identical across both builds.
 */
object MinecraftClient {
  private val facade = LegacyMinecraftClient()

  @JvmStatic fun getInstance(): LegacyMinecraftClient = facade
}

class LegacyMinecraftClient internal constructor() {
  private val minecraft: Minecraft get() = Minecraft.getInstance()
  private var lastConnection: Any? = null
  private var legacyConnection: ClientPlayNetworkHandler? = null

  val runDirectory: File get() = minecraft.gameDirectory
  val player: LocalPlayer? get() = minecraft.player
  val window: LegacyWindow get() = LegacyWindow(minecraft.window)
  val languageManager: LegacyLanguageManager get() = LegacyLanguageManager(minecraft.languageManager.selected)
  val inGameHud: LegacyInGameHud get() = LegacyInGameHud(this)
  val currentScreen: Screen?
    get() {
      // 26.1.2 kept Minecraft.screen; 26.2 moved it behind Gui.screen().
      return runCatching {
            Minecraft::class.java.getField("screen").get(minecraft) as? Screen
          }
          .getOrNull()
          ?: runCatching {
                minecraft.gui.javaClass.getMethod("screen").invoke(minecraft.gui) as? Screen
              }
              .getOrNull()
    }

  val networkHandler: ClientPlayNetworkHandler?
    get() {
      val current = minecraft.connection ?: return null.also {
        lastConnection = null
        legacyConnection = null
      }
      if (current !== lastConnection) {
        lastConnection = current
        legacyConnection = ClientPlayNetworkHandler(current)
      }
      return legacyConnection
    }

  fun setScreen(screen: Screen) {
    minecraft.setScreenAndShow(screen)
  }
}

data class LegacyLanguageManager(val language: String)

@JvmInline
value class LegacyWindow(private val delegate: com.mojang.blaze3d.platform.Window) {
  val handle: Long get() = delegate.handle()
}

class LegacyInGameHud(private val client: LegacyMinecraftClient) {
  val chatHud: LegacyChatHud get() = LegacyChatHud(client)
}

class LegacyChatHud(private val client: LegacyMinecraftClient) {
  fun addMessage(message: Component) {
    client.player?.sendSystemMessage(message)
  }
}
