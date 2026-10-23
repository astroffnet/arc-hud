package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Игровые правила (создание мира) в стиле Arc:
 * категории слева со счётчиками изменённых, карточки правил с описанием, переключатели и числа со степпером,
 * отметка и сброс изменённых правил, поиск, фильтр «только изменённые», сброс всего.
 */
public class ArcGameRulesScreen extends ArcScreen {
    private static final int CARD = 34;
    private static final int SIDE = 128;

    private final GameRules rules;
    private final GameRules defaults = new GameRules();
    private final Consumer<Optional<GameRules>> callback;
    private final List<Entry> all = new ArrayList<>();
    private List<Entry> shown = new ArrayList<>();
    private GameRules.Category category; // null — все
    private boolean onlyChanged;
    private EditBox search, numberEdit;
    private Entry editing;
    private double scroll;
    private final Map<Entry, float[]> anim = new HashMap<>();
    private ArcButton changedBtn;

    private static final class Entry {
        final GameRules.Key<?> key;
        final boolean bool;
        final String name, desc, id;

        Entry(GameRules.Key<?> key, boolean bool) {
            this.key = key;
            this.bool = bool;
            this.id = key.getId();
            this.name = I18n.get(key.getDescriptionId());
            String dk = key.getDescriptionId() + ".description";
            this.desc = I18n.exists(dk) ? I18n.get(dk) : "";
        }
    }

    public ArcGameRulesScreen(GameRules rules, Consumer<Optional<GameRules>> callback) {
        super(Component.translatable("editGamerule.title"), null);
        this.rules = rules;
        this.callback = callback;
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                all.add(new Entry(key, true));
            }

            @Override
            public void visitInteger(GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
                all.add(new Entry(key, false));
            }
        });
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    // ------------------------------------------------------------------ значения
    @SuppressWarnings("unchecked")
    private boolean boolOf(GameRules r, Entry e) {
        return r.getRule((GameRules.Key<GameRules.BooleanValue>) e.key).get();
    }

    @SuppressWarnings("unchecked")
    private int intOf(GameRules r, Entry e) {
        return r.getRule((GameRules.Key<GameRules.IntegerValue>) e.key).get();
    }

    @SuppressWarnings("unchecked")
    private void setBool(Entry e, boolean v) {
        rules.getRule((GameRules.Key<GameRules.BooleanValue>) e.key).set(v, null);
    }

    @SuppressWarnings("unchecked")
    private void setInt(Entry e, int v) {
        rules.getRule((GameRules.Key<GameRules.IntegerValue>) e.key).set(v, null);
    }

    private boolean changed(Entry e) {
        return e.bool ? boolOf(rules, e) != boolOf(defaults, e) : intOf(rules, e) != intOf(defaults, e);
    }

    private void reset(Entry e) {
        if (e.bool) setBool(e, boolOf(defaults, e));
        else setInt(e, intOf(defaults, e));
    }

    // ------------------------------------------------------------------ разметка
    private int listX() {
        return px + 10 + SIDE + 8;
    }

    private int listW() {
        return pw - 20 - SIDE - 8;
    }

    private int listTop() {
        return py + 48;
    }

    private int listBottom() {
        return py + ph - 32;
    }

    @Override
    protected void init() {
        layout(600, 360);
        String q = search == null ? "" : search.getValue();
        search = addRenderableWidget(new EditBox(font, listX(), py + 26, listW() - 110, 16, Component.translatable("archud.controls.search")));
        search.setHint(Component.translatable("archud.controls.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(q);
        search.setResponder(s -> refresh());
        changedBtn = addRenderableWidget(new ArcButton(listX() + listW() - 106, py + 25, 106, 18, changedLabel(), b -> {
            onlyChanged = !onlyChanged;
            b.setMessage(changedLabel());
            refresh();
        }));
        numberEdit = new EditBox(font, 0, 0, 52, 14, Component.empty());
        numberEdit.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,9}"));
        numberEdit.visible = false;
        addWidget(numberEdit);
        addRenderableWidget(new ArcButton(px + 10, py + ph - 24, 110, 18, Component.translatable("archud.rules.reset_all"), b -> {
            for (Entry e : all) reset(e);
            refresh();
        }).danger());
        addRenderableWidget(new ArcButton(px + pw - 216, py + ph - 24, 100, 18, Component.translatable("gui.cancel"),
                b -> callback.accept(Optional.empty())));
        addRenderableWidget(new ArcButton(px + pw - 110, py + ph - 24, 100, 18, Component.translatable("gui.done"), b -> done()));
        refresh();
    }

    private Component changedLabel() {
        return Component.translatable(onlyChanged ? "archud.rules.only_changed" : "archud.rules.all");
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (category != null && e.key.getCategory() != category) continue;
            if (onlyChanged && !changed(e)) continue;
            if (!q.isEmpty() && !e.name.toLowerCase(Locale.ROOT).contains(q) && !e.id.toLowerCase(Locale.ROOT).contains(q)
                    && !e.desc.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(e);
        }
        shown = out;
        stopEditing(true);
    }

    private void done() {
        stopEditing(true);
        callback.accept(Optional.of(rules));
    }

    @Override
    public void onClose() {
        callback.accept(Optional.empty());
    }

    // ------------------------------------------------------------------ числа
    private void startEditing(Entry e) {
        stopEditing(true);
        editing = e;
        numberEdit.setValue(Integer.toString(intOf(rules, e)));
        numberEdit.visible = true;
        setFocused(numberEdit);
        numberEdit.setFocused(true);
        numberEdit.moveCursorToEnd();
    }

    private void stopEditing(boolean apply) {
        if (editing != null && apply) {
            try {
                setInt(editing, Integer.parseInt(numberEdit.getValue()));
            } catch (NumberFormatException ignored) {
            }
        }
        editing = null;
        if (numberEdit != null) {
            numberEdit.visible = false;
            numberEdit.setFocused(false);
        }
    }

    // ------------------------------------------------------------------ ввод
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // категории
        int sx = px + 10, sy = py + 26;
        List<GameRules.Category> cats = categories();
        for (int i = 0; i <= cats.size(); i++) {
            int y = sy + i * 20;
            if (mx >= sx && mx < sx + SIDE && my >= y && my < y + 18) {
                category = i == 0 ? null : cats.get(i - 1);
                scroll = 0;
                refresh();
                return true;
            }
        }
        if (editing != null && numberEdit.isMouseOver(mx, my)) return numberEdit.mouseClicked(mx, my, button);
        if (mx >= listX() && mx < listX() + listW() && my >= listTop() && my < listBottom()) {
            int i = (int) ((my - listTop() + scroll) / CARD);
            if (i >= 0 && i < shown.size()) {
                Entry e = shown.get(i);
                int y = listTop() + i * CARD - (int) scroll;
                int right = listX() + listW() - 8;
                // сброс правила
                int rx = right - (e.bool ? 30 : 104) - 16;
                if (changed(e) && mx >= rx && mx < rx + 14 && my >= y + 9 && my < y + 23) {
                    reset(e);
                    stopEditing(false);
                    return true;
                }
                if (e.bool) {
                    setBool(e, !boolOf(rules, e));
                    return true;
                }
                int step = hasShiftDown() ? 10 : 1;
                if (mx >= right - 18 && mx < right) {
                    stopEditing(true);
                    setInt(e, intOf(rules, e) + step);
                    return true;
                }
                if (mx >= right - 104 && mx < right - 86) {
                    stopEditing(true);
                    setInt(e, intOf(rules, e) - step);
                    return true;
                }
                if (mx >= right - 84 && mx < right - 20) {
                    startEditing(e);
                    return true;
                }
            }
            stopEditing(true);
            return true;
        }
        stopEditing(true);
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (editing != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                stopEditing(true);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                stopEditing(false);
                return true;
            }
            return numberEdit.keyPressed(key, scan, mods);
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (editing != null) return numberEdit.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        stopEditing(true);
        scroll = Mth.clamp(scroll - delta * CARD, 0, maxScroll());
        return true;
    }

    private double maxScroll() {
        return Math.max(0, shown.size() * CARD - (listBottom() - listTop()));
    }

    private List<GameRules.Category> categories() {
        List<GameRules.Category> out = new ArrayList<>();
        for (GameRules.Category c : GameRules.Category.values()) {
            for (Entry e : all) {
                if (e.key.getCategory() == c) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ отрисовка
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int accent = Theme.accent();
        // категории
        int sx = px + 10, sy = py + 26;
        List<GameRules.Category> cats = categories();
        for (int i = 0; i <= cats.size(); i++) {
            GameRules.Category c = i == 0 ? null : cats.get(i - 1);
            int y = sy + i * 20;
            boolean sel = c == category;
            boolean hov = mouseX >= sx && mouseX < sx + SIDE && mouseY >= y && mouseY < y + 18;
            g.fill(sx, y, sx + SIDE, y + 18, sel ? Draw.alpha(accent, 0x40) : hov ? 0x1AFFFFFF : 0x0AFFFFFF);
            if (sel) g.fill(sx, y, sx + 2, y + 18, Draw.alpha(accent, 0xFF));
            String name = c == null ? Component.translatable("archud.rules.all_cats").getString() : I18n.get(c.getDescriptionId());
            int total = 0, mod = 0;
            for (Entry e : all) {
                if (c != null && e.key.getCategory() != c) continue;
                total++;
                if (changed(e)) mod++;
            }
            String count = mod > 0 ? mod + "/" + total : Integer.toString(total);
            int cw = Math.round(font.width(count) * 0.65F);
            String n = font.width(name) > SIDE - cw - 14 ? font.plainSubstrByWidth(name, SIDE - cw - 18) + "…" : name;
            g.drawString(font, n, sx + 6, y + 5, sel ? 0xFFFFFFFF : Draw.TEXT, true);
            Draw.small(g, font, count, sx + SIDE - 5 - cw, y + 6, mod > 0 ? Draw.alpha(accent, 0xFF) : Draw.TEXT_DIM, 0.65F);
        }

        // карточки
        int lx = listX(), lw = listW(), top = listTop(), bottom = listBottom();
        Draw.panel(g, lx - 2, top - 2, lw + 4, bottom - top + 4, 0x60080B10, 0x25FFFFFF);
        scroll = Mth.clamp(scroll, 0, maxScroll());
        g.enableScissor(lx, top, lx + lw, bottom);
        int y = top - (int) scroll;
        for (Entry e : shown) {
            if (y + CARD >= top && y <= bottom) renderCard(g, e, lx, y, lw, mouseX, mouseY, accent, top, bottom);
            y += CARD;
        }
        g.disableScissor();
        if (shown.isEmpty()) g.drawCenteredString(font, Component.translatable("archud.rules.empty"), lx + lw / 2, top + 30, Draw.TEXT_DIM);
        if (maxScroll() > 0) {
            int track = bottom - top;
            int thumb = Math.max(20, (int) (track * track / (double) (shown.size() * CARD)));
            int ty = top + (int) ((track - thumb) * (scroll / maxScroll()));
            g.fill(lx + lw - 2, ty, lx + lw, ty + thumb, Draw.alpha(accent, 0xA0));
        }
        long changedCount = all.stream().filter(this::changed).count();
        String info = Component.translatable("archud.rules.changed", changedCount).getString();
        Draw.small(g, font, info, px + pw - 10 - font.width(info) * 0.7F, py + 9, changedCount > 0 ? Draw.alpha(accent, 0xFF) : Draw.TEXT_DIM, 0.7F);
        super.render(g, mouseX, mouseY, partialTick);
        if (editing != null) numberEdit.render(g, mouseX, mouseY, partialTick);
    }

    private void renderCard(GuiGraphics g, Entry e, int x, int y, int w, int mouseX, int mouseY, int accent, int top, int bottom) {
        boolean hov = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + CARD - 2, bottom);
        boolean ch = changed(e);
        g.fill(x, y, x + w, y + CARD - 2, hov ? 0x16FFFFFF : 0x0AFFFFFF);
        if (ch) g.fill(x, y, x + 2, y + CARD - 2, Draw.alpha(accent, 0xFF));
        int right = x + w - 8;
        int ctrlW = e.bool ? 30 : 104;
        int textMax = w - ctrlW - 40;
        String name = font.width(e.name) > textMax ? font.plainSubstrByWidth(e.name, textMax - 6) + "…" : e.name;
        g.drawString(font, name, x + 8, y + 5, ch ? 0xFFFFFFFF : Draw.TEXT, true);
        String sub = e.desc.isEmpty() ? e.id : e.desc;
        if (font.width(sub) * 0.65F > textMax) sub = font.plainSubstrByWidth(sub, (int) (textMax / 0.65F) - 8) + "…";
        Draw.small(g, font, sub, x + 8, y + 17, Draw.TEXT_DIM, 0.65F);
        if (!e.desc.isEmpty()) Draw.small(g, font, e.id, x + 8, y + 25, 0xFF5F6B78, 0.5F);

        if (ch) {
            // сброс к значению по умолчанию
            int rx = right - ctrlW - 16, ry = y + 9;
            boolean rh = mouseX >= rx && mouseX < rx + 14 && mouseY >= ry && mouseY < ry + 14;
            Draw.panel(g, rx, ry, 14, 14, rh ? Draw.PANEL_LIGHT : Draw.PANEL, Draw.alpha(accent, rh ? 0xFF : 0x80));
            g.drawCenteredString(font, "↺", rx + 7, ry + 3, Draw.alpha(accent, 0xFF));
            if (rh) setTooltipForNextRenderPass(Component.translatable("archud.rules.reset",
                    e.bool ? Component.translatable(boolOf(defaults, e) ? "options.on" : "options.off").getString()
                            : Integer.toString(intOf(defaults, e))));
        }
        if (e.bool) {
            // переключатель с плавным ползунком
            boolean on = boolOf(rules, e);
            float[] a = anim.computeIfAbsent(e, k -> new float[]{on ? 1 : 0});
            a[0] += ((on ? 1F : 0F) - a[0]) * 0.3F;
            int tx = right - 30, ty = y + 10;
            int bg = Draw.mix(0xFF2A3038, Draw.alpha(accent, 0xE0), a[0]);
            Draw.panel(g, tx, ty, 30, 12, bg, Draw.mix(0x40FFFFFF, Draw.alpha(accent, 0xFF), a[0]));
            int kx = tx + 1 + Math.round(17 * a[0]);
            g.fill(kx, ty + 1, kx + 12, ty + 11, 0xFFEAF2FA);
        } else {
            // − [число] +
            int bx = right - 104, by = y + 9;
            stepper(g, bx, by, "−", mouseX, mouseY, accent);
            stepper(g, right - 18, by, "+", mouseX, mouseY, accent);
            int vx = right - 84;
            boolean vh = mouseX >= vx && mouseX < vx + 64 && mouseY >= by && mouseY < by + 16;
            if (editing == e) {
                numberEdit.setX(vx + 6);
                numberEdit.setY(by + 1);
                numberEdit.setWidth(52);
            } else {
                Draw.panel(g, vx, by, 64, 16, vh ? Draw.PANEL_LIGHT : 0xD00A0E13, vh ? Draw.alpha(accent, 0xC0) : 0x40FFFFFF);
                String v = Integer.toString(intOf(rules, e));
                g.drawCenteredString(font, v, vx + 32, by + 4, ch ? 0xFFFFFFFF : Draw.TEXT);
            }
        }
    }

    private void stepper(GuiGraphics g, int x, int y, String s, int mouseX, int mouseY, int accent) {
        boolean h = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 16;
        Draw.panel(g, x, y, 18, 16, h ? Draw.PANEL_LIGHT : Draw.PANEL, h ? Draw.alpha(accent, 0xFF) : Draw.alpha(accent, 0x60));
        g.drawCenteredString(font, s, x + 9, y + 4, h ? 0xFFFFFFFF : Draw.TEXT);
    }

    @Override
    public void tick() {
        if (numberEdit != null && editing != null) numberEdit.tick();
        // в режиме «только изменённые» сброшенное правило пропадает из списка
        if (onlyChanged && editing == null && shown.stream().anyMatch(e -> !changed(e))) refresh();
    }
}
