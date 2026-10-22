package com.arc4io.archud.mixin;

import com.arc4io.archud.compat.AdAstraCompat;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Скрывает родной HUD Ad Astra (баллон кислорода и батарея), когда его показывает Arc HUD.
 * @Pseudo — если Ad Astra не установлен, миксин просто не применяется.
 */
@Pseudo
@Mixin(targets = "earth.terrarium.adastra.client.screens.player.OverlayScreen", remap = false)
public abstract class AdAstraOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void archud$hide(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        if (AdAstraCompat.replacesOverlay()) ci.cancel();
    }
}
