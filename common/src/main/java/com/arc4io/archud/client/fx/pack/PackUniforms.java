package com.arc4io.archud.client.fx.pack;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Значения uniform-переменных в формате OptiFine / Iris (viewWidth, frameTimeCounter, sunPosition, gbufferProjection…)
 * — то, что шейдерпаки ждут в проходах composite / final.
 */
public final class PackUniforms {
    private static final Matrix4f MODEL_VIEW = new Matrix4f(), PROJECTION = new Matrix4f();
    private static final Matrix4f PREV_MV = new Matrix4f(), PREV_PROJ = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO, prevCameraPos = Vec3.ZERO;
    private static Camera camera;
    private static final long START = System.nanoTime();
    private static long lastFrame;
    private static float frameTime, wetness, eyeSkySmooth, eyeBlockSmooth, centerDepth = 1F;
    private static int frameCounter;

    /** Значения текущего кадра: имя → Float / Integer / float[] / int[] / Matrix4f. */
    static final Map<String, Object> VALUES = new HashMap<>();

    private PackUniforms() {}

    /** Из начала отрисовки мира: матрица вида (поворот камеры) и проекция. */
    public static void capture(Matrix4f modelView, Matrix4f projection, Camera cam) {
        PREV_MV.set(MODEL_VIEW);
        PREV_PROJ.set(PROJECTION);
        MODEL_VIEW.set(modelView);
        PROJECTION.set(projection);
        prevCameraPos = cameraPos;
        cameraPos = cam.getPosition();
        camera = cam;
    }

    static void update(float pt, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        frameTime = lastFrame == 0 ? 0.016F : Math.min(1F, (now - lastFrame) / 1e9F);
        lastFrame = now;
        frameCounter = (frameCounter + 1) % 720720;
        Map<String, Object> v = VALUES;
        v.clear();
        float near = 0.05F, far = mc.gameRenderer.getRenderDistance();
        v.put("viewWidth", (float) w);
        v.put("viewHeight", (float) h);
        v.put("aspectRatio", w / (float) Math.max(1, h));
        v.put("near", near);
        v.put("far", far);
        v.put("frameTimeCounter", ((now - START) / 1e9F) % 3600F);
        v.put("frameTime", frameTime);
        v.put("frameCounter", frameCounter);
        v.put("screenBrightness", mc.options.gamma().get().floatValue());
        v.put("gbufferModelView", new Matrix4f(MODEL_VIEW));
        v.put("gbufferModelViewInverse", new Matrix4f(MODEL_VIEW).invert());
        v.put("gbufferProjection", new Matrix4f(PROJECTION));
        v.put("gbufferProjectionInverse", new Matrix4f(PROJECTION).invert());
        v.put("gbufferPreviousModelView", new Matrix4f(PREV_MV));
        v.put("gbufferPreviousProjection", new Matrix4f(PREV_PROJ));
        v.put("shadowModelView", new Matrix4f());
        v.put("shadowModelViewInverse", new Matrix4f());
        v.put("shadowProjection", new Matrix4f());
        v.put("shadowProjectionInverse", new Matrix4f());
        v.put("cameraPosition", vec(cameraPos));
        v.put("previousCameraPosition", vec(prevCameraPos));
        v.put("eyeAltitude", (float) cameraPos.y);
        v.put("fogColor", new float[]{RenderSystem.getShaderFogColor()[0], RenderSystem.getShaderFogColor()[1], RenderSystem.getShaderFogColor()[2]});
        v.put("fogStart", RenderSystem.getShaderFogStart());
        v.put("fogEnd", RenderSystem.getShaderFogEnd());
        v.put("fogDensity", 0.0F);
        if (mc.level != null) {
            long day = mc.level.getDayTime();
            v.put("worldTime", (int) (day % 24000L));
            v.put("worldDay", (int) (day / 24000L));
            v.put("moonPhase", mc.level.getMoonPhase());
            float celestial = mc.level.getTimeOfDay(pt);
            float sunAngle = (celestial + 0.25F) % 1F;
            v.put("sunAngle", sunAngle);
            v.put("shadowAngle", sunAngle < 0.5F ? sunAngle : sunAngle - 0.5F);
            Matrix4f sky = new Matrix4f().rotateY((float) Math.toRadians(-90)).rotateX(celestial * (float) Math.PI * 2F);
            Vector3f sunWorld = sky.transformPosition(new Vector3f(0, 100, 0));
            Vector3f sun = MODEL_VIEW.transformDirection(new Vector3f(sunWorld));
            Vector3f moon = new Vector3f(sun).negate();
            Vector3f up = MODEL_VIEW.transformDirection(new Vector3f(0, 100, 0));
            v.put("sunPosition", new float[]{sun.x, sun.y, sun.z});
            v.put("moonPosition", new float[]{moon.x, moon.y, moon.z});
            v.put("upPosition", new float[]{up.x, up.y, up.z});
            Vector3f light = sunAngle < 0.5F ? sun : moon;
            v.put("shadowLightPosition", new float[]{light.x, light.y, light.z});
            float rain = mc.level.getRainLevel(pt);
            v.put("rainStrength", rain);
            wetness += (rain - wetness) * Math.min(1F, frameTime * 0.2F);
            v.put("wetness", wetness);
            Vec3 sky3 = mc.level.getSkyColor(cameraPos, pt);
            v.put("skyColor", vec(sky3));
            BlockPos eye = BlockPos.containing(cameraPos);
            int sl = mc.level.getBrightness(LightLayer.SKY, eye), bl = mc.level.getBrightness(LightLayer.BLOCK, eye);
            v.put("eyeBrightness", new int[]{bl * 16, sl * 16});
            float k = Math.min(1F, frameTime * 2F);
            eyeSkySmooth += (sl * 16 - eyeSkySmooth) * k;
            eyeBlockSmooth += (bl * 16 - eyeBlockSmooth) * k;
            v.put("eyeBrightnessSmooth", new int[]{Math.round(eyeBlockSmooth), Math.round(eyeSkySmooth)});
        }
        int inWater = 0;
        if (camera != null) {
            FogType f = camera.getFluidInCamera();
            inWater = f == FogType.WATER ? 1 : f == FogType.LAVA ? 2 : f == FogType.POWDER_SNOW ? 3 : 0;
        }
        v.put("isEyeInWater", inWater);
        float nv = 0F, blind = 0F, dark = 0F;
        if (mc.player != null) {
            if (mc.player.hasEffect(MobEffects.NIGHT_VISION)) nv = 1F;
            if (mc.player.hasEffect(MobEffects.BLINDNESS)) blind = 1F;
            if (mc.player.hasEffect(MobEffects.DARKNESS)) dark = 1F;
        }
        v.put("nightVision", nv);
        v.put("blindness", blind);
        v.put("darknessFactor", dark);
        v.put("darknessLightFactor", 0F);
        v.put("playerMood", 0F);
        v.put("heldItemId", 0);
        v.put("heldItemId2", 0);
        v.put("heldBlockLightValue", 0);
        v.put("heldBlockLightValue2", 0);
        v.put("entityId", 0);
        v.put("blockEntityId", 0);
        // глубина в центре экрана (для DOF): по точке, на которую смотрит игрок
        float target = 1F;
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            double d = mc.hitResult.getLocation().distanceTo(cameraPos);
            Vector4f clip = PROJECTION.transform(new Vector4f(0, 0, (float) -d, 1));
            target = clip.w == 0 ? 1F : Math.max(0F, Math.min(1F, clip.z / clip.w * 0.5F + 0.5F));
        }
        centerDepth += (target - centerDepth) * Math.min(1F, frameTime * 6F);
        v.put("centerDepthSmooth", centerDepth);
        v.put("archud_MVP", new Matrix4f().setOrtho(0F, 1F, 0F, 1F, -1F, 1F));
    }

    private static float[] vec(Vec3 v) {
        return new float[]{(float) v.x, (float) v.y, (float) v.z};
    }
}
