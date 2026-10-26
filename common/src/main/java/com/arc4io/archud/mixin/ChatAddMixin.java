package com.arc4io.archud.mixin;

import com.arc4io.archud.client.extras.ChatExtras;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Время сообщений, схлопывание повторов, упоминания ника. */
@Mixin(ChatComponent.class)
public abstract class ChatAddMixin {
    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private Component archud$decorate(Component msg) {
        return ChatExtras.process((ChatComponent) (Object) this, msg);
    }
}
