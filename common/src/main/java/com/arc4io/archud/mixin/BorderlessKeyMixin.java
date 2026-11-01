package com.arc4io.archud.mixin;

import com.arc4io.archud.client.video.Borderless;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** F11 переключает «полноэкранный в окне» (без рамки) вместо эксклюзивного полноэкранного режима. */
@Mixin(KeyboardHandler.class)
public abstract class BorderlessKeyMixin {
    @Redirect(method = "keyPress", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;toggleFullScreen()V"), require = 0)
    private void archud$f11(Window w) {
        if (!Borderless.toggle(w)) w.toggleFullScreen();
    }
}
