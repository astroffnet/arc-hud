package com.arc4io.archud.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Матрицы кадра для шейдерпаков Arc (gbufferModelView, gbufferProjection, позиция камеры). */
@Mixin(LevelRenderer.class)
public abstract class PackCaptureMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void archud$packMatrices(PoseStack pose, float pt, long nanos, boolean outline, Camera camera, GameRenderer gr, LightTexture light,
                                     Matrix4f projection, CallbackInfo ci) {
        com.arc4io.archud.client.fx.pack.PackUniforms.capture(pose.last().pose(), projection, camera);
    }
}
