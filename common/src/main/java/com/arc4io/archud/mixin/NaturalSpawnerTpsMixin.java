package com.arc4io.archud.mixin;

import com.arc4io.archud.server.TpsGuard;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TPS: при лаге естественный спавн мобов через тик. */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerTpsMixin {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private static void archud$spawn(CallbackInfo ci) {
        if (TpsGuard.skipSpawn()) ci.cancel();
    }
}
