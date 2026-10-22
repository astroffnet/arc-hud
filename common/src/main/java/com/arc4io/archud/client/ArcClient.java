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
    }

    public static ClientPlatform platform() {
        return platform;
    }

    /** Вызывается в конце каждого клиентского тика. */
    public static void tick(Minecraft mc) {
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

        BunnyHop.tick(mc);
        HudRenderer.tick(mc);
    }
}
