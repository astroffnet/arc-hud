package com.arc4io.archud.client.extras;

import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Экран «Соединение потеряно»: кнопка «Переподключиться» и автопереподключение с обратным отсчётом
 * (включается кнопкой «Авто» прямо на этом экране).
 */
public final class ReconnectHooks {
    private static final long DELAY = 5000L;
    private static Screen owner;
    private static long openedAt;
    private static ArcButton autoBtn;

    private ReconnectHooks() {}

    private static String lastIp() {
        String ip = ArcConfig.get().lastServerIp;
        return ip == null ? "" : ip;
    }

    public static void onInit(Screen screen, Consumer<AbstractWidget> add) {
        if (!(screen instanceof DisconnectedScreen) || lastIp().isEmpty()) {
            owner = null;
            return;
        }
        owner = screen;
        openedAt = Util.getMillis();
        int w = screen.width, h = screen.height;
        add.accept(new ArcButton(w / 2 - 100, h - 52, 130, 20, Component.translatable("archud.reconnect.now", lastIp()), b -> reconnect()));
        autoBtn = new ArcButton(w / 2 + 34, h - 52, 66, 20, autoLabel(), b -> {
            ArcConfig cfg = ArcConfig.get();
            cfg.autoReconnect = !cfg.autoReconnect;
            ArcConfig.save();
            openedAt = Util.getMillis();
            b.setMessage(autoLabel());
        });
        add.accept(autoBtn);
    }

    private static Component autoLabel() {
        return Component.translatable(ArcConfig.get().autoReconnect ? "archud.reconnect.auto_on" : "archud.reconnect.auto_off");
    }

    public static void onRender(Screen screen, GuiGraphics g) {
        if (screen != owner || !ArcConfig.get().autoReconnect) return;
        long left = DELAY - (Util.getMillis() - openedAt);
        Minecraft mc = Minecraft.getInstance();
        if (left <= 0) {
            owner = null;
            reconnect();
            return;
        }
        g.drawCenteredString(mc.font, Component.translatable("archud.reconnect.in", (left + 999) / 1000), screen.width / 2, screen.height - 64, 0xFFFFD54F);
    }

    private static void reconnect() {
        Minecraft mc = Minecraft.getInstance();
        String ip = lastIp();
        if (ip.isEmpty()) return;
        String name = ArcConfig.get().lastServerName == null || ArcConfig.get().lastServerName.isEmpty() ? ip : ArcConfig.get().lastServerName;
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(ip), new ServerData(name, ip, false), false);
    }
}
