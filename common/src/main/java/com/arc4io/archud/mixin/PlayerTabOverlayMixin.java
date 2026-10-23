package com.arc4io.archud.mixin;

import com.arc4io.archud.client.hud.ArcTabList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Список игроков (Tab) в стиле Arc. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$render(GuiGraphics g, int width, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        if (!ArcTabList.enabled()) return;
        ArcTabList.render((PlayerTabOverlay) (Object) this, g, width, scoreboard, objective);
        ci.cancel();
    }
}
