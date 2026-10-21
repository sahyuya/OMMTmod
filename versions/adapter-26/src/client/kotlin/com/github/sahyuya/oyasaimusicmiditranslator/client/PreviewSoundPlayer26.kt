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

/** Music may use a candle/mob event too; never reclaim it as world ambience. */
interface OmmtMusicSound {
  val startedAtNanos: Long
  var omittedForPolyphony: Boolean
  val requiredForStart: Boolean get() = false
}

/** Minecraft 26 adapter for the shared deterministic local audition API. */
object PreviewSoundPlayer {
  private val editorVoices = mutableMapOf<SoundInstance, Int>()
  private var browserVoice: SoundInstance? = null

  fun stopBrowserPreview() {
    browserVoice?.let { Minecraft.getInstance().soundManager.stop(it) }
    browserVoice = null
  }

  fun previewBrowserSound(eventId: String, seed: Long): Boolean {
    stopBrowserPreview()
    return playId(eventId, 1f, 1f, seed, 0, browserPreview = true)
  }

  fun stopInaudibleEditorParts(audible: (Int) -> Boolean) {
    val manager = Minecraft.getInstance().soundManager
    val iterator = editorVoices.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (!audible(entry.value)) { manager.stop(entry.key); iterator.remove() }
      else if (!manager.isActive(entry.key)) iterator.remove()
    }
  }
  var lastDispatchStarted: Boolean = false
    private set
  fun play(sound: SoundEvent, volume: Float, pitch: Float, seed: Long) {
    val client = Minecraft.getInstance()
    val player = client.player ?: return
    client.soundManager.play(
        object : SimpleSoundInstance(
            sound,
            SoundSource.RECORDS,
            volume.coerceIn(0f, 1f),
            pitch.coerceIn(0.01f, 4f),
            RandomSource.create(seed),
            player.x,
            player.y,
            player.z,
        ), OmmtMusicSound {
          override val startedAtNanos = System.nanoTime()
          override var omittedForPolyphony = false
        },
    )
  }

  /** Dispatches one deterministic event at a camera-relative pan position. */
  fun playId(
      eventId: String,
      volume: Float,
      pitch: Float,
      seed: Long,
      pan: Int,
      requireStart: Boolean = false,
      editorPart: Int? = null,
      browserPreview: Boolean = false,
  ): Boolean {
    lastDispatchStarted = false
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

    val instance = object : SimpleSoundInstance(
            id,
            SoundSource.RECORDS,
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
        ), OmmtMusicSound {
          override val requiredForStart = requireStart
          override val startedAtNanos = System.nanoTime()
          override var omittedForPolyphony = false
        }
    val result = client.soundManager.play(instance)
    if (browserPreview) browserVoice = instance
    if (editorPart != null) {
      editorVoices.keys.removeAll { !client.soundManager.isActive(it) }
      if (client.soundManager.isActive(instance)) editorVoices[instance] = editorPart
    }
    // Muting is intentional, but an allocation failure must not produce a false START ACK.
    lastDispatchStarted = !instance.omittedForPolyphony && (result != net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED ||
        client.options.getSoundSourceVolume(SoundSource.RECORDS) == 0f ||
        client.options.getSoundSourceVolume(SoundSource.MASTER) == 0f)
    return instance.omittedForPolyphony || lastDispatchStarted
  }
}
