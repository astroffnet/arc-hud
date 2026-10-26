package com.arc4io.archud.mixin;

import com.arc4io.archud.server.TpsGuard;
import com.arc4io.archud.server.WorldSaver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Встроенный сервер: сохранение и в паузе; дальность симуляции с учётом временного ограничения при лаге. */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"), require = 0)
    private void archud$save(BooleanSupplier haveTime, CallbackInfo ci) {
        TpsGuard.optionSim(Math.max(2, Minecraft.getInstance().options.simulationDistance().get()));
        WorldSaver.onTick((IntegratedServer) (Object) this);
    }

    @ModifyArg(method = "tickServer", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;setSimulationDistance(I)V"))
    private int archud$simDistance(int distance) {
        return TpsGuard.simulationDistance(distance);
    }
}
