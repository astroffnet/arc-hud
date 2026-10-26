package com.arc4io.archud.client.extras;

import com.arc4io.archud.client.ArcKeys;
import com.arc4io.archud.client.camera.ArcCamera;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.map.WaypointWorldRenderer;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Набор мелких фишек Arc HUD (каждая отключается в настройках):
 * зум, свободный обзор, полная яркость, автобег, копирование координат, «чистый» скриншот,
 * свой прицел, хитмаркер, всплывающий урон, счётчик стрел, прочность у прицела и предупреждение о поломке,
 * панель полёта на элитрах, название биома при входе, режим AFK,
 * напоминание о перерыве, координаты смерти в чате, предупреждение о полном инвентаре,
 * учёт времени игры на серверах.
 */
public final class Extras {
    private record Popup(double x, double y, double z, String text, int color, long time) {}

    private static long sessionStart = Util.getMillis();
    private static long worldJoin;
    // зум
    private static float zoom = 1F, zoomTarget = 4F;
    private static boolean zooming;
    // хитмаркер
    private static long hitTime;
    private static boolean hitKill;
    private static Entity lastAttacked;
    // всплывающий урон
    private static final Map<Integer, Float> HP = new HashMap<>();
    private static final List<Popup> POPUPS = new ArrayList<>();
    // прочность
    private static String warned = "";
    // биом
    private static ResourceKey<Biome> biomeCandidate, biomeShown;
    private static long biomeSince, biomeShowTime;
    private static Component biomeName;
    // AFK и перерыв
    private static double lastMx, lastMy;
    private static long lastInput = Util.getMillis();
    private static long nextBreak;
    // смерть
    private static boolean wasDead;
    // чистый скриншот
    private static int cleanShot;
    private static boolean hideBefore;
    // статистика сервера
    private static int serverTicks;

    private Extras() {}

    // ================================================================== тик

    public static void tick(Minecraft mc) {
        ArcConfig cfg = ArcConfig.get();
        LocalPlayer p = mc.player;
        long now = Util.getMillis();
        if (p == null || mc.level == null) {
            worldJoin = 0;
            HP.clear();
            POPUPS.clear();
            if (ArcCamera.freelook()) ArcCamera.freelookEnd(mc);
            return;
        }
        if (worldJoin == 0) worldJoin = now;

        // зум
        zooming = cfg.zoomEnabled && mc.screen == null && ArcKeys.ZOOM.isDown();
        if (!zooming) zoomTarget = 4F;
        // свободный обзор
        boolean fl = cfg.freelookEnabled && mc.screen == null && ArcKeys.FREELOOK.isDown();
        if (fl && !ArcCamera.freelook() && !ArcCamera.active()) ArcCamera.freelookStart(mc);
        else if (!fl && ArcCamera.freelook()) ArcCamera.freelookEnd(mc);
        // клавиши-переключатели
        while (ArcKeys.FULLBRIGHT.consumeClick()) {
            cfg.fullbright = !cfg.fullbright;
            ArcConfig.save();
            p.displayClientMessage(Component.translatable(cfg.fullbright ? "archud.extra.fullbright_on" : "archud.extra.fullbright_off")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
        while (ArcKeys.AUTO_SPRINT.consumeClick()) {
            cfg.alwaysSprint = !cfg.alwaysSprint;
            ArcConfig.save();
            p.displayClientMessage(Component.translatable(cfg.alwaysSprint ? "archud.extra.sprint_on" : "archud.extra.sprint_off")
                    .withStyle(ChatFormatting.AQUA), true);
        }
        while (ArcKeys.COPY_COORDS.consumeClick()) {
            String c = p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ();
            mc.keyboardHandler.setClipboard(c);
            p.displayClientMessage(Component.translatable("archud.extra.coords_copied", c).withStyle(ChatFormatting.GREEN), true);
        }
        while (ArcKeys.CLEAN_SCREENSHOT.consumeClick()) {
            if (cleanShot == 0) {
                hideBefore = mc.options.hideGui;
                mc.options.hideGui = true;
                cleanShot = 2;
            }
        }
        if (cleanShot > 0 && --cleanShot == 0) {
            Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> mc.execute(() -> mc.gui.getChat().addMessage(msg)));
            mc.options.hideGui = hideBefore;
        }
        // автобег
        if (cfg.alwaysSprint && p.input != null && p.input.forwardImpulse > 0.8F && !p.isShiftKeyDown() && !p.isUsingItem()
                && !p.isSprinting() && !p.horizontalCollision && (p.getFoodData().getFoodLevel() > 6 || p.getAbilities().mayfly)) {
            p.setSprinting(true);
        }

        trackDamage(mc, p, now);
        if (lastAttacked != null && now - hitTime < 700 && !lastAttacked.isAlive()) hitKill = true;
        durabilityWarning(mc, p, cfg);
        biome(mc, p, now);
        afkAndBreak(mc, p, cfg, now);

        // координаты смерти
        boolean dead = p.isDeadOrDying();
        if (dead && !wasDead && cfg.deathCoordsChat) {
            String dim = mc.level.dimension().location().getPath();
            mc.gui.getChat().addMessage(Component.translatable("archud.extra.death", p.getBlockX(), p.getBlockY(), p.getBlockZ(), dim)
                    .withStyle(ChatFormatting.RED));
        }
        wasDead = dead;

        // время игры на сервере
        if (mc.getCurrentServer() != null && !mc.isLocalServer() && ++serverTicks >= 20) {
            serverTicks = 0;
            String ip = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            ArcConfig.ServerStat st = cfg.serverStats.computeIfAbsent(ip, k -> new ArcConfig.ServerStat());
            if (st.name.isEmpty()) st.name = mc.getCurrentServer().name;
            st.playSeconds++;
            if (st.playSeconds % 60 == 0) ArcConfig.save();
        }
    }

    private static void trackDamage(Minecraft mc, LocalPlayer p, long now) {
        if (!ArcConfig.get().mobDamageNumbers) return;
        Map<Integer, Float> seen = new HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == p || e.distanceToSqr(p) > 32 * 32) continue;
            float hp = le.getHealth();
            Float prev = HP.get(e.getId());
            if (prev != null) {
                float d = hp - prev;
                if (d < -0.05F) POPUPS.add(new Popup(e.getX(), e.getY() + e.getBbHeight() + 0.25, e.getZ(),
                        "-" + fmt(-d), le.isAlive() ? 0xFFFF6B6B : 0xFFFFD54F, now));
                else if (d > 0.45F) POPUPS.add(new Popup(e.getX(), e.getY() + e.getBbHeight() + 0.25, e.getZ(), "+" + fmt(d), 0xFF9CFF5A, now));
            }
            seen.put(e.getId(), hp);
        }
        HP.clear();
        HP.putAll(seen);
        POPUPS.removeIf(pp -> now - pp.time() > 1200);
        while (POPUPS.size() > 40) POPUPS.remove(0);
    }

    private static String fmt(float v) {
        return Math.abs(v - Math.round(v)) < 0.05F ? Integer.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static void durabilityWarning(Minecraft mc, LocalPlayer p, ArcConfig cfg) {
        if (!cfg.breakSound) return;
        ItemStack s = p.getMainHandItem();
        if (!s.isDamageableItem()) return;
        int left = s.getMaxDamage() - s.getDamageValue();
        if (left <= 0 || left > Math.max(1, s.getMaxDamage() / 10)) return;
        String key = BuiltInRegistries.ITEM.getKey(s.getItem()) + ":" + (left * 50 / s.getMaxDamage());
        if (key.equals(warned)) return;
        warned = key;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_BREAK, 1.7F, 0.6F));
        p.displayClientMessage(Component.translatable("archud.extra.low_durability", s.getHoverName(), left).withStyle(ChatFormatting.RED), true);
    }

    private static void biome(Minecraft mc, LocalPlayer p, long now) {
        if (!ArcConfig.get().biomeTitle) return;
        Optional<ResourceKey<Biome>> key = mc.level.getBiome(p.blockPosition()).unwrapKey();
        if (key.isEmpty()) return;
        if (!key.get().equals(biomeCandidate)) {
            biomeCandidate = key.get();
            biomeSince = now;
        }
        if (now - biomeSince > 1500 && !biomeCandidate.equals(biomeShown)) {
            biomeShown = biomeCandidate;
            var id = biomeShown.location();
            biomeName = Component.translatableWithFallback("biome." + id.getNamespace() + "." + id.getPath(),
                    com.arc4io.archud.client.hud.HudRenderer.pretty(id.getPath()));
            biomeShowTime = now;
        }
    }

    private static void afkAndBreak(Minecraft mc, LocalPlayer p, ArcConfig cfg, long now) {
        double mx = mc.mouseHandler.xpos(), my = mc.mouseHandler.ypos();
        boolean input = mx != lastMx || my != lastMy || mc.screen != null
                || (p.input != null && (Math.abs(p.input.forwardImpulse) + Math.abs(p.input.leftImpulse) > 0 || p.input.jumping))
                || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown();
        lastMx = mx;
        lastMy = my;
        if (input) lastInput = now;
        if (cfg.breakReminder > 0) {
            long interval = cfg.breakReminder * 60_000L;
            if (nextBreak == 0) nextBreak = sessionStart + interval;
            if (now >= nextBreak) {
                nextBreak = now + interval;
                long mins = (now - sessionStart) / 60_000L;
                SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                        Component.translatable("archud.extra.break_title"), Component.translatable("archud.extra.break_text", mins / 60, mins % 60));
            }
        }
    }

    public static boolean afk() {
        return Util.getMillis() - lastInput > 180_000L;
    }

    /** Время в мире с момента входа, мс. */
    public static long worldTime() {
        return worldJoin == 0 ? 0 : Util.getMillis() - worldJoin;
    }

    // ================================================================== хуки

    public static void onAttack(Entity target) {
        if (!ArcConfig.get().hitMarker || !(target instanceof LivingEntity)) return;
        lastAttacked = target;
        hitTime = Util.getMillis();
        hitKill = false;
    }

    public static double zoomFov(double fov) {
        float target = zooming ? zoomTarget : 1F;
        zoom += (target - zoom) * 0.35F;
        if (Math.abs(zoom - target) < 0.01F) zoom = target;
        return zoom <= 1.001F ? fov : fov / zoom;
    }

    public static boolean zoomScroll(double delta) {
        if (!zooming) return false;
        zoomTarget = Mth.clamp(zoomTarget * (delta > 0 ? 1.25F : 0.8F), 1.5F, 40F);
        return true;
    }

    public static boolean fullbright() {
        return ArcConfig.get().fullbright;
    }

    // ================================================================== отрисовка

    /** Свой прицел. true — нарисован (ванильный отменить). */
    public static boolean renderCrosshair(GuiGraphics g) {
        ArcConfig cfg = ArcConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (cfg.crosshairStyle == 0 || mc.player == null || mc.options.renderDebug) return false;
        if (!mc.options.getCameraType().isFirstPerson()) return true;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int c = Draw.brighten(Theme.accent(), 0.4F);
        int sh = 0x90000000;
        switch (cfg.crosshairStyle) {
            case 1 -> {
                g.fill(cx - 2, cy - 2, cx + 2, cy + 2, sh);
                g.fill(cx - 1, cy - 1, cx + 1, cy + 1, c);
            }
            case 2 -> {
                for (int[] r : new int[][]{{-6, -1, -2, 0}, {2, -1, 6, 0}, {-1, -6, 0, -2}, {-1, 2, 0, 6}}) {
                    g.fill(cx + r[0] - 1, cy + r[1] - 1, cx + r[2] + 1, cy + r[3] + 1, sh);
                    g.fill(cx + r[0], cy + r[1], cx + r[2], cy + r[3], c);
                }
                g.fill(cx - 1, cy - 1, cx, cy, c);
            }
            default -> {
                for (int a = 0; a < 24; a++) {
                    double ang = a * Math.PI * 2 / 24;
                    int x = cx + (int) Math.round(Math.cos(ang) * 4.5), y = cy + (int) Math.round(Math.sin(ang) * 4.5);
                    g.fill(x - 1, y - 1, x, y, c);
                }
                g.fill(cx - 1, cy - 1, cx, cy, c);
            }
        }
        // индикатор атаки
        if (mc.options.attackIndicator().get() == AttackIndicatorStatus.CROSSHAIR) {
            float s = mc.player.getAttackStrengthScale(0F);
            if (s < 1F) {
                g.fill(cx - 8, cy + 9, cx + 8, cy + 11, 0x80000000);
                g.fill(cx - 8, cy + 9, cx - 8 + Math.round(16 * s), cy + 11, c);
            }
        }
        return true;
    }

    public static void renderOverlay(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui) return;
        ArcConfig cfg = ArcConfig.get();
        long now = Util.getMillis();
        int w = g.guiWidth(), h = g.guiHeight();
        int cx = w / 2, cy = h / 2;
        var font = mc.font;
        int accent = Theme.accent();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();

        // всплывающий урон
        if (cfg.mobDamageNumbers && WaypointWorldRenderer.ready()) {
            for (Popup pp : POPUPS) {
                float age = (now - pp.time()) / 1200F;
                float[] s = WaypointWorldRenderer.project(pp.x(), pp.y() + age * 0.8, pp.z(), w, h);
                if (s[2] <= 0) continue;
                int a = (int) (255 * (1F - age * age));
                float sc = Mth.clamp(6F / Math.max(1F, s[2]), 0.6F, 1.4F);
                Draw.smallCentered(g, font, pp.text(), s[0], s[1], Draw.alpha(pp.color(), Math.max(8, a)), sc);
            }
        }
        // хитмаркер
        if (cfg.hitMarker && now - hitTime < 350 && firstPerson) {
            int a = (int) (255 * (1F - (now - hitTime) / 350F));
            int col = Draw.alpha(hitKill ? 0xFFFF4040 : 0xFFFFFFFF, Math.max(8, a));
            for (int i = 3; i < 7; i++) {
                g.fill(cx - i - 1, cy - i - 1, cx - i, cy - i, col);
                g.fill(cx + i, cy - i - 1, cx + i + 1, cy - i, col);
                g.fill(cx - i - 1, cy + i, cx - i, cy + i + 1, col);
                g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, col);
            }
        }
        ItemStack held = p.getMainHandItem();
        // стрелы
        if (cfg.arrowCounter && firstPerson && (held.getItem() instanceof BowItem || held.getItem() instanceof CrossbowItem)) {
            int arrows = 0;
            Inventory inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.TIPPED_ARROW)) arrows += s.getCount();
            }
            String t = p.getAbilities().instabuild ? "∞" : Integer.toString(arrows);
            g.pose().pushPose();
            g.pose().translate(cx + 7, cy + 5, 0);
            g.pose().scale(0.5F, 0.5F, 1F);
            g.renderItem(new ItemStack(Items.ARROW), 0, 0);
            g.pose().popPose();
            Draw.small(g, font, t, cx + 16, cy + 7, arrows == 0 && !p.getAbilities().instabuild ? 0xFFFF6B6B : 0xFFFFFFFF, 0.6F);
        }
        // прочность у прицела
        if (cfg.lowDurabilityCrosshair && firstPerson && held.isDamageableItem()) {
            float f = 1F - held.getDamageValue() / (float) held.getMaxDamage();
            if (f < 0.25F) {
                int col = f < 0.1F ? 0xFFFF5252 : 0xFFFFD54F;
                g.fill(cx - 9, cy + 13, cx + 9, cy + 15, 0x90000000);
                g.fill(cx - 9, cy + 13, cx - 9 + Math.round(18 * f), cy + 15, col);
                String t = Integer.toString(held.getMaxDamage() - held.getDamageValue());
                Draw.smallCentered(g, font, t, cx, cy + 17, col, 0.5F);
            }
        }
        // элитры
        if (cfg.elytraHud && p.isFallFlying()) renderElytra(g, mc, p, cx, cy, accent);
        // полный инвентарь
        if (cfg.fullInventoryWarning && !p.isCreative() && !p.isSpectator()) {
            boolean full = true;
            for (int i = 0; i < 36 && full; i++) if (p.getInventory().getItem(i).isEmpty()) full = false;
            if (full) {
                float pulse = 0.6F + 0.4F * Mth.sin(now / 250F);
                Draw.smallCentered(g, font, Component.translatable("archud.extra.inventory_full").getString(), cx, h - 62,
                        Draw.alpha(0xFFFF8A65, (int) (255 * pulse)), 0.75F);
            }
        }
        // зум
        if (zooming && zoom > 1.05F) {
            Draw.smallCentered(g, font, String.format(Locale.ROOT, "×%.1f", zoom), cx, h - 74, 0xC0FFFFFF, 0.7F);
        }
        // название биома
        if (cfg.biomeTitle && biomeName != null && now - biomeShowTime < 3500) {
            float t = (now - biomeShowTime) / 3500F;
            float a = t < 0.15F ? t / 0.15F : t > 0.75F ? (1F - t) / 0.25F : 1F;
            int alpha = (int) (255 * Mth.clamp(a, 0, 1));
            if (alpha > 8) {
                String n = biomeName.getString();
                int ty = (int) (h * 0.22F);
                Draw.smallCentered(g, font, n, cx, ty, Draw.alpha(0xFFFFFFFF, alpha), 1.6F);
                int lw = (int) (font.width(n) * 1.6F / 2) + 10;
                g.fill(cx - lw, ty + 16, cx + lw, ty + 17, Draw.alpha(accent, alpha));
            }
        }
        // AFK
        if (cfg.afkOverlay && afk() && mc.screen == null) {
            g.fill(0, 0, w, h, 0x50000000);
            long sec = (now - lastInput) / 1000;
            Draw.smallCentered(g, font, "AFK", cx, cy - 14, Draw.alpha(accent, 0xFF), 2F);
            Draw.smallCentered(g, font, String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60), cx, cy + 6, 0xFFE0E6EE, 0.9F);
        }
    }

    private static void renderElytra(GuiGraphics g, Minecraft mc, LocalPlayer p, int cx, int cy, int accent) {
        var font = mc.font;
        int x = cx - 170, y = cy - 28, w = 92, h = 56;
        Draw.accentPanel(g, x, y, w, h, accent);
        double speed = p.getDeltaMovement().length() * 20.0;
        ItemStack ely = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        int rockets = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.FIREWORK_ROCKET)) rockets += s.getCount();
        }
        String[][] rows = {
                {Component.translatable("archud.extra.ely_speed").getString(), String.format(Locale.ROOT, "%.1f b/s", speed)},
                {Component.translatable("archud.extra.ely_alt").getString(), Integer.toString(p.getBlockY())},
                {Component.translatable("archud.extra.ely_pitch").getString(), Math.round(-p.getXRot()) + "°"},
                {Component.translatable("archud.extra.ely_dur").getString(), ely.isDamageableItem()
                        ? Math.round(100F * (1F - ely.getDamageValue() / (float) ely.getMaxDamage())) + "%" : "—"},
                {Component.translatable("archud.extra.ely_rockets").getString(), Integer.toString(rockets)},
        };
        int ly = y + 4;
        for (String[] r : rows) {
            Draw.small(g, font, r[0], x + 5, ly, Draw.TEXT_DIM, 0.65F);
            Draw.small(g, font, r[1], x + w - 5 - font.width(r[1]) * 0.65F, ly, Draw.TEXT, 0.65F);
            ly += 10;
        }
        if (p.getXRot() > 45 && speed > 25) Draw.smallCentered(g, font, Component.translatable("archud.extra.ely_dive").getString(), cx, cy + 34, 0xFFFF8A65, 0.7F);
    }
}
