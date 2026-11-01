package com.arc4io.archud.mixin;

import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Regeneration (Doctor Who): своя полоска «регенераций осталось» у хотбара (тёмно-красная, с числом посередине)
 * рисуется поверх всего HUD. Arc HUD показывает то же самое в панели «Регенерация» — родная полоска скрывается.
 */
@Pseudo
@Mixin(targets = "mc.craig.software.regen.client.screen.overlay.RegenerationOverlay", remap = false)
public abstract class RegenOverlayMixin {
    @Inject(method = "renderUi", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void archud$hideRegenBar(GuiGraphics g, CallbackInfo ci) {
        if (ArcConfig.get().regenerationIntegration && HudRenderer.replacesStatusBars()) ci.cancel();
    }
}
