package com.arc4io.archud.mixin;

import com.arc4io.archud.server.AuthGate;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Отслеживание /login и /register для {@link AuthGate}. */
@Mixin(Commands.class)
public abstract class CommandsAuthMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), require = 0)
    private void archud$auth(ParseResults<CommandSourceStack> parse, String command, CallbackInfoReturnable<Integer> cir) {
        if (parse.getContext().getSource().getEntity() instanceof ServerPlayer p) AuthGate.onCommand(p, command);
    }
}
