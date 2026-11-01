package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.recipes.RecipeIndex;
import com.arc4io.archud.client.recipes.RecipeTransfer;
import com.arc4io.archud.mixin.SlotPosAccessor;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Новый верстак Arc HUD (написан с нуля, не перерисовка ванильного).
 * Слева — «Можно сделать»: всё, что собирается из того, что у вас есть (с числом наборов), ниже — то, чего
 * не хватает совсем чуть-чуть; поиск по названию. Клик раскладывает рецепт по сетке, Shift — сразу на максимум.
 * В центре — крупная сетка 3×3 с результатом, кнопки «Очистить» и «Всё в инвентарь», под ними инвентарь.
 */
public class ArcCraftingScreen extends AbstractContainerScreen<CraftingMenu> {
    private static final int LW = 150, GAP = 6, CW = 176, H = 200, CELL = 18;
    private static final int GRID_X = 14, GRID_Y = 24, STEP = 20;
    private static final int RES_X = 132, RES_Y = 44;
    private static final int INV_Y = 120;

    /** Рецепт, который можно собрать: запись индекса, сколько наборов, сколько входов не хватает. */
    private record Craftable(RecipeIndex.Entry entry, ItemStack output, int sets, int missing) {}

    private EditBox search;
    private List<Craftable> craftables = List.of();
    private int invHash = Integer.MIN_VALUE, scroll;
    private String lastQuery = "";
    private Craftable hovered;
    private ArcButton clearBtn, takeBtn;

    public ArcCraftingScreen(CraftingMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = LW + GAP + CW;
        imageHeight = H;
    }

    private int cx() {
        return leftPos + LW + GAP;
    }

    @Override
    protected void init() {
        ScreenFit.ensure(this, imageWidth + 8, imageHeight + 8);
        super.init();
        layoutSlots();
        String prev = search != null ? search.getValue() : "";
        search = addRenderableWidget(new EditBox(font, leftPos + 6, topPos + 20, LW - 12, 14, Component.translatable("archud.craft.search")));
        search.setHint(Component.translatable("archud.craft.search").withStyle(s -> s.withColor(0x6A7380)));
        search.setValue(prev);
        search.setResponder(v -> scroll = 0);
        int bx = cx() + 8, by = topPos + GRID_Y + 3 * STEP + 4;
        clearBtn = addRenderableWidget(new ArcButton(bx, by, 76, 14, Component.translatable("archud.craft.clear"), b -> clearGrid()));
        takeBtn = addRenderableWidget(new ArcButton(cx() + CW - 84, by, 76, 14, Component.translatable("archud.craft.take_all"),
                b -> click(menu.getSlot(0), 0, ClickType.QUICK_MOVE)));
        invHash = Integer.MIN_VALUE;
    }

    private static void move(Slot s, int x, int y) {
        ((SlotPosAccessor) s).archud$setX(x);
        ((SlotPosAccessor) s).archud$setY(y);
    }

    /** Свои места для слотов: крупная сетка, результат, инвентарь под ними. */
    private void layoutSlots() {
        int ox = LW + GAP;
        move(menu.getSlot(0), ox + RES_X + 4, RES_Y + 4);
        for (int i = 0; i < 9; i++) move(menu.getSlot(1 + i), ox + GRID_X + 1 + (i % 3) * STEP, GRID_Y + 1 + (i / 3) * STEP);
        for (int i = 0; i < 27; i++) move(menu.getSlot(10 + i), ox + 8 + (i % 9) * CELL, INV_Y + (i / 9) * CELL);
        for (int i = 0; i < 9; i++) move(menu.getSlot(37 + i), ox + 8 + i * CELL, INV_Y + 3 * CELL + 4);
    }

    private void click(Slot s, int button, ClickType type) {
        if (minecraft.gameMode == null || minecraft.player == null) return;
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, s.index, button, type, minecraft.player);
    }

    private void clearGrid() {
        for (int i = 1; i <= 9; i++) if (menu.getSlot(i).hasItem()) click(menu.getSlot(i), 0, ClickType.QUICK_MOVE);
    }

    // ------------------------------------------------------------------ «можно сделать»

    private int inventoryHash() {
        int h = 1;
        for (int i = 1; i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            h = h * 31 + (s.isEmpty() ? 0 : Item.getId(s.getItem()) * 97 + s.getCount());
        }
        return h * 31 + search.getValue().hashCode();
    }

    private void recompute() {
        Map<Item, Integer> have = new HashMap<>();
        for (int i = 1; i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) have.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Craftable> ok = new ArrayList<>(), almost = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecipeIndex.Entry e : RecipeIndex.entriesOf(RecipeIndex.CRAFTING)) {
            if (e.card() != null || e.outputs().isEmpty() || e.outputs().get(0).isEmpty()) continue;
            ItemStack out = e.outputs().get(0).get(0);
            if (!q.isEmpty() && !out.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
            Map<Item, Integer> left = new HashMap<>(have), need = new HashMap<>();
            int missing = 0;
            for (List<ItemStack> alts : e.inputs()) {
                if (alts.isEmpty()) continue;
                Item best = null;
                int bestCount = 0;
                for (ItemStack a : alts) {
                    int c = left.getOrDefault(a.getItem(), 0);
                    if (c > bestCount) {
                        bestCount = c;
                        best = a.getItem();
                    }
                }
                if (best == null) {
                    missing++;
                    continue;
                }
                left.merge(best, -1, Integer::sum);
                need.merge(best, 1, Integer::sum);
            }
            if (missing > 2 || (missing > 0 && need.isEmpty())) continue;
            int sets = 0;
            if (missing == 0) {
                sets = 64;
                for (Map.Entry<Item, Integer> n : need.entrySet()) sets = Math.min(sets, have.getOrDefault(n.getKey(), 0) / n.getValue());
                if (sets <= 0) continue;
            }
            String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(out.getItem()) + "|" + missing;
            if (!seen.add(key)) continue; // одинаковые результаты (разные доски и т. п.) — один раз
            (missing == 0 ? ok : almost).add(new Craftable(e, out, sets, missing));
        }
        java.util.Comparator<Craftable> byName = java.util.Comparator.comparing(c -> c.output().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
        ok.sort(byName);
        almost.sort(java.util.Comparator.comparingInt(Craftable::missing).thenComparing(byName));
        List<Craftable> all = new ArrayList<>(ok);
        all.addAll(almost);
        craftables = all;
    }

    private int listCols() {
        return (LW - 12) / CELL;
    }

    private int listRows() {
        return (H - 44 - 6) / CELL;
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int h = inventoryHash();
        if (h != invHash) {
            invHash = h;
            recompute();
        }
        renderBackground(g);
        super.render(g, mx, my, pt);
        if (hovered != null) {
            List<Component> tip = new ArrayList<>(getTooltipFromItem(minecraft, hovered.output()));
            if (hovered.missing() == 0) tip.add(Component.translatable("archud.craft.sets", hovered.sets()).withStyle(s -> s.withColor(0x9CFF5A)));
            else tip.add(Component.translatable("archud.craft.missing", hovered.missing()).withStyle(s -> s.withColor(0xFF6B6B)));
            tip.add(Component.translatable("archud.craft.hint").withStyle(s -> s.withColor(0x6A7380)));
            g.renderComponentTooltip(font, tip, mx, my);
        } else {
            renderTooltip(g, mx, my);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        int accent = Theme.accent();
        int x = leftPos, y = topPos, cx = cx();
        // левая карточка: можно сделать
        Draw.panel(g, x, y, LW, H, 0xF00E1218, Draw.alpha(accent, 0x70));
        g.fill(x + 3, y, x + LW - 3, y + 1, Draw.alpha(accent, 0xE0));
        g.drawString(font, Component.translatable("archud.craft.can_make"), x + 7, y + 7, Draw.TEXT, true);
        String cnt = String.valueOf(craftables.stream().filter(c -> c.missing() == 0).count());
        Draw.small(g, font, cnt, x + LW - 7 - font.width(cnt) * 0.7F, y + 8, Draw.TEXT_DIM, 0.7F);
        hovered = null;
        int cols = listCols(), rows = listRows(), lx = x + 6, ly = y + 40;
        int maxScroll = Math.max(0, (craftables.size() + cols - 1) / cols - rows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int start = scroll * cols;
        for (int i = 0; i < cols * rows && start + i < craftables.size(); i++) {
            Craftable c = craftables.get(start + i);
            int ix = lx + (i % cols) * CELL, iy = ly + (i / cols) * CELL;
            boolean hov = mx >= ix && mx < ix + CELL && my >= iy && my < iy + CELL;
            int bg = c.missing() == 0 ? 0xE0101820 : 0xC0180E10;
            int border = hov ? Draw.alpha(accent, 0xE0) : c.missing() == 0 ? 0x40FFFFFF : 0x50FF5050;
            Draw.panel(g, ix, iy, CELL, CELL, bg, border);
            g.renderItem(c.output(), ix + 1, iy + 1);
            if (c.missing() == 0 && c.sets() > 1) {
                String s = c.sets() >= 64 ? "64+" : String.valueOf(c.sets());
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                Draw.small(g, font, s, ix + CELL - 1 - font.width(s) * 0.6F, iy + CELL - 6, 0xFFFFFFFF, 0.6F);
                g.pose().popPose();
            }
            if (c.missing() > 0) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                g.fill(ix + 1, iy + 1, ix + CELL - 1, iy + CELL - 1, 0x50300000);
                g.pose().popPose();
            }
            if (hov) hovered = c;
        }
        if (craftables.isEmpty()) {
            Draw.wrapped(g, font, Component.translatable("archud.craft.nothing").getString(), lx + 2, ly + 4, LW - 16, Draw.TEXT_DIM, 0.7F);
        }
        if (maxScroll > 0) {
            int trackH = rows * CELL;
            int th = Math.max(10, trackH * rows / (rows + maxScroll));
            int ty = ly + (trackH - th) * scroll / maxScroll;
            g.fill(x + LW - 4, ly, x + LW - 3, ly + trackH, 0x30FFFFFF);
            g.fill(x + LW - 4, ty, x + LW - 3, ty + th, Draw.alpha(accent, 0xC0));
        }

        // центральная карточка: сетка, результат, инвентарь
        Draw.panel(g, cx, y, CW, H, 0xF0101419, Draw.alpha(accent, 0x90));
        g.fill(cx + 3, y, cx + CW - 3, y + 1, Draw.alpha(accent, 0xE0));
        g.drawString(font, title, cx + 8, y + 8, Draw.TEXT, true);
        for (int i = 0; i < 9; i++) {
            Slot s = menu.getSlot(1 + i);
            int sx = x + s.x - 1, sy = y + s.y - 1;
            Draw.panel(g, sx - 1, sy - 1, 20, 20, 0xE0070A0E, s == hoveredSlot ? Draw.alpha(accent, 0xC0) : 0x50FFFFFF);
        }
        // стрелка: подсвечена, когда рецепт сложился
        boolean ready = menu.getSlot(0).hasItem();
        int ay = y + RES_Y + 12, ax1 = cx + GRID_X + 3 * STEP + 6, ax2 = cx + RES_X - 6;
        int ac = ready ? Draw.alpha(accent, 0xFF) : 0x50FFFFFF;
        g.fill(ax1, ay - 1, ax2 - 4, ay + 1, ac);
        for (int k = 0; k < 5; k++) g.fill(ax2 - 5 + k, ay - 5 + k, ax2 - 4 + k, ay + 6 - k, ac);
        // результат
        Slot res = menu.getSlot(0);
        int rx = x + res.x - 5, ry = y + res.y - 5;
        float glow = ready ? 0.55F + 0.45F * (float) Math.sin(Util.getMillis() / 300.0) : 0F;
        Draw.panel(g, rx, ry, 26, 26, 0xE0141A21, ready ? Draw.alpha(accent, (int) (0x80 + 0x7F * glow)) : 0x50FFFFFF);
        if (ready && res.getItem().getCount() > 0) {
            String name = font.plainSubstrByWidth(res.getItem().getHoverName().getString(), CW - RES_X + 8);
            Draw.smallCentered(g, font, name, rx + 13, ry + 29, Draw.TEXT, 0.6F);
        }
        clearBtn.active = false;
        for (int i = 1; i <= 9; i++) clearBtn.active |= menu.getSlot(i).hasItem();
        takeBtn.active = ready;
        // инвентарь
        Draw.small(g, font, Component.translatable("container.inventory").getString(), cx + 8, y + INV_Y - 9, Draw.TEXT_DIM, 0.7F);
        for (int i = 10; i < 46; i++) {
            Slot s = menu.getSlot(i);
            Draw.panel(g, x + s.x - 1, y + s.y - 1, 18, 18, 0xC0080B10, s == hoveredSlot ? Draw.alpha(accent, 0xB0) : 0x30FFFFFF);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // подписи рисуются в renderBg
    }

    // ------------------------------------------------------------------ ввод

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (hovered != null && hovered.missing() == 0 && (button == 0 || button == 1)) {
            RecipeTransfer.Plan p = RecipeTransfer.plan(this, hovered.entry(), hasShiftDown() || button == 1);
            if (p != null && p.ok() && p.run() != null) {
                p.run().run();
                invHash = Integer.MIN_VALUE;
            }
            return true;
        }
        if (hovered != null) {
            com.arc4io.archud.client.recipes.ArcRecipeScreen.open(this, hovered.output(), false);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < leftPos + LW) {
            scroll -= (int) Math.signum(delta);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                search.setFocused(false);
                return true;
            }
            search.keyPressed(key, scan, mods);
            return true; // E и цифры не закрывают окно, пока печатаем
        }
        if (hovered != null && com.arc4io.archud.client.ArcKeys.RECIPES.matches(key, scan)) {
            com.arc4io.archud.client.recipes.ArcRecipeScreen.open(this, hovered.output(), false);
            return true;
        }
        if (hovered != null && com.arc4io.archud.client.ArcKeys.USES.matches(key, scan)) {
            com.arc4io.archud.client.recipes.ArcRecipeScreen.open(this, hovered.output(), true);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (search.isFocused()) return search.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    protected void containerTick() {
        search.tick();
    }
}
