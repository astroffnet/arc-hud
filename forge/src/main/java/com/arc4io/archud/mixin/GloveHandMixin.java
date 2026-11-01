package com.arc4io.archud.mixin;

import com.arc4io.archud.client.dev.CompatDriver;
import com.arc4io.archud.forge.compat.AetherGloves;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Перчатки Aether от первого лица: рисуются вместе с рукой, а не отдельным проходом. */
@Mixin(PlayerRenderer.class)
public abstract class GloveHandMixin {
    @Inject(method = "renderRightHand", at = @At("HEAD"), require = 0)
    private void archud$right(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, CallbackInfo ci) {
        AetherGloves.fpRight = true;
    }

    @Inject(method = "renderLeftHand", at = @At("HEAD"), require = 0)
    private void archud$left(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, CallbackInfo ci) {
        AetherGloves.fpRight = false;
    }

    @Inject(method = "renderHand", at = @At("HEAD"), require = 0)
    private void archud$handBegin(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
                                  CallbackInfo ci) {
        AetherGloves.begin(player, ((PlayerRenderer) (Object) this).getModel(), buffers, true);
        if (CompatDriver.handTilt != 0F) {
            // тест: так руку двигают моды анимаций (Punchy и т. п.) — до ванильной отрисовки руки
            pose.pushPose();
            pose.mulPose(Axis.ZP.rotationDegrees(CompatDriver.handTilt));
        }
    }

    @Inject(method = "renderHand", at = @At("RETURN"), require = 0)
    private void archud$handEnd(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
                                CallbackInfo ci) {
        AetherGloves.end(player);
        if (CompatDriver.handTilt != 0F) pose.popPose();
    }
}
