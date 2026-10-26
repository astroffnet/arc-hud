package com.arc4io.archud.mixin;

import com.arc4io.archud.client.extras.ContainerExtras;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Пока печатаете в поиске сундука, клавиши (E, 1–9, Q) не закрывают окно и не двигают предметы. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSearchKeyMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$key(int key, int scan, int mods, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerExtras.consumeKey(key, scan, mods)) cir.setReturnValue(true);
    }
}
