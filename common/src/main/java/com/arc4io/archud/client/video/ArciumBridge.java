package com.arc4io.archud.client.video;

import net.minecraft.client.gui.screens.Screen;

/** Поддержка Arcium (форк Embeddium): его экран настроек создаётся рефлексией. */
public final class ArciumBridge {
    private static final String CLS = "io.arc4io.arcium.gui.ArciumOptionsScreen";
    private static Boolean loaded;

    private ArciumBridge() {}

    public static boolean loaded() {
        if (loaded == null) {
            try {
                Class.forName(CLS, false, ArciumBridge.class.getClassLoader());
                loaded = true;
            } catch (Throwable t) {
                loaded = false;
            }
        }
        return loaded;
    }

    public static boolean is(Screen s) {
        return s != null && CLS.equals(s.getClass().getName());
    }

    public static Screen create(Screen parent) {
        if (!loaded()) return null;
        try {
            Object s = Class.forName(CLS).getMethod("create", Screen.class).invoke(null, parent);
            return s instanceof Screen sc ? sc : null;
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("archud").warn("Arc HUD: cannot open Arcium options screen", t);
            return null;
        }
    }

    /** Если вместо экрана Arcium пришёл ванильный экран, а Arcium установлен — подменить «оригинал». */
    public static Screen swap(Screen parent, Screen original) {
        if (original == null || is(original) || !loaded()) return original;
        if (!(original instanceof net.minecraft.client.gui.screens.VideoSettingsScreen)) return original;
        Screen a = create(parent);
        return a != null ? a : original;
    }

    public static String label(String name, Screen original) {
        return is(original) ? "Arcium" : name;
    }
}
