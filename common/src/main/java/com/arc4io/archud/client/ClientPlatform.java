package com.arc4io.archud.client;

import com.arc4io.archud.network.ArcPacket;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/**
 * Клиентская часть платформы (Forge / Fabric).
 */
public interface ClientPlatform {
    void sendToServer(ArcPacket packet, ResourceLocation arg);

    default void sendToServer(ArcPacket packet) {
        sendToServer(packet, null);
    }

    /** Экран списка модов (Forge Mods / Mod Menu) или null, если недоступен. */
    Screen createModListScreen(Screen parent);

    /** id HUD-элементов других модов (на Forge — зарегистрированные оверлеи). */
    default java.util.List<String> hudOverlayIds() {
        return java.util.List.of();
    }
}
