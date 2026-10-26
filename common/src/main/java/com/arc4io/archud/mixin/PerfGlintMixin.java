package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оптимизация: без блеска зачарований (лишний проход отрисовки на каждый зачарованный предмет). */
@Mixin(ItemStack.class)
public abstract class PerfGlintMixin {
    @Inject(method = "hasFoil", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$foil(CallbackInfoReturnable<Boolean> cir) {
        if (Perf.on("x_glint")) cir.setReturnValue(false);
    }
}
