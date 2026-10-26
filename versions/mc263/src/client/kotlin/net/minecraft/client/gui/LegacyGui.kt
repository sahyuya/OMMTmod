package net.minecraft.client.gui

import net.minecraft.client.input.MouseButtonEvent

typealias Click = MouseButtonEvent

class LegacyTextRenderer(internal val delegate: Font) {
  fun getWidth(text: String): Int = delegate.width(text)
}

/** Legacy rendering is intentionally inert; OMMT 26 renders through ImGui's draw lists. */
class DrawContext {
  fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) = Unit
  fun drawTextWithShadow(font: LegacyTextRenderer, text: String, x: Int, y: Int, color: Int) = Unit
  fun drawCenteredTextWithShadow(font: LegacyTextRenderer, text: String, x: Int, y: Int, color: Int) = Unit
}
