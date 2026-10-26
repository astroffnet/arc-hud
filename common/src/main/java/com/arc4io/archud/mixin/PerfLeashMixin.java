package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.PerfHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Оптимизация: поводки — только вблизи. */
@Mixin(MobRenderer.class)
public abstract class PerfLeashMixin {
    @Inject(method = "renderLeash", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$leash(Mob mob, float pt, PoseStack pose, MultiBufferSource buffers, Entity holder, CallbackInfo ci) {
        if (PerfHooks.skipLeash(mob)) ci.cancel();
    }
}
