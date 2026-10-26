package com.arc4io.archud.mixin;

import com.arc4io.archud.server.TpsGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TPS: дальние мобы/предметы реже при лаге; случайные тики блоков реже при сильном лаге. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTpsMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$activation(Entity entity, CallbackInfo ci) {
        if (TpsGuard.skipEntity((ServerLevel) (Object) this, entity)) ci.cancel();
    }

    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true, require = 0)
    private int archud$randomTicks(int speed) {
        return TpsGuard.randomTicks(speed);
    }
}
