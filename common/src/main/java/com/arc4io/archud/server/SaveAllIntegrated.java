package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * /save-all и /save-all flush для одиночной игры. Ванильная команда выдаёт «недостаточно места на диске»,
 * если сохранение вернуло false — во встроенном сервере это бывает и при успешной записи (другие моды
 * подменяют результат). Здесь мир сохраняется так же, но об ошибке сообщается только при настоящей ошибке
 * записи, с её текстом.
 */
public final class SaveAllIntegrated {
    private SaveAllIntegrated() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("save-all").requires(s -> s.hasPermission(4))
                .executes(c -> run(c, false))
                .then(Commands.literal("flush").executes(c -> run(c, true))));
    }

    private static int run(CommandContext<CommandSourceStack> c, boolean flush) {
        CommandSourceStack src = c.getSource();
        MinecraftServer server = src.getServer();
        src.sendSuccess(() -> Component.translatable("commands.save.saving"), false);
        long t0 = System.nanoTime();
        try {
            boolean ok = server.saveEverything(true, flush, true);
            if (!ok) ArcHud.LOGGER.warn("Arc HUD: saveEverything returned false (levels: {}), the world was written anyway",
                    server.getAllLevels().iterator().hasNext());
        } catch (Throwable t) {
            ArcHud.LOGGER.error("Arc HUD: /save-all failed", t);
            src.sendFailure(Component.translatable("archud.save.error", String.valueOf(t.getMessage())));
            return 0;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        src.sendSuccess(() -> Component.translatable("archud.save.done", ms), true);
        return 1;
    }
}
