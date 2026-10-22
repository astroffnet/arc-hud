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
    }

    public static ClientPlatform platform() {
        return platform;
    }

    /** Вызывается в конце каждого клиентского тика. */
    public static void tick(Minecraft mc) {
        com.arc4io.archud.client.map.MapScanner.worldCheck(mc);
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

        while (ArcKeys.WORLD_MAP.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new com.arc4io.archud.client.map.WorldMapScreen(null));
        }
        while (ArcKeys.ADD_WAYPOINT.consumeClick()) {
            com.arc4io.archud.client.map.Waypoint w = com.arc4io.archud.client.map.WaypointManager.addHere();
            if (w != null && mc.screen == null) mc.setScreen(new com.arc4io.archud.client.map.WaypointEditScreen(null, w, false));
        }
        while (ArcKeys.ZOOM_IN.consumeClick()) {
            cfg.minimapZoom = Math.min(6.0, cfg.minimapZoom * 1.25);
            ArcConfig.save();
        }
        while (ArcKeys.ZOOM_OUT.consumeClick()) {
            cfg.minimapZoom = Math.max(0.25, cfg.minimapZoom / 1.25);
            ArcConfig.save();
        }

        com.arc4io.archud.client.map.WaypointManager.tick(mc);
        com.arc4io.archud.client.map.MapScanner.tick(mc);
        com.arc4io.archud.client.map.Navigator.tick(mc);
        com.arc4io.archud.client.map.TrailManager.tick(mc);
        BunnyHop.tick(mc);
        HudRenderer.tick(mc);
    }
}
