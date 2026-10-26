package com.arc4io.archud.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x")
    double archud$x();

    @Accessor("y")
    double archud$y();

    @Accessor("z")
    double archud$z();

    @Accessor("age")
    int archud$age();
}
