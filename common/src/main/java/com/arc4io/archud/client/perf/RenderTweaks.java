package com.arc4io.archud.client.perf;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Дополнительные оптимизации отрисовки (каждая отключается в настройках):
 *  - частицы вне поля зрения не рисуются, а их количество за кадр ограничено;
 *  - тени сущностей рисуются только вблизи;
 *  - текст табличек — только вблизи (дальше он всё равно не читается);
 *  - «ленивые» анимации текстур: пока мир не виден (окно свёрнуто или открыт непрозрачный экран),
 *    анимированные текстуры (вода, лава, огонь, порталы, текстуры модов) не пересчитываются.
 */
public final class RenderTweaks {
    private static int particlesThisFrame;
    private static long frame = -1;
    private static Vec3 camPos = Vec3.ZERO;
    private static Vector3f look = new Vector3f(0, 0, 1);
    private static double cosLimit;
    public static int culledParticles;

    private RenderTweaks() {}

    /** Вызывается перед отрисовкой частиц (раз в кадр). */
    public static void beginParticles(Camera camera) {
        long now = Minecraft.getInstance().getFrameTimeNs();
        if (now == frame) return;
        frame = now;
        particlesThisFrame = 0;
        culledParticles = 0;
        camPos = camera.getPosition();
        look = new Vector3f(camera.getLookVector());
        Minecraft mc = Minecraft.getInstance();
        double fov = Math.toRadians(mc.options.fov().get() * 1.0);
        double aspect = mc.getWindow().getWidth() / Math.max(1.0, mc.getWindow().getHeight());
        double half = Math.atan(Math.tan(fov / 2) * Math.max(1.0, aspect)) + Math.toRadians(12); // запас по краям
        cosLimit = Math.cos(Math.min(Math.PI * 0.95, half));
    }

    /** true — частицу пропустить. */
    public static boolean skipParticle(double x, double y, double z) {
        ArcConfig cfg = ArcConfig.get();
        if (!cfg.particleCulling) return false;
        if (++particlesThisFrame > cfg.maxParticles && cfg.maxParticles > 0) {
            culledParticles++;
            return true;
        }
        double dx = x - camPos.x, dy = y - camPos.y, dz = z - camPos.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 9) return false; // рядом с камерой — всегда рисуем
        double dot = (dx * look.x() + dy * look.y() + dz * look.z()) / Math.sqrt(d2);
        if (dot < cosLimit) {
            culledParticles++;
            return true;
        }
        return false;
    }

    public static boolean skipShadow(Entity e) {
        int d = ArcConfig.get().shadowDistance;
        if (d <= 0) return false;
        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        return e.distanceToSqr(cam.getPosition()) > (double) d * d;
    }

    public static boolean skipSignText(net.minecraft.core.BlockPos pos) {
        int d = ArcConfig.get().signTextDistance;
        if (d <= 0) return false;
        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        return cam.getPosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > (double) d * d;
    }

    /** Мир не виден — анимации текстур можно не пересчитывать. */
    public static boolean skipTextureAnimation() {
        if (!ArcConfig.get().lazyTextureAnimations) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false; // меню: панорама и анимированные иконки
        if (!mc.isWindowActive() && ArcConfig.get().dynamicFps) return true;
        Screen s = mc.screen;
        return s != null && s.isPauseScreen() && mc.isPaused();
    }
}
