package net.minecraft.util

object Identifier {
  @JvmStatic
  fun of(namespace: String, path: String): net.minecraft.resources.Identifier =
      net.minecraft.resources.Identifier.fromNamespaceAndPath(namespace, path)

  @JvmStatic
  fun tryParse(value: String): net.minecraft.resources.Identifier? =
      net.minecraft.resources.Identifier.tryParse(value)
}
