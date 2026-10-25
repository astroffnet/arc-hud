package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.FirstPersonCam;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Своё тело от первого лица: предметы в руках рисует обычный вид от первого лица, не тело. */
@Mixin(ItemInHandLayer.class)
public abstract class HeldItemLayerMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$held(PoseStack pose, MultiBufferSource buf, int light, LivingEntity e, float a, float b, float c, float d, float f, float g, CallbackInfo ci) {
        if (FirstPersonCam.renderingBody) ci.cancel();
    }
}
