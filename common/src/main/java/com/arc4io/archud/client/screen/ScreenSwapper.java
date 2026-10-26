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

    private static java.lang.reflect.Field pauseFlag;
    private static boolean pauseFlagSearched;

    /** PauseScreen(false) открывается по F3+Esc — его не подменяем. */
    private static boolean showsPauseMenu(Screen s) {
        try {
            if (!pauseFlagSearched) {
                pauseFlagSearched = true;
                for (java.lang.reflect.Field f : net.minecraft.client.gui.screens.PauseScreen.class.getDeclaredFields()) {
                    if (f.getType() == boolean.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        pauseFlag = f;
                        break;
                    }
                }
            }
            return pauseFlag == null || pauseFlag.getBoolean(s);
        } catch (Throwable t) {
            return true;
        }
    }

    public static Screen swap(Screen next, Screen current) {
        Screen result = swapInner(next, current);
        ScreenFit.onScreenChange(result);
        return result;
    }

    private static Screen swapInner(Screen next, Screen current) {
        if (next == null) return null;
        ArcConfig cfg = ArcConfig.get();
        if (next instanceof TitleScreen && titleScreenActive()) {
            // при «уступать другим модам» трогаем только ванильный экран, иначе — и подклассы (меню Aether и т.п.)
            boolean vanilla = next.getClass() == TitleScreen.class;
            if (vanilla || !cfg.yieldTitleScreenToMods) return new ArcTitleScreen();
        }
        // Меню модов (Aether через Cumulus и т.п.), которые не наследуют TitleScreen
        if (cfg.customTitleScreen && !cfg.yieldTitleScreenToMods && !(next instanceof ArcTitleScreen)
                && next.getClass().getSimpleName().endsWith("TitleScreen")) {
            return new ArcTitleScreen();
        }
        Screen video = com.arc4io.archud.client.video.ArcVideoScreen.wrap(next, current);
        if (video != null) return video;
        if (cfg.customControlsScreen && next instanceof KeyBindsScreen) {
            return new ArcControlsScreen(current, Minecraft.getInstance().options);
        }
        Minecraft mc = Minecraft.getInstance();
        Class<?> c = next.getClass();
        if (next instanceof net.minecraft.client.gui.screens.DeathScreen) {
            com.arc4io.archud.client.map.WaypointManager.onDeath();
        }
        if (cfg.customPauseScreen && c == net.minecraft.client.gui.screens.PauseScreen.class && showsPauseMenu(next)) {
            return new ArcPauseScreen();
        }
        if (cfg.customOptionsScreens) {
            if (c == net.minecraft.client.gui.screens.OptionsScreen.class) return new ArcOptionsScreen(current, mc.options);
            if (c == net.minecraft.client.gui.screens.SoundOptionsScreen.class) return ArcOptionListScreen.sound(current, mc.options);
            if (c == net.minecraft.client.gui.screens.ChatOptionsScreen.class) return ArcOptionListScreen.chat(current, mc.options);
            if (c == net.minecraft.client.gui.screens.AccessibilityOptionsScreen.class) return ArcOptionListScreen.accessibility(current, mc.options);
            if (c == net.minecraft.client.gui.screens.SkinCustomizationScreen.class) return new ArcAppearanceScreen(current, mc.options);
            if (c == net.minecraft.client.gui.screens.LanguageSelectScreen.class) return new ArcLanguageScreen(current, mc.options);
        }
        if (cfg.customAdvancements && c == net.minecraft.client.gui.screens.advancements.AdvancementsScreen.class && mc.player != null) {
            return new ArcAdvancementsScreen(mc.player.connection.getAdvancements());
        }
        if (cfg.customStats && c == net.minecraft.client.gui.screens.achievement.StatsScreen.class && mc.player != null) {
            return new ArcStatsScreen(current, mc.player.getStats());
        }
        if (cfg.customLanScreen && c == net.minecraft.client.gui.screens.ShareToLanScreen.class) {
            return new ArcLanScreen(current);
        }
        if (cfg.customWorldSelect && c == net.minecraft.client.gui.screens.worldselection.SelectWorldScreen.class) {
            return new ArcWorldSelectScreen(((com.arc4io.archud.mixin.SelectWorldScreenAccessor) next).archud$lastScreen());
        }
        if (cfg.customGameRules && c == net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen.class) {
            var acc = (com.arc4io.archud.mixin.EditGameRulesScreenAccessor) next;
            return new ArcGameRulesScreen(acc.archud$gameRules(), acc.archud$exitCallback());
        }
        if (cfg.customMultiplayer && c == net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen.class) {
            Screen back = ((com.arc4io.archud.mixin.JoinMultiplayerScreenAccessor) next).archud$lastScreen();
            return new ArcMultiplayerScreen(back != null ? back : new net.minecraft.client.gui.screens.TitleScreen());
        }
        if (cfg.customInventory && c == net.minecraft.client.gui.screens.inventory.InventoryScreen.class && mc.player != null
                && !ModButtonsHarvest.active && mc.gameMode != null && !mc.gameMode.hasInfiniteItems()) {
            var platform = com.arc4io.archud.client.ArcClient.platform();
            if (platform.classicInventory()) return new ArcInventoryScreen(mc.player);
            // с Curios — меню с аксессуарами откроет сервер (экран придёт следом)
            if (platform.openServerInventory(mc)) return current instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> ? null : current;
            return ArcSurvivalScreen.vanilla(mc.player);
        }
        if (cfg.customCreative && c == net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class && mc.player != null) {
            return new ArcCreativeScreen(mc.player);
        }
        if (cfg.customEnchanting && c == net.minecraft.client.gui.screens.inventory.EnchantmentScreen.class && mc.player != null) {
            var e = (net.minecraft.client.gui.screens.inventory.EnchantmentScreen) next;
            return new ArcEnchantmentScreen(e.getMenu(), mc.player.getInventory(), e.getTitle());
        }
        return com.arc4io.archud.client.ArcClient.platform().swapPlatformScreen(next, current);
    }
}
