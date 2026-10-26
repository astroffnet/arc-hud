package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.FirstPersonCam;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Виды от первого лица Arc: крен, своё покачивание, угол обзора бодикама и эффект объектива. */
@Mixin(GameRenderer.class)
public abstract class GameRendererCamMixin {
    @Inject(method = "bobHurt", at = @At("TAIL"), require = 0)
    private void archud$roll(PoseStack pose, float partialTick, CallbackInfo ci) {
        FirstPersonCam.applyRoll(pose);
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$bob(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (FirstPersonCam.bobView(pose, partialTick)) ci.cancel();
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void archud$fov(Camera camera, float partialTick, boolean world, CallbackInfoReturnable<Double> cir) {
        double v = FirstPersonCam.fov(cir.getReturnValue(), world);
        if (world) v = com.arc4io.archud.client.extras.Extras.zoomFov(v);
        if (v != cir.getReturnValue()) cir.setReturnValue(v);
    }

    @Inject(method = "render", require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V"))
    private void archud$lens(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        FirstPersonCam.postProcess(partialTick);
    }

    @Inject(method = "getNightVisionScale", at = @At("HEAD"), cancellable = true, require = 0)
    private static void archud$fullbright(net.minecraft.world.entity.LivingEntity e, float pt, CallbackInfoReturnable<Float> cir) {
        if (com.arc4io.archud.client.extras.Extras.fullbright() && e instanceof net.minecraft.client.player.LocalPlayer
                && !e.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)) cir.setReturnValue(1.0F);
    }

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"), require = 0)
    private void archud$reload(net.minecraft.server.packs.resources.ResourceManager manager, CallbackInfo ci) {
        FirstPersonCam.reset();
    }
}
