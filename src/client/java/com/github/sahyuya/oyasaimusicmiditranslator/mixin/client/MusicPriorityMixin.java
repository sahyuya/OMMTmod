package com.github.sahyuya.oyasaimusicmiditranslator.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.github.sahyuya.oyasaimusicmiditranslator.client.OmmtMusicSound;
import com.github.sahyuya.oyasaimusicmiditranslator.client.MusicVoicePolicy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reclaim ambient sound first, then rank our musical voices, only after allocation fails. */
@Mixin(SoundEngine.class)
public abstract class MusicPriorityMixin {
    @Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Redirect(method = "play", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/sounds/ChannelAccess;createHandle(Lcom/mojang/blaze3d/audio/Library$Pool;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChannelAccess.ChannelHandle> ommt$prioritizeMusic(
            ChannelAccess access, Library.Pool pool, SoundInstance incoming) {
        // Vanilla immediately joins this future too; preserve that thread boundary.
        ChannelAccess.ChannelHandle allocated = access.createHandle(pool).join();
        if (allocated != null || pool != Library.Pool.STATIC || incoming.getSource() != SoundSource.RECORDS) {
            return CompletableFuture.completedFuture(allocated);
        }
        ChannelAccess.ChannelHandle victim = null;
        float quietest = Float.MAX_VALUE;
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : instanceToChannel.entrySet()) {
            SoundInstance sound = entry.getKey();
            if (entry.getValue().isStopped() || sound instanceof OmmtMusicSound || !ommt$isAmbient(sound) || sound.getSound().shouldStream()) continue;
            if (sound.getVolume() < quietest) {
                quietest = sound.getVolume();
                victim = entry.getValue();
            }
        }
        if (victim == null && incoming instanceof OmmtMusicSound music) {
            float weakest = Float.MAX_VALUE;
            long now = System.nanoTime();
            for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : instanceToChannel.entrySet()) {
                SoundInstance sound = entry.getKey();
                if (!(sound instanceof OmmtMusicSound voice) || entry.getValue().isStopped() || sound.isLooping() || sound.getSound().shouldStream()) continue;
                float score = MusicVoicePolicy.priority(sound.getVolume(), (now - voice.getStartedAtNanos()) / 1_000_000L);
                if (score < weakest) { weakest = score; victim = entry.getValue(); }
            }
            if (victim != null && !music.getRequiredForStart() && !MusicVoicePolicy.shouldReplace(incoming.getVolume(), weakest)) {
                music.setOmittedForPolyphony(true);
                return CompletableFuture.completedFuture(null);
            }
        }
        if (victim == null) return CompletableFuture.completedFuture(null);
        // These jobs execute in order on Minecraft's sound executor. Maintenance releases
        // stopped channels; normal SoundEngine housekeeping removes the old instance later.
        victim.execute(Channel::stop);
        access.scheduleTick();
        return access.createHandle(pool);
    }

    @Unique
    private static boolean ommt$isAmbient(SoundInstance sound) {
        SoundSource source = sound.getSource();
        // Candles/campfires use BLOCKS, while note blocks use RECORDS. Protect local musical uses (marker above)
        // and server self-emitter music; reclaim only these world ambience events.
        if (source == SoundSource.BLOCKS && sound instanceof net.minecraft.client.resources.sounds.SimpleSoundInstance) {
            String id = sound.getIdentifier().toString();
            return id.equals("minecraft:block.candle.ambient") || id.equals("minecraft:block.fire.ambient") ||
                id.equals("minecraft:block.campfire.crackle");
        }
        return source == SoundSource.AMBIENT || source == SoundSource.WEATHER ||
            source == SoundSource.HOSTILE || source == SoundSource.NEUTRAL;
    }
}
