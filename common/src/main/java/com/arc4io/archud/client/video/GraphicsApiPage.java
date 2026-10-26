package com.arc4io.archud.client.video;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.gfx.GlContext;
import com.arc4io.archud.client.mods.GameRestarter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Страница «API»: графический API (OpenGL / Vulkan) и версия контекста OpenGL. */
final class GraphicsApiPage {
    /** Версия, с которой запущена игра (для подписи «после перезапуска»). */
    private static final int LAUNCHED = GlContext.requested();

    private GraphicsApiPage() {}

    static VideoPage page() {
        VideoPage p = new VideoPage(Component.translatable("archud.gfx.page"));
        List<VideoEntry> api = new ArrayList<>();
        api.add(new EffectsPage.Info("api", () -> Component.translatable(GlContext.vulkan() ? "archud.fx.api.vulkan" : "archud.fx.api.opengl")));
        if (GlContext.vulkan()) {
            api.add(new EffectsPage.Info("vulkan", () -> Component.translatable("archud.fx.vulkan.active")));
        } else if (ArcHud.platform().isModLoaded("forge")) {
            api.add(new EffectsPage.Info("vulkan", () -> Component.translatable("archud.fx.vulkan.forge")));
        } else {
            api.add(new Link("vulkan", "https://modrinth.com/mod/vulkanmod"));
        }
        p.group(Component.translatable("archud.fx.group.api"), api);

        if (!GlContext.vulkan()) {
            List<VideoEntry> gl = new ArrayList<>();
            gl.add(new GlVersion());
            gl.add(new EffectsPage.Info("gl_current", () -> Component.literal(GlContext.current())));
            gl.add(new EffectsPage.Info("gl_max", () -> Component.literal(GlContext.maxSupported() == 0 ? "?" : GlContext.label(GlContext.maxSupported()))));
            gl.add(new EffectsPage.Info("gl_gpu", () -> Component.literal(GlContext.renderer())));
            if (GameRestarter.supported()) gl.add(new Restart());
            p.group(Component.translatable("archud.fx.group.gl"), gl);
        }
        return p;
    }

    /** Версия OpenGL: Авто, 2.0 … 4.6 (только поддерживаемые видеокартой). */
    private static final class GlVersion implements VideoEntry {
        public Component name() {
            return Component.translatable("archud.fx.gl_version");
        }

        public Component description() {
            if (GlContext.revertedAfterCrash()) return Component.translatable("archud.fx.gl_version.reverted");
            return Component.translatable("archud.fx.gl_version.desc");
        }

        public Component valueText() {
            int v = GlContext.requested();
            Component base = v == 0 ? Component.translatable("archud.fx.gl_version.auto")
                    : Component.literal(GlContext.label(v) + (v >= 32 ? " Core" : " Legacy"));
            if (v != LAUNCHED) return base.copy().append(Component.translatable("archud.fx.restart_needed"));
            return base;
        }

        public Kind kind() {
            return Kind.CYCLE;
        }

        public boolean changed() {
            return GlContext.requested() != 0;
        }

        public void cycle(int dir) {
            List<Integer> c = GlContext.choices();
            int idx = Math.max(0, c.indexOf(GlContext.requested()));
            GlContext.set(c.get(Math.floorMod(idx + dir, c.size())));
        }
    }

    private static final class Restart implements VideoEntry {
        public Component name() {
            return Component.translatable("archud.fx.restart");
        }

        public Component description() {
            return Component.translatable("archud.fx.restart.desc");
        }

        public Component valueText() {
            return Component.empty();
        }

        public Kind kind() {
            return Kind.BUTTON;
        }

        public boolean available() {
            return GlContext.requested() != LAUNCHED;
        }

        public void press() {
            if (GlContext.requested() != LAUNCHED) GameRestarter.restart(Minecraft.getInstance());
        }

        public Component buttonText() {
            return Component.translatable("archud.fx.restart.button");
        }
    }

    /** Ссылка на страницу мода (VulkanMod). */
    private record Link(String id, String url) implements VideoEntry {
        public Component name() {
            return Component.translatable("archud.fx." + id);
        }

        public Component description() {
            return Component.translatable("archud.fx." + id + ".desc");
        }

        public Component valueText() {
            return Component.translatable("archud.fx." + id + ".value");
        }

        public Kind kind() {
            return Kind.BUTTON;
        }

        public void press() {
            Minecraft mc = Minecraft.getInstance();
            var parent = mc.screen;
            mc.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(open -> {
                if (open) net.minecraft.Util.getPlatform().openUri(url);
                mc.setScreen(parent);
            }, url, true));
        }

        public Component buttonText() {
            return Component.translatable("archud.fx.link");
        }
    }
}
