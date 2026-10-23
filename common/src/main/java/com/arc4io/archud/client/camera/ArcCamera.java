package com.arc4io.archud.client.camera;

import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.CameraInvoker;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Камера от третьего лица в стиле Zelda / современных экшен-игр:
 *  - камера за плечом (правым/левым), плавно следует за персонажем, не проходит сквозь стены;
 *  - свободный обзор: мышь крутит камеру, а персонаж поворачивается туда, куда идёт (W — «от камеры»);
 *  - прицеливание: при атаке, использовании предмета (лук, щит, еда), полёте на элитрах персонаж
 *    смотрит туда же, куда камера, появляется прицел;
 *  - Alt + колесо мыши — отдалить / приблизить.
 */
public final class ArcCamera {
    private static boolean active;
    private static float camYaw, camPitch;
    private static Vec3 smoothEye;
    private static float curDist = 4F, curSide, curHeight;
    private static long lastFrame;
    private static boolean wasAiming;

    private ArcCamera() {}

    public static boolean active() {
        return active;
    }

    public static void toggle(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (!active) {
            start(mc); // плавный «отъезд» камеры
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            p.displayClientMessage(Component.translatable("archud.camera.on"), true);
        } else {
            off(mc);
            p.displayClientMessage(Component.translatable("archud.camera.off"), true);
        }
    }

    private static void off(Minecraft mc) {
        active = false;
        if (mc.player != null) {
            mc.player.setYRot(camYaw);
            mc.player.setXRot(camPitch);
        }
        mc.options.setCameraType(CameraType.FIRST_PERSON);
    }

    /**
     * Порядок F5: от первого лица → камера Arc → обычная от третьего лица → (вид спереди, если включён) → от первого лица.
     */
    public static CameraType nextPerspective(CameraType current) {
        Minecraft mc = Minecraft.getInstance();
        ArcConfig cfg = ArcConfig.get();
        if (!cfg.cameraInF5Cycle || mc.player == null) return current.cycle();
        if (current == CameraType.FIRST_PERSON) {
            start(mc);
            return CameraType.THIRD_PERSON_BACK;
        }
        if (current == CameraType.THIRD_PERSON_BACK && active) {
            // камера Arc -> обычная от третьего лица, смотрим туда же, куда смотрела камера
            active = false;
            mc.player.setYRot(camYaw);
            mc.player.setXRot(camPitch);
            return CameraType.THIRD_PERSON_BACK;
        }
        if (current == CameraType.THIRD_PERSON_BACK) return cfg.cameraFrontView ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON;
        return CameraType.FIRST_PERSON;
    }

    private static void start(Minecraft mc) {
        LocalPlayer p = mc.player;
        active = true;
        camYaw = p.getYRot();
        camPitch = p.getXRot();
        smoothEye = null;
        curDist = 0.5F;
        curSide = 0F;
    }

    public static void swapShoulder() {
        ArcConfig cfg = ArcConfig.get();
        cfg.cameraLeftShoulder = !cfg.cameraLeftShoulder;
        ArcConfig.save();
    }

    public static void tick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.level == null) {
            active = false;
            return;
        }
        // игрок сам переключил вид (F5) — выходим из режима
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) active = false;
    }

    /** Персонаж смотрит туда же, куда камера (прицел, атака, использование предмета, элитры, транспорт). */
    public static boolean aiming() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return true;
        if (!ArcConfig.get().cameraFreeLook) return true;
        return mc.options.keyAttack.isDown() || mc.options.keyUse.isDown() || p.isUsingItem() || p.isFallFlying()
                || p.isPassenger() || p.isSpectator() || mc.options.keyPickItem.isDown();
    }

    /** Поворот мышью. true — поворот обработан камерой (ванильный поворот персонажа отменяется). */
    public static boolean turn(LocalPlayer p, double dx, double dy) {
        if (!active) return false;
        camPitch = Mth.clamp(camPitch + (float) dy * 0.15F, -90F, 90F);
        camYaw += (float) dx * 0.15F;
        if (aiming()) faceCamera(p);
        return true;
    }

    private static void faceCamera(LocalPlayer p) {
        p.setYRot(camYaw);
        p.setXRot(camPitch);
        p.setYHeadRot(camYaw);
    }

    /** Движение относительно камеры: персонаж поворачивается в сторону движения. */
    public static void remapInput(Input in) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!active || p == null) return;
        boolean aim = aiming();
        if (aim) {
            if (!wasAiming) faceCamera(p);
            wasAiming = true;
            return;
        }
        wasAiming = false;
        float f = in.forwardImpulse, l = in.leftImpulse;
        if (Math.abs(f) + Math.abs(l) < 0.01F) return;
        float desired = camYaw - (float) Math.toDegrees(Math.atan2(l, f));
        float cur = p.getYRot();
        float diff = Mth.wrapDegrees(desired - cur);
        float step = Mth.clamp(diff, -40F, 40F); // плавный разворот
        p.setYRot(cur + step);
        p.setYHeadRot(cur + step);
        p.setXRot(p.getXRot() * 0.7F);
        in.forwardImpulse = Math.min(1F, (float) Math.sqrt(f * f + l * l)) * (Math.abs(diff) > 120 ? 0.35F : 1F);
        in.leftImpulse = 0F;
    }

    /** После ванильной настройки камеры: ставим свою позицию и поворот. */
    public static void setupCamera(Camera camera, Entity entity, boolean detached, float partialTick) {
        if (!active || !detached || entity == null) return;
        ArcConfig cfg = ArcConfig.get();
        CameraInvoker inv = (CameraInvoker) camera;
        long now = Util.getMillis();
        float dt = lastFrame == 0 ? 0.016F : Math.min(0.1F, (now - lastFrame) / 1000F);
        lastFrame = now;

        Vec3 eye = new Vec3(Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()) + entity.getEyeHeight(),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));
        if (smoothEye == null || smoothEye.distanceToSqr(eye) > 64) smoothEye = eye;
        if (cfg.cameraSmoothing) {
            float a = 1F - (float) Math.exp(-dt * 16F);
            float ay = 1F - (float) Math.exp(-dt * 9F); // по вертикали мягче (прыжки, ступеньки)
            smoothEye = new Vec3(Mth.lerp(a, smoothEye.x, eye.x), Mth.lerp(ay, smoothEye.y, eye.y), Mth.lerp(a, smoothEye.z, eye.z));
        } else smoothEye = eye;

        float k = 1F - (float) Math.exp(-dt * 8F);
        curDist += (cfg.cameraDistance - curDist) * k;
        float targetSide = (cfg.cameraLeftShoulder ? 1F : -1F) * cfg.cameraShoulder;
        curSide += (targetSide - curSide) * k;
        curHeight += (cfg.cameraHeight - curHeight) * k;

        inv.archud$setRotation(camYaw, camPitch);
        inv.archud$setPosition(smoothEye.x, smoothEye.y, smoothEye.z);
        // сдвиг к плечу — без прохода сквозь стены
        Vec3 base = camera.getPosition();
        inv.archud$move(0, curHeight, curSide);
        Vec3 shifted = camera.getPosition();
        if (entity.level() != null) {
            HitResult hit = entity.level().clip(new ClipContext(base, shifted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3 h = hit.getLocation();
                inv.archud$setPosition(Mth.lerp(0.7, base.x, h.x), Mth.lerp(0.7, base.y, h.y), Mth.lerp(0.7, base.z, h.z));
            }
        }
        double d = inv.archud$getMaxZoom(curDist);
        inv.archud$move(-d, 0, 0);
    }

    /** Alt + колесо — расстояние камеры. */
    public static boolean scroll(double delta) {
        if (!active || !net.minecraft.client.gui.screens.Screen.hasAltDown()) return false;
        ArcConfig cfg = ArcConfig.get();
        cfg.cameraDistance = Mth.clamp(cfg.cameraDistance - (float) delta * 0.5F, 1.5F, 10F);
        ArcConfig.save();
        return true;
    }
}
