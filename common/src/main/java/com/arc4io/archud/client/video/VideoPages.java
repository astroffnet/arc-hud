package com.arc4io.archud.client.video;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Страницы настроек для ванилы и OptiFine. */
public final class VideoPages {
    private static Boolean optifine;

    private VideoPages() {}

    public static boolean optifine() {
        if (optifine == null) {
            try {
                Class.forName("net.optifine.Config", false, VideoPages.class.getClassLoader());
                optifine = true;
            } catch (Throwable t) {
                optifine = false;
            }
        }
        return optifine;
    }

    private static List<VideoEntry> list(OptionInstance<?>... opts) {
        List<VideoEntry> out = new ArrayList<>();
        for (OptionInstance<?> o : opts) if (o != null) out.add(new OptionEntry(o));
        return out;
    }

    public static List<VideoPage> vanilla() {
        Options o = Minecraft.getInstance().options;
        List<VideoPage> pages = new ArrayList<>();
        pages.add(new VideoPage(Component.translatable("archud.video.page.graphics"))
                .group(Component.translatable("archud.video.group.quality"), list(o.graphicsMode(), o.ambientOcclusion(), o.cloudStatus(),
                        o.particles(), o.entityShadows(), o.mipmapLevels(), o.biomeBlendRadius()))
                .group(Component.translatable("archud.video.group.distance"), list(o.renderDistance(), o.simulationDistance(), o.entityDistanceScaling())));
        pages.add(new VideoPage(Component.translatable("archud.video.page.performance"))
                .group(null, list(o.framerateLimit(), o.enableVsync(), o.prioritizeChunkUpdates(), o.fullscreen())));
        pages.add(new VideoPage(Component.translatable("archud.video.page.view"))
                .group(null, list(o.fov(), o.gamma(), o.guiScale(), o.bobView(), o.attackIndicator(), o.showAutosaveIndicator()))
                .group(Component.translatable("archud.video.group.effects"), list(o.screenEffectScale(), o.fovEffectScale(), o.darknessEffectScale(),
                        o.glintSpeed(), o.glintStrength())));
        return pages;
    }

    /** OptiFine: страницы его экранов (статические массивы OptionInstance) + кнопка шейдеров. */
    public static List<VideoPage> optifinePages(Screen parent) {
        List<VideoPage> pages = new ArrayList<>();
        try {
            List<VideoEntry> main = fromClass("net.minecraft.client.gui.screens.VideoSettingsScreen");
            if (!main.isEmpty()) pages.add(new VideoPage(Component.translatable("archud.video.page.graphics")).group(null, main));
            else pages.addAll(vanilla());
            String[][] sub = {
                    {"net.optifine.gui.GuiDetailSettingsOF", "of.options.detailsTitle", "Details"},
                    {"net.optifine.gui.GuiQualitySettingsOF", "of.options.qualityTitle", "Quality"},
                    {"net.optifine.gui.GuiAnimationSettingsOF", "of.options.animationsTitle", "Animations"},
                    {"net.optifine.gui.GuiPerformanceSettingsOF", "of.options.performanceTitle", "Performance"},
                    {"net.optifine.gui.GuiOtherSettingsOF", "of.options.otherTitle", "Other"},
            };
            for (String[] s : sub) {
                List<VideoEntry> e = fromClass(s[0]);
                if (!e.isEmpty()) pages.add(new VideoPage(Component.translatableWithFallback(s[1], s[2])).group(null, e));
            }
            // шейдеры — отдельный экран OptiFine
            List<VideoEntry> sh = new ArrayList<>();
            sh.add(new ButtonEntry(Component.translatableWithFallback("of.options.shadersTitle", "Shaders"), () -> {
                try {
                    Class<?> c = Class.forName("net.optifine.shaders.gui.GuiShaders");
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen((Screen) c.getConstructor(Screen.class, Options.class).newInstance(mc.screen, mc.options));
                } catch (Throwable t) {
                    ArcHud.LOGGER.warn("Arc HUD: OptiFine shaders screen", t);
                }
            }));
            pages.add(new VideoPage(Component.translatableWithFallback("of.options.shadersTitle", "Shaders")).group(null, sh));
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: OptiFine options unavailable", t);
        }
        return pages;
    }

    private static List<VideoEntry> fromClass(String name) {
        List<VideoEntry> out = new ArrayList<>();
        try {
            Class<?> c = Class.forName(name);
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !f.getType().isArray()
                        || !OptionInstance.class.isAssignableFrom(f.getType().getComponentType())) continue;
                f.setAccessible(true);
                Object[] arr = (Object[]) f.get(null);
                if (arr == null) continue;
                for (Object o : arr) if (o instanceof OptionInstance<?> oi) out.add(new OptionEntry(oi));
                if (!out.isEmpty()) break;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Кнопка-переход (например, шейдеры OptiFine). */
    record ButtonEntry(Component name, Runnable action) implements VideoEntry {
        @Override
        public Component description() {
            return Component.empty();
        }

        @Override
        public Component valueText() {
            return Component.literal("→");
        }

        @Override
        public Kind kind() {
            return Kind.BUTTON;
        }

        @Override
        public void press() {
            action.run();
        }
    }
}
