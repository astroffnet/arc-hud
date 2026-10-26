package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.PerfHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Оптимизация: подписи над сущностями — только вблизи; имена мобов можно скрыть. */
@Mixin(EntityRenderer.class)
public abstract class PerfNameTagMixin {
    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$tag(Entity entity, Component name, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (PerfHooks.skipNameTag(entity)) ci.cancel();
    }
}
