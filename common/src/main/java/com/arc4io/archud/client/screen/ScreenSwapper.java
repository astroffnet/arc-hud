package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;

import java.util.List;

/**
 * Подмена ванильных экранов на экраны Arc HUD (главное меню, назначение клавиш).
 */
public final class ScreenSwapper {
    /** Моды, которые сами меняют главное меню — с ними Arc HUD своё меню не ставит. */
    private static final List<String> TITLE_SCREEN_MODS = List.of(
            "aether", "fancymenu", "custommainmenu", "simplemenu");
    private static Boolean otherMenuMod;

    private ScreenSwapper() {}

    private static boolean otherTitleMod() {
        if (otherMenuMod == null) {
            otherMenuMod = false;
            for (String id : TITLE_SCREEN_MODS) {
                if (ArcHud.platform().isModLoaded(id)) {
                    otherMenuMod = true;
                    ArcHud.LOGGER.info("Arc HUD: найден мод '{}' со своим главным меню — меню Arc HUD не подменяется", id);
                    break;
                }
            }
        }
        return otherMenuMod;
    }

    public static boolean titleScreenActive() {
        ArcConfig cfg = ArcConfig.get();
        return cfg.customTitleScreen && !(cfg.yieldTitleScreenToMods && otherTitleMod());
    }

    public static Screen swap(Screen next, Screen current) {
        if (next == null) return null;
        ArcConfig cfg = ArcConfig.get();
        // только «чистый» ванильный экран — подклассы других модов не трогаем
        if (next.getClass() == TitleScreen.class && titleScreenActive()) {
            return new ArcTitleScreen();
        }
        if (cfg.customControlsScreen && next instanceof KeyBindsScreen) {
            return new ArcControlsScreen(current, Minecraft.getInstance().options);
        }
        return next;
    }
}
