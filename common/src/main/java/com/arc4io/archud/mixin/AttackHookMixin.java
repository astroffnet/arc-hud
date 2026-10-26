package com.arc4io.archud.mixin;

import com.arc4io.archud.client.extras.Extras;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Хитмаркер: удар по существу. */
@Mixin(MultiPlayerGameMode.class)
public abstract class AttackHookMixin {
    @Inject(method = "attack", at = @At("HEAD"), require = 0)
    private void archud$attack(Player player, Entity target, CallbackInfo ci) {
        Extras.onAttack(target);
    }
}
