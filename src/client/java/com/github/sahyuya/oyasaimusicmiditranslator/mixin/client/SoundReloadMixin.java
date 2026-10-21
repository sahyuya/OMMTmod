package com.github.sahyuya.oyasaimusicmiditranslator.mixin.client;

import com.github.sahyuya.oyasaimusicmiditranslator.client.SoundBankManifest;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class SoundReloadMixin {
    @Inject(method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("TAIL"))
    private void ommt$invalidateBankCache(CallbackInfo ci) {
        SoundBankManifest.INSTANCE.invalidateCache();
    }
}
