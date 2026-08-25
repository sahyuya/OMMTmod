package net.minecraft.text

import net.minecraft.network.chat.Component

object Text {
  @JvmStatic fun literal(value: String): Component = Component.literal(value)
}
