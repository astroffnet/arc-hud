package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оптимизация: стопка выброшенных предметов рисуется одной моделью, а не 2–5 копиями. */
@Mixin(ItemEntityRenderer.class)
public abstract class PerfItemEntityMixin {
    @Inject(method = "getRenderAmount", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$single(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (Perf.on("x_item_single")) cir.setReturnValue(1);
    }
}
