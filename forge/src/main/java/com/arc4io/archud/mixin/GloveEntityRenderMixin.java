package com.arc4io.archud.mixin;

import com.arc4io.archud.forge.compat.AetherGloves;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Перчатки Aether от третьего лица: запомнить руки во время модели игрока, дорисовать перчатки после. */
@Mixin(LivingEntityRenderer.class)
public abstract class GloveEntityRenderMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void archud$glovesBegin(LivingEntity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        AetherGloves.begin(e, ((LivingEntityRenderer<?, ?>) (Object) this).getModel(), buffers, false);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0)
    private void archud$glovesEnd(LivingEntity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        AetherGloves.end(e);
    }
}
