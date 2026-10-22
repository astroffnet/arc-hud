package com.arc4io.archud.client;

import com.arc4io.archud.network.ArcPacket;
import net.minecraft.client.gui.screens.Screen;

/**
 * Клиентская часть платформы (Forge / Fabric).
 */
public interface ClientPlatform {
    void sendToServer(ArcPacket packet);

    /** Экран списка модов (Forge Mods / Mod Menu) или null, если недоступен. */
    Screen createModListScreen(Screen parent);
}
