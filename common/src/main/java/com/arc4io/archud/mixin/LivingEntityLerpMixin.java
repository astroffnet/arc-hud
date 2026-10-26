package com.arc4io.archud.mixin;

import com.arc4io.archud.server.TpsGuard;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Клиент: при низком TPS мобы плавно идут между редкими обновлениями позиции, а не прыгают. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityLerpMixin {
    @ModifyVariable(method = "lerpTo", at = @At("HEAD"), argsOnly = true, require = 0)
    private int archud$lerpSteps(int steps) {
        return TpsGuard.lerpSteps(steps);
    }
}
