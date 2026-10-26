package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оптимизация: без отрисовки дождя/снега и без обводки светящихся сущностей (отдельный проход). */
@Mixin(LevelRenderer.class)
public abstract class PerfLevelRendererMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$rain(LightTexture light, float pt, double x, double y, double z, CallbackInfo ci) {
        if (Perf.on("w_rain")) ci.cancel();
    }

    @Inject(method = "shouldShowEntityOutlines", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$outlines(CallbackInfoReturnable<Boolean> cir) {
        if (Perf.on("s_entity_outline")) cir.setReturnValue(false);
    }

    /** Arc TSR: матрицы камеры этого кадра для совмещения с прошлым. */
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void archud$taauCapture(com.mojang.blaze3d.vertex.PoseStack pose, float pt, long nanos, boolean outline,
                                    net.minecraft.client.Camera camera, net.minecraft.client.renderer.GameRenderer gr,
                                    LightTexture light, org.joml.Matrix4f projection, CallbackInfo ci) {
        com.arc4io.archud.client.fx.Taau.capture(pose.last().pose(), projection, camera.getPosition());
    }

    /** Адаптивная дальность: чанки за радиусом не рисуются и не перестраиваются. */
    @Inject(method = "applyFrustum", at = @At("TAIL"), require = 0)
    private void archud$adaptive(net.minecraft.client.renderer.culling.Frustum frustum, CallbackInfo ci) {
        com.arc4io.archud.client.perf.AdaptiveDistance.filter(this);
    }

    @Inject(method = "compileChunks", at = @At("HEAD"), require = 0)
    private void archud$compileStart(net.minecraft.client.Camera camera, CallbackInfo ci) {
        com.arc4io.archud.client.perf.AdaptiveDistance.compileStart();
    }

    @Inject(method = "compileChunks", at = @At("RETURN"), require = 0)
    private void archud$compileEnd(net.minecraft.client.Camera camera, CallbackInfo ci) {
        com.arc4io.archud.client.perf.AdaptiveDistance.compileEnd();
    }
}
