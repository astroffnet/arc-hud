package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.Mth;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ресурспаки: две колонки (доступные / включённые), включение кликом, порядок стрелками,
 * иконки паков, кнопка «Открыть папку». Применяется по «Готово».
 */
public class ArcPackScreen extends ArcScreen {
    private static final int ROW = 30;
    /** Значок пака без pack.png (как в ванильном экране). */
    private static final ResourceLocation UNKNOWN_PACK = new ResourceLocation("textures/misc/unknown_pack.png");
    private final Map<String, ResourceLocation> icons = new HashMap<>();
    private final java.util.List<ResourceLocation> registered = new java.util.ArrayList<>();
    private static int iconCounter;
    private final Options options;
    private final List<Pack> available = new ArrayList<>();
    private final List<Pack> selected = new ArrayList<>(); // сверху — высший приоритет
    private double scrollA, scrollS;
    private int listY, listH, colW;

    public ArcPackScreen(Screen parent, Options options) {
        super(Component.translatable("resourcePack.title"), parent);
        this.options = options;
    }

    @Override
    protected void init() {
        layout(520, height - 20);
        PackRepository repo = minecraft.getResourcePackRepository();
        if (available.isEmpty() && selected.isEmpty()) {
            repo.reload();
            List<Pack> sel = new ArrayList<>(repo.getSelectedPacks());
            for (int i = sel.size() - 1; i >= 0; i--) selected.add(sel.get(i));
            for (Pack p : repo.getAvailablePacks()) if (!sel.contains(p)) available.add(p);
        }
        colW = (pw - 30) / 2;
        listY = py + 40;
        listH = ph - 40 - 32;
        addRenderableWidget(new ArcButton(px + 8, py + ph - 24, 140, 18, Component.translatable("pack.openFolder"),
                b -> Util.getPlatform().openFile(minecraft.getResourcePackDirectory().toFile())));
        addRenderableWidget(new ArcButton(px + pw - 108, py + ph - 24, 100, 18, Component.translatable("gui.done"), b -> apply()));
    }

    private void apply() {
        PackRepository repo = minecraft.getResourcePackRepository();
        List<String> ids = new ArrayList<>();
        for (int i = selected.size() - 1; i >= 0; i--) ids.add(selected.get(i).getId());
        repo.setSelected(ids);
        options.updateResourcePacks(repo);
        onClose();
    }

    private ResourceLocation icon(Pack pack) {
        return icons.computeIfAbsent(pack.getId(), id -> {
            try (PackResources res = pack.open()) {
                IoSupplier<InputStream> sup = res.getRootResource("pack.png");
                if (sup != null) {
                    try (InputStream in = sup.get()) {
                        NativeImage img = NativeImage.read(in);
                        // уникальный id на каждое открытие экрана: старые текстуры освобождаются в removed()
                        ResourceLocation loc = ArcHud.id("pack_icon/" + (iconCounter++) + "_" + Integer.toHexString(id.hashCode()));
                        minecraft.getTextureManager().register(loc, new DynamicTexture(img));
                        registered.add(loc);
                        return loc;
                    }
                }
            } catch (Throwable e) {
                ArcHud.LOGGER.debug("Arc HUD: pack icon for {} failed: {}", id, e.toString());
            }
            return UNKNOWN_PACK;
        });
    }

    @Override
    public void removed() {
        for (ResourceLocation r : registered) minecraft.getTextureManager().release(r);
        registered.clear();
        icons.clear();
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        int ax = px + 10, sx = px + 20 + colW;
        g.drawString(font, Component.translatable("pack.available.title"), ax, py + 27, Draw.TEXT_DIM, true);
        g.drawString(font, Component.translatable("pack.selected.title"), sx, py + 27, Draw.alpha(accent, 0xFF), true);
        scrollA = Mth.clamp(scrollA, 0, Math.max(0, available.size() * ROW - listH));
        scrollS = Mth.clamp(scrollS, 0, Math.max(0, selected.size() * ROW - listH));
        column(g, available, ax, scrollA, false, mouseX, mouseY, accent);
        column(g, selected, sx, scrollS, true, mouseX, mouseY, accent);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void column(GuiGraphics g, List<Pack> list, int x, double scroll, boolean sel, int mx, int my, int accent) {
        Draw.panel(g, x - 2, listY - 2, colW + 4, listH + 4, 0x50000000, 0x30FFFFFF);
        g.enableScissor(x, listY, x + colW, listY + listH);
        for (int i = 0; i < list.size(); i++) {
            int y = listY + i * ROW - (int) scroll;
            if (y + ROW < listY || y > listY + listH) continue;
            Pack p = list.get(i);
            boolean hov = mx >= x && mx < x + colW && my >= y && my < y + ROW - 2 && my >= listY && my < listY + listH;
            boolean incompatible = !p.getCompatibility().isCompatible();
            Draw.panel(g, x, y, colW, ROW - 2, hov ? Draw.PANEL_LIGHT : Draw.PANEL,
                    incompatible ? 0xA0FF5252 : sel ? Draw.alpha(accent, 0x90) : 0x30FFFFFF);
            ResourceLocation ic = icon(p);
            if (ic != null) g.blit(ic, x + 3, y + 3, 22, 22, 0, 0, 64, 64, 64, 64);
            String title = p.getTitle().getString();
            int tw = colW - 32 - (sel ? 22 : 0);
            if (font.width(title) > tw) title = font.plainSubstrByWidth(title, tw - 6) + "…";
            g.drawString(font, title, x + 28, y + 4, incompatible ? 0xFFFF8A80 : Draw.TEXT, true);
            String desc = p.getDescription().getString().replace('\n', ' ');
            if (font.width(desc) * 0.6F > tw) desc = font.plainSubstrByWidth(desc, (int) ((tw - 6) / 0.6F)) + "…";
            Draw.small(g, font, desc, x + 28, y + 15, Draw.TEXT_DIM, 0.6F);
            if (sel && hov && !p.isFixedPosition()) {
                g.drawString(font, "▲", x + colW - 18, y + 2, 0xFFFFFFFF, true);
                g.drawString(font, "▼", x + colW - 18, y + 15, 0xFFFFFFFF, true);
            }
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (my < listY || my >= listY + listH) return false;
        int ax = px + 10, sx = px + 20 + colW;
        if (mx >= ax && mx < ax + colW) {
            int idx = (int) ((my - listY + scrollA) / ROW);
            if (idx >= 0 && idx < available.size()) {
                Pack p = available.remove(idx);
                selected.add(0, p);
                return true;
            }
        } else if (mx >= sx && mx < sx + colW) {
            int idx = (int) ((my - listY + scrollS) / ROW);
            if (idx >= 0 && idx < selected.size()) {
                Pack p = selected.get(idx);
                double rowY = my - (listY + idx * ROW - scrollS);
                if (mx >= sx + colW - 20 && !p.isFixedPosition()) {
                    int to = rowY < ROW / 2.0 ? idx - 1 : idx + 1;
                    if (to >= 0 && to < selected.size() && !selected.get(to).isFixedPosition()) {
                        selected.set(idx, selected.get(to));
                        selected.set(to, p);
                    }
                    return true;
                }
                if (!p.isRequired()) {
                    selected.remove(idx);
                    available.add(0, p);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < px + 15 + colW) scrollA -= delta * ROW;
        else scrollS -= delta * ROW;
        return true;
    }
}
