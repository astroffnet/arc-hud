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
    private static int frames, fps;
    private static long fpsTimer;
    private static double speedBps;
    private static Vec3 lastPos;
    private static ResourceKey<Level> lastDim;
    private static Map<Item, Integer> lastCounts = new HashMap<>();
    private static boolean countsReady;
    private static final List<Popup> POPUPS = new ArrayList<>();
    private static final List<Pickup> PICKUPS = new ArrayList<>();
    private static final Map<MobEffect, Integer> EFFECT_MAX = new HashMap<>();
    private static Field bossMapField;
    private static boolean bossFieldSearched;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private record Popup(String text, int color, long start, int dx) {}

    private static final class Pickup {
        final ItemStack stack;
        int count;
        long time;

        Pickup(ItemStack stack, int count, long time) {
            this.stack = stack;
            this.count = count;
            this.time = time;
        }
    }

    private record InfoLine(int icon, Component text, int color) {}

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
        return 54;
    }

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
            if (cfg.customHotbar) hotbar(g, mc, p, w, h, partialTick, accent, k);
            if (survival) statusBars(g, mc, p, font, w, h, accent, k, now);
            xpRow(g, p, font, w, h, accent, hasXp, survival, k);
            gearPanel(g, mc, p, font, h, accent, now);
            chips(g, p, font, w, h, accent);
            if (survival && cfg.fallWarning) fallWarning(g, p, font, w, h);
            if (cfg.damageNumbers) popups(g, font, w, h, now);
            if (cfg.pickupNotifications) pickups(g, mc, font, w, h, accent, now);
            if (cfg.movementStatus) movementStatus(g, p, font, h, accent);
            if (cfg.bhopHud && cfg.bunnyHop) bhopHud(g, font, w, h, accent);
        }
        if (!debug) {
            if (cfg.infoPanel) infoPanel(g, mc, p, font, accent);
            int topY = 2 + bossCount(mc) * 19;
            if (cfg.compass) {
                compass(g, p, font, w, topY, accent);
                topY += 16;
            }
            targetInfo(g, mc, font, w, topY, accent);
            if (cfg.effectsPanel) effects(g, mc, p, font, w, accent);
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

        // верхний тонкий ряд
        int leftY = h - 51;
        int rightY = h - 51;
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
                float[] v;
                try {
                    v = bar.values().get();
                } catch (Exception e) {
                    v = null;
                }
                if (v == null || v.length < 2) continue;
                float frac = v[1] <= 0 ? 0 : v[0] / v[1];
                thinRow(g, font, leftX, leftY, rowW, Icons.STAR, frac, Draw.brighten(bar.color(), 0.25F), Draw.darken(bar.color(), 0.3F),
                        bar.label().getString() + " " + fmt(v[0]), accent);
                leftY -= 10;
            }
        }
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
        Draw.ticks(g, bx, by, bw, bh, 20F, 2F);
        String s = food + (cfg.saturation ? "  ✦" + String.format("%.1f", sat) : "");
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

    // ------------------------------------------------------------- снаряжение
    private static void gearPanel(GuiGraphics g, Minecraft mc, LocalPlayer p, Font font, int h, int accent, long now) {
        ArcConfig cfg = ArcConfig.get();
        List<ItemStack> list = new ArrayList<>();
        if (cfg.armorDurability) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack s = p.getItemBySlot(slot);
                if (s.isDamageableItem()) list.add(s);
            }
        }
        if (cfg.heldDurability) {
            if (p.getMainHandItem().isDamageableItem()) list.add(p.getMainHandItem());
            if (p.getOffhandItem().isDamageableItem()) list.add(p.getOffhandItem());
        }
        if (list.isEmpty()) return;
        int x = 2;
        int y = h / 2 - list.size() * 10;
        for (ItemStack s : list) {
            int max = Math.max(1, s.getMaxDamage());
            float frac = 1F - s.getDamageValue() / (float) max;
            boolean low = frac < 0.1F;
            int bg = low && cfg.lowDurabilityWarning ? Draw.mix(Draw.PANEL, 0xC0FF2020, Draw.pulse(700)) : Draw.PANEL;
            Draw.panel(g, x, y, 54, 19, bg, Draw.alpha(accent, 0x40));
            g.renderItem(s, x + 2, y + 1);
            int color = Draw.mix(0xFFFF4040, 0xFF6BFF7A, frac);
            Draw.small(g, font, Math.round(frac * 100) + "%", x + 20, y + 3, color, 0.75F);
            Draw.small(g, font, Integer.toString(max - s.getDamageValue()), x + 20, y + 10, Draw.TEXT_DIM, 0.5F);
            g.fill(x + 36, y + 14, x + 52, y + 16, 0xC0000000);
            g.fill(x + 36, y + 14, x + 36 + Math.round(16 * frac), y + 16, color);
            y += 20;
        }
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

    // ----------------------------------------------------- подбор предметов
    private static void pickups(GuiGraphics g, Minecraft mc, Font font, int w, int h, int accent, long now) {
        int y = h - 64;
        for (int i = PICKUPS.size() - 1; i >= 0; i--) {
            Pickup pk = PICKUPS.get(i);
            float age = (now - pk.time) / 3500F;
            float slide = Draw.easeOut(Math.min(1F, (now - pk.time) / 220F));
            float fade = age > 0.8F ? 1F - (age - 0.8F) / 0.2F : 1F;
            String txt = "+" + pk.count + " " + pk.stack.getHoverName().getString();
            if (font.width(txt) > 110) txt = font.plainSubstrByWidth(txt, 104) + "…";
            int pw = font.width(txt) + 26;
            int x = (int) (w - 4 - pw + (1F - slide) * 40F);
            int a = Math.max(8, (int) (255 * fade));
            Draw.panel(g, x, y, pw, 18, Draw.scaleAlpha(Draw.PANEL, fade), Draw.alpha(accent, (int) (0x70 * fade)));
            g.fill(x, y + 2, x + 2, y + 16, Draw.alpha(accent, a));
            if (fade > 0.3F) g.renderItem(pk.stack, x + 4, y + 1);
            g.drawString(font, txt, x + 22, y + 5, Draw.alpha(0xEAF2FA, a), true);
            y -= 20;
        }
    }

    // ------------------------------------------------------- статус движения
    private static void movementStatus(GuiGraphics g, LocalPlayer p, Font font, int h, int accent) {
        List<String> tags = new ArrayList<>();
        if (p.isFallFlying()) tags.add(Component.translatable("archud.status.glide").getString());
        else if (p.getAbilities().flying) tags.add(Component.translatable("archud.status.fly").getString());
        if (p.isSwimming()) tags.add(Component.translatable("archud.status.swim").getString());
        else if (p.isSprinting()) tags.add(Component.translatable("archud.status.sprint").getString());
        if (p.isShiftKeyDown()) tags.add(Component.translatable("archud.status.sneak").getString());
        int y = h - 14;
        for (String t : tags) {
            int tw = Math.round(font.width(t) * 0.75F);
            Draw.panel(g, 4, y, tw + 8, 11, Draw.PANEL, Draw.alpha(accent, 0x80));
            Draw.small(g, font, t, 8, y + 3, Draw.brighten(accent, 0.4F), 0.75F);
            y -= 13;
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

    // ------------------------------------------------------- панель информации
    private static void infoPanel(GuiGraphics g, Minecraft mc, LocalPlayer p, Font font, int accent) {
        ArcConfig cfg = ArcConfig.get();
        List<InfoLine> lines = new ArrayList<>();
        BlockPos pos = p.blockPosition();
        Level level = mc.level;

        if (cfg.coordinates) lines.add(new InfoLine(Icons.PIN, Component.literal(pos.getX() + "  " + pos.getY() + "  " + pos.getZ()), Draw.TEXT));
        if (cfg.facing) {
            Direction d = p.getDirection();
            int heading = Math.round(Mth.wrapDegrees(p.getYRot() + 180F) + 180F) % 360;
            lines.add(new InfoLine(Icons.COMPASS, Component.translatable("archud.dir." + d.getName()).append(" " + heading + "°"), Draw.TEXT));
        }
        if (cfg.biome) {
            Optional<ResourceKey<Biome>> key = level.getBiome(pos).unwrapKey();
            key.ifPresent(k -> {
                ResourceLocation id = k.location();
                lines.add(new InfoLine(Icons.LEAF, Component.translatableWithFallback("biome." + id.getNamespace() + "." + id.getPath(), pretty(id.getPath())), 0xFFA5D6A7));
            });
        }
        if (cfg.dimension) {
            ResourceLocation dim = level.dimension().location();
            Component name = Component.translatableWithFallback("archud.dim." + dim.getNamespace() + "." + dim.getPath(), pretty(dim.getPath()));
            if (!dim.getNamespace().equals("minecraft") && cfg.modNames) {
                name = name.copy().append(Component.literal("  " + ArcHud.platform().modName(dim.getNamespace())).withStyle(s -> s.withColor(0x8C9AAB)));
            }
            lines.add(new InfoLine(Icons.GLOBE, name, Draw.brighten(accent, 0.3F)));
        }
        StringBuilder perf = new StringBuilder();
        if (cfg.fps) perf.append(fps).append(" FPS");
        if (cfg.ping && mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(p.getUUID());
            if (info != null && !mc.isLocalServer()) {
                if (perf.length() > 0) perf.append("  ");
                perf.append(info.getLatency()).append(" ms");
            }
        }
        if (perf.length() > 0) lines.add(new InfoLine(Icons.CHIP, Component.literal(perf.toString()), fps < 30 && cfg.fps ? 0xFFFF8A65 : Draw.TEXT));

        long dayTime = level.getDayTime();
        StringBuilder time = new StringBuilder();
        if (cfg.gameTime) {
            long t = (dayTime + 6000L) % 24000L;
            time.append(String.format("%02d:%02d", t / 1000L, (t % 1000L) * 60L / 1000L));
        }
        if (cfg.realTime) {
            if (time.length() > 0) time.append("  |  ");
            time.append(LocalTime.now().format(CLOCK));
        }
        if (time.length() > 0) lines.add(new InfoLine(Icons.CLOCK, Component.literal(time.toString()), Draw.TEXT));
        if (cfg.dayCounter || cfg.weather) {
            MutableLine ml = new MutableLine();
            long tod = dayTime % 24000L;
            boolean night = tod >= 13000L && tod < 23000L;
            int icon = night ? Icons.MOON : Icons.SUN;
            Component c = Component.empty();
            if (cfg.dayCounter) c = Component.translatable("archud.info.day", dayTime / 24000L + 1);
            if (cfg.weather) {
                if (level.isThundering()) {
                    icon = Icons.STORM;
                    c = c.copy().append("  ").append(Component.translatable("archud.info.storm"));
                } else if (level.isRaining()) {
                    icon = Icons.RAIN;
                    c = c.copy().append("  ").append(Component.translatable("archud.info.rain"));
                }
            }
            ml.set(icon, c);
            lines.add(new InfoLine(ml.icon, ml.text, Draw.TEXT));
        }
        if (cfg.speed) lines.add(new InfoLine(Icons.SPEED, Component.literal(String.format("%.2f", speedBps)).append(Component.translatable("archud.info.bps")), Draw.TEXT));
        if (cfg.lightLevel) {
            int block = level.getBrightness(LightLayer.BLOCK, pos);
            int sky = level.getBrightness(LightLayer.SKY, pos);
            int color = block <= 0 ? 0xFFFF7A7A : block < 8 ? 0xFFFFD54F : Draw.TEXT;
            lines.add(new InfoLine(Icons.LIGHT, Component.translatable("archud.info.light", block, sky), color));
        }
        if (lines.isEmpty()) return;

        int width = 0;
        for (InfoLine l : lines) width = Math.max(width, font.width(l.text()));
        int pw = width + 18, ph = lines.size() * 11 + 4;
        Draw.accentPanel(g, 2, 2, pw, ph, accent);
        int y = 4;
        for (InfoLine l : lines) {
            Draw.icon(g, l.icon(), 5, y);
            g.drawString(font, l.text(), 16, y + 1, l.color(), true);
            y += 11;
        }
    }

    private static final class MutableLine {
        int icon;
        Component text;

        void set(int icon, Component text) {
            this.icon = icon;
            this.text = text;
        }
    }

    // ------------------------------------------------------------------ компас
    private static void compass(GuiGraphics g, LocalPlayer p, Font font, int w, int y, int accent) {
        int cw = 182, cx = w / 2, x = cx - cw / 2;
        Draw.accentPanel(g, x, y, cw, 13, accent);
        float heading = Mth.wrapDegrees(p.getYRot() + 180F); // 0 = север
        g.enableScissor(x + 2, y, x + cw - 2, y + 13);
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int deg = 0; deg < 360; deg += 15) {
            float diff = Mth.wrapDegrees(deg - heading);
            if (Math.abs(diff) > 95) continue;
            int px = cx + Math.round(diff * (cw / 2F) / 90F);
            if (deg % 45 == 0) {
                String n = Component.translatable("archud.compass." + names[deg / 45]).getString();
                int col = deg == 0 ? 0xFFFF5252 : deg % 90 == 0 ? 0xFFFFFFFF : 0xFFB0BEC5;
                float sc = deg % 90 == 0 ? 0.85F : 0.6F;
                Draw.smallCentered(g, font, n, px, y + (deg % 90 == 0 ? 3 : 4), col, sc);
            } else {
                g.fill(px, y + 9, px + 1, y + 12, 0x80FFFFFF);
            }
        }
        g.disableScissor();
        // указатель
        g.fill(cx, y + 1, cx + 1, y + 12, Draw.alpha(accent, 0xFF));
        g.fill(cx - 1, y + 11, cx + 2, y + 13, Draw.alpha(accent, 0xFF));
    }

    private static int bossCount(Minecraft mc) {
        try {
            Object overlay = mc.gui.getBossOverlay();
            if (!bossFieldSearched) {
                bossFieldSearched = true;
                for (Field f : overlay.getClass().getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        bossMapField = f;
                        break;
                    }
                }
            }
            if (bossMapField != null) return Math.min(4, ((Map<?, ?>) bossMapField.get(overlay)).size());
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ----------------------------------------------------------- цель/блок
    private static void targetInfo(GuiGraphics g, Minecraft mc, Font font, int w, int y, int accent) {
        ArcConfig cfg = ArcConfig.get();
        Entity target = mc.crosshairPickEntity;
        if (cfg.targetInfo && target instanceof LivingEntity living && !living.isInvisible()) {
            Component name = living.getDisplayName();
            String mod = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).getNamespace();
            float hp = living.getHealth(), max = Math.max(1, living.getMaxHealth());
            int pw = Math.max(120, font.width(name) + 24);
            int x = w / 2 - pw / 2;
            Draw.accentPanel(g, x, y, pw, 26, accent);
            Draw.icon(g, Icons.SKULL, x + 3, y + 3);
            g.drawString(font, name, x + 14, y + 3, Draw.TEXT, true);
            Draw.bar(g, x + 4, y + 14, pw - 8, 5, hp / max, 0xFFFF6B6B, 0xFFB71C1C);
            Draw.smallCentered(g, font, fmt(hp) + " / " + fmt(max) + (living.getArmorValue() > 0 ? "   ◈ " + living.getArmorValue() : ""), w / 2F, y + 20, 0xFFE0E0E0, 0.5F);
            if (cfg.modNames && !mod.equals("minecraft")) {
                String mn = ArcHud.platform().modName(mod);
                Draw.small(g, font, mn, x + pw - 4 - font.width(mn) * 0.5F, y + 4, 0xFF7FA7C9, 0.5F);
            }
            return;
        }
        if (cfg.blockInfo && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockState state = mc.level.getBlockState(bhr.getBlockPos());
            if (state.isAir()) return;
            Component name = state.getBlock().getName();
            String mod = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
            String mn = cfg.modNames ? ArcHud.platform().modName(mod) : "";
            int pw = Math.max(font.width(name), Math.round(font.width(mn) * 0.6F)) + 28;
            int x = w / 2 - pw / 2;
            Draw.accentPanel(g, x, y, pw, 20, accent);
            ItemStack icon = new ItemStack(state.getBlock().asItem());
            if (!icon.isEmpty()) g.renderItem(icon, x + 2, y + 2);
            g.drawString(font, name, x + 21, y + 3, Draw.TEXT, true);
            if (!mn.isEmpty()) Draw.small(g, font, mn, x + 21, y + 12.5F, 0xFF7FA7C9, 0.6F);
        }
    }

    // ---------------------------------------------------------------- эффекты
    private static void effects(GuiGraphics g, Minecraft mc, LocalPlayer p, Font font, int w, int accent) {
        ArcConfig cfg = ArcConfig.get();
        Collection<MobEffectInstance> active = p.getActiveEffects();
        if (active.isEmpty()) {
            EFFECT_MAX.clear();
            return;
        }
        List<MobEffectInstance> list = new ArrayList<>();
        for (MobEffectInstance e : active) if (e.showIcon()) list.add(e);
        list.sort((a, b) -> Boolean.compare(!a.getEffect().isBeneficial(), !b.getEffect().isBeneficial()));
        for (Iterator<MobEffect> it = EFFECT_MAX.keySet().iterator(); it.hasNext(); ) {
            MobEffect m = it.next();
            if (!p.hasEffect(m)) it.remove();
        }

        boolean compact = list.size() > 7;
        int y = 2;
        int x = w - 2;
        int col = 0;
        for (MobEffectInstance e : list) {
            MobEffect effect = e.getEffect();
            int dur = e.getDuration();
            int maxDur = EFFECT_MAX.merge(effect, dur, Math::max);
            boolean blinking = !e.isInfiniteDuration() && dur < 200 && (dur / 5) % 2 == 0;
            int color = 0xFF000000 | effect.getColor();
            TextureAtlasSprite sprite = mc.getMobEffectTextures().get(effect);

            if (compact) {
                int ex = x - 22 - col * 24;
                Draw.panel(g, ex, y, 22, 22, Draw.PANEL, Draw.alpha(color, 0xA0));
                if (!blinking) g.blit(ex + 2, y + 2, 0, 18, 18, sprite);
                if (cfg.effectTimers && !e.isInfiniteDuration()) {
                    float f = Mth.clamp(dur / (float) Math.max(1, maxDur), 0, 1);
                    g.fill(ex + 2, y + 20, ex + 2 + Math.round(18 * f), y + 21, color);
                }
                if (++col >= 6) {
                    col = 0;
                    y += 24;
                }
                continue;
            }

            Component name = effect.getDisplayName().copy();
            if (e.getAmplifier() > 0) name = name.copy().append(" ").append(Component.translatable("enchantment.level." + (e.getAmplifier() + 1)));
            String timeStr = e.isInfiniteDuration() ? "∞" : MobEffectUtil.formatDuration(e, 1.0F).getString();
            int pw = Math.max(font.width(name), font.width(timeStr)) + 28;
            int ex = x - pw;
            Draw.panel(g, ex, y, pw, 22, Draw.PANEL, Draw.alpha(color, 0x90));
            g.fill(ex, y + 2, ex + 2, y + 20, effect.isBeneficial() ? Draw.alpha(accent, 0xFF) : 0xFFFF5252);
            if (!blinking) g.blit(ex + 4, y + 2, 0, 18, 18, sprite);
            g.drawString(font, name, ex + 24, y + 3, effect.isBeneficial() ? Draw.TEXT : 0xFFFF8A80, true);
            if (cfg.effectTimers) {
                Draw.small(g, font, timeStr, ex + 24, y + 13, Draw.TEXT_DIM, 0.75F);
                if (!e.isInfiniteDuration()) {
                    float f = Mth.clamp(dur / (float) Math.max(1, maxDur), 0, 1);
                    g.fill(ex + 24, y + 19, ex + pw - 3, y + 20, 0x60000000);
                    g.fill(ex + 24, y + 19, ex + 24 + Math.round((pw - 27) * f), y + 20, color);
                }
            }
            y += 24;
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
