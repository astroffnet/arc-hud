package com.arc4io.archud.client.recipes;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.screen.ArcButton;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Просмотр рецептов (как JEI/REI/EMI): «как сделать» и «где используется» для предмета.
 * Слева — вкладки категорий (верстак, печи, кузнечный стол, типы рецептов модов, топливо), справа — карточки
 * рецептов с перебором вариантов ингредиентов (теги). ЛКМ по предмету — его рецепты, ПКМ — применение,
 * Backspace — назад, Esc — вернуться в игру/инвентарь.
 */
public class ArcRecipeScreen extends Screen {
    private record State(ItemStack focus, boolean uses, int category, int page, Map<ResourceLocation, List<RecipeIndex.Entry>> data) {}

    private final Screen parent;
    private final Deque<State> history = new ArrayDeque<>();
    private ItemStack focus;
    private boolean uses;
    private Map<ResourceLocation, List<RecipeIndex.Entry>> data = Map.of();
    private List<ResourceLocation> cats = List.of();
    private int category, page, tabScroll;
    private final List<List<Placed>> pages = new ArrayList<>();
    private int x0, y0, w, h;
    private ItemStack hoverStack = ItemStack.EMPTY;
    private final ItemBatch batch = new ItemBatch();
    private final List<Object[]> decorations = new ArrayList<>();
    private ResourceLocation hoverCat;
    private List<Component> hoverTip;
    private static final int TOP = 64; // заголовок + вкладки категорий + строка категории
    private ArcButton modeBtn, backBtn, prevBtn, nextBtn;

    private ArcRecipeScreen(Screen parent, ItemStack focus, boolean uses) {
        super(Component.translatable("archud.recipes.title"));
        this.parent = parent;
        this.focus = focus.copyWithCount(1);
        this.uses = uses;
        if (!focus.isEmpty()) load();
    }

    /** Все рецепты указанных категорий (кнопка «рецепты» в GUI мода, как showTypes у JEI). */
    public static void openTypes(Screen parent, List<ResourceLocation> types) {
        Map<ResourceLocation, List<RecipeIndex.Entry>> d = new java.util.LinkedHashMap<>();
        for (ResourceLocation t : types) {
            List<RecipeIndex.Entry> l = RecipeIndex.entriesOf(t);
            if (!l.isEmpty()) d.put(t, new ArrayList<>(l));
        }
        if (d.isEmpty()) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof ArcRecipeScreen rs) {
            rs.history.push(new State(rs.focus, rs.uses, rs.category, rs.page, rs.data));
            rs.focus = ItemStack.EMPTY;
            rs.data = d;
            rs.cats = new ArrayList<>(d.keySet());
            rs.category = 0;
            rs.page = 0;
            rs.rebuildWidgets();
            return;
        }
        ArcRecipeScreen s = new ArcRecipeScreen(parent, ItemStack.EMPTY, false);
        s.data = d;
        s.cats = new ArrayList<>(d.keySet());
        s.category = 0;
        mc.setScreen(s);
    }

    /** Открыть рецепты/применение; если ничего нет — false (ничего не открывается). */
    public static boolean open(Screen parent, ItemStack stack, boolean uses) {
        if (stack == null || stack.isEmpty()) return false;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof ArcRecipeScreen rs) {
            rs.navigate(stack, uses);
            return true;
        }
        Map<ResourceLocation, List<RecipeIndex.Entry>> d = uses ? RecipeIndex.usesOf(stack) : RecipeIndex.recipesFor(stack);
        if (d.isEmpty()) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable(uses ? "archud.recipes.no_uses" : "archud.recipes.none", stack.getHoverName()), true);
            return false;
        }
        mc.setScreen(new ArcRecipeScreen(parent, stack, uses));
        return true;
    }

    /** Переключиться на категорию (если она есть среди открытых). */
    public boolean selectCategory(ResourceLocation type) {
        int i = cats.indexOf(type);
        if (i < 0) return false;
        category = i;
        page = 0;
        paginate();
        return true;
    }

    private void navigate(ItemStack stack, boolean u) {
        Map<ResourceLocation, List<RecipeIndex.Entry>> d = u ? RecipeIndex.usesOf(stack) : RecipeIndex.recipesFor(stack);
        if (d.isEmpty()) return;
        history.push(new State(focus, uses, category, page, data));
        focus = stack.copyWithCount(1);
        uses = u;
        category = 0;
        page = 0;
        tabScroll = 0;
        load();
        rebuildWidgets();
    }

    private void load() {
        data = uses ? RecipeIndex.usesOf(focus) : RecipeIndex.recipesFor(focus);
        cats = new ArrayList<>(data.keySet());
        category = Math.max(0, Math.min(category, cats.size() - 1));
    }

    // ------------------------------------------------------------------ раскладка карточки

    private static int cols(RecipeIndex.Entry e) {
        int n = e.inputs().size();
        if (e.gridW() > 0) return e.gridW();
        if (e.type().getPath().equals("crafting")) return Math.min(3, Math.max(1, n));
        if (n <= 1) return 1;
        if (n <= 4) return 2;
        if (n <= 9) return 3;
        return Math.min(6, (n + 2) / 3);
    }

    private static int cardH(RecipeIndex.Entry e) {
        if (e.card() != null) return e.card().height() + 10;
        int c = cols(e);
        int rows = Math.max(1, (e.inputs().size() + c - 1) / c);
        int outRows = Math.max(1, Math.min(3, e.outputs().size()));
        return Math.max(rows, outRows) * 18 + 10 + (e.note().isEmpty() ? 0 : 9);
    }

    /** Карточка на странице: позиция и размер относительно области карточек. */
    private record Placed(RecipeIndex.Entry e, int x, int y, int w, int h) {}

    private static int naturalW(RecipeIndex.Entry e, net.minecraft.client.gui.Font font) {
        if (e.card() != null) return e.card().width() + 10;
        int n = e.outputs().size();
        int outW = n == 0 ? (e.type().equals(RecipeIndex.FUEL) ? 14 : 0) : n == 1 ? 22 : ((n + 2) / 3) * 20;
        int body = 6 + cols(e) * 18 + 6 + 24 + outW + 16;
        int note = e.note().isEmpty() ? 0 : Math.round(font.width(e.note()) * 0.6F) + 12;
        return Math.max(body, note);
    }

    /** Карточки сеткой: ширина колонки — по самому широкому рецепту категории, остаток растягивается. */
    private void paginate() {
        pages.clear();
        if (cats.isEmpty()) return;
        List<RecipeIndex.Entry> list = data.get(cats.get(category));
        int areaW = w - 36, avail = h - TOP - 22;
        int nat = 110;
        for (RecipeIndex.Entry e : list) nat = Math.max(nat, naturalW(e, font));
        nat = Math.min(nat, areaW);
        int columns = Math.max(1, Math.min(4, (areaW + 4) / (nat + 4)));
        int cw = (areaW - (columns - 1) * 4) / columns;
        List<Placed> cur = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < list.size(); i += columns) {
            int rowH = 0;
            for (int k = i; k < Math.min(list.size(), i + columns); k++) rowH = Math.max(rowH, cardH(list.get(k)));
            if (!cur.isEmpty() && y + rowH > avail) {
                pages.add(cur);
                cur = new ArrayList<>();
                y = 0;
            }
            for (int k = i; k < Math.min(list.size(), i + columns); k++) cur.add(new Placed(list.get(k), (k - i) * (cw + 4), y, cw, rowH));
            y += rowH + 4;
        }
        if (!cur.isEmpty()) pages.add(cur);
        page = Math.max(0, Math.min(page, pages.size() - 1));
    }

    @Override
    protected void init() {
        w = Math.min(width - 16, 460);
        // справа оставляем место под список предметов
        int free = width - w - 16;
        x0 = free >= 110 ? 8 + Math.max(0, (width - 16 - w - 130) / 2) : (width - w) / 2;
        h = height - 16;
        y0 = 8;
        backBtn = addRenderableWidget(new ArcButton(x0 + 4, y0 + 4, 16, 16, Component.literal("◀"), b -> back()));
        backBtn.active = !history.isEmpty();
        modeBtn = addRenderableWidget(new ArcButton(x0 + w - 94, y0 + 4, 90, 16,
                Component.translatable(uses ? "archud.recipes.show_recipes" : "archud.recipes.show_uses"), b -> navigateMode()));
        modeBtn.visible = !focus.isEmpty();
        prevBtn = addRenderableWidget(new ArcButton(x0 + 32, y0 + h - 19, 18, 14, Component.literal("‹"), b -> {
            if (page > 0) page--;
        }));
        nextBtn = addRenderableWidget(new ArcButton(x0 + w - 22, y0 + h - 19, 18, 14, Component.literal("›"), b -> {
            if (page < pages.size() - 1) page++;
        }));
        paginate();
        RecipeOverlay.attach(this, x0 + w);
    }

    private void navigateMode() {
        Map<ResourceLocation, List<RecipeIndex.Entry>> d = !uses ? RecipeIndex.usesOf(focus) : RecipeIndex.recipesFor(focus);
        if (d.isEmpty()) {
            modeBtn.setMessage(Component.translatable("archud.recipes.empty"));
            return;
        }
        navigate(focus, !uses);
    }

    private void back() {
        if (history.isEmpty()) return;
        State s = history.pop();
        focus = s.focus();
        uses = s.uses();
        category = s.category();
        page = s.page();
        data = s.data();
        cats = new ArrayList<>(data.keySet());
        category = Math.max(0, Math.min(category, cats.size() - 1));
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
        int accent = Theme.accent();
        hoverStack = ItemStack.EMPTY;
        hoverCat = null;
        hoverTip = null;
        Draw.panel(g, x0, y0, w, h, 0xF00E1218, Draw.alpha(accent, 0x90));
        g.fill(x0 + 3, y0, x0 + w - 3, y0 + 1, Draw.alpha(accent, 0xE0));
        // заголовок
        g.renderItem(focus, x0 + 24, y0 + 4);
        String head = Component.translatable(focus.isEmpty() ? "archud.recipes.title" : uses ? "archud.recipes.uses_of" : "archud.recipes.recipes_of").getString();
        Draw.small(g, font, head, x0 + 43, y0 + 4, Draw.TEXT_DIM, 0.6F);
        String nm = font.plainSubstrByWidth(focus.isEmpty() && !cats.isEmpty() ? categoryName(cats.get(category)).getString()
                : focus.getHoverName().getString(), w - 43 - 100);
        g.drawString(font, nm, x0 + 43, y0 + 11, 0xFFFFFFFF, true);
        if (!focus.isEmpty() && mouseX >= x0 + 24 && mouseX < x0 + 40 && mouseY >= y0 + 4 && mouseY < y0 + 20) hoverStack = focus;
        g.fill(x0 + 4, y0 + 23, x0 + w - 4, y0 + 24, Draw.alpha(accent, 0x40));

        renderTabs(g, mouseX, mouseY, accent);
        batch.clear();
        decorations.clear();
        if (!cats.isEmpty() && !pages.isEmpty()) {
            ResourceLocation c = cats.get(category);
            // строка категории (как у JEI): название и число рецептов
            String cname = categoryName(c).getString();
            String cnt = String.valueOf(data.get(c).size());
            int cy = y0 + 52;
            g.drawCenteredString(font, font.plainSubstrByWidth(cname, w - 80), x0 + 32 + (w - 36) / 2, cy, 0xFFFFFFFF);
            Draw.small(g, font, cnt, x0 + w - 6 - font.width(cnt) * 0.6F, cy + 2, Draw.TEXT_DIM, 0.6F);
            renderCatalysts(g, c, mouseX, mouseY, accent);
            for (Placed pl : pages.get(page)) {
                renderCard(g, pl.e(), x0 + 32 + pl.x(), y0 + TOP + pl.y(), pl.w(), pl.h(), mouseX, mouseY, accent);
            }
            String pg = (page + 1) + " / " + pages.size();
            g.drawCenteredString(font, pg, x0 + 32 + (w - 36) / 2, y0 + h - 16, 0xFFCFD8DC);
        }
        batch.draw(g, 100F);
        for (Object[] d : decorations) g.renderItemDecorations(font, (ItemStack) d[0], (Integer) d[1], (Integer) d[2]);
        prevBtn.active = page > 0;
        nextBtn.active = page < pages.size() - 1;
        super.render(g, mouseX, mouseY, pt);
        RecipeOverlay.render(this, g, mouseX, mouseY);
        if (!hoverStack.isEmpty()) {
            List<Component> tip = new ArrayList<>(getTooltipFromItem(minecraft, hoverStack));
            tip.add(Component.translatable("archud.recipes.hint").withStyle(s -> s.withColor(0x6A7380)));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        } else if (hoverTip != null && !hoverTip.isEmpty()) {
            g.renderComponentTooltip(font, hoverTip, mouseX, mouseY);
        } else if (hoverCat != null) {
            g.renderTooltip(font, Component.literal(categoryName(hoverCat).getString() + " (" + data.get(hoverCat).size() + ")"), mouseX, mouseY);
        }
    }

    private int tabsVisible() {
        return Math.max(1, (w - 8 - 24) / 24);
    }

    private int tabX(int i) {
        return x0 + 16 + (i - tabScroll) * 24;
    }

    /** Вкладки категорий сверху, как у JEI; если не влезают — стрелки по краям. */
    private void renderTabs(GuiGraphics g, int mx, int my, int accent) {
        int vis = tabsVisible();
        tabScroll = Math.max(0, Math.min(tabScroll, cats.size() - vis));
        int ty = y0 + 26;
        g.fill(x0 + 4, y0 + 48, x0 + w - 4, y0 + 49, Draw.alpha(accent, 0x40));
        for (int i = tabScroll; i < Math.min(cats.size(), tabScroll + vis); i++) {
            int tx = tabX(i);
            boolean sel = i == category;
            boolean hov = mx >= tx && mx < tx + 22 && my >= ty && my < ty + 21;
            Draw.panel(g, tx, ty, 22, sel ? 22 : 21, sel ? Draw.alpha(accent, 0x50) : hov ? 0x30FFFFFF : 0xC0080B10,
                    sel ? Draw.alpha(accent, 0xE0) : 0x40FFFFFF);
            drawCategoryIcon(g, cats.get(i), tx + 3, ty + 3, 1F);
            if (hov) hoverCat = cats.get(i);
        }
        if (cats.size() > vis) {
            boolean l = tabScroll > 0, r = tabScroll + vis < cats.size();
            g.drawString(font, "‹", x0 + 6, ty + 6, l ? 0xFFFFFFFF : 0x40FFFFFF, false);
            g.drawString(font, "›", x0 + w - 10, ty + 6, r ? 0xFFFFFFFF : 0x40FFFFFF, false);
        }
    }

    private void drawCategoryIcon(GuiGraphics g, ResourceLocation cat, int x, int y, float scale) {
        var drawer = RecipeIndex.ICON_DRAWERS.get(cat);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1F);
        if (drawer != null) {
            try {
                drawer.accept(g, new int[]{0, 0});
            } catch (Throwable t) {
                g.renderItem(RecipeIndex.icon(cat), 0, 0);
            }
        } else g.renderItem(RecipeIndex.icon(cat), 0, 0);
        g.pose().popPose();
    }

    /** Станки категории (катализаторы JEI) — колонкой слева от рецептов. */
    private void renderCatalysts(GuiGraphics g, ResourceLocation c, int mx, int my, int accent) {
        List<ItemStack> list = RecipeIndex.catalysts(c);
        int max = Math.max(0, (h - TOP - 22) / 20);
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            int sx = x0 + 6, sy = y0 + TOP + i * 20;
            slot(g, List.of(list.get(i)), sx, sy, mx, my, Draw.alpha(accent, 0x70), false);
        }
    }

    private void slot(GuiGraphics g, List<ItemStack> alts, int x, int y, int mx, int my, int border, boolean big) {
        int s = big ? 22 : 18, o = big ? 3 : 1;
        Draw.panel(g, x, y, s, s, 0xE0070A0E, border);
        if (alts.isEmpty()) return;
        ItemStack st = alts.get((int) ((Util.getMillis() / 1000) % alts.size()));
        batch.add(st, x + o, y + o);
        if (st.getCount() > 1) decorations.add(new Object[]{st, x + o, y + o});
        if (mx >= x && mx < x + s && my >= y && my < y + s) {
            g.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0x40FFFFFF);
            hoverStack = st;
        }
    }

    private void renderCard(GuiGraphics g, RecipeIndex.Entry e, int x, int y, int cw, int ch, int mx, int my, int accent) {
        Draw.panel(g, x, y, cw, ch, 0xC0101419, 0x30FFFFFF);
        if (e.card() != null) {
            RecipeCard card = e.card();
            int ox = x + (cw - card.width()) / 2, oy = y + 5;
            int lx = mx - ox, ly = my - oy;
            boolean inside = lx >= 0 && ly >= 0 && lx < card.width() && ly < card.height();
            g.pose().pushPose();
            g.pose().translate(ox, oy, 0);
            try {
                card.render(g, inside ? lx : -1000, inside ? ly : -1000);
            } catch (Throwable ignored) {
            }
            g.pose().popPose();
            if (inside) {
                try {
                    ItemStack st = card.stackAt(lx, ly);
                    if (!st.isEmpty()) hoverStack = st;
                    else hoverTip = card.tooltip(lx, ly);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        int c = cols(e);
        int n = e.inputs().size();
        int rows = Math.max(1, (n + c - 1) / c);
        int outRows = Math.max(1, Math.min(3, e.outputs().size()));
        int body = Math.max(rows, outRows) * 18;
        int gx = x + 6, gy = y + 5 + (body - rows * 18) / 2;
        for (int i = 0; i < n; i++) {
            slot(g, e.inputs().get(i), gx + (i % c) * 18, gy + (i / c) * 18, mx, my, 0x50FFFFFF, false);
        }
        int ax = gx + c * 18 + 6, ay = y + 5 + body / 2;
        if (!e.outputs().isEmpty()) {
            // стрелка
            g.fill(ax, ay - 1, ax + 16, ay + 1, Draw.alpha(accent, 0xC0));
            for (int k = 0; k < 4; k++) g.fill(ax + 12 + k, ay - 4 + k, ax + 13 + k, ay + 5 - k, Draw.alpha(accent, 0xC0));
            int ox = ax + 24, oy = y + 5 + (body - outRows * 18) / 2;
            for (int i = 0; i < e.outputs().size(); i++) {
                boolean first = i == 0;
                int col = i / 3, row = i % 3;
                if (first && e.outputs().size() == 1) {
                    slot(g, e.outputs().get(0), ox, y + 5 + body / 2 - 11, mx, my, Draw.alpha(accent, 0xA0), true);
                } else {
                    slot(g, e.outputs().get(i), ox + col * 20, oy + row * 18, mx, my, Draw.alpha(accent, 0x80), false);
                }
            }
        } else if (e.type().equals(RecipeIndex.FUEL)) {
            // огонь
            int fx = ax + 4, fy = ay - 6;
            g.fill(fx + 3, fy, fx + 5, fy + 12, 0xFFFF8A3D);
            g.fill(fx + 1, fy + 4, fx + 7, fy + 12, 0xFFFFB347);
            g.fill(fx + 2, fy + 8, fx + 6, fy + 12, 0xFFFFE082);
        }
        // справа — станок и id рецепта
        ItemStack cat = e.catalyst();
        if (cat != null && !cat.isEmpty() && cw > 150 && RecipeIndex.catalysts(e.type()).isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + cw - 14, y + 3, 0);
            g.pose().scale(0.625F, 0.625F, 1F);
            g.renderItem(cat, 0, 0);
            g.pose().popPose();
            if (mx >= x + cw - 14 && mx < x + cw - 4 && my >= y + 3 && my < y + 13) hoverStack = cat;
        }
        if (!e.note().isEmpty()) Draw.small(g, font, e.note(), x + 6, y + ch - 9, Draw.TEXT_DIM, 0.6F);
        if (e.recipe() != null && cw > 200 && !e.id().getNamespace().equals("minecraft")) {
            String id = e.id().getNamespace();
            float sw = font.width(id) * 0.5F;
            if (sw < cw - 120) Draw.small(g, font, id, x + cw - 4 - sw, y + ch - 7, 0x50FFFFFF, 0.5F);
        }
    }

    public static Component categoryName(ResourceLocation type) {
        Component title = RecipeIndex.TITLES.get(type);
        if (title != null) return title;
        String key = "archud.recipes.type." + type.getNamespace() + "." + type.getPath();
        Component c = Component.translatable(key);
        if (!c.getString().equals(key)) return c;
        // имя мода + тип: «create: mixing» → «Mixing (create)»
        String p = type.getPath().replace('_', ' ');
        p = Character.toUpperCase(p.charAt(0)) + p.substring(1);
        return Component.literal(type.getNamespace().equals("minecraft") ? p : p + " (" + type.getNamespace() + ")");
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int vis = tabsVisible();
        int ty = y0 + 26;
        if (cats.size() > vis && my >= ty && my < ty + 21) {
            if (mx >= x0 + 4 && mx < x0 + 14) {
                tabScroll = Math.max(0, tabScroll - vis);
                return true;
            }
            if (mx >= x0 + w - 14 && mx < x0 + w - 4) {
                tabScroll = Math.min(cats.size() - vis, tabScroll + vis);
                return true;
            }
        }
        for (int i = tabScroll; i < Math.min(cats.size(), tabScroll + vis); i++) {
            int tx = tabX(i);
            if (mx >= tx && mx < tx + 22 && my >= ty && my < ty + 21) {
                category = i;
                page = 0;
                paginate();
                return true;
            }
        }
        if (!hoverStack.isEmpty() && (button == 0 || button == 1)) {
            ItemStack target = hoverStack.copy();
            navigate(target, button == 1);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (my >= y0 + 24 && my < y0 + 48) {
            tabScroll -= (int) Math.signum(delta);
            return true;
        }
        if (delta < 0 && page < pages.size() - 1) page++;
        else if (delta > 0 && page > 0) page--;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_BACKSPACE && !history.isEmpty()) {
            back();
            return true;
        }
        if (com.arc4io.archud.client.ArcKeys.RECIPES.matches(key, scan) && !hoverStack.isEmpty()) {
            navigate(hoverStack.copy(), (mods & GLFW.GLFW_MOD_SHIFT) != 0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT && page > 0) page--;
        else if (key == GLFW.GLFW_KEY_RIGHT && page < pages.size() - 1) page++;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
