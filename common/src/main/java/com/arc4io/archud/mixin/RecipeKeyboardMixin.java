package com.arc4io.archud.mixin;

import com.arc4io.archud.client.recipes.RecipeOverlay;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Клавиши для списка рецептов (поиск, R над предметом) — на любом окне, на Forge и Fabric. */
@Mixin(KeyboardHandler.class)
public abstract class RecipeKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$key(long window, int key, int scan, int action, int mods, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow() || mc.screen == null || action == 0) return;
        if (RecipeOverlay.key(mc.screen, key, scan, mods)) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$char(long window, int codepoint, int mods, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow() || mc.screen == null || !Character.isBmpCodePoint(codepoint)) return;
        if (RecipeOverlay.typed(mc.screen, (char) codepoint, mods)) ci.cancel();
    }
}
