package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Оптимизация: без тумана на суше (под водой, в лаве и при слепоте туман остаётся). */
@Mixin(FogRenderer.class)
public abstract class PerfFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"), require = 0)
    private static void archud$fog(Camera camera, FogRenderer.FogMode mode, float far, boolean thick, float pt, CallbackInfo ci) {
        if (camera.getFluidInCamera() != FogType.NONE || thick) return;
        float adaptive = com.arc4io.archud.client.perf.AdaptiveDistance.fogEnd();
        if (adaptive > 0 && mode == FogRenderer.FogMode.FOG_TERRAIN && adaptive < RenderSystem.getShaderFogEnd()) {
            // адаптивная дальность: туман у края радиуса, чтобы не было видно обреза
            RenderSystem.setShaderFogStart(adaptive - Math.max(4F, Math.min(64F, adaptive / 10F)));
            RenderSystem.setShaderFogEnd(adaptive);
            return;
        }
        if (!Perf.on("w_fog")) return;
        if (camera.getEntity() instanceof net.minecraft.world.entity.LivingEntity le
                && (le.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) || le.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS))) return;
        RenderSystem.setShaderFogStart(far * 4F);
        RenderSystem.setShaderFogEnd(far * 5F);
    }
}
