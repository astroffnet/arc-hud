package com.arc4io.archud.mixin;

import com.arc4io.archud.client.recipes.RecipeOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Клики и колесо над списком предметов — раньше, чем окно получит их (не роняет предметы и т. п.). */
@Mixin(MouseHandler.class)
public abstract class RecipeMouseMixin {
    private static double archud$gx(Minecraft mc) {
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }

    private static double archud$gy(Minecraft mc) {
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$press(long window, int button, int action, int mods, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow() || mc.screen == null || action != 1) return;
        if (RecipeOverlay.click(mc.screen, archud$gx(mc), archud$gy(mc), button)) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$scroll(long window, double dx, double dy, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow() || mc.screen == null || dy == 0) return;
        if (RecipeOverlay.scroll(mc.screen, archud$gx(mc), archud$gy(mc), Math.signum(dy))) ci.cancel();
    }
}
