package com.arc4io.archud.client.video;

import com.arc4io.archud.client.perf.Perf;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Страницы Arc HUD в настройках графики (добавляются к ванильным / Sodium / OptiFine):
 * «Оптимизация» — 100 переключателей с пресетами; «Эффекты» — встроенный шейдер, масштаб рендера, растения.
 */
public final class ArcPages {
    private ArcPages() {}

    public static List<VideoPage> append(List<VideoPage> pages) {
        List<VideoPage> out = new ArrayList<>(pages);
        out.add(optimization());
        out.add(EffectsPage.page());
        out.add(GraphicsApiPage.page());
        return out;
    }

    private static VideoPage optimization() {
        VideoPage p = new VideoPage(Component.translatable("archud.perf.page"));
        List<VideoEntry> presets = new ArrayList<>();
        presets.add(new Info());
        presets.add(new Preset("max", 2));
        presets.add(new Preset("balanced", 1));
        presets.add(new Preset("off", 0));
        p.group(Component.translatable("archud.perf.presets"), presets);
        for (String g : Perf.GROUPS) {
            List<VideoEntry> list = new ArrayList<>();
            for (Perf.Opt o : Perf.ALL) if (o.group().equals(g)) list.add(new PerfEntry(o));
            p.group(Component.translatable("archud.perf.group." + g), list);
        }
        return p;
    }

    /** Кнопка пресета. */
    private record Preset(String id, int which) implements VideoEntry {
        public Component name() {
            return Component.translatable("archud.perf.preset." + id);
        }

        public Component description() {
            return Component.translatable("archud.perf.preset." + id + ".desc");
        }

        public Component valueText() {
            return Component.empty();
        }

        public Kind kind() {
            return Kind.BUTTON;
        }

        public void press() {
            Perf.preset(which);
        }

        public Component buttonText() {
            return Component.translatable("archud.perf.apply");
        }
    }

    /** Строка «Включено N из 100». */
    private static final class Info implements VideoEntry {
        @Override
        public Component name() {
            return Component.translatable("archud.perf.enabled");
        }

        @Override
        public Component description() {
            return Component.translatable("archud.perf.enabled.desc");
        }

        @Override
        public Component valueText() {
            return Component.literal(Perf.enabledCount() + " / " + Perf.ALL.size());
        }

        @Override
        public Kind kind() {
            return Kind.READONLY;
        }
    }

    /** Один переключатель оптимизации. */
    static final class PerfEntry implements VideoEntry {
        private final Perf.Opt opt;

        PerfEntry(Perf.Opt opt) {
            this.opt = opt;
        }

        @Override
        public Component name() {
            return Component.translatable("archud.perf." + opt.id());
        }

        @Override
        public Component description() {
            return Component.translatable("archud.perf." + opt.id() + ".desc");
        }

        @Override
        public Component valueText() {
            int v = opt.get();
            if (opt.id().equals("r_adaptive")) {
                if (v == 0) return Component.translatable("options.off");
                String now = com.arc4io.archud.client.perf.AdaptiveDistance.info();
                return Component.literal(v + " FPS" + (now.isEmpty() ? "" : " · " + now));
            }
            return switch (opt.kind()) {
                case TOGGLE -> Component.translatable(v != 0 ? "options.on" : "options.off");
                case DIST -> v == 0 ? Component.translatable("archud.perf.unlimited") : Component.translatable("archud.perf.blocks", v);
                case COUNT -> v == 0 ? Component.translatable("archud.perf.unlimited") : Component.literal(String.valueOf(v));
            };
        }

        @Override
        public Kind kind() {
            return opt.kind() == Perf.Kind.TOGGLE ? Kind.TOGGLE : Kind.CYCLE;
        }

        @Override
        public Component impact() {
            return Component.translatable("archud.perf.impact." + opt.group());
        }

        @Override
        public boolean changed() {
            return opt.get() != 0;
        }

        @Override
        public boolean bool() {
            return opt.get() != 0;
        }

        @Override
        public void setBool(boolean v) {
            opt.set(v ? 1 : 0);
            ArcConfig.save();
        }

        @Override
        public void cycle(int dir) {
            int[] vals = opt.values();
            int cur = opt.get(), idx = 0;
            for (int i = 0; i < vals.length; i++) if (vals[i] == cur) idx = i;
            idx = Math.floorMod(idx + dir, vals.length);
            opt.set(vals[idx]);
            ArcConfig.save();
        }
    }
}
