package com.arc4io.archud.client.video;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.client.screen.ArcWidgets;
import com.arc4io.archud.client.screen.ScreenFit;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Экран «Настройки графики» Arc HUD — один вид для ванилы, Sodium / Embeddium и OptiFine.
 * Слева — страницы, сверху — поиск по всем настройкам, в центре — строки «название | элемент управления»
 * (свои переключатели, ползунки и списки), справа — описание выбранной настройки и её влияние на FPS.
 * Для Sodium изменения применяются кнопкой «Применить» (как в нём самом), «Отменить» возвращает всё назад.
 * Кнопка «Оригинал» открывает родной экран мода.
 */
public class ArcVideoScreen extends Screen implements ScreenFit.Fits {
    public enum Source { VANILLA, SODIUM, OPTIFINE }

    /** Следующее открытие экрана не подменять (кнопка «Оригинал»). */
    public static boolean bypass;

    private static final int ROW = 20, HEADER = 14;
    private static int lastPage;

    private final Screen parent;
    private final Source source;
    private final List<VideoPage> pages;
    private final List<SodiumEntry> sodiumEntries;
    private final Screen original;
    private final Map<VideoEntry, AbstractWidget> widgets = new IdentityHashMap<>();
    private final Map<VideoEntry, int[]> rowPos = new HashMap<>();
    private EditBox search;
    private ArcButton applyBtn, undoBtn;
    private int page;
    private double scroll;
    private int maxScroll;
    private VideoEntry hovered, focused, dragging;
    private int sideW, listX, listW, listTop, listBottom, descX, descW;
    private int guiScaleBefore, mipBefore;

    /** Для автоматических скриншотов: открыть страницу с конца (2 — «Оптимизация», 1 — «Эффекты»). */
    public static int showcaseFromEnd;

    public ArcVideoScreen(Screen parent, Source source, List<VideoPage> pages, List<SodiumEntry> sodiumEntries, Screen original) {
        super(Component.translatable("archud.video.title"));
        this.parent = parent;
        this.source = source;
        this.pages = ArcPages.append(pages); // + «Оптимизация» и «Эффекты» Arc HUD
        this.sodiumEntries = sodiumEntries;
        this.original = original;
        if (showcaseFromEnd > 0) {
            lastPage = this.pages.size() - showcaseFromEnd;
            showcaseFromEnd = 0;
        }
        this.page = Mth.clamp(lastPage, 0, Math.max(0, this.pages.size() - 1));
        Minecraft mc = Minecraft.getInstance();
        guiScaleBefore = mc.options.guiScale().get();
        mipBefore = mc.options.mipmapLevels().get();
    }

    // ================================================================== подмена экранов

    /** Подмена для открывающегося экрана; null — оставить как есть. */
    public static Screen wrap(Screen next, Screen current) {
        if (bypass) {
            bypass = false;
            return null;
        }
        if (!ArcConfig.get().customVideoSettings) return null;
        try {
            if (SodiumBridge.isSodiumScreen(next)) return fromSodium(next, SodiumBridge.parentOf(next));
            if (next instanceof net.minecraft.client.gui.screens.VideoSettingsScreen) {
                if (SodiumBridge.present()) {
                    Screen s = SodiumBridge.createScreen(current);
                    if (s != null) return fromSodium(s, current);
                }
                if (VideoPages.optifine()) {
                    List<VideoPage> p = VideoPages.optifinePages(current);
                    if (!p.isEmpty()) return new ArcVideoScreen(current, Source.OPTIFINE, p, List.of(), next);
                    return null;
                }
                return new ArcVideoScreen(current, Source.VANILLA, VideoPages.vanilla(), List.of(), next);
            }
        } catch (Throwable t) {
            com.arc4io.archud.ArcHud.LOGGER.warn("Arc HUD: custom video settings failed, using the original screen", t);
        }
        return null;
    }

    private static Screen fromSodium(Screen sodium, Screen parent) {
        List<SodiumEntry> all = new ArrayList<>();
        List<VideoPage> pages = SodiumBridge.pages(sodium, all);
        if (pages == null) return null;
        return new ArcVideoScreen(parent, Source.SODIUM, pages, all, sodium);
    }

    // ================================================================== разметка

    @Override
    protected void init() {
        ScreenFit.ensure(this, 420, 240);
        widgets.clear();
        sideW = Math.min(130, Math.max(96, width / 6));
        listX = sideW + 14;
        descW = width >= 560 ? Math.min(170, width / 4) : 0;
        descX = width - 8 - descW;
        listW = (descW > 0 ? descX - 8 : width - 8) - listX;
        listTop = 40;
        listBottom = height - (descW > 0 ? 32 : 66);

        String prev = search != null ? search.getValue() : "";
        search = new EditBox(font, listX, 14, Math.min(220, listW - 4), 16, Component.translatable("archud.video.search"));
        search.setHint(Component.translatable("archud.video.search").withStyle(s -> s.withColor(0x6A7380)));
        search.setValue(prev);
        search.setResponder(v -> {
            scroll = 0;
            rebuildWidgets();
        });
        addRenderableWidget(search);

        int by = height - 26;
        int bx = width - 8;
        bx -= 80;
        addRenderableWidget(new ArcButton(bx, by, 80, 18, Component.translatable("gui.done"), b -> onClose()));
        if (source == Source.SODIUM) {
            bx -= 84;
            applyBtn = addRenderableWidget(new ArcButton(bx, by, 80, 18, Component.translatable("archud.video.apply"), b -> applySodium()));
            bx -= 84;
            undoBtn = addRenderableWidget(new ArcButton(bx, by, 80, 18, Component.translatable("archud.video.undo"), b -> {
                for (SodiumEntry e : sodiumEntries) e.reset();
            }));
        }
        if (original != null) {
            addRenderableWidget(new ArcButton(8, by, sideW, 18, Component.translatable("archud.video.original", sourceName()), b -> {
                bypass = true;
                minecraft.setScreen(original);
            }));
        }
        // ванильные виджеты для настроек неизвестного вида — создаются один раз, позиция обновляется при прокрутке
        for (Row r : rows()) {
            if (r.entry != null && r.entry.kind() == VideoEntry.Kind.WIDGET) {
                AbstractWidget w = r.entry.createWidget(0, 0, Math.min(200, listW - 16));
                if (w != null) {
                    widgets.put(r.entry, w);
                    addRenderableWidget(w);
                }
            }
        }
    }

    private String sourceName() {
        return switch (source) {
            case SODIUM -> SodiumBridge.brand();
            case OPTIFINE -> "OptiFine";
            default -> "Minecraft";
        };
    }

    // ================================================================== строки

    private record Row(Component header, VideoEntry entry) {}

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            for (VideoPage p : pages) {
                boolean head = false;
                for (VideoPage.Group g : p.groups) {
                    for (VideoEntry e : g.entries()) {
                        if (!e.name().getString().toLowerCase(Locale.ROOT).contains(q)
                                && !e.description().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
                        if (!head) {
                            out.add(new Row(p.name, null));
                            head = true;
                        }
                        out.add(new Row(null, e));
                    }
                }
            }
            return out;
        }
        if (pages.isEmpty()) return out;
        VideoPage p = pages.get(Mth.clamp(page, 0, pages.size() - 1));
        boolean first = true;
        for (VideoPage.Group g : p.groups) {
            if (g.title() != null) out.add(new Row(g.title(), null));
            else if (!first) out.add(new Row(Component.empty(), null));
            first = false;
            for (VideoEntry e : g.entries()) out.add(new Row(null, e));
        }
        return out;
    }

    // ================================================================== отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        ArcWidgets.drawBackground(g, width, height);
        int accent = Theme.accent();
        hovered = null;
        rowPos.clear();

        // шапка
        g.drawString(font, title, 10, 10, Draw.TEXT, true);
        Draw.small(g, font, sourceName(), 10, 22, Draw.alpha(accent, 0xFF), 0.7F);
        boolean pending = source == Source.SODIUM && sodiumEntries.stream().anyMatch(VideoEntry::changed);
        if (applyBtn != null) {
            applyBtn.active = pending;
            undoBtn.active = pending;
        }

        // страницы
        Draw.panel(g, 6, listTop - 4, sideW + 2, listBottom - listTop + 8, 0xC00B0F14, Draw.alpha(accent, 0x50));
        boolean searching = !search.getValue().isBlank();
        for (int i = 0; i < pages.size(); i++) {
            int y = listTop + i * 20;
            if (y + 18 > listBottom) break;
            boolean sel = i == page && !searching;
            boolean hov = mx >= 8 && mx < 8 + sideW - 2 && my >= y && my < y + 18;
            if (sel) {
                Draw.hGradient(g, 8, y, 8 + sideW - 2, y + 18, Draw.alpha(accent, 0x55), Draw.alpha(accent, 0x08));
                g.fill(8, y + 2, 10, y + 16, accent);
            } else if (hov) g.fill(8, y, 8 + sideW - 2, y + 18, 0x20FFFFFF);
            String n = pages.get(i).name.getString();
            int max = (int) ((sideW - 18) / 0.8F);
            if (font.width(n) > max) n = font.plainSubstrByWidth(n, max - font.width("…")) + "…";
            Draw.small(g, font, n, 15, y + 6, sel ? 0xFFFFFFFF : Draw.TEXT, 0.8F);
        }

        // список
        Draw.panel(g, listX - 4, listTop - 4, listW + 8, listBottom - listTop + 8, 0xC00B0F14, Draw.alpha(accent, 0x50));
        List<Row> rows = rows();
        int total = 0;
        for (Row r : rows) total += r.entry == null ? HEADER : ROW;
        maxScroll = Math.max(0, total - (listBottom - listTop));
        scroll = Mth.clamp(scroll, 0, maxScroll);
        int y = listTop - (int) scroll;
        for (AbstractWidget w : widgets.values()) w.visible = false;
        g.enableScissor(listX - 2, listTop - 2, listX + listW + 2, listBottom + 2);
        for (Row r : rows) {
            if (r.entry == null) {
                if (y + HEADER > listTop && y < listBottom && !r.header.getString().isEmpty()) {
                    Draw.small(g, font, r.header.getString().toUpperCase(Locale.ROOT), listX + 2, y + 5, Draw.alpha(accent, 0xFF), 0.65F);
                    g.fill(listX + 4 + (int) (font.width(r.header.getString().toUpperCase(Locale.ROOT)) * 0.65F), y + 8, listX + listW - 2, y + 9, 0x25FFFFFF);
                }
                y += HEADER;
                continue;
            }
            if (y + ROW > listTop - ROW && y < listBottom) drawRow(g, r.entry, listX, y, listW, mx, my, accent);
            y += ROW;
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int track = listBottom - listTop;
            int th = Math.max(16, track * track / (track + maxScroll));
            int ty = listTop + (int) ((track - th) * (scroll / maxScroll));
            g.fill(listX + listW + 2, ty, listX + listW + 4, ty + th, Draw.alpha(accent, 0xA0));
        }
        if (rows.isEmpty()) Draw.smallCentered(g, font, Component.translatable("archud.video.nothing").getString(), listX + listW / 2F, listTop + 30, Draw.TEXT_DIM, 0.8F);

        // описание
        VideoEntry info = hovered != null ? hovered : focused;
        int dx = descW > 0 ? descX : listX, dy = descW > 0 ? listTop - 4 : listBottom + 8;
        int dw = descW > 0 ? descW : listW, dh = descW > 0 ? listBottom - listTop + 8 : height - 32 - dy;
        Draw.panel(g, dx, dy, dw, dh, 0xC00B0F14, Draw.alpha(accent, 0x50));
        if (info != null) {
            int ty = dy + 6;
            for (FormattedCharSequence l : font.split(info.name(), dw - 12)) {
                g.drawString(font, l, dx + 6, ty, 0xFFFFFFFF, true);
                ty += 10;
            }
            Component impact = info.impact();
            if (impact != null) {
                Draw.small(g, font, Component.translatable("archud.video.impact", impact).getString(), dx + 6, ty + 1, 0xFFFFD54F, 0.6F);
                ty += 9;
            }
            if (info.changed()) {
                Draw.small(g, font, Component.translatable("archud.video.pending").getString(), dx + 6, ty + 1, 0xFF9CFF5A, 0.6F);
                ty += 9;
            }
            ty += 3;
            float sc = 0.7F;
            for (FormattedCharSequence l : font.split(info.description(), (int) ((dw - 12) / sc))) {
                if (ty + 8 > dy + dh) break;
                g.pose().pushPose();
                g.pose().translate(dx + 6, ty, 0);
                g.pose().scale(sc, sc, 1F);
                g.drawString(font, l, 0, 0, 0xFFC8D2DC, false);
                g.pose().popPose();
                ty += 7;
            }
        } else {
            Draw.smallCentered(g, font, Component.translatable("archud.video.hover").getString(), dx + dw / 2F, dy + 14, Draw.TEXT_DIM, 0.65F);
        }
        if (pending) Draw.small(g, font, Component.translatable("archud.video.unsaved").getString(), sideW + 18, height - 20, 0xFFFFD54F, 0.7F);

        super.render(g, mx, my, pt);
    }

    private void drawRow(GuiGraphics g, VideoEntry e, int x, int y, int w, int mx, int my, int accent) {
        boolean inView = my >= listTop && my < listBottom;
        boolean hov = inView && mx >= x && mx < x + w && my >= y && my < y + ROW - 1;
        if (hov) hovered = e;
        boolean avail = e.available();
        g.fill(x, y, x + w, y + ROW - 1, hov ? 0x1EFFFFFF : 0x0AFFFFFF);
        if (e.changed()) g.fill(x, y + 2, x + 2, y + ROW - 3, 0xFF9CFF5A);
        int cw = Math.min(150, w / 2);
        int cx = x + w - cw - 4;
        rowPos.put(e, new int[]{cx, y, cw});
        if (e.kind() == VideoEntry.Kind.WIDGET) {
            AbstractWidget wd = widgets.get(e);
            if (wd != null) {
                wd.setX(x + 4);
                wd.setY(y + 1);
                wd.setWidth(w - 8);
                wd.visible = y >= listTop - 2 && y + ROW <= listBottom + 2;
            }
            return;
        }
        String name = e.name().getString();
        int nameMax = cx - x - 10;
        if (font.width(name) > nameMax) name = font.plainSubstrByWidth(name, nameMax - font.width("…")) + "…";
        g.drawString(font, name, x + 6, y + 6, avail ? Draw.TEXT : 0xFF5A606A, false);
        int c = avail ? accent : 0xFF4A505A;
        boolean ctrlHov = hov && mx >= cx;
        switch (e.kind()) {
            case TOGGLE -> {
                boolean on = e.bool();
                int tx = cx + cw - 30, ty = y + 4;
                Draw.panel(g, tx, ty, 28, 12, on ? Draw.alpha(c, 0xC0) : 0x40FFFFFF, ctrlHov ? 0xFFFFFFFF : 0x40FFFFFF);
                int kx = on ? tx + 17 : tx + 2;
                g.fill(kx, ty + 2, kx + 9, ty + 10, 0xFFFFFFFF);
                String v = e.valueText().getString();
                Draw.small(g, font, v, tx - 4 - font.width(v) * 0.7F, ty + 3, on ? 0xFFE0E6EE : Draw.TEXT_DIM, 0.7F);
            }
            case SLIDER -> {
                double f = e.fraction();
                int sy = y + 13;
                g.fill(cx, sy, cx + cw, sy + 2, 0x40FFFFFF);
                g.fill(cx, sy, cx + (int) (cw * f), sy + 2, c);
                int kx = cx + (int) ((cw - 6) * f);
                Draw.panel(g, kx, sy - 3, 6, 8, dragging == e || ctrlHov ? 0xFFFFFFFF : 0xFFDCE6F0, c);
                String v = e.valueText().getString();
                Draw.small(g, font, v, cx + cw - font.width(v) * 0.7F, y + 3, 0xFFE0E6EE, 0.7F);
            }
            case CYCLE -> {
                Draw.panel(g, cx, y + 2, cw, ROW - 5, ctrlHov ? Draw.alpha(c, 0x40) : 0x24FFFFFF, ctrlHov ? c : 0x30FFFFFF);
                g.drawString(font, "‹", cx + 4, y + 5, avail ? 0xFFB0BEC5 : 0xFF4A505A, false);
                g.drawString(font, "›", cx + cw - 8, y + 5, avail ? 0xFFB0BEC5 : 0xFF4A505A, false);
                String v = e.valueText().getString();
                int vmax = cw - 22;
                if (font.width(v) > vmax) v = font.plainSubstrByWidth(v, vmax - font.width("…")) + "…";
                g.drawCenteredString(font, v, cx + cw / 2, y + 5, avail ? 0xFFFFFFFF : 0xFF5A606A);
            }
            case BUTTON -> {
                Draw.panel(g, cx, y + 2, cw, ROW - 5, ctrlHov ? Draw.alpha(c, 0x50) : 0x24FFFFFF, c);
                g.drawCenteredString(font, e.buttonText(), cx + cw / 2, y + 5, 0xFFFFFFFF);
            }
            default -> {
                String v = e.valueText().getString();
                Draw.small(g, font, v, cx + cw - font.width(v) * 0.75F, y + 6, Draw.TEXT_DIM, 0.75F);
            }
        }
    }

    // ================================================================== ввод

    private VideoEntry entryAt(double mx, double my) {
        if (my < listTop || my >= listBottom) return null;
        for (Map.Entry<VideoEntry, int[]> en : rowPos.entrySet()) {
            int[] p = en.getValue();
            if (my >= p[1] && my < p[1] + ROW - 1 && mx >= listX && mx < listX + listW) return en.getKey();
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (search.isFocused() && !search.isMouseOver(mx, my)) search.setFocused(false);
        // страницы
        if (mx >= 8 && mx < 8 + sideW && my >= listTop) {
            int i = (int) ((my - listTop) / 20);
            if (i >= 0 && i < pages.size() && my < listTop + i * 20 + 18) {
                if (page != i || !search.getValue().isBlank()) click();
                page = i;
                lastPage = i;
                scroll = 0;
                if (!search.getValue().isBlank()) search.setValue("");
                else rebuildWidgets();
                return true;
            }
        }
        VideoEntry e = entryAt(mx, my);
        if (e == null || !e.available()) return e != null;
        focused = e;
        int[] p = rowPos.get(e);
        switch (e.kind()) {
            case TOGGLE -> {
                e.setBool(!e.bool());
                click();
            }
            case CYCLE -> {
                int dir = button == 1 || mx < p[0] + p[2] / 2.0 ? -1 : 1;
                if (mx < p[0]) dir = button == 1 ? -1 : 1;
                e.cycle(dir);
                click();
            }
            case SLIDER -> {
                dragging = e;
                if (mx >= p[0]) e.setFraction((mx - p[0]) / p[2]);
            }
            case BUTTON -> {
                click();
                e.press();
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            int[] p = rowPos.get(dragging);
            if (p != null) dragging.setFraction((mx - p[0]) / p[2]);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        VideoEntry e = entryAt(mx, my);
        if (e != null && hasShiftDown() && e.available()) {
            // Shift + колесо — менять значение под курсором
            if (e.kind() == VideoEntry.Kind.SLIDER) e.setFraction(e.fraction() + Math.signum(delta) / e.steps());
            else if (e.kind() == VideoEntry.Kind.CYCLE) e.cycle(delta > 0 ? 1 : -1);
            return true;
        }
        if (mx >= listX - 4 && mx < listX + listW + 6) {
            scroll = Mth.clamp(scroll - delta * ROW * 2, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!search.isFocused() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_F && hasControlDown()) {
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ================================================================== применение

    private void applySodium() {
        SodiumBridge.apply(sodiumEntries);
        click();
    }

    @Override
    public void onClose() {
        if (source == Source.SODIUM) SodiumBridge.apply(sodiumEntries);
        Minecraft mc = Minecraft.getInstance();
        mc.options.save();
        com.arc4io.archud.config.ArcConfig.save();
        if (source != Source.SODIUM) {
            if (mc.options.guiScale().get() != guiScaleBefore) mc.resizeDisplay();
            if (mc.options.mipmapLevels().get() != mipBefore) {
                mc.updateMaxMipLevel(mc.options.mipmapLevels().get());
                mc.delayTextureReload();
            }
        }
        mc.setScreen(parent);
    }

    @Override
    public void tick() {
        search.tick();
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
