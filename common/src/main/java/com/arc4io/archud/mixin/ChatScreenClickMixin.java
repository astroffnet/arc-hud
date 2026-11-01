package com.arc4io.archud.mixin;

import com.arc4io.archud.client.map.ShareInbox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Кнопки уведомления «Принять / Отклонить метку» нажимаются, пока открыт чат. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenClickMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$shareClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && ShareInbox.click(mx, my)) cir.setReturnValue(true);
    }
}
