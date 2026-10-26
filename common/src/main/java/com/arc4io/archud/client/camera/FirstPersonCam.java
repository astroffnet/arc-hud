package com.arc4io.archud.client.camera;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.CameraInvoker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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


/**
 * Дополнительные виды от первого лица — входят в цикл F5 (обычный → улучшенный → бодикам → третье лицо):
 *  - «Бодикам»: камера закреплена на груди — ниже и чуть впереди глаз, широкий угол обзора, инерция поворота,
 *    сильная тряска и крен при ходьбе и поворотах, эффект объектива (бочкообразное искажение, хроматические
 *    аберрации, виньетка, шум). Прицел скрыт.
 */
public final class FirstPersonCam {
    public enum Style { VANILLA, BODYCAM }

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

    private FirstPersonCam() {}

    public static Style style() {
        int s = ArcConfig.get().firstPersonStyle;
        return s == 2 ? Style.BODYCAM : Style.VANILLA; // 1 — бывший «улучшенный», теперь обычный
    }

    /** Наш стиль действует прямо сейчас (вид от первого лица, камера Arc от третьего лица выключена). */
    public static boolean active(Style s) {
        Minecraft mc = Minecraft.getInstance();
        return style() == s && mc.player != null && mc.options.getCameraType().isFirstPerson() && !ArcCamera.active()
                && mc.getCameraEntity() == mc.player;
    }

    public static boolean anyActive() {
        return active(Style.BODYCAM);
    }

    /** Смена стиля по F5 (0 — обычный, 1 — улучшенный, 2 — бодикам). */
    public static void setStyle(Minecraft mc, int style) {
        ArcConfig cfg = ArcConfig.get();
        if (cfg.firstPersonStyle == style) return;
        cfg.firstPersonStyle = style;
        ArcConfig.save();
        init = false;
        if (mc.player != null && style != 0) {
            String key = "archud.fp.bodycam";
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
        return active(Style.BODYCAM);
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
}
