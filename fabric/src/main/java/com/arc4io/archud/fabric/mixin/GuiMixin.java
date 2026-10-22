package com.arc4io.archud.fabric.mixin;

import com.arc4io.archud.client.hud.HudRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.PlayerRideableJumping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Отключает ванильные полосы, когда их заменяет Arc HUD (на Forge то же делается через RenderGuiOverlayEvent).
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void archud$hideHealth(GuiGraphics graphics, CallbackInfo ci) {
        if (HudRenderer.replacesStatusBars()) ci.cancel();
    }

    @Inject(method = "renderVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void archud$hideVehicle(GuiGraphics graphics, CallbackInfo ci) {
        if (HudRenderer.replacesStatusBars()) ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void archud$hideXp(GuiGraphics graphics, int x, CallbackInfo ci) {
        if (HudRenderer.replacesStatusBars()) ci.cancel();
    }

    @Inject(method = "renderJumpMeter", at = @At("HEAD"), cancellable = true)
    private void archud$hideJump(PlayerRideableJumping rideable, GuiGraphics graphics, int x, CallbackInfo ci) {
        if (HudRenderer.replacesStatusBars()) ci.cancel();
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void archud$hideHotbar(float partialTick, GuiGraphics graphics, CallbackInfo ci) {
        if (HudRenderer.replacesHotbar()) ci.cancel();
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void archud$hideEffects(GuiGraphics graphics, CallbackInfo ci) {
        if (HudRenderer.replacesEffects()) ci.cancel();
    }
}
