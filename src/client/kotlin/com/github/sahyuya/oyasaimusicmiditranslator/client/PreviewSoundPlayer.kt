package com.github.sahyuya.oyasaimusicmiditranslator.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.AbstractSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.random.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object PreviewSoundPlayer {

    private class PannedSoundInstance(
        sound: SoundEvent,
        volume: Float,
        pitch: Float,
        seed: Long,
        pan: Int // -100 (完全左) 〜 100 (完全右)
    ) : AbstractSoundInstance(sound, SoundCategory.MASTER, Random.create(seed)) {
        
        private val posX: Double
        private val posZ: Double

        init {
            this.volume = volume.coerceIn(0f, 1f)
            this.pitch = pitch.coerceIn(0.01f, 4f)
            
            val panNormalized = pan.coerceIn(-100, 100) / 100.0
            val angle = panNormalized * (PI / 2.0)
            
            // 座標計算のみ行い保持する
            // ※注意: ここに player.x や player.z は絶対に足さないでください
            this.posX = sin(angle)
            this.posZ = -cos(angle)
        }

        // ====================================================================
        // 【重要】フィールド代入ではなく、メソッドを明示的にオーバーライドする
        // ====================================================================

        // 相対座標モードを強制（これでワールド座標ではなくプレイヤー基準になる）
        override fun isRelative(): Boolean = true

        // 距離減衰を無効化（常に指定したボリュームで鳴る）
        override fun getAttenuationType(): SoundInstance.AttenuationType = SoundInstance.AttenuationType.NONE

        // 計算した相対座標を返す
        override fun getX(): Double = posX
        override fun getY(): Double = 0.0
        override fun getZ(): Double = posZ
    }

    fun playId(sound: SoundEvent, volume: Float, pitch: Float, seed: Long, pan: Int): Boolean {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return false

        client.soundManager.play(PannedSoundInstance(sound, volume, pitch, seed, pan))
        return true
    }

    fun play(sound: SoundEvent, volume: Float, pitch: Float, seed: Long) {
        playId(sound, volume, pitch, seed, 0)
    }
}