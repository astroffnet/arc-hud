package com.arc4io.archud.client;

import com.arc4io.archud.client.bhop.BunnyHop;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.screen.ArcConfigScreen;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Клиентская логика, общая для Forge и Fabric.
 */
public final class ArcClient {
    private static ClientPlatform platform;

    private ArcClient() {}

    public static void init(ClientPlatform p) {
        platform = p;
        com.arc4io.archud.compat.Integrations.init();
        ArcClientNet.init();
    }

    public static ClientPlatform platform() {
        return platform;
    }

    /** Вызывается в конце каждого клиентского тика. */
    public static void tick(Minecraft mc) {
        com.arc4io.archud.client.map.MapScanner.worldCheck(mc);
        ArcClientNet.tick(mc);
        if (mc.player == null) return;
        ArcConfig cfg = ArcConfig.get();

        while (ArcKeys.OPEN_BACKPACK.consumeClick()) {
            if (mc.screen == null) platform.sendToServer(ArcPacket.OPEN_BACKPACK);
        }
        while (ArcKeys.SORT_INVENTORY.consumeClick()) {
            platform.sendToServer(ArcPacket.SORT_INVENTORY);
        }
        while (ArcKeys.TOGGLE_HUD.consumeClick()) {
            cfg.enabled = !cfg.enabled;
            ArcConfig.save();
            mc.player.displayClientMessage(Component.translatable(cfg.enabled ? "archud.msg.hud_on" : "archud.msg.hud_off")
                    .withStyle(ChatFormatting.AQUA), true);
        }
        while (ArcKeys.TOGGLE_BHOP.consumeClick()) {
            cfg.bunnyHop = !cfg.bunnyHop;
            ArcConfig.save();
            mc.player.displayClientMessage(Component.translatable(cfg.bunnyHop ? "archud.msg.bhop_on" : "archud.msg.bhop_off")
                    .withStyle(cfg.bunnyHop ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        }
        while (ArcKeys.OPEN_CONFIG.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new ArcConfigScreen(null));
        }

        while (ArcKeys.HOST_PANEL.consumeClick()) {
            if (mc.screen == null && com.arc4io.archud.client.screen.HostPanelScreen.available()) {
                mc.setScreen(new com.arc4io.archud.client.screen.HostPanelScreen(null));
            }
        }
        while (ArcKeys.WAYPOINTS.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new com.arc4io.archud.client.map.WaypointListScreen(null));
        }
        while (ArcKeys.WORLD_MAP.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new com.arc4io.archud.client.map.WorldMapScreen(null));
        }
        while (ArcKeys.ADD_WAYPOINT.consumeClick()) {
            com.arc4io.archud.client.map.Waypoint w = com.arc4io.archud.client.map.WaypointManager.createHere();
            if (w != null && mc.screen == null) mc.setScreen(new com.arc4io.archud.client.map.WaypointEditScreen(null, w, true));
        }
        while (ArcKeys.ZOOM_IN.consumeClick()) {
            cfg.minimapZoom = Math.min(6.0, cfg.minimapZoom * 1.25);
            ArcConfig.save();
        }
        while (ArcKeys.ZOOM_OUT.consumeClick()) {
            cfg.minimapZoom = Math.max(0.25, cfg.minimapZoom / 1.25);
            ArcConfig.save();
        }

        lanNotice(mc);
        com.arc4io.archud.client.map.WaypointManager.tick(mc);
        com.arc4io.archud.client.map.MapScanner.tick(mc);
        com.arc4io.archud.client.map.Navigator.tick(mc);
        com.arc4io.archud.client.map.TrailManager.tick(mc);
        BunnyHop.tick(mc);
        HudRenderer.tick(mc);
    }

    private static boolean wasPublished;

    /** После «Открыть для сети» (наш экран, ванильный или /publish) — сообщение с IP-адресом и портом, по клику копируется. */
    private static void lanNotice(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        boolean pub = server != null && server.isPublished();
        if (pub && !wasPublished) {
            int port = server.getPort();
            try {
                port = ((com.arc4io.archud.mixin.IntegratedServerAccessor) server).archud$getPublishedPort();
            } catch (Throwable ignored) {
            }
            java.util.List<String> ips = NetUtil.localAddresses();
            String main = ips.get(0) + ":" + port;
            Component addr = Component.literal(main).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, main))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("chat.copy.click"))));
            mc.gui.getChat().addMessage(Component.translatable("archud.lan.address", addr).withStyle(ChatFormatting.GREEN));
            if (ips.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < ips.size(); i++) sb.append(i > 1 ? ", " : "").append(ips.get(i)).append(':').append(port);
                mc.gui.getChat().addMessage(Component.translatable("archud.lan.other", sb.toString()).withStyle(ChatFormatting.GRAY));
            }
        }
        wasPublished = pub;
    }
}
