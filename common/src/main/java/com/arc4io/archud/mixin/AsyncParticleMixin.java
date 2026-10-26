package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.AsyncParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** Многопоточное обновление частиц (см. AsyncParticles). Приоритет выше — очередь из рабочих потоков раньше лимитов. */
@Mixin(value = ParticleEngine.class, priority = 900)
public abstract class AsyncParticleMixin {
    @Inject(method = "tickParticleList", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$async(Collection<Particle> list, CallbackInfo ci) {
        if (AsyncParticles.tickList((ParticleEngine) (Object) this, list)) ci.cancel();
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$deferAdd(Particle particle, CallbackInfo ci) {
        if (AsyncParticles.deferAdd(particle)) ci.cancel();
    }
}
