package com.arc4io.archud.mixin;

import com.arc4io.archud.server.AntiEsp;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AntiESP: сущность за стенами не отправляется игроку (см. {@link AntiEsp}). */
@Mixin(Entity.class)
public abstract class EntityAntiEspMixin {
    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$antiEsp(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (AntiEsp.hidden((Entity) (Object) this, player)) cir.setReturnValue(false);
    }
}
