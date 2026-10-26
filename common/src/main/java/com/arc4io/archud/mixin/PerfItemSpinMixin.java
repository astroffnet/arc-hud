package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оптимизация: выброшенные предметы не вращаются. */
@Mixin(ItemEntity.class)
public abstract class PerfItemSpinMixin {
    @Inject(method = "getSpin", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$spin(float pt, CallbackInfoReturnable<Float> cir) {
        if (Perf.on("x_item_spin")) cir.setReturnValue(0F);
    }
}
