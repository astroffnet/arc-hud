package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;

/**
 * Подключение интеграций с модами (только если мод установлен). Все — через рефлексию,
 * Arc HUD не требует этих модов ни для сборки, ни для запуска.
 */
public final class Integrations {
    private Integrations() {}

    static ClassLoader loader() {
        return ArcHud.class.getClassLoader();
    }

    static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader());
    }

    public static void init() {
        try {
            if (ArcHud.platform().isModLoaded("waystones")) WaystonesCompat.init();
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: Waystones — интеграция не подключена: {}", t.toString());
        }
        try {
            if (ArcHud.platform().isModLoaded("tempad")) TempadCompat.init();
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: Tempad — интеграция не подключена: {}", t.toString());
        }
        try {
            if (ArcHud.platform().isModLoaded("ad_astra")) AdAstraCompat.init();
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: Ad Astra — интеграция не подключена: {}", t.toString());
        }
        try {
            if (ArcHud.platform().isModLoaded("regen")) RegenCompat.init();
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: Regeneration — интеграция не подключена: {}", t.toString());
        }
    }
}
