package com.github.sahyuya.oyasaimusicmiditranslator.client

import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource

/** Minecraft 26 adapter for the shared deterministic local audition API. */
object PreviewSoundPlayer {
  fun play(sound: SoundEvent, volume: Float, pitch: Float, seed: Long) {
    val client = Minecraft.getInstance()
    val player = client.player ?: return
    client.soundManager.play(
        SimpleSoundInstance(
            sound,
            SoundSource.MASTER,
            volume.coerceIn(0f, 1f),
            pitch.coerceIn(0.01f, 4f),
            RandomSource.create(seed),
            player.x,
            player.y,
            player.z,
        ),
    )
  }

  /** Dispatches one deterministic event at a camera-relative pan position. */
  fun playId(
      eventId: String,
      volume: Float,
      pitch: Float,
      seed: Long,
      pan: Int,
  ): Boolean {
    val id = Identifier.tryParse(eventId) ?: return false
    val client = Minecraft.getInstance()
    val player = client.player ?: return false
    if (client.soundManager.getSoundEvent(id) == null) return false

    val theta = Math.toRadians(pan.coerceIn(-100, 100) / 100.0 * 90.0)
    val yaw = Math.toRadians(player.yRot.toDouble())
    val forward = player.lookAngle
    val rightX = -cos(yaw)
    val rightZ = -sin(yaw)
    val cosTheta = cos(theta)
    val sinTheta = sin(theta)
    val x = player.x + forward.x * cosTheta + rightX * sinTheta
    val y = player.eyeY + forward.y * cosTheta
    val z = player.z + forward.z * cosTheta + rightZ * sinTheta

    client.soundManager.play(
        SimpleSoundInstance(
            id,
            SoundSource.MASTER,
            volume.coerceIn(0f, 1f),
            pitch.coerceIn(0.01f, 4f),
            RandomSource.create(seed),
            false,
            0,
            SoundInstance.Attenuation.LINEAR,
            x,
            y,
            z,
            false,
        ),
    )
    return true
  }
}
