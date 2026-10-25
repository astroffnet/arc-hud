package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.enchant.EnchantClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.enchant.EnchantLogic;
import com.arc4io.archud.mixin.EnchantmentScreenInvoker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Новый стол зачарования. Слева — книга, ячейки предмета и лазурита, карточка предмета (сила полок, уровень,
 * лазурит) и инвентарь. Справа — две вкладки:
 *  - «Варианты»: три ванильных варианта карточками, где виден ВЕСЬ набор зачарований каждого варианта
 *    (сервер с Arc HUD присылает его; без мода на сервере — ванильная подсказка «…?»), стоимость и лазурит;
 *    кнопка «Перебросить» (новые варианты за 1 лазурит);
 *  - «Выбор»: игрок сам выбирает зачарования и уровни (максимум ограничен силой полок, несовместимые
 *    отключаются), видит цену и зачаровывает одной кнопкой. Проверяется на сервере.
 * Ванильный экран остаётся родителем, поэтому моды, работающие с EnchantmentScreen, продолжают работать.
 */
public class ArcEnchantmentScreen extends EnchantmentScreen {
    private static final int W = 330;
    private static final int RX = 180, RW = 146;
    private static final int CONTENT_Y = 24, CONTENT_BOTTOM = 138;
    private static final int CARD_H = 37;
    private static final int ROW_H = 14;
    private static final int MAGIC = 0xFFB388FF;
    private static final int LAPIS = 0xFF3A66D0;
    private static final int GREEN = 0xFF9CFF5A;
    private static final int RED = 0xFFFF6E6E;

    private static int tab; // 0 — варианты, 1 — выбор (запоминается)
    private final Map<Enchantment, Integer> chosen = new LinkedHashMap<>();
    private List<EnchantLogic.Option> options = List.of();
    private int optionsKey;
    private int scroll;
    private int lastKey;
    private int requestTimer;
    private String lastItemKey = "";

    public ArcEnchantmentScreen(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = W;
    }

    // ------------------------------------------------------------------ состояние

    private ItemStack item() {
        return menu.getSlot(0).getItem();
    }

    private int lapis() {
        ItemStack s = menu.getSlot(1).getItem();
        return s.is(Items.LAPIS_LAZULI) ? s.getCount() : 0;
    }

    private boolean creative() {
        return minecraft.player != null && minecraft.player.getAbilities().instabuild;
    }

    private int level() {
        return minecraft.player == null ? 0 : minecraft.player.experienceLevel;
    }

    private boolean hasData() {
        return EnchantClient.has(menu.containerId);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        ItemStack it = item();
        int key = Objects.hash(it.getItem(), it.getCount(), it.getTag(), menu.costs[0], menu.costs[1], menu.costs[2],
                menu.getEnchantmentSeed(), lapis());
        String itemKey = it.isEmpty() ? "" : it.getItem() + "|" + it.getTag();
        if (!itemKey.equals(lastItemKey)) {
            lastItemKey = itemKey;
            chosen.clear();
            scroll = 0;
        }
        if (key != lastKey) {
            lastKey = key;
            requestTimer = 3; // данные о стоимости приходят чуть позже предмета
        }
        if (requestTimer > 0 && --requestTimer == 0 && EnchantClient.serverSupports()) EnchantClient.requestPreview(menu.containerId);
        int power = hasData() ? EnchantClient.power : 0;
        int ok = Objects.hash(itemKey, power);
        if (ok != optionsKey) {
            optionsKey = ok;
            options = EnchantLogic.options(it, power);
            chosen.keySet().removeIf(e -> options.stream().noneMatch(o -> o.enchantment() == e));
            for (EnchantLogic.Option o : options) chosen.computeIfPresent(o.enchantment(), (e, l) -> Math.min(l, o.maxLevel()));
        }
    }

    private boolean selectiveAvailable() {
        return hasData() && EnchantClient.selective;
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // ванильные «строки» зачарования (x 60..168, y 14..71) у нас заняты карточкой предмета —
        // не даём ванилле показывать там свои подсказки
        boolean block = inVanillaRows(mouseX, mouseY);
        super.render(g, block ? -10000 : mouseX, block ? -10000 : mouseY, partialTick);
        renderTooltips(g, mouseX, mouseY);
    }

    private boolean inVanillaRows(double mx, double my) {
        return mx >= leftPos + 60 && mx < leftPos + 168 && my >= topPos + 14 && my < topPos + 71;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int accent = Theme.accent();
        int x = leftPos, y = topPos;
        // основная панель + отдельная правая колонка
        Draw.panel(g, x, y, 176, imageHeight, 0xF0101419, Draw.alpha(accent, 0x90));
        g.fill(x + 3, y, x + 173, y + 1, Draw.alpha(MAGIC, 0xE0));
        g.fillGradient(x + 1, y + 1, x + 175, y + 50, Draw.alpha(MAGIC, 0x22), 0x00000000);
        Draw.panel(g, x + RX - 2, y, RW + 4, imageHeight, 0xF00E1117, Draw.alpha(MAGIC, 0x80));
        g.fill(x + RX + 1, y, x + RX + RW - 1, y + 1, Draw.alpha(MAGIC, 0xE0));

        // ниша книги
        Draw.panel(g, x + 7, y + 14, 50, 30, 0xC0060A10, Draw.alpha(MAGIC, 0x60));
        g.fillGradient(x + 8, y + 15, x + 56, y + 43, 0x10000000, Draw.alpha(MAGIC, 0x38));
        ((EnchantmentScreenInvoker) this).archud$renderBook(g, x, y, partialTick);

        renderItemCard(g, x + 60, y + 14);

        g.fill(x + 7, y + 80, x + 169, y + 81, Draw.alpha(accent, 0x40));
        g.fill(x + 6, y + 140, x + 170, y + 160, Draw.alpha(accent, 0x20));
        renderSlots(g, x, y, accent);

        // вкладки
        renderTab(g, x + RX, y + 5, 72, Component.translatable("archud.ench.tab_offers"), tab == 0, true, mouseX, mouseY);
        renderTab(g, x + RX + 74, y + 5, 72, Component.translatable("archud.ench.tab_pick"), tab == 1, selectiveAvailable(), mouseX, mouseY);
        if (tab == 1 && selectiveAvailable()) renderPick(g, x + RX, y, mouseX, mouseY);
        else renderOffers(g, x + RX, y, mouseX, mouseY);
    }

    private void renderSlots(GuiGraphics g, int x, int y, int accent) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.isActive()) continue;
            int sx = x + slot.x - 1, sy = y + slot.y - 1;
            boolean special = i < 2;
            int border = slot == hoveredSlot ? Draw.alpha(accent, 0xA0)
                    : i == 0 ? Draw.alpha(MAGIC, 0xC0) : i == 1 ? Draw.alpha(LAPIS, 0xC0) : 0x50FFFFFF;
            Draw.panel(g, sx, sy, 18, 18, special ? 0xE0182028 : 0xE00B0F14, border);
            if (i == 1 && !slot.hasItem()) {
                g.renderItem(new ItemStack(Items.LAPIS_LAZULI), sx + 1, sy + 1);
                g.pose().pushPose();
                g.pose().translate(0, 0, 300);
                g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xB0182028);
                g.pose().popPose();
            }
        }
    }

    /** Карточка предмета: название, сила полок, лазурит и уровень. */
    private void renderItemCard(GuiGraphics g, int cx, int cy) {
        Draw.panel(g, cx, cy, 109, 57, 0xC0080B10, Draw.alpha(MAGIC, 0x50));
        ItemStack it = item();
        if (it.isEmpty()) {
            Draw.small(g, font, Component.translatable("archud.ench.place").getString(), cx + 5, cy + 5, Draw.TEXT_DIM, 0.6F);
        } else {
            String name = it.getHoverName().getString();
            float max = (109 - 10) / 0.75F;
            if (font.width(name) > max) name = font.plainSubstrByWidth(name, (int) max - font.width("…")) + "…";
            Draw.small(g, font, name, cx + 5, cy + 4, it.isEnchantable() ? Draw.TEXT : Draw.TEXT_DIM, 0.75F);
            if (!it.isEnchantable()) Draw.small(g, font, Component.translatable("archud.ench.cannot").getString(), cx + 5, cy + 12, RED, 0.55F);
        }
        // сила полок
        int power = hasData() ? EnchantClient.power : -1;
        Draw.small(g, font, Component.translatable("archud.ench.power").getString(), cx + 5, cy + 22, Draw.TEXT_DIM, 0.55F);
        String pv = power < 0 ? "?" : power + " / " + EnchantLogic.MAX_POWER;
        Draw.small(g, font, pv, cx + 104 - font.width(pv) * 0.55F, cy + 22, power >= EnchantLogic.MAX_POWER ? MAGIC : Draw.TEXT, 0.55F);
        int segW = 6;
        for (int i = 0; i < EnchantLogic.MAX_POWER; i++) {
            int sx = cx + 5 + i * (segW + 0) + i / 5 * 2;
            boolean on = power > i;
            g.fill(sx, cy + 29, sx + segW - 1, cy + 32, on ? Draw.alpha(MAGIC, 0xFF) : 0x30FFFFFF);
        }
        // лазурит / уровень
        int lap = lapis();
        g.fill(cx + 5, cy + 38, cx + 13, cy + 46, LAPIS);
        Draw.outline(g, cx + 5, cy + 38, 8, 8, 0xFF7FA2FF);
        Draw.small(g, font, Integer.toString(lap), cx + 16, cy + 39, Draw.TEXT, 0.7F);
        String lv = Component.translatable("archud.ench.level_short", level()).getString();
        Draw.small(g, font, lv, cx + 104 - font.width(lv) * 0.7F, cy + 39, GREEN, 0.7F);
        float xp = minecraft.player == null ? 0 : minecraft.player.experienceProgress;
        g.fill(cx + 5, cy + 50, cx + 104, cy + 52, 0x60000000);
        g.fill(cx + 5, cy + 50, cx + 5 + Math.round(99 * xp), cy + 52, GREEN);
    }

    private void renderTab(GuiGraphics g, int tx, int ty, int w, Component label, boolean active, boolean enabled, int mx, int my) {
        boolean hov = enabled && mx >= tx && mx < tx + w && my >= ty && my < ty + 14;
        int bg = active ? Draw.alpha(MAGIC, 0x40) : hov ? 0xE01A2029 : 0xE0101419;
        int border = active ? Draw.alpha(MAGIC, 0xFF) : enabled ? 0x50FFFFFF : 0x25FFFFFF;
        Draw.panel(g, tx, ty, w, 14, bg, border);
        if (active) g.fill(tx + 2, ty + 12, tx + w - 2, ty + 13, MAGIC);
        Draw.smallCentered(g, font, label.getString(), tx + w / 2F, ty + 4, enabled ? (active ? 0xFFFFFFFF : Draw.TEXT) : 0xFF5A606A, 0.7F);
    }

    // ---------- вкладка «Варианты»

    private int cardY(int i) {
        return CONTENT_Y + i * (CARD_H + 1);
    }

    private boolean canAfford(int i) {
        int cost = menu.costs[i];
        return cost > 0 && (creative() || (lapis() >= i + 1 && level() >= cost));
    }

    private void renderOffers(GuiGraphics g, int px, int py, int mx, int my) {
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            int cx = px, cy = py + cardY(i);
            int cost = menu.costs[i];
            if (cost <= 0) {
                Draw.panel(g, cx, cy, RW, CARD_H, 0x80080B10, 0x25FFFFFF);
                Draw.smallCentered(g, font, Component.translatable("archud.ench.offer_n", roman(i + 1)).getString(), cx + RW / 2F, cy + 15, 0xFF3C4250, 0.6F);
                continue;
            }
            any = true;
            boolean can = canAfford(i);
            boolean hov = can && mx >= cx && mx < cx + RW && my >= cy && my < cy + CARD_H;
            int bg = !can ? 0xC0140C0E : hov ? Draw.alpha(MAGIC, 0x40) : 0xE0141B24;
            int border = !can ? 0x60FF5252 : hov ? Draw.alpha(MAGIC, 0xFF) : Draw.alpha(MAGIC, 0x70);
            Draw.panel(g, cx, cy, RW, CARD_H, bg, border);
            if (hov) g.fill(cx + 1, cy + 1, cx + 3, cy + CARD_H - 1, MAGIC);
            boolean enoughLapis = creative() || lapis() >= i + 1;
            boolean enoughLevel = creative() || level() >= cost;
            // лазурит
            g.fill(cx + 5, cy + 4, cx + 17, cy + 16, enoughLapis ? LAPIS : 0xFF3A3F48);
            Draw.outline(g, cx + 5, cy + 4, 12, 12, enoughLapis ? 0xFF7FA2FF : 0xFF5A606A);
            String n = Integer.toString(i + 1);
            g.drawString(font, n, cx + 11 - font.width(n) / 2, cy + 6, enoughLapis ? 0xFFFFFFFF : 0xFF8C9AAB, true);
            // стоимость
            String c = Integer.toString(cost);
            g.drawString(font, c, cx + RW - 6 - font.width(c), cy + 5, enoughLevel ? GREEN : RED, true);
            Draw.small(g, font, Component.translatable("archud.ench.lvl").getString(), cx + RW - 6 - font.width(c) - 13, cy + 7, Draw.TEXT_DIM, 0.55F);
            // список зачарований
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            if (hasData() && !EnchantClient.PREVIEW[i].isEmpty()) {
                for (EnchantmentInstance e : EnchantClient.PREVIEW[i]) {
                    lines.add(e.enchantment.getFullname(e.level).getString());
                    colors.add(e.enchantment.isCurse() ? RED : rarityColor(e.enchantment));
                }
            } else {
                Enchantment e = Enchantment.byId(menu.enchantClue[i]);
                lines.add(e == null ? "???" : e.getFullname(menu.levelClue[i]).getString() + "…?");
                colors.add(0xFFE8DCFF);
                lines.add(Component.translatable("archud.ench.surprise").getString());
                colors.add(Draw.TEXT_DIM);
            }
            int ly = cy + 5;
            int nameMax = (int) ((RW - 24 - 34) / 0.75F);
            for (int k = 0; k < Math.min(3, lines.size()); k++) {
                float sc = k == 0 ? 0.75F : 0.6F;
                String s = lines.get(k);
                int max = k == 0 ? nameMax : (int) ((RW - 26) / sc);
                if (k == 2 && lines.size() > 3) s = Component.translatable("archud.ench.more", lines.size() - 2).getString();
                if (font.width(s) > max) s = font.plainSubstrByWidth(s, max - font.width("…")) + "…";
                int col = can ? colors.get(k) : 0xFF8C7A80;
                if (k == 2 && lines.size() > 3) col = Draw.TEXT_DIM;
                Draw.small(g, font, s, cx + 21, ly, col, sc);
                ly += k == 0 ? 9 : 7;
            }
            String sub = Component.translatable("archud.ench.cost", cost, i + 1).getString();
            Draw.small(g, font, sub, cx + 21, cy + CARD_H - 8, can ? Draw.TEXT_DIM : Draw.alpha(RED, 0xC0), 0.5F);
        }
        // низ: переброс или подсказка
        int by = py + 142;
        if (hasData() && EnchantClient.reroll && !item().isEmpty() && any) {
            boolean can = creative() || lapis() >= 1;
            boolean hov = can && mx >= px && mx < px + RW && my >= by && my < by + 18;
            Draw.panel(g, px, by, RW, 18, hov ? Draw.alpha(MAGIC, 0x40) : 0xE0141B24, can ? Draw.alpha(MAGIC, hov ? 0xFF : 0x80) : 0x40FFFFFF);
            Draw.smallCentered(g, font, Component.translatable("archud.ench.reroll").getString(), px + RW / 2F, by + 6,
                    can ? 0xFFE8DCFF : 0xFF5A606A, 0.7F);
        } else {
            String hint = Component.translatable(!EnchantClient.serverSupports() ? "archud.ench.vanilla_server"
                    : item().isEmpty() ? "archud.ench.place" : "archud.ench.hint_offers").getString();
            for (var line : font.split(Component.literal(hint), (int) (RW / 0.55F))) {
                g.pose().pushPose();
                g.pose().translate(px + 2, by + 2, 0);
                g.pose().scale(0.55F, 0.55F, 1F);
                g.drawString(font, line, 0, 0, Draw.TEXT_DIM, false);
                g.pose().popPose();
                by += 6;
            }
        }
    }

    // ---------- вкладка «Выбор»

    private int visibleRows() {
        return (CONTENT_BOTTOM - CONTENT_Y) / ROW_H;
    }

    private Enchantment conflictFor(Enchantment e) {
        for (Map.Entry<Enchantment, Integer> c : chosen.entrySet()) {
            if (c.getValue() > 0 && c.getKey() != e && !c.getKey().isCompatibleWith(e)) return c.getKey();
        }
        return null;
    }

    private void renderPick(GuiGraphics g, int px, int py, int mx, int my) {
        ItemStack it = item();
        if (it.isEmpty() || options.isEmpty()) {
            String key = it.isEmpty() ? "archud.ench.place" : "archud.ench.cannot";
            Draw.smallCentered(g, font, Component.translatable(key).getString(), px + RW / 2F, py + 70, Draw.TEXT_DIM, 0.65F);
            return;
        }
        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, options.size() - rows));
        for (int r = 0; r < rows && scroll + r < options.size(); r++) {
            EnchantLogic.Option o = options.get(scroll + r);
            Enchantment e = o.enchantment();
            int lvl = chosen.getOrDefault(e, 0);
            Enchantment conflict = lvl > 0 ? null : conflictFor(e);
            int rx = px, ry = py + CONTENT_Y + r * ROW_H;
            boolean on = lvl > 0;
            int bg = on ? Draw.alpha(MAGIC, 0x30) : conflict != null ? 0x80100A0C : 0xC0101419;
            Draw.panel(g, rx, ry, RW - 5, ROW_H - 1, bg, on ? Draw.alpha(MAGIC, 0xC0) : 0x30FFFFFF);
            g.fill(rx + 3, ry + 5, rx + 6, ry + 8, conflict != null ? 0xFF5A606A : rarityColor(e));
            String name = net.minecraft.network.chat.Component.translatable(e.getDescriptionId()).getString();
            int nameMax = (int) ((RW - 5 - 52) / 0.65F);
            if (font.width(name) > nameMax) name = font.plainSubstrByWidth(name, nameMax - font.width("…")) + "…";
            Draw.small(g, font, name, rx + 9, ry + 2, conflict != null ? 0xFF5A606A : on ? 0xFFFFFFFF : Draw.TEXT, 0.65F);
            String maxS = Component.translatable("archud.ench.max", roman(o.maxLevel())).getString()
                    + (o.maxLevel() < e.getMaxLevel() ? " / " + roman(e.getMaxLevel()) : "");
            Draw.small(g, font, maxS, rx + 9, ry + 8, 0xFF5A6474, 0.45F);
            // [-] уровень [+]
            int bx = rx + RW - 5 - 44;
            drawMini(g, bx, ry + 2, "-", on, mx, my);
            String lv = on ? roman(lvl) : "—";
            Draw.smallCentered(g, font, lv, bx + 22, ry + 4, on ? MAGIC : 0xFF5A606A, 0.65F);
            drawMini(g, bx + 33, ry + 2, "+", conflict == null && lvl < o.maxLevel(), mx, my);
        }
        // полоса прокрутки
        if (options.size() > rows) {
            int trackH = rows * ROW_H - 1;
            int th = Math.max(10, trackH * rows / options.size());
            int ty = py + CONTENT_Y + (trackH - th) * scroll / Math.max(1, options.size() - rows);
            g.fill(px + RW - 3, py + CONTENT_Y, px + RW - 1, py + CONTENT_Y + trackH, 0x30FFFFFF);
            g.fill(px + RW - 3, ty, px + RW - 1, ty + th, Draw.alpha(MAGIC, 0xC0));
        }
        // итог
        EnchantLogic.Cost cost = EnchantLogic.cost(chosen);
        boolean any = cost.required() > 0;
        boolean enoughLevel = creative() || level() >= cost.required();
        boolean enoughLapis = creative() || lapis() >= cost.lapis();
        int sy = py + 140;
        if (any) {
            String a = Component.translatable("archud.ench.need", cost.required()).getString();
            String b = Component.translatable("archud.ench.takes", cost.consumed()).getString();
            String c = Component.translatable("archud.ench.lapis", cost.lapis()).getString();
            float ax = px + 2;
            Draw.small(g, font, a, ax, sy, enoughLevel ? GREEN : RED, 0.55F);
            ax += font.width(a) * 0.55F + 4;
            Draw.small(g, font, b, ax, sy, Draw.TEXT_DIM, 0.55F);
            ax += font.width(b) * 0.55F + 4;
            Draw.small(g, font, c, ax, sy, enoughLapis ? 0xFF7FA2FF : RED, 0.55F);
        } else {
            Draw.small(g, font, Component.translatable("archud.ench.pick_hint").getString(), px + 2, sy, Draw.TEXT_DIM, 0.55F);
        }
        boolean can = any && enoughLevel && enoughLapis;
        int by = py + 147;
        boolean hov = can && mx >= px && mx < px + RW && my >= by && my < by + 15;
        Draw.panel(g, px, by, RW, 15, hov ? Draw.alpha(MAGIC, 0x50) : can ? Draw.alpha(MAGIC, 0x28) : 0xE0141B24,
                can ? Draw.alpha(MAGIC, hov ? 0xFF : 0xA0) : 0x40FFFFFF);
        Draw.smallCentered(g, font, Component.translatable("archud.ench.do").getString(), px + RW / 2F, by + 4,
                can ? 0xFFFFFFFF : 0xFF5A606A, 0.75F);
    }

    private void drawMini(GuiGraphics g, int bx, int by, String s, boolean enabled, int mx, int my) {
        boolean hov = enabled && mx >= bx && mx < bx + 11 && my >= by && my < by + 9;
        Draw.panel(g, bx, by, 11, 9, hov ? Draw.alpha(MAGIC, 0x60) : 0xE0141B24, enabled ? Draw.alpha(MAGIC, hov ? 0xFF : 0x80) : 0x25FFFFFF);
        Draw.smallCentered(g, font, s, bx + 5.5F, by + 1.5F, enabled ? 0xFFFFFFFF : 0xFF4A505A, 0.75F);
    }

    // ---------- подсказки

    private void renderTooltips(GuiGraphics g, int mx, int my) {
        int px = leftPos + RX, py = topPos;
        if (tab == 0 || !selectiveAvailable()) {
            for (int i = 0; i < 3; i++) {
                int cy = py + cardY(i);
                if (menu.costs[i] <= 0 || mx < px || mx >= px + RW || my < cy || my >= cy + CARD_H) continue;
                List<Component> tip = new ArrayList<>();
                tip.add(Component.translatable("archud.ench.offer_n", roman(i + 1)).withStyle(ChatFormatting.LIGHT_PURPLE));
                if (hasData() && !EnchantClient.PREVIEW[i].isEmpty()) {
                    for (EnchantmentInstance e : EnchantClient.PREVIEW[i]) tip.add(e.enchantment.getFullname(e.level).copy().withStyle(e.enchantment.isCurse() ? ChatFormatting.RED : ChatFormatting.GRAY));
                } else {
                    Enchantment e = Enchantment.byId(menu.enchantClue[i]);
                    if (e != null) tip.add(Component.translatable("container.enchant.clue", e.getFullname(menu.levelClue[i])).withStyle(ChatFormatting.GRAY));
                }
                tip.add(Component.empty());
                if (!creative() && lapis() < i + 1) tip.add(Component.translatable("archud.ench.no_lapis").withStyle(ChatFormatting.RED));
                if (!creative() && level() < menu.costs[i]) tip.add(Component.translatable("archud.ench.no_level", menu.costs[i]).withStyle(ChatFormatting.RED));
                tip.add(Component.translatable("archud.ench.cost", menu.costs[i], i + 1).withStyle(ChatFormatting.DARK_GRAY));
                g.renderComponentTooltip(font, tip, mx, my);
                return;
            }
        } else {
            int rows = visibleRows();
            for (int r = 0; r < rows && scroll + r < options.size(); r++) {
                int ry = py + CONTENT_Y + r * ROW_H;
                if (mx < px || mx >= px + RW - 50 || my < ry || my >= ry + ROW_H - 1) continue;
                Enchantment e = options.get(scroll + r).enchantment();
                List<Component> tip = new ArrayList<>();
                tip.add(Component.translatable(e.getDescriptionId()).withStyle(ChatFormatting.LIGHT_PURPLE));
                String descKey = e.getDescriptionId() + ".desc"; // описание из модов вроде Enchantment Descriptions
                if (net.minecraft.client.resources.language.I18n.exists(descKey)) tip.add(Component.translatable(descKey).withStyle(ChatFormatting.GRAY));
                tip.add(Component.translatable("archud.ench.rarity." + e.getRarity().name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.DARK_GRAY));
                Enchantment c = chosen.getOrDefault(e, 0) > 0 ? null : conflictFor(e);
                if (c != null) tip.add(Component.translatable("archud.ench.conflict", Component.translatable(c.getDescriptionId())).withStyle(ChatFormatting.RED));
                int max = options.get(scroll + r).maxLevel();
                if (max < e.getMaxLevel()) tip.add(Component.translatable("archud.ench.more_shelves").withStyle(ChatFormatting.YELLOW));
                g.renderComponentTooltip(font, tip, mx, my);
                return;
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, Draw.alpha(MAGIC, 0xFF), true);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Draw.TEXT_DIM, true);
    }

    // ------------------------------------------------------------------ ввод

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && clickMine(mx, my)) return true;
        if (inVanillaRows(mx, my)) return true; // карточка предмета, ванильные строки здесь не работают
        return super.mouseClicked(mx, my, button);
    }

    private boolean clickMine(double mx, double my) {
        int px = leftPos + RX, py = topPos;
        // вкладки
        if (my >= py + 5 && my < py + 19) {
            if (mx >= px && mx < px + 72) {
                setTab(0);
                return true;
            }
            if (mx >= px + 74 && mx < px + 146 && selectiveAvailable()) {
                setTab(1);
                return true;
            }
        }
        if (mx < px || mx >= px + RW) return false;
        if (tab == 0 || !selectiveAvailable()) {
            for (int i = 0; i < 3; i++) {
                int cy = py + cardY(i);
                if (my >= cy && my < cy + CARD_H) {
                    if (canAfford(i) && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, i);
                        click();
                    }
                    return true;
                }
            }
            int by = py + 142;
            if (my >= by && my < by + 18 && hasData() && EnchantClient.reroll && !item().isEmpty() && (creative() || lapis() >= 1)) {
                EnchantClient.requestReroll(menu.containerId);
                click();
                return true;
            }
            return false;
        }
        // выбор
        int rows = visibleRows();
        for (int r = 0; r < rows && scroll + r < options.size(); r++) {
            int ry = py + CONTENT_Y + r * ROW_H;
            if (my < ry || my >= ry + ROW_H - 1) continue;
            EnchantLogic.Option o = options.get(scroll + r);
            Enchantment e = o.enchantment();
            int lvl = chosen.getOrDefault(e, 0);
            int bx = px + RW - 5 - 44;
            if (mx >= bx && mx < bx + 11 && lvl > 0) {
                if (lvl - 1 <= 0) chosen.remove(e);
                else chosen.put(e, lvl - 1);
                click();
            } else if ((mx >= bx + 33 && mx < bx + 44) || (mx < bx && lvl == 0)) {
                // «+» или клик по названию выключенного — включить / поднять уровень
                if (conflictFor(e) == null && lvl < o.maxLevel()) {
                    chosen.put(e, lvl == 0 ? (mx < bx ? o.maxLevel() : 1) : lvl + 1);
                    click();
                }
            } else if (mx < bx && lvl > 0) {
                chosen.remove(e); // клик по названию включённого — убрать
                click();
            }
            return true;
        }
        int by = py + 147;
        if (my >= by && my < by + 15) {
            EnchantLogic.Cost cost = EnchantLogic.cost(chosen);
            boolean can = cost.required() > 0 && (creative() || (level() >= cost.required() && lapis() >= cost.lapis()));
            if (can) {
                EnchantClient.requestSelect(menu.containerId, chosen);
                chosen.clear();
            }
            return true;
        }
        return false;
    }

    private void setTab(int t) {
        if (tab != t) click();
        tab = t;
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int px = leftPos + RX;
        if (tab == 1 && mx >= px && mx < px + RW && my >= topPos + CONTENT_Y && my < topPos + CONTENT_BOTTOM) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, options.size() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    // ------------------------------------------------------------------ вспомогательное

    private static int rarityColor(Enchantment e) {
        return switch (e.getRarity()) {
            case COMMON -> 0xFFE8EEF4;
            case UNCOMMON -> 0xFFFFE082;
            case RARE -> 0xFF7FE0FF;
            case VERY_RARE -> 0xFFD59BFF;
        };
    }

    private static String roman(int n) {
        String key = "enchantment.level." + n;
        return net.minecraft.client.resources.language.I18n.exists(key) ? Component.translatable(key).getString() : Integer.toString(n);
    }
}
