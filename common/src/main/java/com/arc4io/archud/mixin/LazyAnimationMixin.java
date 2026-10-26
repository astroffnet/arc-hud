package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.RenderTweaks;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Ленивые» анимации текстур: не пересчитываются, пока мир не виден. */
@Mixin(TextureAtlas.class)
public abstract class LazyAnimationMixin {
    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$lazy(CallbackInfo ci) {
        if (RenderTweaks.skipTextureAnimation()) ci.cancel();
    }
}
