package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.menu.AccessoryInfo;
import com.arc4io.archud.mixin.SlotPosAccessor;
import com.arc4io.archud.network.ArcPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Новый инвентарь выживания (с нуля, не ретекстур). Три колонки:
 * <ul>
 *   <li>персонаж: броня и вторая рука вокруг модели игрока, уровень и опыт, характеристики;</li>
 *   <li>крафт 2×2, инвентарь и хотбар, эффекты снизу (не вылезают за окно — лишние сворачиваются в «+N»);</li>
 *   <li>аксессуары — слоты Curios, Aether и др. прямо в инвентаре, без отдельных кнопок; кнопки других модов
 *       (Регенерация и т. п.) — в полосе «Моды». Нет модов — нет колонки.</li>
 * </ul>
 * Книги рецептов нет. Работает с ванильным меню инвентаря (индексы 0–45) и с меню Arc HUD с аксессуарами (46+).
 */
public class ArcSurvivalScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final int HDR = 20, COL_L = 8, COL_M = 128, ROW = 18;
    private static final int RIGHT_W = 90;

    private final Map<Slot, int[]> original = new LinkedHashMap<>();
    private final List<Slot> accessories = new ArrayList<>();
    private List<ModButtons.Entry> modButtons = List.of();
    private boolean hasRight;
    private int accScroll, accRows, accVisibleRows, accY;
    private float xMouse, yMouse;
    private ArcButton sortBtn, packBtn;
    private final List<MobEffectInstance> hiddenEffects = new ArrayList<>();
    private int moreX = -1, moreY;

    public ArcSurvivalScreen(AbstractContainerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        for (int i = 46; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s instanceof AccessoryInfo) accessories.add(s);
        }
    }

    /** Слоты Curios/Aether встроены в инвентарь — их кнопки не нужны. */
    private boolean integrated() {
        return !accessories.isEmpty() || menu.getClass() != net.minecraft.world.inventory.InventoryMenu.class;
    }

    @Override
    protected void init() {
        modButtons = ModButtons.harvest(minecraft, width, height, cls -> integrated()
                && (cls.startsWith("top.theillusivec4.curios") || (cls.startsWith("com.aetherteam") && cls.contains("Accessor"))));
        hasRight = !accessories.isEmpty() || !modButtons.isEmpty();
        imageWidth = hasRight ? COL_M + 162 + 8 + RIGHT_W + 6 : COL_M + 162 + 8;
        imageHeight = 246;
        ScreenFit.ensure(this, imageWidth + 8, imageHeight + 8);
        super.init();
        layoutSlots();

        int mx = leftPos + COL_M;
        sortBtn = addRenderableWidget(new ArcButton(mx + 162 - 38, topPos + HDR + 50, 18, 16, Component.empty(),
                b -> ArcClient.platform().sendToServer(ArcPacket.SORT_INVENTORY)).icon(Icons.SORT));
        sortBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("archud.sort.tooltip")));
        packBtn = addRenderableWidget(new ArcButton(mx + 162 - 18, topPos + HDR + 50, 18, 16, Component.empty(),
                b -> ArcClient.platform().sendToServer(ArcPacket.OPEN_BACKPACK)).icon(Icons.BACKPACK));
        packBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("key.archud.backpack")));
        boolean hasPack = false;
        try {
            hasPack = !ArcHud.platform().findEquippedBackpack(minecraft.player).isEmpty();
        } catch (Exception ignored) {
        }
        packBtn.active = hasPack;
    }

    private static void move(Slot s, int x, int y) {
        ((SlotPosAccessor) s).archud$setX(x);
        ((SlotPosAccessor) s).archud$setY(y);
    }

    private void place(Slot s, int x, int y) {
        original.putIfAbsent(s, new int[]{s.x, s.y});
        move(s, x, y);
    }

    private void layoutSlots() {
        List<Slot> sl = menu.slots;
        if (sl.size() < 46) return;
        // персонаж: броня слева от модели, вторая рука под ней
        for (int i = 0; i < 4; i++) place(sl.get(5 + i), COL_L + 1, HDR + 6 + i * 19);
        place(sl.get(45), COL_L + 1, HDR + 6 + 4 * 19 + 4);
        // крафт 2×2 и результат
        int mx = COL_M;
        for (int i = 0; i < 4; i++) place(sl.get(1 + i), mx + 1 + (i % 2) * ROW, HDR + 14 + (i / 2) * ROW);
        place(sl.get(0), mx + 75, HDR + 23);
        // инвентарь и хотбар
        int invY = HDR + 80;
        for (int i = 0; i < 27; i++) place(sl.get(9 + i), mx + 1 + (i % 9) * ROW, invY + (i / 9) * ROW);
        for (int i = 0; i < 9; i++) place(sl.get(36 + i), mx + 1 + i * ROW, invY + 3 * ROW + 6);
        // аксессуары
        accY = HDR + 14;
        int modsH = modButtons.isEmpty() ? 0 : 14 + ((modButtons.size() + 3) / 4) * 22;
        accVisibleRows = Math.max(1, (imageHeight - 8 - modsH - accY) / ROW);
        accRows = (accessories.size() + 3) / 4;
        accScroll = Math.max(0, Math.min(accScroll, accRows - accVisibleRows));
        layoutAccessories();
    }

    private void layoutAccessories() {
        int rx = COL_M + 162 + 8 + 9;
        for (int i = 0; i < accessories.size(); i++) {
            int row = i / 4 - accScroll;
            Slot s = accessories.get(i);
            if (row < 0 || row >= accVisibleRows) place(s, -10000, -10000);
            else place(s, rx + (i % 4) * ROW, accY + row * ROW);
        }
    }

    @Override
    public void removed() {
        // ванильное меню инвентаря общее для всех экранов — вернуть слоты на места
        for (Map.Entry<Slot, int[]> e : original.entrySet()) move(e.getKey(), e.getValue()[0], e.getValue()[1]);
        super.removed();
    }

    // ------------------------------------------------------------------ отрисовка
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        xMouse = mouseX;
        yMouse = mouseY;
        renderBackground(g);
        super.render(g, mouseX, mouseY, pt);
        for (ModButtons.Entry e : modButtons) e.render(g, mouseX, mouseY, pt);
        renderTooltip(g, mouseX, mouseY);
        for (ModButtons.Entry e : modButtons) {
            if (e.inside(mouseX, mouseY) && !e.widget.isHovered()) g.renderTooltip(font, e.name, mouseX, mouseY);
        }
        if (moreX >= 0 && mouseX >= moreX && mouseX < moreX + 40 && mouseY >= moreY && mouseY < moreY + 22) {
            List<Component> lines = new ArrayList<>();
            for (MobEffectInstance e : hiddenEffects) lines.add(effectName(e).copy().append(" ").append(MobEffectUtil.formatDuration(e, 1.0F)));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
        Slot hs = hoveredSlot;
        if (hs instanceof AccessoryInfo ai && !hs.hasItem() && menu.getCarried().isEmpty()) {
            g.renderTooltip(font, ai.label(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mouseX, int mouseY) {
        int accent = Theme.accent();
        int x = leftPos, y = topPos, w = imageWidth, h = imageHeight;
        Draw.panel(g, x, y, w, h, 0xF20E1217, Draw.alpha(accent, 0x90));
        g.fill(x + 3, y, x + w - 3, y + 1, Draw.alpha(accent, 0xE0));
        g.fillGradient(x + 1, y + 1, x + w - 1, y + 36, Draw.alpha(accent, 0x1C), 0x00000000);
        LocalPlayer p = minecraft.player;
        // заголовок: имя и уровень
        g.drawString(font, title, x + COL_L, y + 6, Draw.alpha(accent, 0xFF), true);
        if (p != null) {
            String name = p.getGameProfile().getName();
            g.drawString(font, name, x + COL_M + 162 - font.width(name), y + 6, Draw.TEXT, true);
        }

        // --- персонаж
        int cx = x + COL_L, cy = y + HDR;
        Draw.panel(g, cx + 22, cy + 2, 92, 98, 0xB0060A10, Draw.alpha(accent, 0x50));
        g.fillGradient(cx + 23, cy + 3, cx + 113, cy + 99, Draw.alpha(accent, 0x26), 0x08000000);
        if (p != null) {
            int ex = cx + 68, ey = cy + 92;
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, ex, ey, 38, ex - xMouse, ey - 60 - yMouse, p);
            // опыт
            float xp = p.experienceProgress;
            g.fill(cx + 1, cy + 104, cx + 113, cy + 107, 0x90000000);
            g.fill(cx + 1, cy + 104, cx + 1 + Math.round(112 * xp), cy + 107, 0xFF9CFF5A);
            String lvl = Component.translatable("archud.inv.level", p.experienceLevel).getString();
            Draw.small(g, font, lvl, cx + 1, cy + 109, 0xFF9CFF5A, 0.75F);
            renderStats(g, p, cx, cy + 120);
        }

        // --- крафт
        int mx = x + COL_M;
        Draw.small(g, font, Component.translatable("archud.inv.crafting").getString(), mx, y + HDR + 4, Draw.TEXT_DIM, 0.75F);
        g.drawString(font, "➜", mx + 46 + 4, y + HDR + 27, Draw.alpha(accent, 0xC0), false);
        Draw.small(g, font, Component.translatable("archud.inv.inventory").getString(), mx, y + HDR + 70, Draw.TEXT_DIM, 0.75F);

        // ячейки
        int selected = p == null ? -1 : 36 + p.getInventory().selected;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (!s.isActive() || s.x < -1000) continue;
            int sx = x + s.x - 1, sy = y + s.y - 1;
            if (i == 0) {
                Draw.panel(g, sx - 4, sy - 4, 26, 26, 0xD0060A10, Draw.alpha(accent, 0xA0));
                continue;
            }
            boolean acc = s instanceof AccessoryInfo;
            int border = i == selected ? Draw.alpha(accent, 0xFF) : acc ? Draw.alpha(0xFFB388FF, 0x70) : 0x30FFFFFF;
            Draw.panel(g, sx, sy, 18, 18, 0xC0080C12, border);
        }
        // хотбар — подложка
        g.fill(mx - 2, y + HDR + 80 + 3 * ROW + 4, mx + 164, y + HDR + 80 + 3 * ROW + 6, Draw.alpha(accent, 0x30));

        renderEffects(g, mx, y + HDR + 80 + 4 * ROW + 12, 162, y + h - 6);

        // --- правая колонка
        if (hasRight) {
            int rx = x + COL_M + 162 + 8;
            g.fill(rx - 4, y + HDR, rx - 3, y + h - 8, Draw.alpha(accent, 0x30));
            if (!accessories.isEmpty()) {
                Draw.small(g, font, Component.translatable("archud.inv.accessories").getString(), rx, y + HDR + 4, Draw.TEXT_DIM, 0.75F);
                if (accRows > accVisibleRows) {
                    int bh = accVisibleRows * ROW;
                    int th = Math.max(8, bh * accVisibleRows / accRows);
                    int ty = y + accY + (bh - th) * accScroll / Math.max(1, accRows - accVisibleRows);
                    g.fill(rx + 83, y + accY, rx + 85, y + accY + bh, 0x30FFFFFF);
                    g.fill(rx + 83, ty, rx + 85, ty + th, Draw.alpha(accent, 0xC0));
                }
            }
            if (!modButtons.isEmpty()) {
                int rows = (modButtons.size() + 3) / 4;
                int my = y + h - 8 - rows * 22;
                Draw.small(g, font, Component.translatable("archud.inv.mods").getString(), rx, my - 11, Draw.TEXT_DIM, 0.75F);
                for (int i = 0; i < modButtons.size(); i++) {
                    int bx = rx + (i % 4) * 22, by = my + (i / 4) * 22;
                    Draw.panel(g, bx, by, 20, 20, 0x90060A10, 0x40FFFFFF);
                    modButtons.get(i).place(bx, by, 20, 20);
                }
            }
        }
    }

    private void renderStats(GuiGraphics g, LocalPlayer p, int x, int y) {
        String[][] rows = {
                {Component.translatable("archud.stat.health").getString(), HudRenderer.fmt(p.getHealth()) + " / " + HudRenderer.fmt(p.getMaxHealth())},
                {Component.translatable("archud.stat.armor").getString(), p.getArmorValue() + ""},
                {Component.translatable("archud.stat.damage").getString(), HudRenderer.fmt(InventoryExtras.withHeld(p, Attributes.ATTACK_DAMAGE))},
                {Component.translatable("archud.stat.attack_speed").getString(), String.format("%.2f", InventoryExtras.withHeld(p, Attributes.ATTACK_SPEED))},
                {Component.translatable("archud.stat.speed").getString(), String.format("%.2f", p.getAttributeValue(Attributes.MOVEMENT_SPEED) * 43.17F)},
                {Component.translatable("archud.stat.food").getString(), p.getFoodData().getFoodLevel() + " / 20"},
        };
        int maxY = topPos + imageHeight - 8;
        for (String[] r : rows) {
            if (y + 9 > maxY) break;
            Draw.small(g, font, r[0], x + 1, y + 1, Draw.TEXT_DIM, 0.75F);
            float vw = font.width(r[1]) * 0.75F;
            Draw.small(g, font, r[1], x + 113 - vw, y + 1, Draw.TEXT, 0.75F);
            y += 10;
        }
    }

    private static Component effectName(MobEffectInstance e) {
        Component n = e.getEffect().getDisplayName();
        if (e.getAmplifier() >= 1 && e.getAmplifier() <= 9) {
            n = n.copy().append(" ").append(Component.translatable("enchantment.level." + (e.getAmplifier() + 1)));
        }
        return n;
    }

    /** Эффекты: карточки 2 в ряд; что не помещается — в «+N» с подсказкой. */
    private void renderEffects(GuiGraphics g, int x, int y, int w, int maxY) {
        hiddenEffects.clear();
        moreX = -1;
        LocalPlayer p = minecraft.player;
        if (p == null) return;
        Collection<MobEffectInstance> all = p.getActiveEffects();
        if (all.isEmpty()) return;
        List<MobEffectInstance> list = new ArrayList<>();
        for (MobEffectInstance e : all) if (e.showIcon()) list.add(e);
        list.sort(java.util.Comparator.comparingInt(MobEffectInstance::getDuration));
        int cardW = (w - 4) / 2, cardH = 22;
        int rows = Math.max(0, (maxY - y) / (cardH + 2));
        int capacity = rows * 2;
        boolean overflow = list.size() > capacity;
        int shown = overflow ? Math.max(0, capacity - 1) : list.size();
        for (int i = 0; i < list.size(); i++) {
            MobEffectInstance e = list.get(i);
            if (i >= shown) {
                hiddenEffects.add(e);
                continue;
            }
            int ex = x + (i % 2) * (cardW + 4), ey = y + (i / 2) * (cardH + 2);
            int col = e.getEffect().isBeneficial() ? 0xFF9CFF5A : 0xFFFF6B6B;
            Draw.panel(g, ex, ey, cardW, cardH, 0xB0060A10, Draw.alpha(col, 0x60));
            TextureAtlasSprite sprite = minecraft.getMobEffectTextures().get(e.getEffect());
            g.blit(ex + 2, ey + 2, 0, 18, 18, sprite);
            String name = effectName(e).getString();
            float maxW = (cardW - 24) / 0.75F;
            if (font.width(name) > maxW) name = font.plainSubstrByWidth(name, (int) maxW - 6) + "…";
            Draw.small(g, font, name, ex + 22, ey + 3, Draw.TEXT, 0.75F);
            Draw.small(g, font, MobEffectUtil.formatDuration(e, 1.0F).getString(), ex + 22, ey + 12, Draw.alpha(col, 0xFF), 0.75F);
        }
        if (overflow) {
            int i = shown;
            moreX = x + (i % 2) * (cardW + 4);
            moreY = y + (i / 2) * (cardH + 2);
            Draw.panel(g, moreX, moreY, 40, cardH, 0xB0060A10, 0x60FFFFFF);
            g.drawCenteredString(font, "+" + hiddenEffects.size(), moreX + 20, moreY + 7, Draw.TEXT);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // подписи рисуются в renderBg
    }

    // ------------------------------------------------------------------ ввод
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (ModButtons.Entry e : modButtons) {
            if (e.inside(mx, my) && e.click(mx, my, button)) return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int rx = leftPos + COL_M + 162 + 8;
        if (hasRight && mx >= rx && accRows > accVisibleRows) {
            accScroll = Math.max(0, Math.min(accRows - accVisibleRows, accScroll - (int) Math.signum(delta)));
            layoutAccessories();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    protected boolean hasClickedOutside(double mx, double my, int left, int top, int button) {
        return mx < left || my < top || mx >= left + imageWidth || my >= top + imageHeight;
    }

    /** Экран из ванильного инвентаря игрока. */
    public static ArcSurvivalScreen vanilla(LocalPlayer player) {
        return new ArcSurvivalScreen(player.inventoryMenu, player.getInventory(), Component.translatable("archud.inv.title"));
    }

    public static boolean enabled() {
        return ArcConfig.get().customInventory;
    }

}
