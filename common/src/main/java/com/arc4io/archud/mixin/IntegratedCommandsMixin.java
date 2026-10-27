package com.arc4io.archud.mixin;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.commands.BanIpCommands;
import net.minecraft.server.commands.BanListCommands;
import net.minecraft.server.commands.BanPlayerCommands;
import net.minecraft.server.commands.DeOpCommands;
import net.minecraft.server.commands.OpCommand;
import net.minecraft.server.commands.PardonCommand;
import net.minecraft.server.commands.PardonIpCommand;
import net.minecraft.server.commands.SaveAllCommand;
import net.minecraft.server.commands.SaveOffCommand;
import net.minecraft.server.commands.SaveOnCommand;
import net.minecraft.server.commands.SetPlayerIdleTimeoutCommand;
import net.minecraft.server.commands.WhitelistCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Серверные команды в одиночной игре и «Открыть для сети»: op, deop, ban, ban-ip, banlist, pardon, pardon-ip,
 * whitelist, save-all/save-on/save-off, setidletimeout (в ваниле они есть только на выделенном сервере).
 * /stop не добавляется — для своего мира есть «Сохранить и выйти».
 */
@Mixin(Commands.class)
public abstract class IntegratedCommandsMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void archud$serverCommands(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        if (selection != Commands.CommandSelection.INTEGRATED) return;
        CommandDispatcher<CommandSourceStack> d = ((Commands) (Object) this).getDispatcher();
        try {
            BanIpCommands.register(d);
            BanListCommands.register(d);
            BanPlayerCommands.register(d);
            DeOpCommands.register(d);
            OpCommand.register(d);
            PardonCommand.register(d);
            PardonIpCommand.register(d);
            com.arc4io.archud.server.SaveAllIntegrated.register(d);
            SaveOffCommand.register(d);
            SaveOnCommand.register(d);
            SetPlayerIdleTimeoutCommand.register(d);
            WhitelistCommand.register(d);
        } catch (Throwable t) {
            com.arc4io.archud.ArcHud.LOGGER.warn("Arc HUD: server commands for singleplayer failed", t);
        }
    }
}
