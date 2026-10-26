package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Новое меню достижений: вкладки-категории слева с процентом, карточки достижений с описанием,
 * прогрессом и датой получения, фильтры «все / получены / не получены», поиск.
 */
public class ArcAdvancementsScreen extends Screen implements ClientAdvancements.Listener, com.arc4io.archud.client.screen.ScreenFit.Fits {
    private static final int CARD_H = 34;
    private final ClientAdvancements advancements;
    private final List<Advancement> roots = new ArrayList<>();
    private final Map<Advancement, AdvancementProgress> progress = new HashMap<>();
    private final List<Advancement> shown = new ArrayList<>();
    private Advancement tab;
    private int filter; // 0 все, 1 получены, 2 не получены
    private EditBox search;
    private double scroll, tabScroll;
    private int px, py, pw, ph, tabsW;
    /** Вид «дерево» (как в ванилле — видно, какое достижение открывает следующее) или «список». */
    private static boolean treeView = true;
    private double panX, panY;
    private boolean panning, panInit;
    private static final int NODE = 26, STEP_X = 34, STEP_Y = 30;

    public ArcAdvancementsScreen(ClientAdvancements advancements) {
        super(Component.translatable("gui.advancements"));
        this.advancements = advancements;
    }

    @Override
    protected void init() {
        com.arc4io.archud.client.screen.ScreenFit.ensure(this, 420, 260);
        pw = Math.min(width - 16, 560);
        ph = height - 16;
        px = (width - pw) / 2;
        py = 8;
        tabsW = Math.min(170, pw / 3);
        roots.clear();
        progress.clear();
        advancements.setListener(this);
        if (tab == null && !roots.isEmpty()) select(roots.get(0));
        int lx = px + tabsW + 14;
        int lw = pw - tabsW - 22;
        addRenderableWidget(new ArcButton(lx, py + 25, 60, 18, Component.translatable(treeView ? "archud.adv.tree" : "archud.adv.list"), b -> {
            treeView = !treeView;
            b.setMessage(Component.translatable(treeView ? "archud.adv.tree" : "archud.adv.list"));
        }));
        lx += 64;
        lw -= 64;
        search = addRenderableWidget(new EditBox(font, lx, py + 26, lw - 3 * 64, 16, Component.translatable("archud.controls.search")));
        search.setHint(Component.translatable("archud.controls.search"));
        search.setResponder(s -> refresh());
        String[] keys = {"archud.adv.all", "archud.adv.done", "archud.adv.todo"};
        for (int i = 0; i < 3; i++) {
            int f = i;
            addRenderableWidget(new ArcButton(lx + lw - (3 - i) * 64 + 4, py + 25, 60, 18, Component.translatable(keys[i]), b -> {
                filter = f;
                refresh();
            }));
        }
        addRenderableWidget(new ArcButton(px + pw - 108, py + ph - 24, 100, 18, Component.translatable("gui.done"), b -> onClose()));
        refresh();
    }

    @Override
    public void removed() {
        advancements.setListener(null);
    }

    private void select(Advancement root) {
        tab = root;
        scroll = 0;
        panInit = false;
        advancements.setSelectedTab(root, true);
        refresh();
    }

    private void collect(Advancement a, List<Advancement> out) {
        if (a.getDisplay() != null) out.add(a);
        for (Advancement c : a.getChildren()) collect(c, out);
    }

    private void refresh() {
        shown.clear();
        if (tab == null) return;
        List<Advancement> all = new ArrayList<>();
        collect(tab, all);
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
        for (Advancement a : all) {
            boolean done = isDone(a);
            if (filter == 1 && !done) continue;
            if (filter == 2 && done) continue;
            DisplayInfo d = a.getDisplay();
            if (!q.isEmpty() && !(d.getTitle().getString().toLowerCase(Locale.ROOT).contains(q)
                    || d.getDescription().getString().toLowerCase(Locale.ROOT).contains(q))) continue;
            shown.add(a);
        }
    }

    private boolean isDone(Advancement a) {
        AdvancementProgress p = progress.get(a);
        return p != null && p.isDone();
    }

    private int[] stats(Advancement root) {
        List<Advancement> all = new ArrayList<>();
        collect(root, all);
        int done = 0;
        for (Advancement a : all) if (isDone(a)) done++;
        return new int[]{done, all.size()};
    }

    private static int frameColor(FrameType f) {
        return switch (f) {
            case CHALLENGE -> 0xFFB388FF;
            case GOAL -> 0xFF4FC3F7;
            default -> 0xFFFFD54F;
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int accent = Theme.accent();
        Draw.accentPanel(g, px, py, pw, ph, accent);
        g.drawString(font, title, px + 10, py + 8, Draw.TEXT, true);
        int totalDone = 0, total = 0;
        for (Advancement r : roots) {
            int[] s = stats(r);
            totalDone += s[0];
            total += s[1];
        }
        String sum = totalDone + " / " + total;
        g.drawString(font, sum, px + pw - 10 - font.width(sum), py + 8, Draw.alpha(accent, 0xFF), true);

        // вкладки
        int tx = px + 8, ty = py + 26, th = ph - 26 - 30;
        Draw.panel(g, tx, ty, tabsW, th, 0x50000000, 0x30FFFFFF);
        g.enableScissor(tx, ty, tx + tabsW, ty + th);
        int y = ty + 2 - (int) tabScroll;
        for (Advancement r : roots) {
            DisplayInfo d = r.getDisplay();
            if (d == null) continue;
            boolean sel = r == tab;
            boolean hov = mouseX >= tx && mouseX < tx + tabsW && mouseY >= y && mouseY < y + 26 && mouseY >= ty && mouseY < ty + th;
            if (sel) g.fill(tx + 2, y, tx + tabsW - 2, y + 25, Draw.alpha(accent, 0x45));
            else if (hov) g.fill(tx + 2, y, tx + tabsW - 2, y + 25, 0x18FFFFFF);
            if (sel) g.fill(tx + 2, y, tx + 4, y + 25, Draw.alpha(accent, 0xFF));
            g.renderItem(d.getIcon(), tx + 6, y + 4);
            String name = d.getTitle().getString();
            if (font.width(name) > tabsW - 32) name = font.plainSubstrByWidth(name, tabsW - 36) + "…";
            g.drawString(font, name, tx + 26, y + 4, sel ? 0xFFFFFFFF : 0xFFCFD8DC, true);
            int[] s = stats(r);
            float f = s[1] == 0 ? 0 : s[0] / (float) s[1];
            g.fill(tx + 26, y + 16, tx + tabsW - 8, y + 18, 0x60000000);
            g.fill(tx + 26, y + 16, tx + 26 + Math.round((tabsW - 34) * f), y + 18, Draw.alpha(accent, 0xFF));
            Draw.small(g, font, Math.round(f * 100) + "%", tx + tabsW - 26, y + 19.5F, Draw.TEXT_DIM, 0.5F);
            y += 28;
        }
        g.disableScissor();

        if (treeView) {
            renderTree(g, mouseX, mouseY, accent);
            super.render(g, mouseX, mouseY, partialTick);
            if (treeHover != null) treeTooltip(g, treeHover, mouseX, mouseY);
            return;
        }
        // карточки
        int lx = px + tabsW + 14, lw = pw - tabsW - 22, ly = py + 48, lh = ph - 48 - 30;
        scroll = Mth.clamp(scroll, 0, Math.max(0, shown.size() * (CARD_H + 3) - lh));
        g.enableScissor(lx, ly, lx + lw, ly + lh);
        y = ly - (int) scroll;
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        Advancement hovered = null;
        for (Advancement a : shown) {
            if (y + CARD_H >= ly && y <= ly + lh) {
                DisplayInfo d = a.getDisplay();
                AdvancementProgress p = progress.get(a);
                boolean done = p != null && p.isDone();
                boolean hidden = d.isHidden() && !done;
                int fc = frameColor(d.getFrame());
                Draw.panel(g, lx, y, lw, CARD_H, done ? 0xC0142018 : Draw.PANEL, done ? Draw.alpha(fc, 0xC0) : 0x30FFFFFF);
                g.fill(lx, y + 2, lx + 2, y + CARD_H - 2, done ? fc : 0xFF4A525C);
                // иконка в рамке
                Draw.panel(g, lx + 6, y + 6, 22, 22, 0x80000000, done ? fc : 0x50FFFFFF);
                if (!hidden) g.renderItem(d.getIcon(), lx + 9, y + 9);
                else g.drawCenteredString(font, "?", lx + 17, y + 13, Draw.TEXT_DIM);
                Component t = hidden ? Component.literal("???") : d.getTitle();
                g.drawString(font, t, lx + 34, y + 5, done ? fc : Draw.TEXT, true);
                if (!hidden) {
                    List<FormattedCharSequence> lines = font.split(d.getDescription(), (int) ((lw - 120) / 0.75F));
                    for (int i = 0; i < Math.min(2, lines.size()); i++) {
                        g.pose().pushPose();
                        g.pose().translate(lx + 34, y + 16 + i * 7, 0);
                        g.pose().scale(0.75F, 0.75F, 1);
                        g.drawString(font, lines.get(i), 0, 0, Draw.TEXT_DIM, false);
                        g.pose().popPose();
                    }
                }
                // правая часть: прогресс / дата
                int rx = lx + lw - 80;
                if (done && p.getFirstProgressDate() != null) {
                    Draw.small(g, font, "✔ " + fmt.format(p.getFirstProgressDate()), rx, y + 6, 0xFF9CFF5A, 0.6F);
                } else if (p != null) {
                    float f = p.getPercent();
                    g.fill(rx, y + 8, rx + 72, y + 11, 0x60000000);
                    g.fill(rx, y + 8, rx + Math.round(72 * f), y + 11, Draw.alpha(accent, 0xFF));
                    String pt = p.getProgressText();
                    Draw.small(g, font, pt != null ? pt : Math.round(f * 100) + "%", rx, y + 14, Draw.TEXT_DIM, 0.6F);
                }
                if (mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + CARD_H && mouseY >= ly && mouseY < ly + lh) hovered = a;
            }
            y += CARD_H + 3;
        }
        g.disableScissor();
        if (shown.isEmpty()) g.drawCenteredString(font, Component.translatable("archud.adv.empty"), lx + lw / 2, ly + 20, Draw.TEXT_DIM);
        super.render(g, mouseX, mouseY, partialTick);
        if (hovered != null) {
            DisplayInfo d = hovered.getDisplay();
            if (!(d.isHidden() && !isDone(hovered))) {
                List<Component> tip = new ArrayList<>();
                tip.add(d.getTitle().copy().withStyle(d.getFrame().getChatColor()));
                tip.add(d.getDescription());
                g.renderComponentTooltip(font, tip, mouseX, mouseY);
            }
        }
    }

    // ------------------------------------------------------------------ дерево
    private Advancement treeHover;

    private boolean visibleInTree(Advancement a) {
        if (a.getDisplay() == null) return false;
        if (isDone(a) || !a.getDisplay().isHidden()) return true;
        Advancement p = a.getParent();
        return p != null && isDone(p);
    }

    private boolean matches(Advancement a) {
        boolean done = isDone(a);
        if (filter == 1 && !done) return false;
        if (filter == 2 && done) return false;
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        DisplayInfo d = a.getDisplay();
        return d.getTitle().getString().toLowerCase(Locale.ROOT).contains(q) || d.getDescription().getString().toLowerCase(Locale.ROOT).contains(q);
    }

    private int[] treeArea() {
        int lx = px + tabsW + 14, lw = pw - tabsW - 22, ly = py + 48, lh = ph - 48 - 30;
        return new int[]{lx, ly, lw, lh};
    }

    private int nodeX(Advancement a, int[] area) {
        return area[0] + (int) panX + Math.round(a.getDisplay().getX() * STEP_X);
    }

    private int nodeY(Advancement a, int[] area) {
        return area[1] + (int) panY + Math.round(a.getDisplay().getY() * STEP_Y);
    }

    private void renderTree(GuiGraphics g, int mouseX, int mouseY, int accent) {
        treeHover = null;
        int[] area = treeArea();
        Draw.panel(g, area[0], area[1], area[2], area[3], 0xA0060A10, 0x30FFFFFF);
        if (tab == null) return;
        List<Advancement> all = new ArrayList<>();
        collect(tab, all);
        if (!panInit) {
            panInit = true;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Advancement a : all) {
                minY = Math.min(minY, a.getDisplay().getY());
                maxY = Math.max(maxY, a.getDisplay().getY());
            }
            panX = 16;
            double treeH = (maxY - minY) * STEP_Y + NODE;
            panY = treeH < area[3] - 16 ? (area[3] - treeH) / 2 - minY * STEP_Y : 12 - minY * STEP_Y;
        }
        // фоновая сетка
        g.enableScissor(area[0] + 1, area[1] + 1, area[0] + area[2] - 1, area[1] + area[3] - 1);
        int gs = 16;
        int ox = Math.floorMod((int) panX, gs), oy = Math.floorMod((int) panY, gs);
        for (int x = area[0] + ox; x < area[0] + area[2]; x += gs) g.fill(x, area[1], x + 1, area[1] + area[3], 0x08FFFFFF);
        for (int y = area[1] + oy; y < area[1] + area[3]; y += gs) g.fill(area[0], y, area[0] + area[2], y + 1, 0x08FFFFFF);
        // связи: от родителя вправо, вниз/вверх, вправо к ребёнку
        for (int pass = 0; pass < 2; pass++) {
            for (Advancement a : all) {
                Advancement p = a.getParent();
                if (p == null || p.getDisplay() == null || !visibleInTree(a) || !visibleInTree(p)) continue;
                boolean lit = isDone(a);
                if (pass == 0 && lit || pass == 1 && !lit) continue;
                int col = lit ? Draw.alpha(accent, 0xFF) : 0xFF39424D;
                int x1 = nodeX(p, area) + NODE, y1 = nodeY(p, area) + NODE / 2;
                int x2 = nodeX(a, area), y2 = nodeY(a, area) + NODE / 2;
                int mid = (x1 + x2) / 2;
                g.fill(x1, y1 - 1, mid + 1, y1 + 1, col);
                g.fill(mid - 1, Math.min(y1, y2) - 1, mid + 1, Math.max(y1, y2) + 1, col);
                g.fill(mid, y2 - 1, x2, y2 + 1, col);
            }
        }
        // узлы
        for (Advancement a : all) {
            if (!visibleInTree(a)) continue;
            int x = nodeX(a, area), y = nodeY(a, area);
            if (x + NODE < area[0] || x > area[0] + area[2] || y + NODE < area[1] || y > area[1] + area[3]) continue;
            DisplayInfo d = a.getDisplay();
            boolean done = isDone(a);
            boolean hidden = d.isHidden() && !done;
            boolean match = matches(a);
            int fc = frameColor(d.getFrame());
            int border = done ? fc : 0xFF56606C;
            int bg = done ? Draw.alpha(fc, 0x60) : 0xE0141A22;
            if (d.getFrame() == FrameType.CHALLENGE) {
                Draw.panel(g, x - 2, y - 2, NODE + 4, NODE + 4, 0x00000000, Draw.alpha(border, 0x80));
            }
            Draw.panel(g, x, y, NODE, NODE, bg, border);
            if (d.getFrame() == FrameType.GOAL) Draw.outline(g, x + 2, y + 2, NODE - 4, NODE - 4, Draw.alpha(border, 0x60));
            if (!hidden) g.renderItem(d.getIcon(), x + 5, y + 5);
            else g.drawCenteredString(font, "?", x + NODE / 2, y + 9, Draw.TEXT_DIM);
            AdvancementProgress pr = progress.get(a);
            if (!done && pr != null && pr.getPercent() > 0) {
                g.fill(x + 3, y + NODE - 4, x + NODE - 3, y + NODE - 2, 0x80000000);
                g.fill(x + 3, y + NODE - 4, x + 3 + Math.round((NODE - 6) * pr.getPercent()), y + NODE - 2, Draw.alpha(accent, 0xFF));
            }
            if (!match) g.fill(x, y, x + NODE, y + NODE, 0xB0060A10);
            if (mouseX >= x && mouseX < x + NODE && mouseY >= y && mouseY < y + NODE && mouseX >= area[0] && mouseX < area[0] + area[2]
                    && mouseY >= area[1] && mouseY < area[1] + area[3]) {
                treeHover = a;
                Draw.outline(g, x - 1, y - 1, NODE + 2, NODE + 2, 0xFFFFFFFF);
            }
        }
        g.disableScissor();
        Draw.small(g, font, Component.translatable("archud.adv.tree_hint").getString(), area[0] + 4, area[1] + area[3] - 8, 0x80FFFFFF, 0.6F);
    }

    private void treeTooltip(GuiGraphics g, Advancement a, int mx, int my) {
        DisplayInfo d = a.getDisplay();
        boolean done = isDone(a);
        List<Component> tip = new ArrayList<>();
        if (d.isHidden() && !done) {
            tip.add(Component.literal("???"));
        } else {
            tip.add(d.getTitle().copy().withStyle(d.getFrame().getChatColor()));
            tip.add(d.getDescription());
        }
        AdvancementProgress p = progress.get(a);
        if (done && p != null && p.getFirstProgressDate() != null) {
            tip.add(Component.literal("✔ " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(p.getFirstProgressDate())).withStyle(s -> s.withColor(0x9CFF5A)));
        } else if (p != null) {
            String pt = p.getProgressText();
            tip.add(Component.literal(pt != null ? pt : Math.round(p.getPercent() * 100) + "%").withStyle(s -> s.withColor(0x8C9AAB)));
        }
        Advancement parent = a.getParent();
        if (!done && parent != null && parent.getDisplay() != null && !isDone(parent)) {
            tip.add(Component.translatable("archud.adv.needs", parent.getDisplay().getTitle()).withStyle(s -> s.withColor(0xFFD54F)));
        }
        g.renderComponentTooltip(font, tip, mx, my);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (treeView && panning) {
            panX += dx;
            panY += dy;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        panning = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (treeView) {
            int[] area = treeArea();
            if (mx >= area[0] && mx < area[0] + area[2] && my >= area[1] && my < area[1] + area[3]) {
                panning = true;
                return true;
            }
        }
        int tx = px + 8, ty = py + 26, th = ph - 26 - 30;
        if (mx >= tx && mx < tx + tabsW && my >= ty && my < ty + th) {
            int idx = (int) ((my - ty - 2 + tabScroll) / 28);
            List<Advancement> visible = new ArrayList<>();
            for (Advancement r : roots) if (r.getDisplay() != null) visible.add(r);
            if (idx >= 0 && idx < visible.size()) select(visible.get(idx));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < px + tabsW + 10) tabScroll = Math.max(0, tabScroll - delta * 28);
        else if (treeView) {
            if (hasShiftDown()) panX += delta * 30;
            else panY += delta * 30;
        }
        else scroll -= delta * (CARD_H + 3) * 2;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft.options.keyAdvancements.matches(keyCode, scanCode) && !search.isFocused()) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- ClientAdvancements.Listener ----
    @Override
    public void onUpdateAdvancementProgress(Advancement advancement, AdvancementProgress p) {
        progress.put(advancement, p);
    }

    @Override
    public void onSelectedTabChanged(Advancement advancement) {
        if (advancement != null && tab == null) {
            tab = advancement;
            refresh();
        }
    }

    @Override
    public void onAddAdvancementRoot(Advancement advancement) {
        roots.add(advancement);
        if (tab == null) tab = advancement;
    }

    @Override
    public void onRemoveAdvancementRoot(Advancement advancement) {
        roots.remove(advancement);
        if (tab == advancement) tab = roots.isEmpty() ? null : roots.get(0);
    }

    @Override
    public void onAddAdvancementTask(Advancement advancement) {
    }

    @Override
    public void onRemoveAdvancementTask(Advancement advancement) {
    }

    @Override
    public void onAdvancementsCleared() {
        roots.clear();
        progress.clear();
        tab = null;
    }
}
