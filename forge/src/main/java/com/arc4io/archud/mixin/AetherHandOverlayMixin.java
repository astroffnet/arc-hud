package com.arc4io.archud.mixin;

import com.arc4io.archud.forge.compat.AetherGloves;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Свой проход Aether для перчатки от первого лица не нужен — перчатка рисуется вместе с рукой. */
@Pseudo
@Mixin(targets = "com.aetherteam.aether.client.event.hooks.HandRenderHooks", remap = false)
public abstract class AetherHandOverlayMixin {
    @Inject(method = "renderGloveHandOverlay", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void archud$skipOverlay(ItemInHandRenderer r, AbstractClientPlayer player, InteractionHand hand, float pt, float pitch, float swing,
                                           PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (AetherGloves.enabled()) ci.cancel();
    }
}
