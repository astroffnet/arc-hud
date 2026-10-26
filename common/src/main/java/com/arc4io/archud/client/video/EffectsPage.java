package com.arc4io.archud.client.video;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.fx.RenderScale;
import com.arc4io.archud.client.fx.ShaderPacks;
import com.arc4io.archud.client.fx.Waving;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Страница «Эффекты»: встроенный шейдер, масштаб рендера (FSR-подобный апскейл), шевеление растений. */
final class EffectsPage {
    private EffectsPage() {}

    static VideoPage page() {
        VideoPage p = new VideoPage(Component.translatable("archud.fx.page"));
        ArcConfig c = ArcConfig.get();
        List<VideoEntry> shader = new ArrayList<>();
        shader.add(new Bool("shader", () -> c.shaderEnabled, v -> c.shaderEnabled = v, null));
        shader.add(new Bool("hdr", () -> c.shaderHdr, v -> c.shaderHdr = v, null));
        shader.add(new Slider("hdr_strength", 0, 100, 5, () -> c.shaderHdrStrength, v -> c.shaderHdrStrength = v, "%"));
        shader.add(new Bool("bloom", () -> c.shaderBloom, v -> c.shaderBloom = v, null));
        shader.add(new Slider("vibrance", -50, 100, 5, () -> c.shaderVibrance, v -> c.shaderVibrance = v, "%"));
        shader.add(new Slider("contrast", -30, 50, 2, () -> c.shaderContrast, v -> c.shaderContrast = v, "%"));
        shader.add(new Slider("temperature", -50, 50, 5, () -> c.shaderTemperature, v -> c.shaderTemperature = v, ""));
        shader.add(new Slider("sharpen", 0, 100, 5, () -> c.shaderSharpen, v -> c.shaderSharpen = v, "%"));
        shader.add(new Bool("fxaa", () -> c.shaderFxaa, v -> c.shaderFxaa = v, null));
        if (ShaderPacks.inUse()) shader.add(new Info("shaderpack", () -> Component.translatable("archud.fx.shaderpack.value")));
        p.group(Component.translatable("archud.fx.group.shader"), shader);

        List<VideoEntry> scale = new ArrayList<>();
        scale.add(new Slider("render_scale", 25, 100, 5, () -> c.renderScale, v -> c.renderScale = v, "%"));
        scale.add(new Cycle("upscaler", 2, () -> c.upscaler, v -> c.upscaler = v));
        scale.add(new Slider("upscale_sharpness", 0, 100, 5, () -> c.upscaleSharpness, v -> c.upscaleSharpness = v, "%"));
        scale.add(new Info("resolution", () -> Component.literal(RenderScale.info())));
        scale.add(new Info("dlss", () -> Component.translatable("archud.fx.dlss.value")));
        p.group(Component.translatable("archud.fx.group.scale"), scale);

        List<VideoEntry> plants = new ArrayList<>();
        plants.add(new Bool("waving_plants", () -> c.wavingPlants, v -> c.wavingPlants = v, null));
        plants.add(new Bool("waving_leaves", () -> c.wavingLeaves, v -> c.wavingLeaves = v, Waving::rebuildChunks));
        plants.add(new Slider("waving_strength", 0, 100, 5, () -> c.wavingStrength, v -> c.wavingStrength = v, "%"));
        plants.add(new Slider("waving_speed", 0, 100, 5, () -> c.wavingSpeed, v -> c.wavingSpeed = v, "%"));
        if (ArcHud.platform().isModLoaded("sodium") || ArcHud.platform().isModLoaded("embeddium") || ArcHud.platform().isModLoaded("rubidium")) {
            plants.add(new Info("waving_sodium", () -> Component.translatable("archud.fx.waving_sodium.value")));
        }
        p.group(Component.translatable("archud.fx.group.plants"), plants);
        return p;
    }

    private static Component name(String id) {
        return Component.translatable("archud.fx." + id);
    }

    private static Component desc(String id) {
        return Component.translatable("archud.fx." + id + ".desc");
    }

    record Bool(String id, java.util.function.BooleanSupplier get, Consumer<Boolean> set, Runnable after) implements VideoEntry {
        public Component name() {
            return EffectsPage.name(id);
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
            if (after != null) after.run();
        }
    }

    record Slider(String id, int min, int max, int step, IntSupplier get, Consumer<Integer> set, String suffix) implements VideoEntry {
        public Component name() {
            return EffectsPage.name(id);
        }

        public Component description() {
            return desc(id);
        }

        public Component valueText() {
            if (id.equals("render_scale") && get.getAsInt() >= 100) return Component.translatable("archud.fx.render_scale.off");
            return Component.literal(get.getAsInt() + suffix);
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
    }

    record Cycle(String id, int count, IntSupplier get, Consumer<Integer> set) implements VideoEntry {
        public Component name() {
            return EffectsPage.name(id);
        }

        public Component description() {
            return desc(id);
        }

        public Component valueText() {
            return Component.translatable("archud.fx." + id + "." + get.getAsInt());
        }

        public Kind kind() {
            return Kind.CYCLE;
        }

        public void cycle(int dir) {
            set.accept(Math.floorMod(get.getAsInt() + dir, count));
            ArcConfig.save();
        }
    }

    record Info(String id, java.util.function.Supplier<Component> value) implements VideoEntry {
        public Component name() {
            return EffectsPage.name(id);
        }

        public Component description() {
            return desc(id);
        }

        public Component valueText() {
            return value.get();
        }

        public Kind kind() {
            return Kind.READONLY;
        }
    }
}
