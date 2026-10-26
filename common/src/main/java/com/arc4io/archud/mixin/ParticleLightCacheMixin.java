package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Кэш освещения частиц (как Particle Core): свет у частицы пересчитывается, только когда она перешла в другой блок
 * или раз в 10 тиков, а не каждый кадр для каждой частицы.
 */
@Mixin(Particle.class)
public abstract class ParticleLightCacheMixin {
    @Unique private long archud$lightPos = Long.MIN_VALUE;
    @Unique private int archud$light;
    @Unique private int archud$lightAge;

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$cached(float pt, CallbackInfoReturnable<Integer> cir) {
        if (!Perf.on("p_light_cache")) return;
        ParticleAccessor a = (ParticleAccessor) (Object) this;
        int age = a.archud$age();
        long pos = net.minecraft.core.BlockPos.asLong(Mth.floor(a.archud$x()), Mth.floor(a.archud$y()), Mth.floor(a.archud$z()));
        if (pos == archud$lightPos && age - archud$lightAge < 10 && age >= archud$lightAge) cir.setReturnValue(archud$light);
    }

    @Inject(method = "getLightColor", at = @At("RETURN"), require = 0)
    private void archud$store(float pt, CallbackInfoReturnable<Integer> cir) {
        ParticleAccessor a = (ParticleAccessor) (Object) this;
        int age = a.archud$age();
        long pos = net.minecraft.core.BlockPos.asLong(Mth.floor(a.archud$x()), Mth.floor(a.archud$y()), Mth.floor(a.archud$z()));
        if (pos != archud$lightPos || age - archud$lightAge >= 10 || age < archud$lightAge) {
            archud$lightPos = pos;
            archud$light = cir.getReturnValueI();
            archud$lightAge = age;
        }
    }
}
