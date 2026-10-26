package com.arc4io.archud.client.video;

import com.arc4io.archud.client.extras.Crosshair;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Страница «Прицел»: форма, цвет, размеры, обводка, точка, прозрачность — с живым превью. */
final class CrosshairPage {
    private CrosshairPage() {}

    static VideoPage page() {
        ArcConfig c = ArcConfig.get();
        VideoPage p = new VideoPage(Component.translatable("archud.ch.page"));
        List<VideoEntry> shape = new ArrayList<>();
        shape.add(new Cycle("style", Crosshair.STYLES, () -> c.crosshairStyle, v -> c.crosshairStyle = v,
                v -> Component.translatable("archud.crosshair." + v)));
        shape.add(new Cycle("color", Crosshair.COLORS.length, () -> indexOf(c.crosshairColor), v -> c.crosshairColor = Crosshair.COLORS[v],
                v -> Component.translatable("archud.ch.color." + v)));
        shape.add(new Slider("size", 1, 20, 1, () -> c.crosshairSize, v -> c.crosshairSize = v));
        shape.add(new Slider("thickness", 1, 6, 1, () -> c.crosshairThickness, v -> c.crosshairThickness = v));
        shape.add(new Slider("gap", 0, 12, 1, () -> c.crosshairGap, v -> c.crosshairGap = v));
        shape.add(new Slider("opacity", 20, 100, 5, () -> c.crosshairOpacity, v -> c.crosshairOpacity = v));
        shape.add(new Bool("outline", () -> c.crosshairOutline, v -> c.crosshairOutline = v));
        shape.add(new Bool("dot", () -> c.crosshairDot, v -> c.crosshairDot = v));
        shape.add(new Bool("hit", () -> c.crosshairHitColor, v -> c.crosshairHitColor = v));
        p.group(Component.translatable("archud.ch.group"), shape);
        return p;
    }

    private static int indexOf(int color) {
        for (int i = 0; i < Crosshair.COLORS.length; i++) if (Crosshair.COLORS[i] == color) return i;
        return 0;
    }

    /** Превью прицела на двух фонах (светлом и тёмном) в настоящем размере. */
    static boolean drawPreview(GuiGraphics g, int x, int y, int w, int h) {
        ArcConfig c = ArcConfig.get();
        int half = w / 2 - 2;
        g.fill(x, y, x + half, y + h, 0xFF8FB6D8);
        g.fill(x + half + 4, y, x + w, y + h, 0xFF1E2A1C);
        Draw.outline(g, x, y, w, h, 0x40FFFFFF);
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        if (c.crosshairStyle == 0) {
            Draw.smallCentered(g, Minecraft.getInstance().font, Component.translatable("archud.crosshair.0").getString(), x + w / 2F, y + h / 2F - 3, 0xFFFFFFFF, 0.7F);
            return true;
        }
        Crosshair.draw(g, x + half / 2F, y + h / 2F, scale, c, false);
        Crosshair.draw(g, x + half + 4 + (w - half - 4) / 2F, y + h / 2F, scale, c, c.crosshairHitColor);
        return true;
    }

    private static Component name(String id) {
        return Component.translatable("archud.ch." + id);
    }

    private static Component desc(String id) {
        return Component.translatable("archud.ch." + id + ".desc");
    }

    private record Cycle(String id, int count, IntSupplier get, Consumer<Integer> set, java.util.function.IntFunction<Component> label) implements VideoEntry {
        public Component name() {
            return CrosshairPage.name(id);
        }

        public Component description() {
            return desc(id);
        }

        public Component valueText() {
            return label.apply(get.getAsInt());
        }

        public Kind kind() {
            return Kind.CYCLE;
        }

        public void cycle(int dir) {
            set.accept(Math.floorMod(get.getAsInt() + dir, count));
            ArcConfig.save();
        }

        public boolean preview(GuiGraphics g, int x, int y, int w, int h) {
            return drawPreview(g, x, y, w, h);
        }
    }

    private record Slider(String id, int min, int max, int step, IntSupplier get, Consumer<Integer> set) implements VideoEntry {
        public Component name() {
            return CrosshairPage.name(id);
        }

        public Component description() {
            return desc(id);
        }

        public Component valueText() {
            return Component.literal(get.getAsInt() + (id.equals("opacity") ? "%" : " px"));
        }

        public Kind kind() {
            return Kind.SLIDER;
        }

        public double fraction() {
            return (get.getAsInt() - min) / (double) (max - min);
        }

        public void setFraction(double f) {
            int v = min + (int) Math.round(f * (max - min) / step) * step;
            set.accept(Math.max(min, Math.min(max, v)));
            ArcConfig.save();
        }

        public int steps() {
            return (max - min) / step;
        }

        public boolean preview(GuiGraphics g, int x, int y, int w, int h) {
            return drawPreview(g, x, y, w, h);
        }
    }

    private record Bool(String id, BooleanSupplier get, Consumer<Boolean> set) implements VideoEntry {
        public Component name() {
            return CrosshairPage.name(id);
        }

        public Component description() {
            return desc(id);
        }

        public Component valueText() {
            return Component.translatable(get.getAsBoolean() ? "options.on" : "options.off");
        }

        public Kind kind() {
            return Kind.TOGGLE;
        }

        public boolean bool() {
            return get.getAsBoolean();
        }

        public void setBool(boolean v) {
            set.accept(v);
            ArcConfig.save();
        }

        public boolean preview(GuiGraphics g, int x, int y, int w, int h) {
            return drawPreview(g, x, y, w, h);
        }
    }
}
