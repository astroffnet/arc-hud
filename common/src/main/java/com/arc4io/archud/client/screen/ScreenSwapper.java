package com.arc4io.archud.client.screen;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;

/**
 * Подмена ванильных экранов на экраны Arc HUD (главное меню, назначение клавиш).
 */
public final class ScreenSwapper {
    private ScreenSwapper() {}

    public static Screen swap(Screen next, Screen current) {
        if (next == null) return null;
        ArcConfig cfg = ArcConfig.get();
        if (cfg.customTitleScreen && next instanceof TitleScreen) {
            return new ArcTitleScreen();
        }
        if (cfg.customControlsScreen && next instanceof KeyBindsScreen) {
            return new ArcControlsScreen(current, Minecraft.getInstance().options);
        }
        return next;
    }
}
