package com.arc4io.archud.client.hud;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.bhop.BunnyHop;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.util.NbtEnergy;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Arc HUD — главный рендер интерфейса.
 *
 * Список возможностей (каждая отключается в настройках):
 *  1  полоса здоровья                 2  поглощение (золотая полоса)       3  числовые значения HP
 *  4  «след» урона (задержанная полоса) 5  вспышка лечения                   6  цвет HP от яда/иссушения/заморозки
 *  7  виньетка при низком HP           8  полоса голода                      9  насыщенность (saturation)
 * 10  цвет голода при эффекте Голод    11  полоса брони                     12  твёрдость брони (toughness)
 * 13  полоса воздуха                   14  здоровье скакуна                  15  сила прыжка скакуна
 * 16  новый хотбар с плавным выбором   17  слот второй руки                  18  номера слотов
 * 19  индикатор перезарядки атаки      20  панель эффектов с таймерами       21  прогресс длительности эффектов
 * 22  координаты                       23  направление взгляда              24  биом
 * 25  измерение (с поддержкой модов)   26  FPS                               27  пинг
 * 28  игровое время                    29  реальное время                    30  счётчик дней
 * 31  погода                           32  скорость (блоков/сек)             33  уровень света
 * 34  компас-лента                     35  информация о цели (моб + HP)      36  информация о блоке
 * 37  название мода у блока/моба       38  прочность брони                   39  прочность предметов в руках
 * 40  мигание при низкой прочности     41  счётчик стрел                     42  счётчик тотемов
 * 43  энергия предметов (FE / IC2 / NBT) 44 предупреждение о падении          45  всплывающие цифры урона/лечения
 * 46  уведомления о подборе предметов  47  статусы движения (бег/присед/...) 48  цветовые темы измерений
 * 49  полосы аддонов (ArcHudApi)       50  спидометр BunnyHop
 */
public final class HudRenderer {
    private static float shownHealth = -1, trailHealth = -1, shownFood = -1, shownSat = -1, shownXp = -1, hotbarSel = -1;
    private static long lastFrame;
    private static long healFlashUntil, hurtFlashUntil;
    private static float prevHealth = -1;
    private static int frames;
    static int fps;
    private static long fpsTimer;
    private static double speedBps;
    private static Vec3 lastPos;
    private static ResourceKey<Level> lastDim;
    private static Map<Item, Integer> lastCounts = new HashMap<>();
    private static boolean countsReady;
    private static final List<Popup> POPUPS = new ArrayList<>();
    static final List<Pickup> PICKUPS = new ArrayList<>();
    static final Map<MobEffect, Integer> EFFECT_MAX = new HashMap<>();

    private record Popup(String text, int color, long start, int dx) {}

    static final class Pickup {
        final ItemStack stack;
        int count;
        long time;

        Pickup(ItemStack stack, int count, long time) {
            this.stack = stack;
            this.count = count;
            this.time = time;
        }
    }

    private HudRenderer() {}

    // =====================================================================
    //  Что заменяем у ванильного HUD
    // =====================================================================
    private static boolean baseActive() {
        Minecraft mc = Minecraft.getInstance();
        return ArcConfig.get().enabled && mc.player != null && !mc.player.isSpectator();
    }

    public static boolean replacesStatusBars() {
        return baseActive();
    }

    public static boolean replacesHotbar() {
        return baseActive() && ArcConfig.get().customHotbar;
    }

    public static boolean replacesEffects() {
        Minecraft mc = Minecraft.getInstance();
        return ArcConfig.get().enabled && ArcConfig.get().effectsPanel && mc.player != null;
    }

    /** Сколько пикселей снизу занимает HUD (для Forge leftHeight/rightHeight, чтобы HUD других модов вставал выше). */
    public static int occupiedHeight() {
        return occupied;
    }

    private static int occupied = 54;

    // =====================================================================
    //  Тик: трекеры
    // =====================================================================
    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        ArcConfig cfg = ArcConfig.get();
        long now = Util.getMillis();
        Theme.update(mc);

        if (mc.level.dimension() != lastDim) {
            lastDim = mc.level.dimension();
            countsReady = false;
            prevHealth = -1;
            lastPos = null;
        }

        Vec3 pos = p.position();
        if (lastPos != null) {
            double dx = pos.x - lastPos.x, dz = pos.z - lastPos.z;
            double inst = Math.sqrt(dx * dx + dz * dz) * 20.0;
            speedBps = speedBps * 0.6 + inst * 0.4;
        }
        lastPos = pos;

        float hp = p.getHealth() + p.getAbsorptionAmount();
        if (prevHealth >= 0 && Math.abs(hp - prevHealth) >= 0.05F) {
            float d = hp - prevHealth;
            if (cfg.damageNumbers) {
                POPUPS.add(new Popup((d > 0 ? "+" : "") + fmt(d), d > 0 ? 0xFF7CFF6B : 0xFFFF5555, now, p.getRandom().nextInt(31) - 15));
                if (POPUPS.size() > 12) POPUPS.remove(0);
            }
            if (d < 0) hurtFlashUntil = now + 350;
            else healFlashUntil = now + 600;
        }
        prevHealth = hp;

        if (cfg.pickupNotifications) {
            Map<Item, Integer> counts = new HashMap<>();
            Map<Item, ItemStack> samples = new HashMap<>();
            Inventory inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) {
                    counts.merge(s.getItem(), s.getCount(), Integer::sum);
                    samples.putIfAbsent(s.getItem(), s);
                }
            }
            ItemStack carried = p.containerMenu.getCarried();
            if (!carried.isEmpty()) counts.merge(carried.getItem(), carried.getCount(), Integer::sum);

            if (countsReady && mc.screen == null) {
                for (Map.Entry<Item, Integer> e : counts.entrySet()) {
                    int diff = e.getValue() - lastCounts.getOrDefault(e.getKey(), 0);
                    if (diff > 0 && samples.containsKey(e.getKey())) addPickup(samples.get(e.getKey()), diff, now);
                }
            }
            lastCounts = counts;
            countsReady = true;
        }
        POPUPS.removeIf(pp -> now - pp.start() > 1200);
        PICKUPS.removeIf(pk -> now - pk.time > 3500);
    }

    private static void addPickup(ItemStack sample, int count, long now) {
        for (Pickup pk : PICKUPS) {
            if (ItemStack.isSameItemSameTags(pk.stack, sample)) {
                pk.count += count;
                pk.time = now;
                return;
            }
        }
        PICKUPS.add(new Pickup(sample.copyWithCount(1), count, now));
        if (PICKUPS.size() > 6) PICKUPS.remove(0);
    }

    // =====================================================================
    //  Рендер
    // =====================================================================
    public static void render(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        ArcConfig cfg = ArcConfig.get();
        com.arc4io.archud.client.map.WaypointWorldRenderer.renderOverlay(g);
        if (!cfg.enabled) return;

        long now = Util.getMillis();
        frames++;
        if (now - fpsTimer >= 1000) {
            fps = frames;
            frames = 0;
            fpsTimer = now;
        }
        float dt = lastFrame == 0 ? 0.016F : Math.min(0.1F, (now - lastFrame) / 1000F);
        lastFrame = now;
        float k = cfg.smoothAnimations ? 1F - (float) Math.pow(0.0005, dt) : 1F;

        int w = g.guiWidth();
        int h = g.guiHeight();
        int accent = Theme.accent();
        Font font = mc.font;
        boolean survival = mc.gameMode != null && mc.gameMode.canHurtPlayer();
        boolean hasXp = mc.gameMode != null && mc.gameMode.hasExperience();
        boolean spectator = p.isSpectator();
        boolean debug = mc.options.renderDebug;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (!spectator) {
            if (survival && cfg.lowHealthWarning) lowHealthVignette(g, p, w, h);
            // основные полосы и хотбар — масштаб вокруг нижнего центра экрана
            float bs = HudScale.get("bars");
            boolean scaled = Math.abs(bs - 1F) > 0.001F;
            if (scaled) {
                g.pose().pushPose();
                g.pose().translate(w / 2F, h, 0);
                g.pose().scale(bs, bs, 1F);
                g.pose().translate(-w / 2F, -h, 0);
            }
            if (cfg.customHotbar) hotbar(g, mc, p, w, h, partialTick, accent, k);
            if (survival) statusBars(g, mc, p, font, w, h, accent, k, now);
            xpRow(g, p, font, w, h, accent, hasXp, survival, k);
            chips(g, p, font, w, h, accent);
            if (scaled) g.pose().popPose();
            if (survival && cfg.fallWarning) fallWarning(g, p, font, w, h);
            if (cfg.damageNumbers) popups(g, font, w, h, now);
            if (cfg.bhopHud && cfg.bunnyHop) bhopHud(g, font, w, h, accent);
        }
        HudBlocks.renderAnchored(g, mc, p, font, w, h, accent, now, debug, spectator);
        com.arc4io.archud.client.fx.InspectCard.render(g, font, w, h, partialTick);
        if (com.arc4io.archud.client.camera.ArcCamera.active() && cfg.cameraCrosshair && com.arc4io.archud.client.camera.ArcCamera.aiming()) {
            // прицел камеры Arc (в виде от третьего лица ванильного прицела нет)
            int cx = w / 2, cy = h / 2;
            g.fill(cx - 4, cy, cx - 1, cy + 1, 0xE0FFFFFF);
            g.fill(cx + 2, cy, cx + 5, cy + 1, 0xE0FFFFFF);
            g.fill(cx, cy - 4, cx + 1, cy - 1, 0xE0FFFFFF);
            g.fill(cx, cy + 2, cx + 1, cy + 5, 0xE0FFFFFF);
            g.fill(cx, cy, cx + 1, cy + 1, Draw.alpha(accent, 0xFF));
        }
    }

    // ---------------------------------------------------------------- хотбар
    private static void hotbar(GuiGraphics g, Minecraft mc, LocalPlayer p, int w, int h, float pt, int accent, float k) {
        ArcConfig cfg = ArcConfig.get();
        int x = w / 2 - 92;
        int y = h - 23;
        Draw.panel(g, x, y, 184, 22, Draw.PANEL, Draw.alpha(accent, 0x50));
        g.fill(x + 2, y, x + 182, y + 1, Draw.alpha(accent, 0x90));

        int sel = p.getInventory().selected;
        if (hotbarSel < 0) hotbarSel = sel;
        hotbarSel += (sel - hotbarSel) * Math.min(1F, k * 2.2F);
        int sx = Math.round(x + 2 + hotbarSel * 20);

        for (int i = 0; i < 9; i++) {
            int slotX = x + 2 + i * 20;
            g.fill(slotX + 1, y + 2, slotX + 19, y + 20, 0x30FFFFFF);
        }
        // выделение
        g.fillGradient(sx, y + 1, sx + 20, y + 21, Draw.alpha(accent, 0x70), Draw.alpha(accent, 0x20));
        Draw.outline(g, sx, y + 1, 20, 20, Draw.alpha(accent, 0xFF));
        g.fill(sx + 4, y + 20, sx + 16, y + 21, 0xFFFFFFFF);

        for (int i = 0; i < 9; i++) {
            int slotX = x + 2 + i * 20;
            renderSlot(g, mc, p, p.getInventory().items.get(i), slotX + 2, y + 3, pt);
            if (cfg.slotNumbers) Draw.small(g, mc.font, Integer.toString(i + 1), slotX + 2, y + 2, 0x90FFFFFF, 0.5F);
        }

        // перезарядка атаки (на выбранном слоте)
        if (cfg.attackCooldown) {
            float f = p.getAttackStrengthScale(0F);
            if (f < 1F) {
                int bw = Math.round(16 * f);
                g.fill(sx + 2, y + 18, sx + 18, y + 19, 0x80000000);
                g.fill(sx + 2, y + 18, sx + 2 + bw, y + 19, 0xFFFFD54F);
            }
        }

        // вторая рука
        ItemStack off = p.getOffhandItem();
        if (cfg.offhandSlot && !off.isEmpty()) {
            boolean left = p.getMainArm() == HumanoidArm.RIGHT;
            int ox = left ? x - 25 : x + 184 + 3;
            Draw.panel(g, ox, y, 22, 22, Draw.PANEL, Draw.alpha(accent, 0x50));
            renderSlot(g, mc, p, off, ox + 3, y + 3, pt);
        }
    }

    private static void renderSlot(GuiGraphics g, Minecraft mc, LocalPlayer p, ItemStack stack, int x, int y, float pt) {
        if (stack.isEmpty()) return;
        float pop = stack.getPopTime() - pt;
        if (pop > 0F) {
            float s = 1F + pop / 5F;
            g.pose().pushPose();
            g.pose().translate(x + 8, y + 12, 0);
            g.pose().scale(1F / s, (s + 1F) / 2F, 1F);
            g.pose().translate(-(x + 8), -(y + 12), 0);
        }
        g.renderItem(p, stack, x, y, 0);
        if (pop > 0F) g.pose().popPose();
        g.renderItemDecorations(mc.font, stack, x, y);
    }

    // ---------------------------------------------------------- полосы статуса
    private static void statusBars(GuiGraphics g, Minecraft mc, LocalPlayer p, Font font, int w, int h, int accent, float k, long now) {
        ArcConfig cfg = ArcConfig.get();
        int rowY = h - 41;
        int leftX = w / 2 - 92;
        int rightX = w / 2 + 12;
        int rowW = 80;

        if (cfg.healthBar) healthRow(g, mc, p, font, leftX, rowY, rowW, accent, k, now);
        if (cfg.hungerBar) foodRow(g, p, font, rightX, rowY, rowW, accent, k);

        // большие полосы модов (мана/выносливость Legends и т.п.) — над HP и едой
        int leftY = h - 41;
        int rightY = h - 41;
        if (cfg.addonBars) {
            for (ArcHudApi.Bar bar : ArcHudApi.bars()) {
                if (!bar.main()) continue;
                float[] v = values(bar);
                if (v == null) continue;
                boolean right = bar.side() == ArcHudApi.Side.RIGHT;
                int y = (right ? rightY : leftY) - 12;
                mainRow(g, font, right ? rightX : leftX, y, rowW, bar, v, accent, right);
                if (right) rightY = y;
                else leftY = y;
            }
        }
        // верхний тонкий ряд
        leftY -= 10;
        rightY -= 10;
        if (cfg.armorBar && p.getArmorValue() > 0) {
            int armor = p.getArmorValue();
            float tough = (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
            float cap = armor <= 20 ? 20F : (float) Math.ceil(armor / 20F) * 20F;
            String txt = armor + (cfg.toughness && tough > 0 ? "  ◆" + fmt(tough) : "");
            thinRow(g, font, leftX, leftY, rowW, Icons.ARMOR, armor / cap, 0xFFCFD8DC, 0xFF78909C, txt, accent);
            leftY -= 10;
        }
        boolean underwater = p.isEyeInFluid(FluidTags.WATER) || p.getAirSupply() < p.getMaxAirSupply();
        if (cfg.airBar && underwater) {
            float air = Math.max(0, p.getAirSupply()) / (float) Math.max(1, p.getMaxAirSupply());
            int bubbles = Mth.ceil(air * 10);
            thinRow(g, font, rightX, rightY, rowW, Icons.BUBBLE, air, 0xFF8FE3FF, 0xFF2D8FD4, bubbles + "/10", accent);
            rightY -= 10;
        }
        if (cfg.mountHealth && p.getVehicle() instanceof LivingEntity mount) {
            float mh = mount.getHealth(), mm = Math.max(1, mount.getMaxHealth());
            thinRow(g, font, rightX, rightY, rowW, Icons.HEART_MOUNT, mh / mm, 0xFFFFA36B, 0xFFC4521C, fmt(mh) + "/" + fmt(mm), accent);
            rightY -= 10;
        }
        if (cfg.addonBars) {
            for (ArcHudApi.Bar bar : ArcHudApi.bars()) {
                if (bar.main()) continue;
                float[] v = values(bar);
                if (v == null) continue;
                float frac = v[1] <= 0 ? 0 : v[0] / v[1];
                thinRow(g, font, leftX, leftY, rowW, bar.icon(), frac, Draw.brighten(bar.color(), 0.25F), Draw.darken(bar.color(), 0.3F),
                        bar.label().getString() + "  " + fmt(v[0]) + "/" + fmt(v[1]), accent);
                leftY -= 10;
            }
        }
        occupied = Math.max(54, h - Math.min(leftY, rightY) - 6);
    }

    private static float[] values(ArcHudApi.Bar bar) {
        try {
            float[] v = bar.values().get();
            return v == null || v.length < 2 ? null : v;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Большая полоса мода в стиле HP/еды. */
    private static void mainRow(GuiGraphics g, Font font, int x, int y, int w, ArcHudApi.Bar bar, float[] v, int accent, boolean right) {
        float frac = v[1] <= 0 ? 0 : Mth.clamp(v[0] / v[1], 0, 1);
        int color = bar.color();
        Draw.panel(g, x, y, w, 11, Draw.PANEL, Draw.alpha(Draw.mix(accent, color, 0.5F), 0x70));
        int bx = right ? x + 2 : x + 11, by = y + 2, bw = w - 13, bh = 7;
        Draw.icon(g, bar.icon(), right ? x + w - 10 : x + 1, y + 1);
        g.fill(bx, by, bx + bw, by + bh, 0xC0050608);
        Draw.barFill(g, bx, by, bw, bh, frac, Draw.brighten(color, 0.3F), Draw.darken(color, 0.35F), right);
        Draw.ticks(g, bx, by, bw, bh, v[1], v[1] <= 20 ? 2F : v[1] <= 100 ? 10F : v[1] / 10F);
        String s = bar.label().getString() + "  " + fmt(v[0]) + "/" + fmt(v[1]);
        if (font.width(s) * 0.72F > bw - 2) s = fmt(v[0]) + "/" + fmt(v[1]);
        Draw.smallCentered(g, font, s, bx + bw / 2F, by + 0.5F, 0xFFFFFFFF, 0.72F);
    }

    private static void healthRow(GuiGraphics g, Minecraft mc, LocalPlayer p, Font font, int x, int y, int w, int accent, float k, long now) {
        ArcConfig cfg = ArcConfig.get();
        float max = Math.max(1F, p.getMaxHealth());
        float hp = p.getHealth();
        float abs = p.getAbsorptionAmount();
        if (shownHealth < 0) {
            shownHealth = hp;
            trailHealth = hp;
        }
        shownHealth += (hp - shownHealth) * Math.min(1F, k * 1.6F);
        if (trailHealth < shownHealth) trailHealth = shownHealth;
        else trailHealth += (shownHealth - trailHealth) * Math.min(1F, k * 0.35F);

        int top = 0xFFFF4D5E, bot = 0xFFB3122A, icon = Icons.HEART;
        if (cfg.statusColors) {
            if (p.hasEffect(MobEffects.WITHER)) {
                top = 0xFF7A7A7A; bot = 0xFF2A2A2A; icon = Icons.HEART_WITHER;
            } else if (p.hasEffect(MobEffects.POISON)) {
                top = 0xFFA6E05A; bot = 0xFF4E8A1C; icon = Icons.HEART_POISON;
            } else if (p.isFullyFrozen()) {
                top = 0xFFB3EEFF; bot = 0xFF3C8FC4; icon = Icons.HEART_FROZEN;
            }
        }
        if (mc.level.getLevelData().isHardcore()) bot = Draw.darken(bot, 0.35F);

        boolean hurt = now < hurtFlashUntil;
        int border = hurt ? 0xFFFF3B3B : Draw.alpha(accent, 0x55);
        Draw.panel(g, x, y, w, 11, Draw.PANEL, border);
        Draw.icon(g, icon, x + 1, y + 1);

        int bx = x + 11, by = y + 2, bw = w - 13, bh = 7;
        g.fill(bx, by, bx + bw, by + bh, 0xC0050608);
        if (cfg.damageTrail && trailHealth > shownHealth + 0.01F) {
            int t1 = bx + Math.round(bw * Mth.clamp(shownHealth / max, 0, 1));
            int t2 = bx + Math.round(bw * Mth.clamp(trailHealth / max, 0, 1));
            g.fill(t1, by, t2, by + bh, 0xD0FFD6D6);
        }
        int[] fill = Draw.barFill(g, bx, by, bw, bh, shownHealth / max, top, bot, false);
        if (cfg.healGlow) {
            float glow = Math.max(0, (healFlashUntil - now) / 600F);
            if (p.hasEffect(MobEffects.REGENERATION)) glow = Math.max(glow, 0.25F * Draw.pulse(900));
            if (glow > 0) g.fill(fill[0], by, fill[1], by + bh, Draw.alpha(0x7CFF6B, (int) (0x90 * glow)));
        }
        if (cfg.absorption && abs > 0) {
            int aw = Math.round(bw * Mth.clamp(abs / max, 0, 1));
            g.fillGradient(bx, by, bx + aw, by + 2, 0xFFFFE680, 0xFFE0A800);
        }
        Draw.ticks(g, bx, by, bw, bh, max, max <= 40 ? 2F : 10F);
        if (cfg.healthNumbers) {
            String s = fmt(hp) + "/" + fmt(max) + (cfg.absorption && abs > 0 ? " +" + fmt(abs) : "");
            Draw.smallCentered(g, font, s, bx + bw / 2F, by + 0.5F, 0xFFFFFFFF, 0.72F);
        }
    }

    private static void foodRow(GuiGraphics g, LocalPlayer p, Font font, int x, int y, int w, int accent, float k) {
        ArcConfig cfg = ArcConfig.get();
        FoodData fd = p.getFoodData();
        int food = fd.getFoodLevel();
        float sat = fd.getSaturationLevel();
        if (shownFood < 0) {
            shownFood = food;
            shownSat = sat;
        }
        shownFood += (food - shownFood) * Math.min(1F, k * 1.4F);
        shownSat += (sat - shownSat) * Math.min(1F, k * 1.4F);

        boolean hungerFx = cfg.hungerEffectColor && p.hasEffect(MobEffects.HUNGER);
        int top = hungerFx ? 0xFFB5D96A : 0xFFFFB45C;
        int bot = hungerFx ? 0xFF5D7D21 : 0xFFC0661C;
        int border = food <= 6 ? Draw.mix(Draw.alpha(accent, 0x55), 0xFFFF9800, Draw.pulse(800)) : Draw.alpha(accent, 0x55);

        Draw.panel(g, x, y, w, 11, Draw.PANEL, border);
        Draw.icon(g, hungerFx ? Icons.FOOD_HUNGER : Icons.FOOD, x + w - 10, y + 1);
        int bx = x + 2, by = y + 2, bw = w - 13, bh = 7;
        g.fill(bx, by, bx + bw, by + bh, 0xC0050608);
        Draw.barFill(g, bx, by, bw, bh, shownFood / 20F, top, bot, true);
        if (cfg.saturation && shownSat > 0.05F) {
            int sw = Math.round(bw * Mth.clamp(shownSat / 20F, 0, 1));
            g.fill(bx + bw - sw, by + bh - 2, bx + bw, by + bh, 0xF0FFE36E);
        }
        String gain = "";
        if (cfg.foodPreview) {
            // AppleSkin-style: предпросмотр еды в руке — сколько прибавится голода и насыщенности
            ItemStack held = p.getMainHandItem();
            com.arc4io.archud.compat.AppleSkinCompat.Food f = com.arc4io.archud.compat.AppleSkinCompat.food(held, p);
            if (f == null) {
                held = p.getOffhandItem();
                f = com.arc4io.archud.compat.AppleSkinCompat.food(held, p);
            }
            if (f != null && com.arc4io.archud.compat.AppleSkinCompat.canEat(held, p)) {
                int nf = Math.min(20, food + f.hunger());
                float ns = Math.min(nf, sat + f.saturation());
                int a = 0x60 + (int) (0x80 * Draw.pulse(1000));
                int cw = Math.round(bw * food / 20F), nw = Math.round(bw * nf / 20F);
                if (nw > cw) g.fill(bx + bw - nw, by, bx + bw - cw, by + bh, Draw.alpha(top, a));
                if (cfg.saturation) {
                    int sw0 = Math.round(bw * Mth.clamp(sat / 20F, 0, 1)), sw1 = Math.round(bw * Mth.clamp(ns / 20F, 0, 1));
                    if (sw1 > sw0) g.fill(bx + bw - sw1, by + bh - 2, bx + bw - sw0, by + bh, Draw.alpha(0xFFFFE36E, a));
                }
                gain = "  +" + (nf - food) + (cfg.saturation ? " ✦+" + String.format("%.1f", ns - sat) : "");
            }
        }
        if (cfg.exhaustionBar && com.arc4io.archud.compat.AppleSkinCompat.present()) {
            // истощение (0..4): тонкая линия сверху — когда заполнится, спишется насыщенность / голод
            float ex = Mth.clamp(fd.getExhaustionLevel() / 4F, 0F, 1F);
            int ew = Math.round(bw * ex);
            if (ew > 0) g.fill(bx + bw - ew, by, bx + bw, by + 1, 0xC0FFFFFF);
        }
        Draw.ticks(g, bx, by, bw, bh, 20F, 2F);
        String s = food + (cfg.saturation ? "  ✦" + String.format("%.1f", sat) : "") + gain;
        Draw.smallCentered(g, font, s, bx + bw / 2F, by + 0.5F, 0xFFFFFFFF, 0.72F);
    }

    private static void thinRow(GuiGraphics g, Font font, int x, int y, int w, int icon, float frac, int top, int bot, String text, int accent) {
        Draw.panel(g, x, y, w, 9, Draw.PANEL, Draw.alpha(accent, 0x40));
        Draw.icon(g, icon, x + 1, y);
        int bx = x + 11, by = y + 3, bw = w - 13;
        Draw.bar(g, bx, by, bw, 3, frac, top, bot);
        Draw.small(g, font, text, bx + 1, y + 0.5F, 0xFFEAF2FA, 0.5F);
    }

    // ------------------------------------------------------------- опыт / прыжок
    private static void xpRow(GuiGraphics g, LocalPlayer p, Font font, int w, int h, int accent, boolean hasXp, boolean survival, float k) {
        ArcConfig cfg = ArcConfig.get();
        int x = w / 2 - 92, y = h - 28;
        if (cfg.mountJump && p.jumpableVehicle() != null) {
            float jump = p.getJumpRidingScale();
            Draw.bar(g, x, y, 184, 3, jump, 0xFFFFD180, 0xFFFF6D00);
            Draw.icon(g, Icons.JUMP, w / 2 - 4, y - 11);
            return;
        }
        if (!cfg.xpBar || !hasXp) return;
        if (shownXp < 0) shownXp = p.experienceProgress;
        shownXp += (p.experienceProgress - shownXp) * Math.min(1F, k * 1.2F);
        Draw.bar(g, x, y, 184, 3, shownXp, 0xFFB8FF6B, 0xFF3FA21E);
        if (p.experienceLevel > 0 || survival) {
            String lvl = Integer.toString(p.experienceLevel);
            int bw = Math.max(20, font.width(lvl) + 8);
            int bx = w / 2 - bw / 2;
            int by = survival ? h - 41 : h - 40;
            Draw.panel(g, bx, by, bw, 11, Draw.PANEL, 0x8080FF20);
            g.drawCenteredString(font, lvl, w / 2, by + 2, 0xFF9CFF5A);
        }
    }

    // -------------------------------------------------------- низкое здоровье
    private static void lowHealthVignette(GuiGraphics g, LocalPlayer p, int w, int h) {
        float frac = p.getHealth() / Math.max(1F, p.getMaxHealth());
        if (frac > 0.3F || p.isDeadOrDying()) return;
        float intensity = (0.3F - frac) / 0.3F;
        int a = (int) (0x70 * (0.35F + 0.65F * intensity) * (0.6F + 0.4F * Draw.pulse(1100)));
        int c = Draw.alpha(0xFF1010, a);
        int edge = Math.max(24, h / 6);
        g.fillGradient(0, 0, w, edge, c, 0x00FF1010);
        g.fillGradient(0, h - edge, w, h, 0x00FF1010, c);
        Draw.hGradient(g, 0, 0, edge, h, c, 0x00FF1010);
        Draw.hGradient(g, w - edge, 0, w, h, 0x00FF1010, c);
    }

    // ------------------------------------------------- фишки справа от хотбара
    private static void chips(GuiGraphics g, LocalPlayer p, Font font, int w, int h, int accent) {
        ArcConfig cfg = ArcConfig.get();
        boolean offRight = cfg.offhandSlot && !p.getOffhandItem().isEmpty() && p.getMainArm() == HumanoidArm.LEFT;
        int x = w / 2 + 92 + 4 + (offRight ? 26 : 0);
        int y = h - 21;

        if (cfg.ammoCounter) {
            ItemStack weapon = p.getMainHandItem().getItem() instanceof ProjectileWeaponItem ? p.getMainHandItem()
                    : p.getOffhandItem().getItem() instanceof ProjectileWeaponItem ? p.getOffhandItem() : ItemStack.EMPTY;
            if (!weapon.isEmpty()) {
                Predicate<ItemStack> ammo = ((ProjectileWeaponItem) weapon.getItem()).getAllSupportedProjectiles();
                ItemStack sample = ItemStack.EMPTY;
                int count = 0;
                Inventory inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (!s.isEmpty() && ammo.test(s)) {
                        if (sample.isEmpty()) sample = s;
                        count += s.getCount();
                    }
                }
                String txt = p.getAbilities().instabuild ? "∞" : Integer.toString(count);
                chip(g, font, x, y, sample.isEmpty() ? new ItemStack(Items.ARROW) : sample, -1, txt, count == 0 ? 0xFFFF5555 : Draw.TEXT, accent);
                y -= 20;
            }
        }
        if (cfg.totemCounter) {
            int totems = 0;
            Inventory inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) totems += inv.getItem(i).getCount();
            }
            if (totems > 0) {
                chip(g, font, x, y, new ItemStack(Items.TOTEM_OF_UNDYING), -1, "×" + totems, 0xFFFFE082, accent);
                y -= 20;
            }
        }
        if (cfg.tempadIntegration) {
            // Tempad: заряд (если у варианта есть шкала) или «готов»; ищется и в браслете Curios / Trinkets
            com.arc4io.archud.compat.TempadCompat.Found tp = com.arc4io.archud.compat.TempadCompat.find(p);
            if (tp != null) {
                ItemStack s = tp.stack();
                int ch = com.arc4io.archud.compat.TempadCompat.charge(s);
                String txt = ch >= 0 ? ch + "%" : "✔";
                boolean cd = p.getCooldowns().isOnCooldown(s.getItem());
                if (cd) txt = Math.round((1F - p.getCooldowns().getCooldownPercent(s.getItem(), 0F)) * 100) + "%";
                if (tp.accessory()) txt = "⌚" + txt;
                chip(g, font, x, y, s, -1, txt, cd ? 0xFFFF8A65 : 0xFFFFB74D, accent);
                y -= 20;
            }
        }
        {
            // перезарядка предмета в руке (свиток / камень телепорта Waystones, эндер-жемчуг и т.д.)
            ItemStack held = p.getMainHandItem();
            float cool = held.isEmpty() ? 0F : p.getCooldowns().getCooldownPercent(held.getItem(), 0F);
            if (cool > 0F) {
                chip(g, font, x, y, held, -1, Math.round(cool * 100) + "%", 0xFFFF8A65, accent);
                y -= 20;
            }
        }
        if (cfg.itemEnergy) {
            for (ItemStack s : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem(), p.getItemBySlot(EquipmentSlot.CHEST)}) {
                long[] e = energy(s);
                if (e == null) continue;
                String txt = NbtEnergy.format(e[0]) + (e[1] > 0 ? "/" + NbtEnergy.format(e[1]) : "");
                chip(g, font, x, y, s, Icons.BOLT, txt, 0xFFFFEE58, accent);
                if (e[1] > 0) {
                    float f = Mth.clamp(e[0] / (float) e[1], 0, 1);
                    g.fill(x + 2, y + 16, x + 2 + Math.round((font.width(txt) * 0.75F + 18) * f), y + 17, 0xFFFFEE58);
                }
                y -= 20;
            }
        }
    }

    private static long[] energy(ItemStack s) {
        if (s.isEmpty()) return null;
        try {
            long[] e = ArcHud.platform().getItemEnergy(s);
            return e != null ? e : NbtEnergy.read(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void chip(GuiGraphics g, Font font, int x, int y, ItemStack stack, int icon, String text, int color, int accent) {
        int tw = Math.round(font.width(text) * 0.75F);
        int cw = 22 + tw;
        Draw.panel(g, x, y, cw, 18, Draw.PANEL, Draw.alpha(accent, 0x45));
        if (!stack.isEmpty()) g.renderItem(stack, x + 1, y + 1);
        if (icon >= 0) Draw.icon(g, icon, x + 12, y + 1);
        Draw.small(g, font, text, x + 19, y + 6, color, 0.75F);
    }

    // ------------------------------------------------- предупреждение о падении
    private static void fallWarning(GuiGraphics g, LocalPlayer p, Font font, int w, int h) {
        if (p.onGround() || p.getAbilities().flying || p.isFallFlying() || p.isInWater() || p.hasEffect(MobEffects.SLOW_FALLING)) return;
        if (p.getDeltaMovement().y >= 0 || p.fallDistance < 4F) return;
        float dmg = Math.max(0, p.fallDistance - 3F);
        boolean deadly = dmg >= p.getHealth() + p.getAbsorptionAmount();
        int color = deadly ? Draw.mix(0xFFFF2020, 0xFFFFFFFF, Draw.pulse(300)) : 0xFFFFB74D;
        String s = "-" + fmt(dmg) + " ❤";
        int tw = font.width(s);
        int x = w / 2 - (tw + 12) / 2, y = h / 2 + 12;
        Draw.panel(g, x - 2, y - 2, tw + 16, 13, 0xA0200000, color);
        Draw.icon(g, Icons.WARNING, x, y);
        g.drawString(font, s, x + 11, y + 1, color, true);
    }

    // ------------------------------------------------------- цифры урона
    private static void popups(GuiGraphics g, Font font, int w, int h, long now) {
        int bx = w / 2 - 52;
        int by = h - 48;
        for (Popup pp : POPUPS) {
            float t = (now - pp.start()) / 1200F;
            if (t > 1) continue;
            float alpha = t < 0.7F ? 1F : 1F - (t - 0.7F) / 0.3F;
            int a = Math.max(8, (int) (255 * alpha));
            float y = by - Draw.easeOut(t) * 22;
            float scale = t < 0.12F ? 0.6F + t * 3.3F : 1F;
            g.pose().pushPose();
            g.pose().translate(bx + pp.dx(), y, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, pp.text(), -font.width(pp.text()) / 2, 0, Draw.alpha(pp.color(), a), true);
            g.pose().popPose();
        }
    }

    // --------------------------------------------------------- BunnyHop HUD
    private static void bhopHud(GuiGraphics g, Font font, int w, int h, int accent) {
        double speed = speedBps;
        int chain = BunnyHop.chain();
        if (speed < 7.5 && chain < 2) return;
        String s = String.format("%.1f b/s", speed);
        int color = Draw.mix(accent, 0xFF76FF03, (float) Mth.clamp((speed - 7.5) / 10.0, 0, 1));
        Draw.smallCentered(g, font, s, w / 2F, h / 2F + 28, color, 0.85F);
        if (chain >= 2) {
            Draw.smallCentered(g, font, "×" + chain + " BHOP", w / 2F, h / 2F + 37, Draw.alpha(0xFFFFFF, 0xC0), 0.6F);
        }
    }

    // ----------------------------------------------------------------- утилиты
    public static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05F) return Integer.toString(Math.round(v));
        return String.format("%.1f", v);
    }

    public static String pretty(String path) {
        String[] parts = path.replace('/', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    public static double speedBps() {
        return speedBps;
    }
}
