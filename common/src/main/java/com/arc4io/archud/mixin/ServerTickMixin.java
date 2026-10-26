package com.arc4io.archud.mixin;

import com.arc4io.archud.server.TpsGuard;
import com.arc4io.archud.server.WorldSaver;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Сохранение мира — в самом начале тика (не ждёт очереди задач, которая при лаге откладывается); замер TPS — в конце. */
@Mixin(MinecraftServer.class)
public abstract class ServerTickMixin {
    @Inject(method = "tickServer", at = @At("HEAD"), require = 0)
    private void archud$saveFirst(BooleanSupplier haveTime, CallbackInfo ci) {
        WorldSaver.onTick((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("TAIL"), require = 0)
    private void archud$tps(BooleanSupplier haveTime, CallbackInfo ci) {
        TpsGuard.tick((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"), require = 0)
    private void archud$stopped(CallbackInfo ci) {
        WorldSaver.serverStopped();
    }
}
