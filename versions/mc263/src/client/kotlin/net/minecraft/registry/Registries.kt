package net.minecraft.registry

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent

object Registries {
  val SOUND_EVENT = LegacySoundEventRegistry
}

object LegacySoundEventRegistry {
  val ids: Set<Identifier> get() = BuiltInRegistries.SOUND_EVENT.keySet()
  fun get(id: Identifier): SoundEvent =
      BuiltInRegistries.SOUND_EVENT.getValue(id) ?: error("unknown sound event: $id")
}
