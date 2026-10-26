package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.RenderTweaks;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Частицы вне поля зрения и сверх лимита не рисуются. */
@Mixin(ParticleEngine.class)
public abstract class ParticleCullMixin {
    @Redirect(method = "render", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
    private void archud$particle(Particle particle, VertexConsumer vc, Camera camera, float pt) {
        RenderTweaks.beginParticles(camera);
        ParticleAccessor a = (ParticleAccessor) particle;
        if (RenderTweaks.skipParticle(a.archud$x(), a.archud$y(), a.archud$z())) return;
        particle.render(vc, camera, pt);
    }
}
