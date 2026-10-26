package net.minecraft.client.gui.widget

import net.minecraft.client.gui.LegacyTextRenderer
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

class TextFieldWidget(
    font: LegacyTextRenderer,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
) : EditBox(font.delegate, x, y, width, height, message) {
  var text: String
    get() = value
    set(value) = setValue(value)

  fun setDrawsBackground(drawsBackground: Boolean) = setBordered(drawsBackground)
  fun setEditableColor(color: Int) = setTextColor(color)
}
