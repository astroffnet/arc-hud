package com.arc4io.archud.client.screen;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.gui.screens.Screen;

/** Какие окна рисовать пакетно: окна Arc HUD, кроме карты (у неё своя прямая отрисовка тайлов). */
public final class GuiBatch {
    private GuiBatch() {}

    public static boolean allowed(Screen s) {
        if (!ArcConfig.get().guiBatching) return false;
        String n = s.getClass().getName();
        return n.startsWith("com.arc4io.archud.") && !n.startsWith("com.arc4io.archud.client.map.") && !n.contains("Crash");
    }
}
