package com.arc4io.archud.client.jei;

import com.arc4io.archud.ArcHud;

/**
 * Вход в мост JEI без ссылок на классы API — чтобы Arc HUD работал и там, где API JEI нет.
 * Мост включается, только если API есть, а самого JEI нет (иначе рецепты показывает JEI).
 */
public final class JeiGate {
    private static Boolean api;

    private JeiGate() {}

    public static boolean apiPresent() {
        if (api == null) {
            boolean a;
            try {
                Class.forName("mezz.jei.api.IModPlugin", false, JeiGate.class.getClassLoader());
                a = true;
            } catch (Throwable t) {
                a = false;
            }
            api = a;
        }
        return api;
    }

    /** Настоящий JEI установлен (его внутренние классы есть). */
    private static Boolean real;

    public static boolean realJei() {
        if (real == null) {
            boolean r;
            try {
                Class.forName("mezz.jei.library.load.PluginLoader", false, JeiGate.class.getClassLoader());
                r = true;
            } catch (Throwable t) {
                r = false;
            }
            real = r;
        }
        return real;
    }

    /** Загрузить плагины модов для JEI в индекс рецептов. Возвращает число добавленных рецептов. */
    public static int load() {
        if (!apiPresent() || realJei() || !com.arc4io.archud.config.ArcConfig.get().jeiPlugins) return 0;
        // EMI/REI сами подключают плагины JEI (через свои слои) — не мешаем
        if (ArcHud.platform().isModLoaded("emi") || ArcHud.platform().isModLoaded("roughlyenoughitems")) return 0;
        try {
            Object r = Class.forName("com.arc4io.archud.client.jei.JeiBridge").getMethod("load").invoke(null);
            return r instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: JEI plugins bridge failed", t);
            return 0;
        }
    }

    /** Дополнительные области экрана мода, занятые его GUI (чтобы список предметов их не перекрывал). */
    public static java.util.List<int[]> extraAreas(net.minecraft.client.gui.screens.Screen s) {
        if (!loaded) return java.util.List.of();
        try {
            @SuppressWarnings("unchecked")
            java.util.List<int[]> r = (java.util.List<int[]>) Class.forName("com.arc4io.archud.client.jei.JeiBridge")
                    .getMethod("extraAreas", net.minecraft.client.gui.screens.Screen.class).invoke(null, s);
            return r;
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /** Клик по «кликабельной области» GUI мода (стрелка печи и т. п.) — открыть рецепты. */
    public static boolean clickArea(net.minecraft.client.gui.screens.Screen s, double mx, double my) {
        if (!loaded) return false;
        try {
            Object r = Class.forName("com.arc4io.archud.client.jei.JeiBridge")
                    .getMethod("clickArea", net.minecraft.client.gui.screens.Screen.class, double.class, double.class).invoke(null, s, mx, my);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean loaded;
}
