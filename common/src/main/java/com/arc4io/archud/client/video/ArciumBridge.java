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

    // ------------------------------------------------------------------ CIT Arcium

    private static long citCheckedAt;
    private static boolean citCached;

    /**
     * Включён ли CIT в самом Arcium. Тогда свой CIT Arc HUD не работает, чтобы два мода не подменяли модели предметов
     * одновременно. Значение берётся из настроек Arcium в памяти (рефлексией), иначе — из его файла настроек;
     * проверяется не чаще раза в секунду, так что переключатель в Arcium действует сразу.
     */
    public static boolean citActive() {
        if (!com.arc4io.archud.ArcHud.platform().isModLoaded("arcium") && !loaded()) return false;
        long now = System.currentTimeMillis();
        if (now - citCheckedAt < 1000) return citCached;
        citCheckedAt = now;
        Boolean v = citFromMemory();
        if (v == null) v = citFromFile();
        citCached = v == null || v; // настройку не нашли — считаем, что CIT Arcium включён (так по умолчанию)
        return citCached;
    }

    private static Boolean citFromMemory() {
        try {
            Class<?> settingsCls = Class.forName("io.arc4io.arcium.ArciumSettings", false, ArciumBridge.class.getClassLoader());
            Object opts = Class.forName("me.jellysquid.mods.sodium.client.SodiumClientMod").getMethod("options").invoke(null);
            if (opts == null) return null;
            for (Class<?> c = opts.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!settingsCls.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object st = f.get(opts);
                    if (st == null) return null;
                    for (java.lang.reflect.Field b : settingsCls.getDeclaredFields()) {
                        if (b.getType() != boolean.class || !isCitName(b.getName())) continue;
                        b.setAccessible(true);
                        return b.getBoolean(st);
                    }
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Boolean citFromFile() {
        java.nio.file.Path dir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        for (String name : new String[]{"arcium-options.json", "embeddium-options.json"}) {
            java.nio.file.Path f = dir.resolve(name);
            if (!java.nio.file.Files.isRegularFile(f)) continue;
            try {
                var root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
                if (!root.has("arcium") || !root.get("arcium").isJsonObject()) continue;
                for (var e : root.getAsJsonObject("arcium").entrySet()) {
                    if (isCitName(e.getKey()) && e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) {
                        return e.getValue().getAsBoolean();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isCitName(String n) {
        String l = n.toLowerCase(java.util.Locale.ROOT).replace("_", "");
        return l.equals("cit") || l.startsWith("cit") || l.contains("customitemtexture");
    }
}
