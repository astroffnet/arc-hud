package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Совместимость с модами рендера: OptiFine, Embeddium / Sodium, Oculus / Iris.
 *  - «Настройки графики» открывают экран Embeddium/Sodium, если он установлен (иначе ванильный / OptiFine);
 *  - кнопка «Шейдеры» открывает меню шейдеров Oculus/Iris или OptiFine;
 *  - название рендера и активного шейдерпака для инфо-панели.
 * Всё через рефлексию — ни один из модов не обязателен.
 */
public final class RenderCompat {
    private static final String[] SODIUM_SCREENS = {
            "org.embeddedt.embeddium.impl.gui.EmbeddiumVideoOptionsScreen",
            "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI",
            "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI"};
    private static final String[] IRIS_CLASSES = {"net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"};

    private static Boolean optifine;
    private static String renderer;
    private static long shaderCheck;
    private static String shaderName = "";

    private RenderCompat() {}

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, RenderCompat.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean optifine() {
        if (optifine == null) optifine = find("net.optifine.Config") != null;
        return optifine;
    }

    public static boolean sodiumLike() {
        return ArcHud.platform().isModLoaded("embeddium") || ArcHud.platform().isModLoaded("rubidium") || ArcHud.platform().isModLoaded("sodium");
    }

    public static boolean irisLike() {
        return ArcHud.platform().isModLoaded("oculus") || ArcHud.platform().isModLoaded("iris");
    }

    public static String renderer() {
        if (renderer == null) {
            if (optifine()) renderer = "OptiFine";
            else if (ArcHud.platform().isModLoaded("embeddium")) renderer = "Embeddium";
            else if (ArcHud.platform().isModLoaded("rubidium")) renderer = "Rubidium";
            else if (ArcHud.platform().isModLoaded("sodium")) renderer = "Sodium";
            else renderer = "Vanilla";
            if (irisLike()) renderer += ArcHud.platform().isModLoaded("oculus") ? " + Oculus" : " + Iris";
        }
        return renderer;
    }

    /** Экран настроек графики: Embeddium/Sodium, если есть; иначе ванильный (у OptiFine — его версия). */
    public static Screen videoSettings(Screen parent, Options options) {
        if (sodiumLike()) {
            for (String name : SODIUM_SCREENS) {
                Class<?> c = find(name);
                if (c == null) continue;
                try {
                    return (Screen) c.getConstructor(Screen.class).newInstance(parent);
                } catch (Throwable ignored) {
                }
                try {
                    Method m = c.getMethod("createScreen", Screen.class);
                    return (Screen) m.invoke(null, parent);
                } catch (Throwable ignored) {
                }
            }
        }
        return new VideoSettingsScreen(parent, options);
    }

    public static boolean shadersAvailable() {
        return irisLike() || optifine();
    }

    /** Меню шейдеров Oculus/Iris или OptiFine, null если недоступно. */
    public static Screen shaderScreen(Screen parent, Options options) {
        if (irisLike()) {
            try {
                Class<?> api = find("net.irisshaders.iris.api.v0.IrisApi");
                Object inst = api.getMethod("getInstance").invoke(null);
                Object screen = api.getMethod("openMainIrisScreenObj", Object.class).invoke(inst, parent);
                if (screen instanceof Screen s) return s;
            } catch (Throwable ignored) {
            }
        }
        if (optifine()) {
            try {
                Class<?> c = find("net.optifine.shaders.gui.GuiShaders");
                return (Screen) c.getConstructor(Screen.class, Options.class).newInstance(parent, options);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Имя активного шейдерпака или пустая строка (обновляется раз в 2 секунды). */
    public static String shaderPack() {
        long now = System.currentTimeMillis();
        if (now - shaderCheck < 2000) return shaderName;
        shaderCheck = now;
        shaderName = "";
        try {
            if (irisLike()) {
                Class<?> api = find("net.irisshaders.iris.api.v0.IrisApi");
                Object inst = api.getMethod("getInstance").invoke(null);
                if ((boolean) api.getMethod("isShaderPackInUse").invoke(inst)) {
                    for (String n : IRIS_CLASSES) {
                        Class<?> iris = find(n);
                        if (iris == null) continue;
                        try {
                            Object name = iris.getMethod("getCurrentPackName").invoke(null);
                            if (name != null) {
                                shaderName = clean(String.valueOf(name));
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (shaderName.isEmpty()) shaderName = "ON";
                }
            } else if (optifine()) {
                Class<?> config = find("net.optifine.Config");
                if ((boolean) config.getMethod("isShaders").invoke(null)) {
                    Field f = find("net.optifine.shaders.Shaders").getField("currentShaderName");
                    shaderName = clean(String.valueOf(f.get(null)));
                }
            }
        } catch (Throwable ignored) {
        }
        return shaderName;
    }

    private static String clean(String s) {
        s = s.replace(".zip", "").replace('_', ' ');
        return s.length() > 28 ? s.substring(0, 27) + "…" : s;
    }
}
