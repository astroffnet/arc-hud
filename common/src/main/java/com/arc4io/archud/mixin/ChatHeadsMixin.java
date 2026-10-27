package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ChatHeads;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Место под голову игрока слева от сообщений: перенос строк уже на ширину головы, клики сдвинуты так же. */
@Mixin(ChatComponent.class)
public abstract class ChatHeadsMixin {
    @ModifyArg(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"),
            index = 1, require = 0)
    private int archud$wrapWidth(int width) {
        return Math.max(40, width - ChatHeads.gutter());
    }

    /** Чат поднят над полосами HUD — клики по строкам сдвигаются так же. */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "screenToChatY", at = @At("HEAD"), argsOnly = true, require = 0)
    private double archud$chatY(double y) {
        return com.arc4io.archud.client.screen.ArcChatRenderer.enabled() ? y + com.arc4io.archud.client.hud.HudRenderer.chatLift() : y;
    }

    @Inject(method = "screenToChatX", at = @At("RETURN"), cancellable = true, require = 0)
    private void archud$chatX(double x, CallbackInfoReturnable<Double> cir) {
        int gut = ChatHeads.gutter();
        if (gut > 0) cir.setReturnValue(cir.getReturnValueD() - gut);
    }
}
