package com.arc4io.archud.mixin;

import com.arc4io.archud.client.console.ClientCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Клиентские команды Arc HUD (кнопки в чате) не отправляются серверу. */
@Mixin(ClientPacketListener.class)
public abstract class ClientCommandMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$clientCommand(String command, CallbackInfo ci) {
        if (ClientCommands.handle(command)) ci.cancel();
    }

    @Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true, require = 0)
    private void archud$clientCommandUnsigned(String command, CallbackInfoReturnable<Boolean> cir) {
        if (ClientCommands.handle(command)) cir.setReturnValue(true);
    }
}
