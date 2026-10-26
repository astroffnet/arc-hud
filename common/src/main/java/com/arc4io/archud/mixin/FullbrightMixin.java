package com.arc4io.archud.mixin;

import com.arc4io.archud.client.extras.Extras;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Полная яркость: освещение считается как с ночным зрением. */
@Mixin(value = LightTexture.class, priority = 500)
public abstract class FullbrightMixin {
    @Redirect(method = "updateLightTexture", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/world/effect/MobEffect;)Z"))
    private boolean archud$nightVision(LocalPlayer player, MobEffect effect) {
        return player.hasEffect(effect) || (effect == MobEffects.NIGHT_VISION && Extras.fullbright());
    }
}
