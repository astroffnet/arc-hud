package com.arc4io.archud.client.video;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sodium / Embeddium / Rubidium: настройки берутся через их API (страницы → группы → настройки) рефлексией,
 * без зависимости при сборке. Применение — как в самом Sodium (флаги перезагрузки, сохранение хранилищ).
 * Страницы аддонов (Sodium Extra, Iris и т.п.) попадают сюда, если они добавлены в экран Sodium.
 */
public final class SodiumBridge {
    private static final String[] SCREENS = {
            "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI",
            "org.embeddedt.embeddium.gui.EmbeddiumVideoOptionsScreen",
            "org.embeddedt.embeddium.impl.gui.EmbeddiumVideoOptionsScreen",
            "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI"
    };
    private static Boolean present;

    private SodiumBridge() {}

    public static boolean present() {
        if (present == null) {
            present = false;
            for (String n : SCREENS) {
                try {
                    Class.forName(n, false, SodiumBridge.class.getClassLoader());
                    present = true;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        return present;
    }

    public static String brand() {
        var p = ArcHud.platform();
        if (p.isModLoaded("embeddium")) return "Embeddium";
        if (p.isModLoaded("rubidium")) return "Rubidium";
        return "Sodium";
    }

    public static boolean isSodiumScreen(Screen s) {
        if (s == null) return false;
        String n = s.getClass().getName();
        for (String c : SCREENS) if (c.equals(n)) return true;
        return false;
    }

    /** Экран Sodium для подмены (создаётся, чтобы аддоны добавили свои страницы). */
    public static Screen createScreen(Screen parent) {
        for (String n : SCREENS) {
            Class<?> c;
            try {
                c = Class.forName(n);
            } catch (Throwable t) {
                continue;
            }
            // Sodium: закрытый конструктор + статический createScreen(Screen); Embeddium: открытый конструктор
            try {
                Object s = c.getMethod("createScreen", Screen.class).invoke(null, parent);
                if (s instanceof Screen sc && field(sc, "pages") != null) return sc;
            } catch (Throwable ignored) {
            }
            try {
                var ctor = c.getDeclaredConstructor(Screen.class);
                ctor.setAccessible(true);
                return (Screen) ctor.newInstance(parent);
            } catch (Throwable t) {
                ArcHud.LOGGER.debug("Arc HUD: cannot create {}: {}", n, t.toString());
            }
        }
        return null;
    }

    public static Screen parentOf(Screen sodium) {
        Object v = field(sodium, "prevScreen");
        return v instanceof Screen s ? s : null;
    }

    /** Страницы из экрана Sodium; null — не удалось. */
    public static List<VideoPage> pages(Screen sodium, List<SodiumEntry> all) {
        try {
            Object raw = field(sodium, "pages");
            if (!(raw instanceof List<?> list) || list.isEmpty()) raw = staticPages();
            if (!(raw instanceof List<?> list2)) return null;
            List<VideoPage> out = new ArrayList<>();
            for (Object page : list2) {
                VideoPage vp = new VideoPage((Component) call(page, "getName"));
                for (Object group : (Iterable<?>) call(page, "getGroups")) {
                    List<VideoEntry> entries = new ArrayList<>();
                    for (Object opt : (Iterable<?>) call(group, "getOptions")) {
                        SodiumEntry e = new SodiumEntry(opt);
                        entries.add(e);
                        all.add(e);
                    }
                    vp.group(null, entries);
                }
                out.add(vp);
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: Sodium options unavailable, using its own screen", t);
            return null;
        }
    }

    private static List<Object> staticPages() throws Exception {
        Class<?> c = Class.forName("me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages");
        List<Object> l = new ArrayList<>();
        for (String m : new String[]{"general", "quality", "performance", "advanced"}) l.add(c.getMethod(m).invoke(null));
        return l;
    }

    /** Применить изменения (как SodiumOptionsGUI.applyChanges). */
    public static void apply(List<SodiumEntry> entries) {
        Minecraft mc = Minecraft.getInstance();
        Set<String> flags = new HashSet<>();
        Set<Object> storages = new HashSet<>();
        for (SodiumEntry e : entries) {
            if (!e.changed()) continue;
            try {
                Object opt = e.opt;
                for (Object f : (Collection<?>) call(opt, "getFlags")) flags.add(((Enum<?>) f).name());
                call(opt, "applyChanges");
                Object st = call(opt, "getStorage");
                if (st != null) storages.add(st);
            } catch (Throwable t) {
                ArcHud.LOGGER.warn("Arc HUD: apply Sodium option failed", t);
            }
        }
        if (mc.levelRenderer != null) {
            if (flags.contains("REQUIRES_RENDERER_RELOAD")) mc.levelRenderer.allChanged();
            else if (flags.contains("REQUIRES_RENDERER_UPDATE")) mc.levelRenderer.needsUpdate();
        }
        if (flags.contains("REQUIRES_ASSET_RELOAD")) {
            mc.updateMaxMipLevel(mc.options.mipmapLevels().get());
            mc.delayTextureReload();
        }
        if (flags.contains("REQUIRES_GAME_RESTART")) {
            SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("archud.video.title"), Component.translatable("archud.video.restart"));
        }
        for (Object st : storages) {
            try {
                call(st, "save");
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- рефлексия

    static Object call(Object o, String name, Object... args) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(o, args);
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // недоступен напрямую (скрытый класс лямбды) — попробуем через интерфейс ниже
                }
            }
        }
        for (Class<?> i : allInterfaces(o.getClass())) {
            for (Method m : i.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(o, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static List<Class<?>> allInterfaces(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        for (; c != null; c = c.getSuperclass()) for (Class<?> i : c.getInterfaces()) out.add(i);
        return out;
    }

    static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
