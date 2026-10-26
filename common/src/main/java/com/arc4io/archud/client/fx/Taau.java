package com.arc4io.archud.client.fx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Arc TSR — временной апскейл (тот же принцип, что у DLSS 2 / FSR 2, без нейросети).
 * Каждый кадр мир рисуется в уменьшенном разрешении со сдвигом камеры на долю пикселя (последовательность Халтона),
 * а шейдер накапливает эти сдвинутые кадры в буфере полного разрешения. Прошлые кадры совмещаются с текущим
 * по глубине и матрицам камеры (репроекция), а устаревшие цвета отсекаются по соседям текущего кадра,
 * чтобы не было шлейфов. В итоге из 50–67% пикселей собирается картинка, близкая к родному разрешению.
 */
public final class Taau {
    private static TextureTarget histA, histB;
    private static PostPass taaToB, taaToA, sharpFromB, sharpFromA;
    private static boolean useA = true; // где лежит прошлая история
    private static boolean historyValid;
    private static int frame;
    /** Текущий сдвиг (в пикселях уменьшенного кадра) — применяется к матрице проекции, пока рисуется мир. */
    private static float jx, jy;
    private static boolean jitterActive;
    private static int jitterW = 1, jitterH = 1;

    private static final Matrix4f curViewProj = new Matrix4f(), prevViewProj = new Matrix4f(), invViewProj = new Matrix4f();
    private static Vec3 curCam = Vec3.ZERO, prevCam = Vec3.ZERO;
    private static boolean captured, havePrev;

    private Taau() {}

    private static float halton(int i, int b) {
        float f = 1, r = 0;
        while (i > 0) {
            f /= b;
            r += f * (i % b);
            i /= b;
        }
        return r;
    }

    /** Начало кадра мира: выбрать сдвиг. w×h — размер уменьшенного кадра. */
    static void beginFrame(int w, int h) {
        int n = 1 + (frame++ & 15);
        jx = halton(n, 2) - 0.5F;
        jy = halton(n, 3) - 0.5F;
        jitterW = w;
        jitterH = h;
        jitterActive = true;
        captured = false;
    }

    static void endFrame() {
        jitterActive = false;
    }

    /** Из миксина GameRenderer.getProjectionMatrix: сдвинуть проекцию на долю пикселя. */
    /** Для проверки в тестовом прогоне: сдвиг проекции действительно применялся. */
    public static int jitterCalls;

    public static void jitter(Matrix4f proj) {
        if (!jitterActive) return;
        jitterCalls++;
        proj.translateLocal(2F * jx / jitterW, 2F * jy / jitterH, 0F);
    }

    /** Из миксина LevelRenderer.renderLevel: запомнить матрицы камеры этого кадра. */
    public static void capture(Matrix4f view, Matrix4f proj, Vec3 cam) {
        if (!jitterActive || captured) return;
        captured = true;
        // проекция без сдвига: снять перенос, добавленный в jitter()
        Matrix4f p = new Matrix4f(proj).translateLocal(-2F * jx / jitterW, -2F * jy / jitterH, 0F);
        prevViewProj.set(curViewProj);
        prevCam = curCam;
        curViewProj.set(p).mul(view);
        invViewProj.set(curViewProj).invert();
        curCam = cam;
        if (!havePrev) {
            prevViewProj.set(curViewProj);
            prevCam = cam;
            havePrev = true;
        }
    }

    /** Накопить кадр scaled (цвет + глубина) и вывести результат в real. */
    static void resolve(Minecraft mc, RenderTarget scaled, RenderTarget real, float sharpness, float pt) {
        int w = real.width, h = real.height;
        if (histA == null) {
            histA = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            histB = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            historyValid = false;
        } else if (histA.width != w || histA.height != h) {
            histA.resize(w, h, Minecraft.ON_OSX);
            histB.resize(w, h, Minecraft.ON_OSX);
            closePasses();
            historyValid = false;
        }
        histA.setFilterMode(9729);
        histB.setFilterMode(9729);
        if (taaToB == null) {
            taaToB = taaPass(mc, scaled, histA, histB);
            taaToA = taaPass(mc, scaled, histB, histA);
            sharpFromB = sharpPass(mc, histB, real);
            sharpFromA = sharpPass(mc, histA, real);
        }
        if (!captured) historyValid = false;
        Vec3 d = curCam.subtract(prevCam);
        if (d.lengthSqr() > 64 * 64) historyValid = false; // телепорт / смена измерения

        PostPass taa = useA ? taaToB : taaToA;
        PostPass sharp = useA ? sharpFromB : sharpFromA;
        var fx = taa.getEffect();
        fx.safeGetUniform("Jitter").set(jx, jy);
        fx.safeGetUniform("InvViewProj").set(invViewProj);
        fx.safeGetUniform("PrevViewProj").set(prevViewProj);
        fx.safeGetUniform("CamDelta").set((float) d.x, (float) d.y, (float) d.z);
        fx.safeGetUniform("HistoryValid").set(historyValid ? 1F : 0F);
        fx.safeGetUniform("Scale").set(scaled.width / (float) w);
        taa.process(pt);

        var sfx = sharp.getEffect();
        sfx.safeGetUniform("Mode").set(1F);
        sfx.safeGetUniform("Sharpness").set(Math.min(0.875F, sharpness * 1.25F)); // накопление чуть смягчает; 0.875 — предел RCAS (вес 3/16)
        sharp.process(pt);

        useA = !useA;
        historyValid = true;
        RenderSystem.enableDepthTest();
    }

    private static PostPass taaPass(Minecraft mc, RenderTarget scaled, TextureTarget history, TextureTarget out) {
        try {
            PostPass p = new PostPass(mc.getResourceManager(), "archud_taau", scaled, out);
            p.addAuxAsset("DepthSampler", scaled::getDepthTextureId, scaled.width, scaled.height);
            p.addAuxAsset("HistorySampler", history::getColorTextureId, history.width, history.height);
            p.setOrthoMatrix(new Matrix4f().setOrtho(0F, out.width, 0F, out.height, 0.1F, 1000F));
            return p;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PostPass sharpPass(Minecraft mc, TextureTarget in, RenderTarget real) {
        try {
            PostPass p = new PostPass(mc.getResourceManager(), "archud_upscale", in, real);
            p.setOrthoMatrix(new Matrix4f().setOrtho(0F, real.width, 0F, real.height, 0.1F, 1000F));
            return p;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Буфер уменьшенного кадра пересоздан (другой масштаб) — проходы и история больше не годятся. */
    static void invalidate() {
        closePasses();
        historyValid = false;
    }

    private static void closePasses() {
        for (PostPass p : new PostPass[]{taaToB, taaToA, sharpFromB, sharpFromA}) if (p != null) p.close();
        taaToB = taaToA = sharpFromB = sharpFromA = null;
    }

    public static void reset() {
        closePasses();
        if (histA != null) histA.destroyBuffers();
        if (histB != null) histB.destroyBuffers();
        histA = histB = null;
        historyValid = false;
        havePrev = false;
        jitterActive = false;
    }
}
