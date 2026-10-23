package com.arc4io.archud.mixin;

import com.arc4io.archud.client.camera.ArcCamera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Мышь крутит камеру, а не персонажа (свободный обзор). */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$turn(double dx, double dy, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer p && ArcCamera.turn(p, dx, dy)) ci.cancel();
    }
}
