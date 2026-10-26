package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Оптимизация: без виньетки и без полноэкранного эффекта портала. */
@Mixin(Gui.class)
public abstract class PerfGuiMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$vignette(GuiGraphics g, Entity e, CallbackInfo ci) {
        if (Perf.on("w_vignette")) ci.cancel();
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$portal(GuiGraphics g, float alpha, CallbackInfo ci) {
        if (Perf.on("w_portal_overlay")) ci.cancel();
    }
}
