package com.arc4io.archud.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ParticleEngine.class)
public interface ParticleEngineInvoker {
    @Invoker("tickParticle")
    void archud$tickParticle(Particle particle);

    @Invoker("updateCount")
    void archud$updateCount(ParticleGroup group, int delta);
}
