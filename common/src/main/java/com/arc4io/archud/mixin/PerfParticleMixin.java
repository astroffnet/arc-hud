package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import com.arc4io.archud.client.perf.PerfHooks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Queue;

/** Оптимизация частиц: типы, дальность, общий лимит, частицы ударов и разрушения блоков. */
@Mixin(ParticleEngine.class)
public abstract class PerfParticleMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$create(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        if (PerfHooks.skipParticle(options, x, y, z)) cir.setReturnValue(null);
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$add(Particle particle, CallbackInfo ci) {
        int count = 0;
        if (Perf.get("p_limit") > 0) {
            ParticleEngineAccessor acc = (ParticleEngineAccessor) this;
            for (Queue<Particle> q : acc.archud$particles().values()) count += q.size();
            count += acc.archud$particlesToAdd().size();
        }
        if (PerfHooks.skipAdd(particle, count)) ci.cancel();
    }

    @Inject(method = "crack", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$crack(BlockPos pos, Direction side, CallbackInfo ci) {
        if (Perf.on("p_block_crack")) ci.cancel();
    }
}
