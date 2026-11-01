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

/** Тени игроков (встроенный шейдер Arc) — сразу после отрисовки сущностей. */
@Mixin(LevelRenderer.class)
public abstract class PlayerShadowMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V", ordinal = 0), require = 0)
    private void archud$playerShadows(PoseStack pose, float pt, long nanos, boolean outline, Camera camera, GameRenderer gr, LightTexture light,
                                      Matrix4f projection, CallbackInfo ci) {
        try {
            com.arc4io.archud.client.fx.PlayerShadows.render(pose, camera, pt);
        } catch (Throwable ignored) {
            com.arc4io.archud.client.fx.PlayerShadows.rendering = false;
        }
    }
}
