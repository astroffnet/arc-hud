package com.arc4io.archud.client.camera;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.CameraInvoker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Дополнительные виды от первого лица (переключаются отдельной клавишей, обычный вид остаётся):
 *  - «Улучшенный»: видно своё тело (ноги, торс), мягкое покачивание при ходьбе, лёгкий наклон при стрейфе,
 *    проседание камеры при приземлении. Прицел и управление как в ванилле.
 *  - «Бодикам»: камера закреплена на груди — ниже и чуть впереди глаз, широкий угол обзора, инерция поворота,
 *    сильная тряска и крен при ходьбе и поворотах, эффект объектива (бочкообразное искажение, хроматические
 *    аберрации, виньетка, шум) и надпись записи с датой и временем. Прицел скрыт.
 */
public final class FirstPersonCam {
    public enum Style { VANILLA, ENHANCED, BODYCAM }

    private static final ResourceLocation SHADER = ArcHud.id("shaders/post/bodycam.json");

    // сглаженные значения
    private static float camYaw, camPitch, roll, strafe, turnRoll;
    private static float lastTargetYaw;
    private static boolean init;
    private static long lastFrame;
    // приземление
    private static boolean wasOnGround = true;
    private static float airFall, landStrength;
    private static long landTime;
    // постэффект
    private static PostChain chain;
    private static boolean chainFailed;
    private static int chainW, chainH;
    /** Сейчас рисуется собственное тело игрока в мире (для скрытия головы, рук и предметов в руках). */
    public static boolean renderingBody;

    private FirstPersonCam() {}

    public static Style style() {
        int s = ArcConfig.get().firstPersonStyle;
        return s == 1 ? Style.ENHANCED : s == 2 ? Style.BODYCAM : Style.VANILLA;
    }

    /** Наш стиль действует прямо сейчас (вид от первого лица, камера Arc от третьего лица выключена). */
    public static boolean active(Style s) {
        Minecraft mc = Minecraft.getInstance();
        return style() == s && mc.player != null && mc.options.getCameraType().isFirstPerson() && !ArcCamera.active()
                && mc.getCameraEntity() == mc.player;
    }

    public static boolean anyActive() {
        return active(Style.ENHANCED) || active(Style.BODYCAM);
    }

    public static void cycle(Minecraft mc) {
        ArcConfig cfg = ArcConfig.get();
        cfg.firstPersonStyle = (cfg.firstPersonStyle + 1) % 3;
        ArcConfig.save();
        init = false;
        if (mc.player != null) {
            String key = switch (style()) {
                case VANILLA -> "archud.fp.vanilla";
                case ENHANCED -> "archud.fp.enhanced";
                case BODYCAM -> "archud.fp.bodycam";
            };
            mc.player.displayClientMessage(Component.translatable("archud.fp.mode", Component.translatable(key)).withStyle(ChatFormatting.AQUA), true);
        }
    }

    // ------------------------------------------------------------------ тик

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        boolean onGround = p.onGround();
        if (!onGround) airFall = Math.max(airFall, p.fallDistance);
        if (onGround && !wasOnGround) {
            if (airFall > 1.2F && !p.isInWater() && !p.getAbilities().flying) {
                landStrength = Math.min(1F, airFall / 8F);
                landTime = Util.getMillis();
            }
            airFall = 0;
        }
        if (onGround || p.isInWater() || p.getAbilities().flying) airFall = onGround ? 0 : airFall;
        wasOnGround = onGround;
    }

    private static float landOffset() {
        if (landStrength <= 0) return 0;
        float t = (Util.getMillis() - landTime) / 1000F;
        if (t > 0.45F) return 0;
        return landStrength * Mth.sin((float) Math.PI * t / 0.45F);
    }

    /** Тело видно в этом режиме (не в полёте на элитрах, не во сне, не при плавании). */
    public static boolean bodyVisible() {
        if (!ArcConfig.get().firstPersonBody || !anyActive()) return false;
        Player p = Minecraft.getInstance().player;
        return p != null && !p.isSleeping() && !p.isFallFlying() && !p.isVisuallySwimming() && !p.isAutoSpinAttack()
                && !p.isSpectator() && !p.isInvisible();
    }

    public static boolean isSelf(Entity e) {
        return e != null && e == Minecraft.getInstance().getCameraEntity();
    }

    // ------------------------------------------------------------------ камера

    public static void setupCamera(Camera camera, Entity entity, boolean detached, float partialTick) {
        if (detached || !(entity instanceof Player p) || entity != Minecraft.getInstance().player) return;
        Style s = style();
        if (s == Style.VANILLA || !active(s)) {
            init = false;
            return;
        }
        CameraInvoker inv = (CameraInvoker) camera;
        long now = Util.getMillis();
        float dt = lastFrame == 0 ? 0.016F : Mth.clamp((now - lastFrame) / 1000F, 0.001F, 0.1F);
        lastFrame = now;
        float targetYaw = p.getViewYRot(partialTick), targetPitch = p.getViewXRot(partialTick);
        if (!init) {
            camYaw = targetYaw;
            camPitch = targetPitch;
            lastTargetYaw = targetYaw;
            roll = strafe = turnRoll = 0;
            init = true;
        }
        float yawVel = Mth.wrapDegrees(targetYaw - lastTargetYaw) / dt; // градусов в секунду
        lastTargetYaw = targetYaw;
        float strafeTarget = p instanceof LocalPlayer lp && lp.input != null ? lp.input.leftImpulse : 0F;
        strafe += (strafeTarget - strafe) * (1F - (float) Math.exp(-dt * 6F));

        float walk = p.walkDist + (p.walkDist - p.walkDistO) * partialTick;
        float bob = Mth.lerp(partialTick, p.oBob, p.bob);
        float phase = walk * (float) Math.PI;
        float land = landOffset();
        Vec3 eye = camera.getPosition();

        if (s == Style.ENHANCED) {
            roll = -strafe * 1.4F;
            if (land > 0) inv.archud$setPosition(eye.x, eye.y - land * 0.22F, eye.z);
            return;
        }

        // ---- бодикам
        float k = 1F - (float) Math.exp(-dt * 16F);
        camYaw += Mth.wrapDegrees(targetYaw - camYaw) * k;
        camPitch += (targetPitch - camPitch) * k;
        turnRoll += (Mth.clamp(-yawVel * 0.025F, -7F, 7F) - turnRoll) * (1F - (float) Math.exp(-dt * 5F));
        float time = now / 1000F;
        float breathe = Mth.sin(time * 1.6F) * 0.35F;
        float swayYaw = Mth.sin(phase) * bob * 22F + Mth.sin(time * 0.9F) * 0.3F;
        float swayPitch = Math.abs(Mth.cos(phase)) * bob * 18F + breathe;
        roll = Mth.sin(phase) * bob * 35F + turnRoll - strafe * 2.5F;
        inv.archud$setRotation(camYaw + swayYaw, Mth.clamp(camPitch + swayPitch, -90F, 90F));

        // крепление на груди: ниже глаз и чуть впереди (по горизонтали взгляда), не сквозь стены
        double yawRad = Math.toRadians(camYaw);
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double up = -0.34 - Math.abs(Mth.cos(phase)) * bob * 1.1 - land * 0.3 + breathe * 0.004;
        double side = Mth.sin(phase) * bob * 0.6;
        Vec3 target = eye.add(fx * 0.24 + fz * side, up, fz * 0.24 - fx * side);
        if (p.isCrouching()) target = target.add(0, 0.06, 0);
        HitResult hit = p.level().clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3 h = hit.getLocation();
            target = eye.add(h.subtract(eye).scale(0.6));
        }
        inv.archud$setPosition(target.x, target.y, target.z);
    }

    /** Крен камеры (вызывается из bobHurt — действует и на мир, и на руку). */
    public static void applyRoll(PoseStack pose) {
        if (!anyActive() || Math.abs(roll) < 0.001F) return;
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
    }

    /**
     * Своё покачивание вместо ванильного. true — ванильное отменить.
     * Улучшенный режим: та же форма, но мягче. Бодикам: тряску делает сама камера.
     */
    public static boolean bobView(PoseStack pose, float partialTick) {
        if (active(Style.BODYCAM)) return true;
        if (!active(Style.ENHANCED)) return false;
        Player p = Minecraft.getInstance().player;
        float f = p.walkDist - p.walkDistO;
        float f1 = -(p.walkDist + f * partialTick);
        float f2 = Mth.lerp(partialTick, p.oBob, p.bob);
        float s = Mth.sin(f1 * (float) Math.PI), c = Mth.cos(f1 * (float) Math.PI);
        pose.translate(s * f2 * 0.3F, -Math.abs(c * f2) * 0.65F, 0F);
        pose.mulPose(Axis.ZP.rotationDegrees(s * f2 * 1.5F));
        pose.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.cos(f1 * (float) Math.PI - 0.2F) * f2) * 2.5F));
        return true;
    }

    /** Угол обзора: у бодикама шире (с учётом подзорной трубы и т.п.). */
    public static double fov(double vanilla, boolean world) {
        if (!world || !active(Style.BODYCAM)) return vanilla;
        double base = Minecraft.getInstance().options.fov().get();
        return vanilla * (ArcConfig.get().bodycamFov / Math.max(30.0, base));
    }

    public static boolean hideCrosshair() {
        return active(Style.BODYCAM) && ArcConfig.get().bodycamHideCrosshair;
    }

    // ------------------------------------------------------------------ эффект объектива

    public static void postProcess(float partialTick) {
        if (!active(Style.BODYCAM) || !ArcConfig.get().bodycamLens || chainFailed) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(), SHADER);
                chainW = chainH = -1;
            }
            int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
            if (w != chainW || h != chainH) {
                chain.resize(w, h);
                chainW = w;
                chainH = h;
            }
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(partialTick);
            mc.getMainRenderTarget().bindWrite(true);
        } catch (Exception e) {
            chainFailed = true;
            ArcHud.LOGGER.warn("Arc HUD: bodycam lens effect unavailable", e);
            if (chain != null) chain.close();
            chain = null;
        }
    }

    /** После перезагрузки ресурсов (F3+T, смена пакетов) шейдер пересоздаётся. */
    public static void reset() {
        if (chain != null) chain.close();
        chain = null;
        chainFailed = false;
    }

    // ------------------------------------------------------------------ надпись записи

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss");

    public static void renderOverlay(GuiGraphics g) {
        if (!active(Style.BODYCAM) || !ArcConfig.get().bodycamOverlay) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        var font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        // рамка-уголки
        int c = 0xB0FFFFFF, m = 10, l = 14;
        g.fill(m, m, m + l, m + 1, c);
        g.fill(m, m, m + 1, m + l, c);
        g.fill(w - m - l, m, w - m, m + 1, c);
        g.fill(w - m - 1, m, w - m, m + l, c);
        g.fill(m, h - m - 1, m + l, h - m, c);
        g.fill(m, h - m - l, m + 1, h - m, c);
        g.fill(w - m - l, h - m - 1, w - m, h - m, c);
        g.fill(w - m - 1, h - m - l, w - m, h - m, c);
        // REC и время — внизу слева (сверху и по бокам место занято HUD)
        boolean blink = (Util.getMillis() / 600) % 2 == 0;
        int rx = m + 4, ry = h - m - 20;
        if (blink) g.fill(rx, ry + 1, rx + 5, ry + 6, 0xFFFF3030);
        Draw.small(g, font, "REC", rx + 8, ry, 0xFFFFFFFF, 0.75F);
        String stamp = LocalDateTime.now().format(STAMP);
        Draw.small(g, font, stamp, rx + 28, ry, 0xFFFFFFFF, 0.75F);
        String unit = "ARC BODYCAM · " + mc.player.getGameProfile().getName().toUpperCase(java.util.Locale.ROOT);
        Draw.small(g, font, unit, m + 4, h - m - 10, 0xD0FFFFFF, 0.6F);
        String coords = String.format(java.util.Locale.ROOT, "%d %d %d", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
        Draw.small(g, font, coords, w - m - 4 - font.width(coords) * 0.6F, h - m - 10, 0xD0FFFFFF, 0.6F);
        // батарея
        int bx = w - m - 22, by = h - m - 21;
        g.fill(bx, by, bx + 16, by + 1, c);
        g.fill(bx, by + 7, bx + 16, by + 8, c);
        g.fill(bx, by, bx + 1, by + 8, c);
        g.fill(bx + 15, by, bx + 16, by + 8, c);
        g.fill(bx + 16, by + 2, bx + 18, by + 6, c);
        g.fill(bx + 2, by + 2, bx + 12, by + 6, 0xFFE0E0E0);
    }
}
