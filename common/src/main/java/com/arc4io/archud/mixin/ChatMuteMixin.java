package com.arc4io.archud.mixin;

import com.arc4io.archud.server.AdminServer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Мут из админ-панели: сообщения заглушённого игрока не рассылаются. */
@Mixin(PlayerList.class)
public abstract class ChatMuteMixin {
    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$mute(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound bound, CallbackInfo ci) {
        if (sender != null && AdminServer.muted(sender)) {
            sender.sendSystemMessage(Component.translatable("archud.admin.you_are_muted").withStyle(ChatFormatting.RED));
            ci.cancel();
        }
    }
}
