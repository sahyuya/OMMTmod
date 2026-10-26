package net.minecraft.client.gui.screen

import net.minecraft.client.LegacyMinecraftClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.LegacyTextRenderer
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.network.chat.Component

open class Screen(title: Component) : net.minecraft.client.gui.screens.Screen(title) {
  protected val client: LegacyMinecraftClient get() = MinecraftClient.getInstance()
  protected val textRenderer: LegacyTextRenderer = LegacyTextRenderer(font)

  protected fun addDrawableChild(widget: TextFieldWidget): TextFieldWidget =
      addRenderableWidget(widget)

  open fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit
  open fun shouldPause(): Boolean = false

  final override fun isPauseScreen(): Boolean = shouldPause()
}
