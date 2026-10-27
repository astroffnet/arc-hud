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
 * Новый инвентарь выживания (свой интерфейс, не ретекстур). Три отдельные карточки:
 * <ul>
 *   <li>слева — характеристики персонажа и эффекты (много эффектов — компактные плашки, не вылезают за окно);</li>
 *   <li>в центре — привычная раскладка: броня, модель игрока, вторая рука, крафт 2×2, инвентарь и хотбар;</li>
 *   <li>справа — аксессуары по модам (Aether, Curios, Legends Core) и кнопки модов (Регенерация и др.).
 *       Нет модов — нет карточки.</li>
 * </ul>
 * Книги рецептов нет. Работает с ванильным меню инвентаря (индексы 0–45) и с меню Arc HUD с аксессуарами (46+).
 */
public class ArcSurvivalScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final int ROW = 18, GAP = 4;
    private static final int LW = 112, CW = 176, RW = 88, H = 194;
    private static final int ACC = 0xFFB388FF;

    private final Map<Slot, int[]> original = new LinkedHashMap<>();
    private final List<Slot> accessories = new ArrayList<>();
    private List<ModButtons.Entry> modButtons = List.of();
    private boolean hasRight;
    private int accScroll, accRows, accVisibleRows, accY;
    private float xMouse, yMouse;
    private ArcButton sortBtn, packBtn;
    private final List<MobEffectInstance> hiddenEffects = new ArrayList<>();
    private final List<int[]> chipHits = new ArrayList<>();
    private final List<MobEffectInstance> chipList = new ArrayList<>();
    private int moreX = -1, moreY, moreW;

    public ArcSurvivalScreen(AbstractContainerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        Map<String, List<Slot>> groups = new LinkedHashMap<>();
        groups.put("Aether", new ArrayList<>());
        groups.put("Curios", new ArrayList<>());
        groups.put("Legends Core", new ArrayList<>());
        for (int i = 46; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s instanceof AccessoryInfo ai) groups.get(ai.group().startsWith("aether_") ? "Aether" : "Curios").add(s);
        }
        if (menu instanceof com.arc4io.archud.menu.AccessoryProvider ap) groups.get("Legends Core").addAll(ap.extraAccessorySlots());
        for (Map.Entry<String, List<Slot>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            accLines.add(new AccLine(e.getKey(), null));
            List<Slot> list = e.getValue();
            for (int i = 0; i < list.size(); i += 4) accLines.add(new AccLine(null, list.subList(i, Math.min(list.size(), i + 4))));
            accessories.addAll(list);
        }
    }

    /** Строка правой карточки: заголовок группы (Aether, Curios, Legends Core) или ряд из 4 слотов. */
    private record AccLine(String title, List<Slot> slots) {
        int height() {
            return title != null ? 11 : 18;
        }
    }

    private final List<AccLine> accLines = new ArrayList<>();
    private final List<Object[]> accHeaders = new ArrayList<>();
    private int accArea, accMaxScroll;

    /** Слоты Curios/Aether встроены в инвентарь — их кнопки не нужны. */
    private boolean integrated() {
        return !accessories.isEmpty() || menu.getClass() != net.minecraft.world.inventory.InventoryMenu.class;
    }

    private static int coreX() {
        return LW + GAP;
    }

    private static int rightX() {
        return LW + GAP + CW + GAP;
    }

    @Override
    protected void init() {
        modButtons = new ArrayList<>(ModButtons.harvest(minecraft, width, height, cls -> integrated()
                && (cls.startsWith("top.theillusivec4.curios") || (cls.startsWith("com.aetherteam") && cls.contains("Accessor")))));
        ModButtons.addShortcuts(minecraft, modButtons);
        hasRight = !accessories.isEmpty() || !modButtons.isEmpty();
        imageWidth = hasRight ? rightX() + RW : LW + GAP + CW;
        imageHeight = H;
        ScreenFit.ensure(this, imageWidth + 8, imageHeight + 8);
        super.init();
        layoutSlots();

        int cx = leftPos + coreX();
        sortBtn = addRenderableWidget(new ArcButton(cx + 100, topPos + 76, 18, 16, Component.empty(),
                b -> ArcClient.platform().sendToServer(ArcPacket.SORT_INVENTORY)).icon(Icons.SORT));
        sortBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("archud.sort.tooltip")));
        packBtn = addRenderableWidget(new ArcButton(cx + 120, topPos + 76, 18, 16, Component.empty(),
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
        int c = coreX();
        // броня — столбик слева от модели, вторая рука — в углу окна модели (как в ванилле)
        for (int i = 0; i < 4; i++) place(sl.get(5 + i), c + 8, 21 + i * ROW);
        place(sl.get(45), c + 77, 75);
        // крафт 2×2 и результат
        for (int i = 0; i < 4; i++) place(sl.get(1 + i), c + 101 + (i % 2) * ROW, 30 + (i / 2) * ROW);
        place(sl.get(0), c + 153, 39);
        // инвентарь и хотбар
        for (int i = 0; i < 27; i++) place(sl.get(9 + i), c + 8 + (i % 9) * ROW, 111 + (i / 9) * ROW);
        for (int i = 0; i < 9; i++) place(sl.get(36 + i), c + 8 + i * ROW, 170);
        // аксессуары
        accY = 18;
        int modsH = modButtons.isEmpty() ? 0 : 14 + ((modButtons.size() + 2) / 3) * 24;
        accArea = Math.max(18, H - 6 - modsH - accY);
        accMaxScroll = 0;
        int tail = 0;
        for (int i = accLines.size() - 1; i >= 0; i--) {
            tail += accLines.get(i).height();
            if (tail > accArea) {
                accMaxScroll = i + 1;
                break;
            }
        }
        accRows = accLines.size();
        accVisibleRows = accRows - accMaxScroll;
        accScroll = Math.max(0, Math.min(accScroll, accMaxScroll));
        layoutAccessories();
    }

    private void layoutAccessories() {
        int rx = rightX() + 7;
        for (Slot s : accessories) place(s, -10000, -10000);
        accHeaders.clear();
        int yy = accY;
        for (int li = accScroll; li < accLines.size(); li++) {
            AccLine l = accLines.get(li);
            if (yy + l.height() > accY + accArea) break;
            if (l.title() != null) accHeaders.add(new Object[]{l.title(), yy});
            else for (int k = 0; k < l.slots().size(); k++) place(l.slots().get(k), rx + 1 + k * ROW, yy + 1);
            yy += l.height();
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
        for (int k = 0; k < chipHits.size(); k++) {
            int[] r = chipHits.get(k);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                MobEffectInstance e = chipList.get(k);
                g.renderTooltip(font, effectName(e).copy().append(" ").append(MobEffectUtil.formatDuration(e, 1.0F)), mouseX, mouseY);
            }
        }
        if (moreX >= 0 && mouseX >= moreX && mouseX < moreX + moreW && mouseY >= moreY && mouseY < moreY + 14) {
            List<Component> lines = new ArrayList<>();
            for (MobEffectInstance e : hiddenEffects) lines.add(effectName(e).copy().append(" ").append(MobEffectUtil.formatDuration(e, 1.0F)));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
        Slot hs = hoveredSlot;
        if (hs instanceof AccessoryInfo ai && !hs.hasItem() && menu.getCarried().isEmpty()) {
            g.renderTooltip(font, ai.label(), mouseX, mouseY);
        } else if (hs != null && !hs.hasItem() && menu.getCarried().isEmpty() && menu instanceof com.arc4io.archud.menu.AccessoryProvider ap
                && ap.accessoryLabel(hs) != null) {
            g.renderTooltip(font, ap.accessoryLabel(hs), mouseX, mouseY);
        }
    }

    private void card(GuiGraphics g, int x, int y, int w, int h, int accent, String title) {
        Draw.panel(g, x, y, w, h, 0xF20E1217, Draw.alpha(accent, 0x80));
        g.fill(x + 3, y, x + w - 3, y + 1, Draw.alpha(accent, 0xE0));
        g.fillGradient(x + 1, y + 1, x + w - 1, y + 24, Draw.alpha(accent, 0x18), 0x00000000);
        if (title != null) Draw.small(g, font, title, x + 7, y + 6, Draw.alpha(accent, 0xFF), 0.75F);
    }

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mouseX, int mouseY) {
        int accent = Theme.accent();
        int x = leftPos, y = topPos;
        LocalPlayer p = minecraft.player;

        // ---------- левая карточка: характеристики и эффекты
        card(g, x, y, LW, H, accent, Component.translatable("archud.inv.stats").getString());
        if (p != null) renderStats(g, p, x + 7, y + 17);
        int effY = y + 17 + 6 * 10 + 6;
        g.fill(x + 7, effY - 3, x + LW - 7, effY - 2, 0x20FFFFFF);
        Draw.small(g, font, Component.translatable("archud.inv.effects").getString(), x + 7, effY + 1, Draw.alpha(accent, 0xFF), 0.75F);
        renderEffects(g, x + 6, effY + 11, LW - 12, y + H - 6);

        // ---------- центр: персонаж, крафт, инвентарь
        int c = x + coreX();
        card(g, c, y, CW, H, accent, null);
        if (p != null) {
            g.drawString(font, p.getGameProfile().getName(), c + 7, y + 6, Draw.TEXT, true);
            // уровень и опыт справа в шапке
            String lvl = Component.translatable("archud.inv.level", p.experienceLevel).getString();
            int lw = Math.round(font.width(lvl) * 0.75F);
            int bx = c + CW - 7 - 56;
            Draw.small(g, font, lvl, bx - 5 - lw, y + 6.5F, 0xFF9CFF5A, 0.75F);
            g.fill(bx, y + 8, bx + 56, y + 11, 0x90000000);
            g.fill(bx, y + 8, bx + Math.round(56 * p.experienceProgress), y + 11, 0xFF9CFF5A);
        }
        // окно модели
        Draw.panel(g, c + 26, y + 20, 70, 74, 0xB0060A10, Draw.alpha(accent, 0x50));
        g.fillGradient(c + 27, y + 21, c + 95, y + 93, Draw.alpha(accent, 0x24), 0x08000000);
        if (p != null) {
            int ex = c + 61, ey = y + 88;
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, ex, ey, 30, ex - xMouse, ey - 50 - yMouse, p);
        }
        Draw.small(g, font, Component.translatable("archud.inv.crafting").getString(), c + 101, y + 21, Draw.TEXT_DIM, 0.75F);
        g.drawString(font, "➜", c + 139, y + 44, Draw.alpha(accent, 0xC0), false);
        Draw.small(g, font, Component.translatable("archud.inv.inventory").getString(), c + 8, y + 101, Draw.TEXT_DIM, 0.75F);

        // ---------- правая карточка: аксессуары и моды
        if (hasRight) {
            int rx = x + rightX();
            card(g, rx, y, RW, H, accent, accessories.isEmpty() ? null : Component.translatable("archud.inv.accessories").getString());
            for (Object[] hd : accHeaders) {
                int hy = y + (Integer) hd[1];
                String t = (String) hd[0];
                Draw.small(g, font, t, rx + 8, hy + 3, Draw.alpha(ACC, 0xD0), 0.6F);
                g.fill(rx + 8 + Math.round(font.width(t) * 0.6F) + 3, hy + 5, rx + 8 + 4 * ROW, hy + 6, 0x30FFFFFF);
            }
            if (accMaxScroll > 0) {
                int bh = accArea;
                int th = Math.max(8, bh * accVisibleRows / Math.max(1, accRows));
                int ty = y + accY + (bh - th) * accScroll / Math.max(1, accMaxScroll);
                g.fill(rx + RW - 5, y + accY, rx + RW - 3, y + accY + bh, 0x30FFFFFF);
                g.fill(rx + RW - 5, ty, rx + RW - 3, ty + th, Draw.alpha(accent, 0xC0));
            }
            if (!modButtons.isEmpty()) {
                int rows = (modButtons.size() + 2) / 3;
                int my = y + H - 6 - rows * 24;
                g.fill(rx + 7, my - 13, rx + RW - 7, my - 12, 0x20FFFFFF);
                Draw.small(g, font, Component.translatable("archud.inv.mods").getString(), rx + 7, my - 9, Draw.alpha(accent, 0xFF), 0.75F);
                for (int i = 0; i < modButtons.size(); i++) {
                    int bx = rx + 8 + (i % 3) * 25, by = my + (i / 3) * 24;
                    Draw.panel(g, bx, by, 22, 22, 0x90060A10, 0x40FFFFFF);
                    modButtons.get(i).place(bx + 1, by + 1, 20, 20);
                }
            }
        }

        // ---------- ячейки
        int selected = p == null ? -1 : 36 + p.getInventory().selected;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (!s.isActive() || s.x < -1000) continue;
            int sx = x + s.x - 1, sy = y + s.y - 1;
            if (i == 0) {
                Draw.panel(g, sx - 4, sy - 4, 26, 26, 0xD0060A10, Draw.alpha(accent, 0xA0));
                continue;
            }
            boolean acc = s instanceof AccessoryInfo || accessories.contains(s);
            int border = i == selected ? Draw.alpha(accent, 0xFF) : acc ? Draw.alpha(ACC, 0x70) : 0x30FFFFFF;
            Draw.panel(g, sx, sy, 18, 18, 0xC0080C12, border);
            if (acc && !s.hasItem() && menu instanceof com.arc4io.archud.menu.AccessoryProvider ap) {
                Component sh = ap.accessoryShort(s);
                if (sh != null) Draw.smallCentered(g, font, sh.getString(), sx + 9, sy + 7, 0x90B388FF, 0.5F);
            }
        }
        // разделитель хотбара
        g.fill(c + 7, y + 166, c + CW - 7, y + 167, Draw.alpha(accent, 0x40));
    }

    private void renderStats(GuiGraphics g, LocalPlayer p, int x, int y) {
        Object[][] rows = {
                {Icons.HEART, Component.translatable("archud.stat.health").getString(), HudRenderer.fmt(p.getHealth()) + "/" + HudRenderer.fmt(p.getMaxHealth())},
                {Icons.ARMOR, Component.translatable("archud.stat.armor").getString(), p.getArmorValue() + ""},
                {Icons.SWORD, Component.translatable("archud.stat.damage").getString(), HudRenderer.fmt(InventoryExtras.withHeld(p, Attributes.ATTACK_DAMAGE))},
                {Icons.BOLT, Component.translatable("archud.stat.attack_speed").getString(), String.format("%.2f", InventoryExtras.withHeld(p, Attributes.ATTACK_SPEED))},
                {Icons.SPEED, Component.translatable("archud.stat.speed").getString(), String.format("%.2f", p.getAttributeValue(Attributes.MOVEMENT_SPEED) * 43.17F)},
                {Icons.FOOD, Component.translatable("archud.stat.food").getString(), p.getFoodData().getFoodLevel() + "/20"},
        };
        for (Object[] r : rows) {
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(0.75F, 0.75F, 1F);
            Draw.icon(g, (Integer) r[0], 0, 0);
            g.pose().popPose();
            String label = (String) r[1], value = (String) r[2];
            float vw = font.width(value) * 0.7F;
            float maxL = (LW - 14 - 10 - vw - 4) / 0.7F;
            if (font.width(label) > maxL) label = font.plainSubstrByWidth(label, (int) maxL - font.width("…")) + "…";
            Draw.small(g, font, label, x + 9, y + 1.5F, Draw.TEXT_DIM, 0.7F);
            Draw.small(g, font, value, x + LW - 14 - vw, y + 1.5F, Draw.TEXT, 0.7F);
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

    private static final Map<Class<?>, java.util.Optional<java.lang.reflect.Method>> ICON_CHECK = new java.util.HashMap<>();

    /** Способности Legends — тоже эффекты; часть из них мод сам прячет из списка (shouldRenderIcon). */
    private static boolean modWantsIcon(MobEffectInstance e) {
        Class<?> c = e.getEffect().getClass();
        java.lang.reflect.Method m = ICON_CHECK.computeIfAbsent(c, k -> {
            try {
                java.lang.reflect.Method mm = k.getMethod("shouldRenderIcon");
                return java.util.Optional.ofNullable(mm.getReturnType() == boolean.class ? mm : null);
            } catch (Throwable t) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
        if (m == null) return true;
        try {
            return (Boolean) m.invoke(e.getEffect());
        } catch (Throwable t) {
            return true;
        }
    }

    /** Эффекты: карточки в столбик; если не помещаются — компактные плашки по две в ряд; остаток — «+N». */
    private void renderEffects(GuiGraphics g, int x, int y, int w, int maxY) {
        hiddenEffects.clear();
        chipHits.clear();
        chipList.clear();
        moreX = -1;
        LocalPlayer p = minecraft.player;
        if (p == null) return;
        Collection<MobEffectInstance> all = p.getActiveEffects();
        List<MobEffectInstance> list = new ArrayList<>();
        for (MobEffectInstance e : all) if (e.showIcon() && modWantsIcon(e)) list.add(e);
        if (list.isEmpty()) {
            Draw.small(g, font, Component.translatable("archud.inv.no_effects").getString(), x + 1, y + 2, 0x60FFFFFF, 0.65F);
            return;
        }
        list.sort(java.util.Comparator.comparingInt(MobEffectInstance::getDuration));
        int cardH = 20;
        int rows = Math.max(0, (maxY - y + 2) / (cardH + 2));
        if (list.size() > rows) {
            renderEffectChips(g, list, x, y, w, maxY);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            MobEffectInstance e = list.get(i);
            int ey = y + i * (cardH + 2);
            int col = e.getEffect().isBeneficial() ? 0xFF9CFF5A : 0xFFFF6B6B;
            Draw.panel(g, x, ey, w, cardH, 0xB0060A10, Draw.alpha(col, 0x60));
            TextureAtlasSprite sprite = minecraft.getMobEffectTextures().get(e.getEffect());
            g.blit(x + 2, ey + 2, 0, 16, 16, sprite);
            String name = effectName(e).getString();
            float maxW = (w - 22) / 0.7F;
            if (font.width(name) > maxW) name = font.plainSubstrByWidth(name, (int) maxW - 6) + "…";
            Draw.small(g, font, name, x + 20, ey + 3, Draw.TEXT, 0.7F);
            Draw.small(g, font, MobEffectUtil.formatDuration(e, 1.0F).getString(), x + 20, ey + 11, Draw.alpha(col, 0xFF), 0.7F);
        }
    }

    private void renderEffectChips(GuiGraphics g, List<MobEffectInstance> list, int x, int y, int w, int maxY) {
        int gap = 2, perRow = 2;
        int chipW = (w - gap) / perRow, chipH = 14;
        int rows = Math.max(0, (maxY - y + gap) / (chipH + gap));
        int capacity = perRow * rows;
        boolean overflow = list.size() > capacity;
        int shown = overflow ? Math.max(0, capacity - 1) : list.size();
        for (int i = 0; i < list.size(); i++) {
            MobEffectInstance e = list.get(i);
            if (i >= shown) {
                hiddenEffects.add(e);
                continue;
            }
            int ex = x + (i % perRow) * (chipW + gap), ey = y + (i / perRow) * (chipH + gap);
            int col = e.getEffect().isBeneficial() ? 0xFF9CFF5A : 0xFFFF6B6B;
            Draw.panel(g, ex, ey, chipW, chipH, 0xB0060A10, Draw.alpha(col, 0x60));
            TextureAtlasSprite sprite = minecraft.getMobEffectTextures().get(e.getEffect());
            g.blit(ex + 1, ey + 1, 0, 12, 12, sprite);
            String t = MobEffectUtil.formatDuration(e, 1.0F).getString();
            if (e.getAmplifier() > 0) t = t + " ·" + (e.getAmplifier() + 1);
            Draw.small(g, font, t, ex + 15, ey + 4.5F, Draw.alpha(col, 0xFF), 0.6F);
            chipHits.add(new int[]{ex, ey, chipW, chipH, i});
            chipList.add(e);
        }
        if (overflow) {
            int i = shown;
            moreX = x + (i % perRow) * (chipW + gap);
            moreY = y + (i / perRow) * (chipH + gap);
            moreW = chipW;
            Draw.panel(g, moreX, moreY, chipW, chipH, 0xB0060A10, 0x60FFFFFF);
            g.drawCenteredString(font, "+" + hiddenEffects.size(), moreX + chipW / 2, moreY + 3, Draw.TEXT);
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
        int rx = leftPos + rightX();
        if (hasRight && mx >= rx && accMaxScroll > 0) {
            accScroll = Math.max(0, Math.min(accMaxScroll, accScroll - (int) Math.signum(delta)));
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
