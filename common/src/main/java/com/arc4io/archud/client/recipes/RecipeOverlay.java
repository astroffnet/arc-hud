package com.arc4io.archud.client.recipes;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcKeys;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.ContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Список всех предметов справа от окна (инвентарь, сундуки, печи, верстаки модов…) с поиском — как у JEI.
 * ЛКМ по предмету — рецепты, ПКМ — где используется. Клавиша «Рецепты» (R) над любым предметом в окне —
 * рецепты, Shift+R — применение. Поиск: по названию, «@мод» — по моду, «#» — по подсказке предмета.
 * Если установлен JEI/REI/EMI, список не показывается (их список и так есть), клавиша R работает.
 */
public final class RecipeOverlay {
    private static final int CELL = 18;
    private static final ItemBatch BATCH = new ItemBatch();
    private static Screen owner;
    private static int x, y, w, h, cols, rows;
    private static int pageStart;
    private static String query = "";
    private static boolean searchFocused;
    private static List<ItemStack> filtered = List.of();
    private static String filteredFor;
    private static ItemStack hovered = ItemStack.EMPTY;
    /** Нажатие на предмет списка: отпустили на месте — рецепты, потащили — перетаскивание в слот-фильтр мода (как в JEI). */
    private static ItemStack dragStack = ItemStack.EMPTY;
    private static int dragButton;
    private static double dragX, dragY;
    private static boolean dragging;
    private static Screen dragScreen;
    private static List<int[]> dragAreas = List.of();
    private static Boolean otherViewer;

    private RecipeOverlay() {}

    public static boolean enabled() {
        return ArcConfig.get().recipeViewer;
    }

    private static boolean otherViewer() {
        if (otherViewer == null) {
            otherViewer = com.arc4io.archud.client.jei.JeiGate.realJei() || ArcHud.platform().isModLoaded("roughlyenoughitems")
                    || ArcHud.platform().isModLoaded("emi");
        }
        return otherViewer;
    }

    private static boolean listFor(Screen s) {
        if (!enabled() || otherViewer() || !ArcConfig.get().recipeItemList) return false;
        if (s instanceof ArcRecipeScreen) return true;
        return s instanceof AbstractContainerScreen<?> && !(s instanceof CreativeModeInventoryScreen);
    }

    /** Правая граница окна + все кнопки, торчащие справа от него. */
    private static int rightEdge(Screen s, int guiRight, int guiTop, int guiBottom) {
        int r = guiRight;
        for (GuiEventListener l : s.children()) {
            if (l instanceof AbstractWidget wd && wd.visible && wd.getX() >= guiRight - 4 && wd.getX() < guiRight + 60) {
                r = Math.max(r, wd.getX() + wd.getWidth());
            }
        }
        // панели, которые мод объявил для JEI (дополнительные области GUI)
        for (int[] a : com.arc4io.archud.client.jei.JeiGate.extraAreas(s)) {
            if (a[0] >= guiRight - 4 && a[0] < guiRight + 120) r = Math.max(r, a[0] + a[2]);
        }
        return r;
    }

    /** Разметить список для экрана (вызывается при открытии/изменении размера). */
    public static void attach(Screen s, int guiRight) {
        owner = s;
        int left = rightEdge(s, guiRight, 0, s.height) + 6;
        x = left;
        y = 6;
        w = s.width - left - 4;
        h = s.height - 6 - 4;
        cols = Math.max(0, Math.min(12, (w - 4) / CELL));
        rows = Math.max(0, (h - 20 - 14) / CELL);
        if (cols < 3 || rows < 3) {
            cols = 0;
            rows = 0;
        }
        w = cols * CELL + 4;
    }

    private static void ensureAttached(Screen s) {
        if (s instanceof AbstractContainerScreen<?> cs) {
            ContainerScreenAccessor a = (ContainerScreenAccessor) cs;
            attach(s, a.archud$left() + a.archud$width());
        } else if (owner != s) {
            // свои окна (просмотр рецептов) размечают список сами в init(); остальным — без списка
            owner = s;
            cols = rows = 0;
        }
    }

    private static List<ItemStack> filteredSource;

    // ------------------------------------------------------------------ для API JEI (фильтр и список ингредиентов)

    public static String query() {
        return query;
    }

    public static void setQuery(String q) {
        query = q == null ? "" : q;
    }

    public static List<ItemStack> filteredItems() {
        return filtered();
    }

    public static ItemStack hoveredItem() {
        return hovered;
    }

    public static boolean searchFocused() {
        return searchFocused;
    }

    public static boolean shown() {
        Screen s = Minecraft.getInstance().screen;
        return s != null && listFor(s) && cols > 0;
    }

    private static List<ItemStack> filtered() {
        List<ItemStack> all = RecipeIndex.items();
        if (query.equals(filteredFor) && all == filteredSource) return filtered;
        filteredFor = query;
        filteredSource = all;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            filtered = all;
        } else {
            List<ItemStack> out = new ArrayList<>();
            Minecraft mc = Minecraft.getInstance();
            for (ItemStack s : all) {
                boolean ok = true;
                for (String part : q.split("\\s+")) {
                    if (part.startsWith("@")) {
                        String ns = BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace();
                        ok &= ns.contains(part.substring(1));
                    } else if (part.startsWith("#")) {
                        boolean any = false;
                        for (Component c : s.getTooltipLines(mc.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL)) {
                            any |= c.getString().toLowerCase(Locale.ROOT).contains(part.substring(1));
                        }
                        ok &= any;
                    } else {
                        ok &= s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(part)
                                || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains(part);
                    }
                    if (!ok) break;
                }
                if (ok) out.add(s);
            }
            filtered = out;
        }
        pageStart = 0;
        return filtered;
    }

    public static void render(Screen s, GuiGraphics g, int mx, int my) {
        hovered = ItemStack.EMPTY;
        if (!listFor(s)) return;
        ensureAttached(s);
        if (cols == 0) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int accent = Theme.accent();
        List<ItemStack> list = filtered();
        int perPage = cols * rows;
        if (pageStart >= list.size()) pageStart = 0;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        Draw.panel(g, x - 2, y, w + 2, h, 0xF00B0F14, Draw.alpha(accent, 0x50));
        // страница
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        String pg = (pageStart / perPage + 1) + "/" + pages;
        g.drawCenteredString(font, pg, x + w / 2, y + 4, 0xFFCFD8DC);
        boolean hovPrev = my >= y + 2 && my < y + 14 && mx >= x && mx < x + 14;
        boolean hovNext = my >= y + 2 && my < y + 14 && mx >= x + w - 16 && mx < x + w - 2;
        g.drawString(font, "‹", x + 4, y + 4, hovPrev ? 0xFFFFFFFF : 0xFF8C9AAB);
        g.drawString(font, "›", x + w - 10, y + 4, hovNext ? 0xFFFFFFFF : 0xFF8C9AAB);
        int gy = y + 16;
        BATCH.clear();
        for (int i = 0; i < perPage && pageStart + i < list.size(); i++) {
            ItemStack st = list.get(pageStart + i);
            int ix = x + 2 + (i % cols) * CELL, iy = gy + (i / cols) * CELL;
            if (mx >= ix && mx < ix + CELL && my >= iy && my < iy + CELL) {
                g.fill(ix, iy, ix + CELL, iy + CELL, 0x50FFFFFF);
                hovered = st;
            }
            BATCH.add(st, ix + 1, iy + 1);
        }
        BATCH.draw(g, 100F);
        // поиск
        int sy = y + h - 16;
        Draw.panel(g, x, sy, w - 2, 14, 0xE0070A0E, searchFocused ? Draw.alpha(accent, 0xE0) : 0x50FFFFFF);
        String shown = query.isEmpty() && !searchFocused ? Component.translatable("archud.recipes.search").getString() : query;
        int col = query.isEmpty() && !searchFocused ? 0xFF6A7380 : 0xFFFFFFFF;
        String vis = font.plainSubstrByWidth(new StringBuilder(shown).reverse().toString(), w - 12);
        vis = new StringBuilder(vis).reverse().toString();
        g.drawString(font, vis, x + 4, sy + 3, col, false);
        if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = x + 4 + font.width(vis);
            g.fill(cx, sy + 2, cx + 1, sy + 12, 0xFFFFFFFF);
        }
        g.pose().popPose();
        if (!hovered.isEmpty()) {
            List<Component> tip = new ArrayList<>(Screen.getTooltipFromItem(mc, hovered));
            tip.add(Component.literal(BuiltInRegistries.ITEM.getKey(hovered.getItem()).getNamespace()).withStyle(st -> st.withColor(0x5A86FF).withItalic(true)));
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.renderComponentTooltip(font, tip, mx, my);
            g.pose().popPose();
        }
        renderDrag(s, g, mx, my);
    }

    private static boolean inside(double mx, double my) {
        return cols > 0 && mx >= x - 2 && mx < x + w && my >= y && my < y + h;
    }

    /** В окне сейчас печатают (поиск креатива, переименование в наковальне, поля модов) — R/U не перехватываем. */
    private static final java.util.Map<Class<?>, List<java.lang.reflect.Field>> EDIT_FIELDS = new java.util.HashMap<>();

    private static boolean typing(Screen s) {
        for (net.minecraft.client.gui.components.events.GuiEventListener f = s.getFocused(); f != null; ) {
            if (f instanceof net.minecraft.client.gui.components.EditBox eb) return eb.canConsumeInput();
            if (f instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c && c.getFocused() != f) f = c.getFocused();
            else break;
        }
        // поля ввода, которые окно держит само (поиск креатива, поля модов вне списка детей)
        List<java.lang.reflect.Field> fields = EDIT_FIELDS.computeIfAbsent(s.getClass(), k -> {
            List<java.lang.reflect.Field> out = new ArrayList<>();
            for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field fl : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(fl.getModifiers()) || !net.minecraft.client.gui.components.EditBox.class.isAssignableFrom(fl.getType())) continue;
                    try {
                        fl.setAccessible(true);
                        out.add(fl);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return out;
        });
        for (java.lang.reflect.Field fl : fields) {
            try {
                if (fl.get(s) instanceof net.minecraft.client.gui.components.EditBox eb && eb.canConsumeInput()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Предмет под курсором: в нашем списке, в ячейке окна или в «ингредиенте» GUI мода (баки, панели — через JEI). */
    public static ItemStack hoveredAnywhere(Screen s) {
        if (!hovered.isEmpty()) return hovered;
        if (s instanceof AbstractContainerScreen<?> cs) {
            Slot sl = ((ContainerScreenAccessor) cs).archud$hovered();
            if (sl != null && sl.hasItem()) return sl.getItem();
        }
        Minecraft mc = Minecraft.getInstance();
        double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        ItemStack mod = com.arc4io.archud.client.jei.JeiGate.ingredientUnder(s, mx, my);
        if (!mod.isEmpty()) return mod;
        ItemStack tip = TooltipStack.last(s);
        return tip;
    }

    public static boolean click(Screen s, double mx, double my, int button) {
        if (!enabled()) return false;
        // «кликабельные области» GUI модов для JEI (стрелка прогресса машины → её рецепты)
        if (button == 0 && s instanceof AbstractContainerScreen<?> && !otherViewer() && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu.getCarried().isEmpty() && !inside(mx, my)
                && com.arc4io.archud.client.jei.JeiGate.clickArea(s, mx, my)) return true;
        if (!listFor(s)) return false;
        ensureAttached(s);
        if (cols == 0) return false;
        if (!inside(mx, my)) {
            searchFocused = false;
            return false;
        }
        int perPage = cols * rows;
        List<ItemStack> list = filtered();
        if (my >= y + 2 && my < y + 14) {
            if (mx < x + w / 2) pageStart = pageStart - perPage < 0 ? Math.max(0, ((list.size() - 1) / perPage) * perPage) : pageStart - perPage;
            else pageStart = pageStart + perPage >= list.size() ? 0 : pageStart + perPage;
            return true;
        }
        if (my >= y + h - 16) {
            searchFocused = true;
            if (button == 1) query = "";
            return true;
        }
        searchFocused = false;
        if (!hovered.isEmpty()) {
            if (Minecraft.getInstance().player != null && !Minecraft.getInstance().player.containerMenu.getCarried().isEmpty()) return true;
            dragStack = hovered.copy();
            dragButton = button;
            dragX = mx;
            dragY = my;
            dragging = false;
            dragScreen = s;
        }
        return true;
    }

    /** Кнопку мыши отпустили: открыть рецепты предмета или бросить его в слот-фильтр. */
    public static boolean release(Screen s, double mx, double my, int button) {
        if (dragStack.isEmpty()) return false;
        ItemStack st = dragStack;
        boolean wasDragging = dragging;
        dragStack = ItemStack.EMPTY;
        dragging = false;
        dragAreas = List.of();
        if (s != dragScreen) return false;
        if (wasDragging) {
            Object r = com.arc4io.archud.client.jei.JeiGate.call("ghostDrop", new Class<?>[]{Screen.class, ItemStack.class, double.class, double.class}, s, st, mx, my);
            return true;
        }
        ArcRecipeScreen.open(s, st, dragButton == 1);
        return true;
    }

    /** Нарисовать перетаскиваемый предмет и подсветить слоты, куда его можно бросить. */
    private static void renderDrag(Screen s, GuiGraphics g, int mx, int my) {
        if (dragStack.isEmpty() || s != dragScreen) return;
        if (!dragging) {
            if (Math.abs(mx - dragX) + Math.abs(my - dragY) < 5) return;
            if (!Boolean.TRUE.equals(com.arc4io.archud.client.jei.JeiGate.call("ghostSupported", new Class<?>[]{Screen.class}, s))) return;
            dragging = true;
            Object a = com.arc4io.archud.client.jei.JeiGate.call("ghostAreas", new Class<?>[]{Screen.class, ItemStack.class, boolean.class}, s, dragStack, true);
            @SuppressWarnings("unchecked")
            List<int[]> areas = a instanceof List<?> l ? (List<int[]>) l : List.of();
            dragAreas = areas;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        for (int[] r : dragAreas) {
            boolean over = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], over ? 0x8040FF60 : 0x4040FF60);
        }
        g.renderItem(dragStack, mx - 8, my - 8);
        g.pose().popPose();
    }

    public static boolean scroll(Screen s, double mx, double my, double delta) {
        if (!listFor(s)) return false;
        ensureAttached(s);
        if (!inside(mx, my)) return false;
        int perPage = cols * rows;
        List<ItemStack> list = filtered();
        if (delta < 0) pageStart = pageStart + perPage >= list.size() ? 0 : pageStart + perPage;
        else pageStart = pageStart - perPage < 0 ? Math.max(0, ((list.size() - 1) / perPage) * perPage) : pageStart - perPage;
        return true;
    }

    public static boolean key(Screen s, int key, int scan, int mods) {
        if (!enabled()) return false;
        if (searchFocused && owner == s) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
                searchFocused = false;
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_V && Screen.hasControlDown()) {
                query = (query + Minecraft.getInstance().keyboardHandler.getClipboard()).replace("\n", "");
                if (query.length() > 50) query = query.substring(0, 50);
                return true;
            }
            return key != GLFW.GLFW_KEY_TAB; // остальные клавиши не уходят в окно (E, цифры, Q)
        }
        if (s instanceof ArcRecipeScreen) return false;
        boolean r = ArcKeys.RECIPES.matches(key, scan), u = ArcKeys.USES.matches(key, scan);
        if ((r || u) && !typing(s)) {
            ItemStack st = hoveredAnywhere(s);
            if (!st.isEmpty()) {
                ArcRecipeScreen.open(s, st, u || (mods & GLFW.GLFW_MOD_SHIFT) != 0);
                return true;
            }
        }
        return false;
    }

    public static boolean typed(Screen s, char c, int mods) {
        if (!searchFocused || owner != s) return false;
        if (c >= ' ' && query.length() < 50) query += c;
        return true;
    }

    public static void onScreenClosed() {
        searchFocused = false;
    }
}
