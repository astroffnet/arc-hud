package com.arc4io.archud.mixin;

import com.arc4io.archud.client.perf.Perf;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Оптимизация: пока открыты настройки (экраны Arc и ванильные «Настройки…»), мир не перерисовывается —
 * за меню остаётся последний кадр. Инвентарь, чат и сундуки не затрагиваются.
 */
@Mixin(GameRenderer.class)
public abstract class PerfGameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$skipWorld(float pt, long nanos, PoseStack pose, CallbackInfo ci) {
        if (!Perf.on("s_world_behind_menu")) return;
        Screen s = Minecraft.getInstance().screen;
        if (s instanceof com.arc4io.archud.client.screen.ArcScreen || s instanceof com.arc4io.archud.client.screen.ArcPauseScreen
                || s instanceof net.minecraft.client.gui.screens.OptionsScreen || s instanceof net.minecraft.client.gui.screens.OptionsSubScreen
                || s instanceof net.minecraft.client.gui.screens.PauseScreen) ci.cancel();
    }
}
