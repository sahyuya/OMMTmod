package com.github.sahyuya.oyasaimusicmiditranslator.mixin.client;

import com.github.sahyuya.oyasaimusicmiditranslator.client.OyasaiEditorScreen;
import com.github.sahyuya.oyasaimusicmiditranslator.client.PlaybackClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep all game/sound/network access on the client thread, with sub-tick dispatch at high FPS. */
@Mixin(Minecraft.class)
public abstract class PlaybackFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void ommt$pumpPlayback(boolean renderLevel, CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.isPaused()) return;
        PlaybackClient.INSTANCE.tick();
        if (client.gui.screen() instanceof OyasaiEditorScreen editor) editor.pumpPlayback();
    }
}
