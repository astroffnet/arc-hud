package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.mixin.SlotPosAccessor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Новая наковальня Arc HUD (написана с нуля, не перерисовка ванильной).
 * <ul>
 *   <li>Слева — «Подходит»: что из инвентаря можно положить к предмету (материал для ремонта, такой же предмет,
 *   книги с подходящими чарами). Клик — положить во второй слот.</li>
 *   <li>В центре — большое поле имени, «предмет + материал → результат», стоимость и инвентарь.</li>
 *   <li>Справа — что получится: новое имя, чары (новые и усиленные подсвечены, несовместимые отмечены),
 *   прочность до и после, штраф за прошлые работы, хватает ли уровней.</li>
 * </ul>
 */
public class ArcAnvilScreen extends AbstractContainerScreen<AnvilMenu> implements ContainerListener {
    private static final int LW = 112, GAP = 6, CW = 176, RW = 140, H = 196, CELL = 18;
    private static final int STEEL = 0xFF9FB3C8, GREEN = 0xFF8CFF7A, RED = 0xFFFF6E6E, GOLD = 0xFFFFD36E;
    private static final int SLOT_Y = 46, IN_X = 16, MAT_X = 66, RES_X = 136, INV_Y = 112;

    private EditBox name;
    private final List<Slot> suggestions = new ArrayList<>();
    private Slot hoveredSuggestion;

    public ArcAnvilScreen(AnvilMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = LW + GAP + CW + GAP + RW;
        imageHeight = H;
    }

    private int cx() {
        return leftPos + LW + GAP;
    }

    private int rx() {
        return leftPos + LW + GAP + CW + GAP;
    }

    @Override
    protected void init() {
        ScreenFit.ensure(this, imageWidth + 8, imageHeight + 8);
        super.init();
        layoutSlots();
        String prev = name != null ? name.getValue() : "";
        name = new EditBox(font, cx() + 10, topPos + 22, CW - 20, 14, Component.translatable("container.repair"));
        name.setCanLoseFocus(false);
        name.setTextColor(-1);
        name.setTextColorUneditable(-1);
        name.setBordered(false);
        name.setMaxLength(50);
        name.setResponder(this::onNameChanged);
        name.setValue(prev);
        addWidget(name);
        setInitialFocus(name);
        name.setEditable(menu.getSlot(0).hasItem());
        menu.addSlotListener(this);
    }

    @Override
    public void removed() {
        super.removed();
        menu.removeSlotListener(this);
    }

    private static void move(Slot s, int x, int y) {
        ((SlotPosAccessor) s).archud$setX(x);
        ((SlotPosAccessor) s).archud$setY(y);
    }

    private void layoutSlots() {
        int ox = LW + GAP;
        move(menu.getSlot(0), ox + IN_X + 5, SLOT_Y + 5);
        move(menu.getSlot(1), ox + MAT_X + 5, SLOT_Y + 5);
        move(menu.getSlot(2), ox + RES_X + 5, SLOT_Y + 5);
        for (int i = 0; i < 27; i++) move(menu.getSlot(3 + i), ox + 8 + (i % 9) * CELL, INV_Y + (i / 9) * CELL);
        for (int i = 0; i < 9; i++) move(menu.getSlot(30 + i), ox + 8 + i * CELL, INV_Y + 3 * CELL + 4);
    }

    // ------------------------------------------------------------------ имя (как в ванильной наковальне)

    private void onNameChanged(String s) {
        Slot slot = menu.getSlot(0);
        if (!slot.hasItem()) return;
        String n = s;
        if (!slot.getItem().hasCustomHoverName() && n.equals(slot.getItem().getHoverName().getString())) n = "";
        if (menu.setItemName(n) && minecraft.player != null) minecraft.player.connection.send(new ServerboundRenameItemPacket(n));
    }

    @Override
    public void slotChanged(AbstractContainerMenu m, int slot, ItemStack stack) {
        if (slot == 0 && name != null) {
            name.setValue(stack.isEmpty() ? "" : stack.getHoverName().getString());
            name.setEditable(!stack.isEmpty());
            setFocused(name);
        }
    }

    @Override
    public void dataChanged(AbstractContainerMenu m, int id, int value) {
    }

    // ------------------------------------------------------------------ что подходит к предмету

    /** Можно ли положить кандидата во второй слот к предмету: ремонт, такой же предмет, книга с подходящими чарами. */
    private static boolean fits(ItemStack item, ItemStack c) {
        if (c.isEmpty() || item.isEmpty()) return false;
        if (item.isDamageableItem() && item.getItem().isValidRepairItem(item, c)) return true;
        if (c.getItem() == item.getItem() && item.isDamageableItem()) return true;
        if (c.getItem() instanceof EnchantedBookItem) {
            for (Enchantment e : EnchantmentHelper.getEnchantments(c).keySet()) {
                if (e.canEnchant(item) || item.getItem() instanceof EnchantedBookItem) return true;
            }
        }
        return false;
    }

    private void collectSuggestions() {
        suggestions.clear();
        ItemStack item = menu.getSlot(0).getItem();
        if (item.isEmpty() || menu.getSlot(1).hasItem()) return;
        for (int i = 3; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (fits(item, s.getItem())) suggestions.add(s);
        }
    }

    private void click(Slot s, int button, ClickType type) {
        if (minecraft.gameMode == null || minecraft.player == null) return;
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, s.index, button, type, minecraft.player);
    }

    /** Положить подсказку во второй слот (всю стопку, остаток — обратно). */
    private void putIntoMaterial(Slot from) {
        if (!menu.getCarried().isEmpty()) return;
        click(from, 0, ClickType.PICKUP);
        click(menu.getSlot(1), 0, ClickType.PICKUP);
        if (!menu.getCarried().isEmpty()) click(from, 0, ClickType.PICKUP);
    }

    // ------------------------------------------------------------------ отрисовка

    private int resultColor() {
        int cost = menu.getCost();
        if (!menu.getSlot(2).hasItem()) return STEEL;
        boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
        if (!creative && (cost >= 40 || minecraft.player == null || minecraft.player.experienceLevel < cost)) return RED;
        return GREEN;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        super.render(g, mx, my, pt);
        name.render(g, mx, my, pt);
        if (hoveredSuggestion != null) {
            List<Component> tip = new ArrayList<>(getTooltipFromItem(minecraft, hoveredSuggestion.getItem()));
            tip.add(Component.translatable("archud.anvil.put").withStyle(s -> s.withColor(0x8A93A0)));
            g.renderComponentTooltip(font, tip, mx, my);
        } else renderTooltip(g, mx, my);
    }

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        int accent = Theme.accent();
        int x = leftPos, y = topPos, cx = cx(), rx = rx();
        collectSuggestions();

        // ---------- слева: подходит к предмету
        Draw.panel(g, x, y, LW, H, 0xF00E1218, Draw.alpha(STEEL, 0x60));
        g.fill(x + 3, y, x + LW - 3, y + 1, Draw.alpha(STEEL, 0xD0));
        g.drawString(font, Component.translatable("archud.anvil.fits"), x + 7, y + 7, Draw.TEXT, true);
        hoveredSuggestion = null;
        if (!menu.getSlot(0).hasItem()) {
            Draw.wrapped(g, font, Component.translatable("archud.anvil.fits_hint").getString(), x + 7, y + 22, LW - 14, Draw.TEXT_DIM, 0.65F);
        } else if (menu.getSlot(1).hasItem()) {
            Draw.wrapped(g, font, Component.translatable("archud.anvil.fits_busy").getString(), x + 7, y + 22, LW - 14, Draw.TEXT_DIM, 0.65F);
        } else if (suggestions.isEmpty()) {
            Draw.wrapped(g, font, Component.translatable("archud.anvil.fits_none").getString(), x + 7, y + 22, LW - 14, Draw.TEXT_DIM, 0.65F);
        } else {
            int cols = (LW - 12) / CELL;
            for (int i = 0; i < suggestions.size() && i < cols * 9; i++) {
                Slot s = suggestions.get(i);
                int ix = x + 6 + (i % cols) * CELL, iy = y + 22 + (i / cols) * CELL;
                boolean hov = mx >= ix && mx < ix + CELL && my >= iy && my < iy + CELL;
                Draw.panel(g, ix, iy, CELL, CELL, 0xE0101820, hov ? Draw.alpha(accent, 0xE0) : Draw.alpha(STEEL, 0x50));
                g.renderItem(s.getItem(), ix + 1, iy + 1);
                g.renderItemDecorations(font, s.getItem(), ix + 1, iy + 1);
                if (hov) hoveredSuggestion = s;
            }
        }

        // ---------- центр: имя, слоты, стоимость, инвентарь
        Draw.panel(g, cx, y, CW, H, 0xF0101419, Draw.alpha(STEEL, 0x90));
        g.fill(cx + 3, y, cx + CW - 3, y + 1, Draw.alpha(STEEL, 0xE0));
        g.fillGradient(cx + 1, y + 1, cx + CW - 1, y + 40, Draw.alpha(STEEL, 0x18), 0);
        g.drawString(font, title, cx + 8, y + 7, Draw.TEXT, true);
        boolean canName = menu.getSlot(0).hasItem();
        Draw.panel(g, cx + 6, y + 18, CW - 12, 16, canName ? 0xE0070A0E : 0x80070A0E, name.isFocused() && canName ? Draw.alpha(accent, 0xE0)
                : Draw.alpha(STEEL, canName ? 0x70 : 0x30));
        if (name.getValue().isEmpty() && canName) Draw.small(g, font, Component.translatable("archud.anvil.rename").getString(), cx + 11, y + 23, Draw.TEXT_DIM, 0.7F);
        // слоты: предмет + материал → результат
        int[] xs = {IN_X, MAT_X, RES_X};
        for (int i = 0; i < 3; i++) {
            Slot s = menu.getSlot(i);
            int sx = cx + xs[i], sy = y + SLOT_Y;
            int border = s == hoveredSlot ? Draw.alpha(accent, 0xD0) : i == 2 ? Draw.alpha(resultColor(), 0xC0) : Draw.alpha(STEEL, 0x90);
            Draw.panel(g, sx, sy, 26, 26, i == 2 ? 0xE0141A21 : 0xE0182028, border);
        }
        int py = y + SLOT_Y + 13, plusX = cx + IN_X + 26 + 12;
        g.fill(plusX - 4, py - 1, plusX + 5, py + 1, Draw.alpha(STEEL, 0xB0));
        g.fill(plusX - 1, py - 4, plusX + 1, py + 5, Draw.alpha(STEEL, 0xB0));
        boolean blocked = (menu.getSlot(0).hasItem() && menu.getSlot(1).hasItem()) && !menu.getSlot(2).hasItem();
        int ac = blocked ? RED : menu.getSlot(2).hasItem() ? resultColor() : 0x50FFFFFF;
        int ax1 = cx + MAT_X + 32, ax2 = cx + RES_X - 6;
        g.fill(ax1, py - 1, ax2 - 4, py + 1, Draw.alpha(ac, 0xD0));
        for (int k = 0; k < 5; k++) g.fill(ax2 - 5 + k, py - 5 + k, ax2 - 4 + k, py + 6 - k, Draw.alpha(ac, 0xD0));
        if (blocked) {
            int m = (ax1 + ax2) / 2;
            for (int k = -3; k <= 3; k++) {
                g.fill(m + k, py + k, m + k + 1, py + k + 1, RED);
                g.fill(m + k, py - k, m + k + 1, py - k + 1, RED);
            }
        }
        // стоимость
        int cost = menu.getCost();
        int costY = y + SLOT_Y + 34;
        if (menu.getSlot(2).hasItem() && cost > 0) {
            boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
            String text = !creative && cost >= 40 ? Component.translatable("container.repair.expensive").getString()
                    : Component.translatable("container.repair.cost", cost).getString();
            int col = resultColor();
            int tw = font.width(text) + 12;
            Draw.panel(g, cx + (CW - tw) / 2, costY, tw, 14, 0xE00B0F14, Draw.alpha(col, 0xA0));
            g.drawCenteredString(font, text, cx + CW / 2, costY + 3, col);
        } else if (blocked) {
            g.drawCenteredString(font, Component.translatable("archud.anvil.incompatible"), cx + CW / 2, costY + 3, RED);
        }
        Draw.small(g, font, Component.translatable("container.inventory").getString(), cx + 8, y + INV_Y - 9, Draw.TEXT_DIM, 0.7F);
        for (int i = 3; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            boolean sug = suggestions.contains(s);
            int border = s == hoveredSlot ? Draw.alpha(accent, 0xB0) : sug ? Draw.alpha(GREEN, 0x80) : 0x30FFFFFF;
            Draw.panel(g, x + s.x - 1, y + s.y - 1, 18, 18, 0xC0080B10, border);
        }

        // ---------- справа: что получится
        renderResult(g, rx, y);
    }

    private void renderResult(GuiGraphics g, int x, int y) {
        Draw.panel(g, x, y, RW, H, 0xF00E1117, Draw.alpha(STEEL, 0x70));
        g.fill(x + 3, y, x + RW - 3, y + 1, Draw.alpha(STEEL, 0xE0));
        g.drawString(font, Component.translatable("archud.anvil.result"), x + 7, y + 7, Draw.TEXT, true);
        ItemStack left = menu.getSlot(0).getItem(), right = menu.getSlot(1).getItem(), out = menu.getSlot(2).getItem();
        int ty = y + 22;
        if (left.isEmpty()) {
            Draw.wrapped(g, font, Component.translatable("archud.anvil.place").getString() + " " + Component.translatable("archud.anvil.place2").getString(),
                    x + 7, ty, RW - 14, Draw.TEXT_DIM, 0.65F);
            return;
        }
        ItemStack shown = out.isEmpty() ? left : out;
        Draw.panel(g, x + 6, ty - 2, RW - 12, 22, 0xC0080B10, Draw.alpha(resultColor(), 0x50));
        g.renderItem(shown, x + 9, ty + 1);
        String nm = font.plainSubstrByWidth(shown.getHoverName().getString(), RW - 36);
        g.drawString(font, nm, x + 28, ty + 1, out.isEmpty() ? Draw.TEXT_DIM : Draw.TEXT, true);
        if (!out.isEmpty() && !out.getHoverName().getString().equals(left.getHoverName().getString())) {
            Draw.small(g, font, Component.translatable("archud.anvil.renamed").getString(), x + 28, ty + 11, GOLD, 0.55F);
        }
        ty += 26;
        // чары: было → станет
        Map<Enchantment, Integer> before = EnchantmentHelper.getEnchantments(left);
        Map<Enchantment, Integer> after = out.isEmpty() ? before : EnchantmentHelper.getEnchantments(out);
        Map<Enchantment, Integer> offered = right.isEmpty() ? Map.of() : EnchantmentHelper.getEnchantments(right);
        if (!after.isEmpty() || !offered.isEmpty()) {
            Draw.small(g, font, Component.translatable("archud.anvil.enchants").getString(), x + 7, ty, STEEL, 0.6F);
            ty += 8;
            for (Map.Entry<Enchantment, Integer> e : after.entrySet()) {
                if (ty > y + H - 50) break;
                Integer was = before.get(e.getKey());
                int col = was == null ? GREEN : was < e.getValue() ? GOLD : Draw.TEXT;
                String mark = was == null ? "+ " : was < e.getValue() ? "↑ " : "  ";
                Draw.small(g, font, mark + e.getKey().getFullname(e.getValue()).getString(), x + 7, ty, col, 0.65F);
                ty += 8;
            }
            // книги с чарами, которые не встанут (несовместимы или не для этого предмета)
            if (!out.isEmpty()) {
                for (Map.Entry<Enchantment, Integer> e : offered.entrySet()) {
                    if (after.containsKey(e.getKey()) || ty > y + H - 50) continue;
                    Draw.small(g, font, "✕ " + e.getKey().getFullname(e.getValue()).getString(), x + 7, ty, RED, 0.65F);
                    ty += 8;
                }
            }
            ty += 4;
        }
        // прочность до/после
        if (left.isDamageableItem()) {
            Draw.small(g, font, Component.translatable("archud.anvil.durability").getString(), x + 7, ty, STEEL, 0.6F);
            ty += 8;
            float a = 1F - left.getDamageValue() / (float) left.getMaxDamage();
            float b = out.isEmpty() ? a : 1F - out.getDamageValue() / (float) out.getMaxDamage();
            int bw = RW - 50;
            g.fill(x + 7, ty, x + 7 + bw, ty + 4, 0x60000000);
            g.fill(x + 7, ty, x + 7 + Math.round(bw * Math.min(a, b)), ty + 4, 0xFF8C9AAB);
            if (b > a) g.fill(x + 7 + Math.round(bw * a), ty, x + 7 + Math.round(bw * b), ty + 4, GREEN);
            String pct = Math.round(a * 100) + "% → " + Math.round(b * 100) + "%";
            Draw.small(g, font, pct, x + 11 + bw, ty - 1, b > a ? GREEN : Draw.TEXT_DIM, 0.55F);
            ty += 10;
        }
        // штраф и уровни
        int penalty = left.getBaseRepairCost() + (right.isEmpty() ? 0 : right.getBaseRepairCost());
        if (penalty > 0) {
            Draw.small(g, font, Component.translatable("archud.anvil.penalty", penalty).getString(), x + 7, ty, Draw.TEXT_DIM, 0.6F);
            ty += 9;
        }
        if (minecraft.player != null) {
            int have = minecraft.player.experienceLevel, cost = menu.getCost();
            String s = Component.translatable("archud.anvil.have", have).getString();
            Draw.small(g, font, s, x + 7, y + H - 12, cost > 0 && have < cost ? RED : Draw.TEXT_DIM, 0.6F);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // подписи рисуются в renderBg
    }

    // ------------------------------------------------------------------ ввод

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (hoveredSuggestion != null && button == 0) {
            putIntoMaterial(hoveredSuggestion);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.player.closeContainer();
            return true;
        }
        // пока печатаем имя — E и цифры не перехватываются окном
        if (name.keyPressed(key, scan, mods) || name.canConsumeInput()) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (name.charTyped(c, mods)) return true;
        return super.charTyped(c, mods);
    }

    @Override
    protected void containerTick() {
        name.tick();
    }

    @Override
    public void resize(net.minecraft.client.Minecraft mc, int w, int h) {
        String v = name.getValue();
        init(mc, w, h);
        name.setValue(v);
    }
}
