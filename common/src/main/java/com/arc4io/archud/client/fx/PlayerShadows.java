package com.arc4io.archud.client.fx;

import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arc RT-тени — своя альтернатива трассировке лучей для теней игроков и мобов (встроенный шейдер Arc).
 * <ul>
 *   <li>Источники света: солнце (днём), луна (ночью) и светящиеся блоки рядом — факелы, фонари, лава, светокамень,
 *   светящиеся блоки модов. Для каждой сущности до каждого источника пускается луч: если его перекрывает блок,
 *   тени от этого источника нет (под крышей нет тени от солнца, за стеной — от факела).</li>
 *   <li>Тень — силуэт настоящей модели (поза, броня, предметы в руках), спроецированный от источника на землю:
 *   от солнца — параллельно, от факела — расходящимся конусом, поэтому тень лежит с противоположной от света стороны
 *   и вытягивается, когда источник низко.</li>
 *   <li>Мягкие края (полутень) — источник света считается не точкой, а маленькой площадкой: несколько смещённых
 *   выборок, как у трассировки лучей с площадным светом. Плотность зависит от яркости источника и расстояния до него.</li>
 * </ul>
 * Ванильный круглый «блин» под сущностями при этом не рисуется.
 */
public final class PlayerShadows {
    /** Сейчас рисуется тень — таблички с именем и т. п. не нужны. */
    public static boolean rendering;
    private static final ResourceLocation WHITE = new ResourceLocation("archud", "textures/misc/white.png");

    /** Светящиеся блоки вокруг камеры (обновляются раз в секунду). */
    private record Light(double x, double y, double z, int emission) {}

    private static final List<Light> LIGHTS = new ArrayList<>();
    private static long lightsScanned;
    private static BlockPos lightsCenter = BlockPos.ZERO;
    /** Кэш лучей «видна ли сущность источнику» (на 4 тика): ключ — id сущности и номер источника. */
    private static final Map<Long, Boolean> RAYS = new HashMap<>();
    private static long raysTick;

    private PlayerShadows() {}

    /** Свой тип отрисовки: полупрозрачный, без двойного затемнения внутри одной выборки. */
    static final class ShadowType extends RenderType {
        private ShadowType(String name, VertexFormat f, VertexFormat.Mode m, int size, boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
            super(name, f, m, size, crumbling, sort, setup, clear);
        }

        static final RenderType SHADOW;

        static {
            net.minecraft.client.renderer.RenderStateShard[] shards = {
                    RENDERTYPE_ENTITY_TRANSLUCENT_SHADER,
                    new TextureStateShard(WHITE, false, false),
                    TRANSLUCENT_TRANSPARENCY,
                    NO_CULL,
                    LIGHTMAP,
                    OVERLAY,
                    new DepthTestStateShard("<", 513),
                    COLOR_DEPTH_WRITE,
                    POLYGON_OFFSET_LAYERING};
            SHADOW = new ShadowType("archud_rt_shadow", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false,
                    () -> {
                        for (var s : shards) s.setupRenderState();
                    },
                    () -> {
                        for (int i = shards.length - 1; i >= 0; i--) shards[i].clearRenderState();
                    });
        }
    }

    public static boolean active() {
        ArcConfig c = ArcConfig.get();
        return c.shaderPlayerShadows && !ShaderPacks.inUse();
    }

    /** Убрать ванильный круг-тень под сущностями (его заменяют настоящие тени). */
    public static boolean hideVanillaBlob() {
        return active() && ArcConfig.get().hideVanillaShadows;
    }

    // ------------------------------------------------------------------ вершины → тень

    /** Вершины любого типа отрисовки — в тень: чёрный цвет нужной прозрачности, полный формат сущности. */
    private static final class ShadowConsumer implements VertexConsumer {
        private VertexConsumer out;
        private int alpha;
        private double x, y, z;
        private float u, v;
        private boolean started;

        void set(VertexConsumer out, int alpha) {
            this.out = out;
            this.alpha = alpha;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            started = true;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            if (!started) return;
            started = false;
            out.vertex(x, y, z).color(0, 0, 0, alpha).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(0F, 1F, 0F).endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }

    private static final VertexConsumer NONE = new VertexConsumer() {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    };

    // ------------------------------------------------------------------ сцена: земля, источники, лучи

    /** Высота поверхности под сущностью (до 6 блоков вниз) или NaN (в воде, над пропастью). */
    private static double ground(ClientLevel level, double x, double y, double z) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(x, y + 0.2, z).mutable();
        for (int i = 0; i < 7; i++) {
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) return Double.NaN;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                if (top <= y + 0.25) return top;
            }
            pos.move(0, -1, 0);
        }
        return Double.NaN;
    }

    /** Раз в секунду: найти светящиеся блоки вокруг камеры. */
    private static void scanLights(ClientLevel level, Vec3 cam) {
        long now = level.getGameTime();
        BlockPos c = BlockPos.containing(cam);
        if (now - lightsScanned < 20 && c.distManhattan(lightsCenter) < 4) return;
        lightsScanned = now;
        lightsCenter = c;
        LIGHTS.clear();
        int r = 14;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    p.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    int e = level.getBlockState(p).getLightEmission();
                    if (e >= 6) LIGHTS.add(new Light(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, e));
                }
            }
        }
        if (LIGHTS.size() > 256) {
            LIGHTS.sort(Comparator.comparingDouble(l -> -l.emission()));
            LIGHTS.subList(256, LIGHTS.size()).clear();
        }
    }

    /** Луч от точки к источнику: true — свет доходит (ничего не загораживает). Кэш на 4 тика. */
    private static boolean visible(ClientLevel level, Entity e, int lightKey, Vec3 from, Vec3 to) {
        long key = ((long) e.getId() << 20) ^ lightKey;
        Boolean cached = RAYS.get(key);
        if (cached != null) return cached;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, e));
        boolean ok = hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 0.6 * 0.6;
        RAYS.put(key, ok);
        return ok;
    }

    // ------------------------------------------------------------------ проекция

    /**
     * Матрица проекции на плоскость y = h от источника L (в координатах сущности): L = (x, y, z, 1) — точка
     * (факел), L = (x, y, z, 0) — направление на бесконечно далёкий источник (солнце). M = (n·L)·I − L·nᵀ.
     */
    private static Matrix4f shadowMatrix(double lx, double ly, double lz, double lw, double h) {
        double[] n = {0, 1, 0, -h};
        double[] l = {lx, ly, lz, lw};
        double d = n[1] * l[1] + n[3] * l[3];
        float[] m = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) m[c * 4 + r] = (float) ((r == c ? d : 0) - l[r] * n[c]);
        }
        return new Matrix4f().set(m);
    }

    private static boolean casts(Entity e) {
        return e instanceof LivingEntity || e instanceof ItemEntity || e instanceof AbstractMinecart || e instanceof Boat;
    }

    // ------------------------------------------------------------------ кадр

    /** Нарисовать тени сущностей рядом (после сущностей, в том же кадре). */
    public static void render(PoseStack pose, Camera camera, float pt) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        ArcConfig cfg = ArcConfig.get();
        int quality = Mth.clamp(cfg.shadowQuality, 1, 3);
        Vec3 cam = camera.getPosition();
        if (level.getGameTime() != raysTick && level.getGameTime() % 4 == 0) {
            RAYS.clear();
            raysTick = level.getGameTime();
        }
        if (quality >= 2) scanLights(level, cam);

        // солнце / луна: направление на источник и сила
        float sun = level.getSunAngle(pt);
        double sx = -Math.sin(sun), sy = Math.cos(sun);
        float skyStrength;
        if (sy >= 0) skyStrength = (float) Mth.clamp(sy * 3.0, 0, 1);
        else {
            sx = -sx;
            sy = -sy;
            skyStrength = (float) Mth.clamp(sy * 3.0, 0, 1) * 0.4F * level.getMoonBrightness();
        }
        skyStrength *= 1F - level.getRainLevel(pt) * 0.75F;
        boolean hasSky = level.dimensionType().hasSkyLight();
        double sl = Math.sqrt(sx * sx + sy * sy);
        sx /= sl;
        sy /= sl;
        sy = Math.max(sy, 0.3); // у самого горизонта тень была бы бесконечной
        double sz = 0.12 * sx;

        // ближние сущности
        List<Entity> list = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!casts(e) || e.isInvisible() || e.isSpectator()) continue;
            if (e.distanceToSqr(cam) > 40 * 40) continue;
            list.add(e);
        }
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(cam)));
        int maxEntities = quality == 3 ? 32 : quality == 2 ? 20 : 12;
        if (list.size() > maxEntities) list = list.subList(0, maxEntities);

        var dispatcher = mc.getEntityRenderDispatcher();
        var buffers = mc.renderBuffers().bufferSource();
        ShadowConsumer wrapped = new ShadowConsumer();
        MultiBufferSource src = type -> type.mode() == VertexFormat.Mode.QUADS ? wrapped : NONE;
        int budget = quality == 3 ? 160 : quality == 2 ? 90 : 40; // всего «прогонов» моделей за кадр
        rendering = true;
        try {
            for (Entity e : list) {
                if (budget <= 0) break;
                double ex = Mth.lerp(pt, e.xo, e.getX()), ey = Mth.lerp(pt, e.yo, e.getY()), ez = Mth.lerp(pt, e.zo, e.getZ());
                double g = ground(level, ex, ey, ez);
                if (Double.isNaN(g) || ey - g > 4) continue;
                double height = Math.max(0.3, e.getBbHeight());
                double camFade = Mth.clamp(1.0 - (Math.sqrt(e.distanceToSqr(cam)) - 28) / 12.0, 0, 1);
                if (camFade <= 0) continue;
                Vec3 mid = new Vec3(ex, ey + height * 0.6, ez);
                int layer = 0;
                VertexConsumer out = buffers.getBuffer(ShadowType.SHADOW);

                // --- солнце / луна (параллельный свет, площадка — мягкие края)
                if (hasSky && skyStrength > 0.05F) {
                    Vec3 toSun = mid.add(sx * 48, sy * 48, sz * 48);
                    if (visible(level, e, 0, mid, toSun)) {
                        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, BlockPos.containing(ex, ey + 1, ez));
                        float strength = skyStrength * Mth.clamp(sky / 15F, 0.2F, 1F) * (float) camFade;
                        int samples = quality == 1 ? 1 : 3;
                        int alpha = (int) (strength * (samples == 1 ? 105 : 48));
                        for (int s = 0; s < samples && alpha > 3; s++) {
                            double j = samples == 1 ? 0 : (s - 1) * 0.045; // угловой размер солнца
                            double dx = sx + j * 0.7, dz = sz + j, dy = sy;
                            budget -= draw(pose, dispatcher, e, pt, src, wrapped, out, alpha, ex, ey, ez, cam,
                                    shadowMatrix(dx, dy, dz, 0, g - ey + 0.012 + layer++ * 0.002));
                        }
                    }
                }

                // --- светящиеся блоки (точечный свет, расходящаяся тень; луч до источника проверяется)
                if (quality >= 2 && !LIGHTS.isEmpty()) {
                    List<double[]> cand = new ArrayList<>();
                    for (int i = 0; i < LIGHTS.size(); i++) {
                        Light l = LIGHTS.get(i);
                        double dx = l.x() - ex, dy = l.y() - mid.y, dz = l.z() - ez;
                        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (d < 0.8 || d > l.emission() - 1) continue;
                        double strength = Math.pow(1.0 - d / l.emission(), 1.3) * (l.emission() / 15.0);
                        if (strength < 0.08) continue;
                        cand.add(new double[]{strength, i});
                    }
                    cand.sort((a, b) -> Double.compare(b[0], a[0]));
                    int used = 0, maxLights = quality == 3 ? 3 : 1;
                    for (double[] c : cand) {
                        if (used >= maxLights || budget <= 0) break;
                        Light l = LIGHTS.get((int) c[1]);
                        Vec3 lp = new Vec3(l.x(), l.y(), l.z());
                        // начало луча чуть ближе к сущности — не упираться в сам светящийся блок
                        Vec3 start = lp.add(mid.subtract(lp).normalize().scale(0.6));
                        if (!visible(level, e, 1 + (int) c[1], mid, start)) continue;
                        used++;
                        float strength = (float) (c[0] * camFade);
                        double lxLocal = l.x() - ex, lzLocal = l.z() - ez;
                        double horiz = Math.sqrt(lxLocal * lxLocal + lzLocal * lzLocal);
                        if (horiz < 0.3) {
                            lxLocal += 0.3;
                            horiz = 0.3;
                        }
                        // низкий источник (факел у пола) «поднимаем»: тень не длиннее ~1.5 роста сущности,
                        // иначе от факела в паре блоков она тянулась бы на десяток блоков
                        double realY = l.y() - ey;
                        double lyLocal = Math.max(realY, height + horiz / 1.5);
                        if (lyLocal > realY + 0.5) strength *= 0.75F;
                        int samples = quality == 3 ? 2 : 1;
                        int alpha = (int) (strength * (samples == 1 ? 120 : 70));
                        for (int s = 0; s < samples && alpha > 3; s++) {
                            double j = samples == 1 ? 0 : (s == 0 ? -0.18 : 0.18); // размер пламени
                            double px = lxLocal + j * (-lzLocal / horiz), pz = lzLocal + j * (lxLocal / horiz);
                            budget -= draw(pose, dispatcher, e, pt, src, wrapped, out, alpha, ex, ey, ez, cam,
                                    shadowMatrix(px, lyLocal, pz, 1, g - ey + 0.012 + layer++ * 0.002));
                        }
                    }
                }
            }
        } finally {
            rendering = false;
        }
        buffers.endBatch(ShadowType.SHADOW);
    }

    private static int draw(PoseStack pose, net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher, Entity e, float pt,
                            MultiBufferSource src, ShadowConsumer wrapped, VertexConsumer out, int alpha, double ex, double ey, double ez,
                            Vec3 cam, Matrix4f m) {
        wrapped.set(out, Math.min(255, alpha));
        pose.pushPose();
        pose.translate(ex - cam.x, ey - cam.y, ez - cam.z);
        pose.mulPoseMatrix(m);
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            net.minecraft.client.renderer.entity.EntityRenderer<Entity> r = (net.minecraft.client.renderer.entity.EntityRenderer) dispatcher.getRenderer(e);
            r.render(e, Mth.lerp(pt, e.yRotO, e.getYRot()), pt, pose, src, 0xF000F0);
        } catch (Throwable ignored) {
        }
        pose.popPose();
        return 1;
    }
}
