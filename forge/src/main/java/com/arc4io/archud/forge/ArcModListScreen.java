package com.arc4io.archud.forge;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.client.screen.ArcScreen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Новый список модов для Forge: поиск, логотипы, авторы, описание, кнопки «Настроить», «Сайт», «Папка модов».
 */
public class ArcModListScreen extends ArcScreen {
    private static final int ROW = 24;
    private static final Map<String, Optional<int[]>> LOGO_SIZE = new HashMap<>();
    private final List<IModInfo> all = new ArrayList<>();
    private final List<IModInfo> shown = new ArrayList<>();
    private IModInfo selected;
    private EditBox search;
    private ArcButton configBtn, siteBtn;
    private double scroll, descScroll;
    private int listW;

    public ArcModListScreen(Screen parent) {
        super(Component.translatable("archud.menu.mods"), parent);
    }

    @Override
    protected void init() {
        layout(620, height - 16);
        all.clear();
        all.addAll(ModList.get().getMods());
        all.sort(Comparator.comparing(m -> m.getDisplayName().toLowerCase(Locale.ROOT)));
        listW = Math.min(200, pw / 3);
        search = addRenderableWidget(new EditBox(font, px + 10, py + 26, listW, 16, Component.translatable("archud.controls.search")));
        search.setHint(Component.translatable("archud.controls.search"));
        search.setResponder(s -> refresh());
        int dx = px + listW + 20;
        configBtn = addRenderableWidget(new ArcButton(dx, py + ph - 24, 110, 18, Component.translatable("archud.mods.config"), b -> openConfig()));
        siteBtn = addRenderableWidget(new ArcButton(dx + 114, py + ph - 24, 90, 18, Component.translatable("archud.mods.site"), b -> openSite()));
        addRenderableWidget(new ArcButton(px + 8, py + ph - 24, listW + 4, 18, Component.translatable("archud.mods.folder"),
                b -> Util.getPlatform().openFile(FMLPaths.MODSDIR.get().toFile())));
        addDone();
        if (selected == null && !all.isEmpty()) selected = all.get(0);
        refresh();
    }

    private void refresh() {
        shown.clear();
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
        for (IModInfo m : all) {
            if (q.isEmpty() || m.getDisplayName().toLowerCase(Locale.ROOT).contains(q) || m.getModId().contains(q)) shown.add(m);
        }
        updateButtons();
    }

    private void updateButtons() {
        configBtn.active = selected != null && ConfigScreenHandler.getScreenFactoryFor(selected).isPresent();
        siteBtn.active = selected != null && selected.getModURL().isPresent();
    }

    private void openConfig() {
        if (selected == null) return;
        Optional<BiFunction<net.minecraft.client.Minecraft, Screen, Screen>> f = ConfigScreenHandler.getScreenFactoryFor(selected);
        f.ifPresent(fn -> minecraft.setScreen(fn.apply(minecraft, this)));
    }

    private void openSite() {
        if (selected != null) selected.getModURL().ifPresent(u -> Util.getPlatform().openUri(u.toString()));
    }

    private static ResourceLocation logoId(IModInfo m) {
        return ArcHud.id("mod_logo/" + m.getModId().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
    }

    /** Загружает логотип мода (один раз), возвращает {ширина, высота} или null. */
    private int[] logo(IModInfo m) {
        return LOGO_SIZE.computeIfAbsent(m.getModId(), id -> {
            try {
                Optional<String> file = m.getLogoFile();
                if (file.isEmpty() || file.get().isBlank()) return Optional.empty();
                Path path = m.getOwningFile().getFile().findResource(file.get());
                if (path == null || !Files.exists(path)) return Optional.empty();
                try (InputStream in = Files.newInputStream(path)) {
                    NativeImage img = NativeImage.read(in);
                    int[] size = {img.getWidth(), img.getHeight()};
                    minecraft.getTextureManager().register(logoId(m), new DynamicTexture(img));
                    return Optional.of(size);
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private void drawLogo(GuiGraphics g, IModInfo m, int x, int y, int boxW, int boxH) {
        int[] s = logo(m);
        if (s == null) {
            Draw.panel(g, x, y, Math.min(boxW, boxH), boxH, 0x60000000, 0x40FFFFFF);
            String letter = m.getDisplayName().isEmpty() ? "?" : m.getDisplayName().substring(0, 1).toUpperCase(Locale.ROOT);
            g.drawCenteredString(font, letter, x + Math.min(boxW, boxH) / 2, y + boxH / 2 - 4, Draw.alpha(Theme.accent(), 0xFF));
            return;
        }
        float scale = Math.min(boxW / (float) s[0], boxH / (float) s[1]);
        int w = Math.max(1, Math.round(s[0] * scale)), h = Math.max(1, Math.round(s[1] * scale));
        g.blit(logoId(m), x, y + (boxH - h) / 2, w, h, 0, 0, s[0], s[1], s[0], s[1]);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        String count = shown.size() + " / " + all.size();
        g.drawString(font, count, px + pw - 10 - font.width(count), py + 8, Draw.alpha(accent, 0xFF), true);

        // список
        int lx = px + 10, ly = py + 46, lh = ph - 46 - 30;
        Draw.panel(g, lx - 2, ly - 2, listW + 4, lh + 4, 0x50000000, 0x30FFFFFF);
        scroll = Mth.clamp(scroll, 0, Math.max(0, shown.size() * ROW - lh));
        g.enableScissor(lx, ly, lx + listW, ly + lh);
        int y = ly - (int) scroll;
        for (IModInfo m : shown) {
            if (y + ROW >= ly && y <= ly + lh) {
                boolean sel = m == selected;
                boolean hov = mouseX >= lx && mouseX < lx + listW && mouseY >= y && mouseY < y + ROW && mouseY >= ly && mouseY < ly + lh;
                if (sel) g.fill(lx, y, lx + listW, y + ROW - 1, Draw.alpha(accent, 0x45));
                else if (hov) g.fill(lx, y, lx + listW, y + ROW - 1, 0x18FFFFFF);
                if (sel) g.fill(lx, y, lx + 2, y + ROW - 1, Draw.alpha(accent, 0xFF));
                drawLogo(g, m, lx + 4, y + 2, 20, 20);
                String n = m.getDisplayName();
                if (font.width(n) > listW - 34) n = font.plainSubstrByWidth(n, listW - 38) + "…";
                g.drawString(font, n, lx + 28, y + 3, sel ? 0xFFFFFFFF : Draw.TEXT, true);
                Draw.small(g, font, m.getVersion().toString(), lx + 28, y + 13, Draw.TEXT_DIM, 0.6F);
            }
            y += ROW;
        }
        g.disableScissor();

        // детали
        if (selected != null) {
            int dx = px + listW + 20, dy = py + 26, dw = pw - listW - 30, dh = ph - 26 - 30;
            Draw.panel(g, dx, dy, dw, dh, 0x40000000, 0x30FFFFFF);
            drawLogo(g, selected, dx + 8, dy + 8, Math.min(dw - 16, 240), 56);
            int ty = dy + 70;
            g.drawString(font, selected.getDisplayName(), dx + 8, ty, 0xFFFFFFFF, true);
            ty += 12;
            Draw.small(g, font, "ID: " + selected.getModId() + "   " + Component.translatable("archud.mods.version").getString() + ": " + selected.getVersion(),
                    dx + 8, ty, Draw.TEXT_DIM, 0.75F);
            ty += 10;
            Object authors = selected.getConfig().getConfigElement("authors").orElse(null);
            if (authors != null) {
                Draw.small(g, font, Component.translatable("archud.mods.authors").getString() + ": " + authors, dx + 8, ty, Draw.alpha(accent, 0xFF), 0.75F);
                ty += 10;
            }
            g.fill(dx + 8, ty + 2, dx + dw - 8, ty + 3, Draw.alpha(accent, 0x40));
            ty += 8;
            List<FormattedCharSequence> lines = font.split(Component.literal(selected.getDescription().trim()), dw - 16);
            int maxLines = (dy + dh - 6 - ty) / 10;
            descScroll = Mth.clamp(descScroll, 0, Math.max(0, lines.size() - maxLines));
            for (int i = (int) descScroll; i < lines.size() && i < descScroll + maxLines; i++) {
                g.drawString(font, lines.get(i), dx + 8, ty, Draw.TEXT, false);
                ty += 10;
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int lx = px + 10, ly = py + 46, lh = ph - 46 - 30;
        if (mx >= lx && mx < lx + listW && my >= ly && my < ly + lh) {
            int idx = (int) ((my - ly + scroll) / ROW);
            if (idx >= 0 && idx < shown.size()) {
                selected = shown.get(idx);
                descScroll = 0;
                updateButtons();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < px + listW + 14) scroll -= delta * ROW * 2;
        else descScroll -= delta * 2;
        return true;
    }
}
