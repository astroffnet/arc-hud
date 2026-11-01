package com.arc4io.archud.client.fx;

import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

/**
 * Тени игроков во встроенном шейдере Arc: модель игрока (с бронёй и предметами в руках) ещё раз проецируется на землю
 * вдоль направления солнца (ночью — луны) и рисуется полупрозрачным чёрным. Перекрывающиеся части не темнеют вдвое
 * (тест глубины «строго меньше» по плоскости тени). Длина и плотность тени зависят от высоты солнца и освещённости неба.
 */
public final class PlayerShadows {
    /** Сейчас рисуется тень — табличка с ником и т. п. не нужны. */
    public static boolean rendering;
    private static final ResourceLocation WHITE = new ResourceLocation("archud", "textures/misc/white.png");

    private PlayerShadows() {}

    /** Свой тип отрисовки: полупрозрачный, без двойного затемнения, со сдвигом от плоскости земли. */
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
            SHADOW = new ShadowType("archud_player_shadow", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false,
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
        return c.shaderEnabled && c.shaderPlayerShadows && !ShaderPacks.inUse();
    }

    /** Вершины любого типа отрисовки — в тень: чёрный цвет нужной прозрачности, полный формат сущности. */
    private static final class ShadowConsumer implements VertexConsumer {
        private final VertexConsumer out;
        private final int alpha;
        private double x, y, z;
        private float u, v;
        private boolean started;

        ShadowConsumer(VertexConsumer out, int alpha) {
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

    /** Высота поверхности под игроком (до 6 блоков вниз) или NaN. */
    private static double ground(Player p) {
        var level = p.level();
        BlockPos.MutableBlockPos pos = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ()).mutable();
        for (int i = 0; i < 7; i++) {
            var state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                if (top <= p.getY() + 0.25) return top;
            }
            if (!state.getFluidState().isEmpty()) return Double.NaN; // в воде тень не нужна
            pos.move(0, -1, 0);
        }
        return Double.NaN;
    }

    /** Нарисовать тени всех видимых игроков рядом (после сущностей, в том же кадре). */
    public static void render(PoseStack pose, Camera camera, float pt) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var level = mc.level;
        // направление света: солнце днём, луна ночью, в мирах без неба — сверху
        float sun = level.getSunAngle(pt);
        double sx = -Math.sin(sun), sy = Math.cos(sun);
        boolean night = sy < 0;
        if (night) {
            sx = -sx;
            sy = -sy;
        }
        if (!level.dimensionType().hasSkyLight()) {
            sx = 0;
            sy = 1;
        }
        sy = Math.max(sy, 0.35); // у самого горизонта тень была бы бесконечной
        double len = Math.sqrt(sx * sx + sy * sy);
        double lx = -sx / len, ly = -sy / len, lz = 0.12 * lx; // небольшой наклон, чтобы тень не лежала строго по оси
        float rain = level.getRainLevel(pt);
        int baseAlpha = (int) ((night ? 70 : 110) * (1F - rain * 0.6F));
        if (baseAlpha < 8) return;

        var dispatcher = mc.getEntityRenderDispatcher();
        var buffers = mc.renderBuffers().bufferSource();
        var cam = camera.getPosition();
        rendering = true;
        try {
            for (Player p : level.players()) {
                if (p.isInvisible() || p.isSpectator() || p.distanceToSqr(cam) > 48 * 48) continue;
                double g = ground(p);
                if (Double.isNaN(g)) continue;
                double px = Mth.lerp(pt, p.xo, p.getX()), py = Mth.lerp(pt, p.yo, p.getY()), pz = Mth.lerp(pt, p.zo, p.getZ());
                if (py - g > 4) continue;
                int sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(px, py + 1, pz));
                int alpha = (int) (baseAlpha * Mth.clamp(sky / 15F, 0.25F, 1F));
                VertexConsumer shadow = buffers.getBuffer(ShadowType.SHADOW);
                VertexConsumer wrapped = new ShadowConsumer(shadow, alpha);
                MultiBufferSource src = type -> type.mode() == VertexFormat.Mode.QUADS ? wrapped : NONE;
                double h = g - py + 0.015; // плоскость тени в координатах игрока
                pose.pushPose();
                pose.translate(px - cam.x, py - cam.y, pz - cam.z);
                Matrix4f m = new Matrix4f();
                // проекция на плоскость y = h вдоль направления света
                m.m10((float) (-lx / ly));
                m.m11(0F);
                m.m12((float) (-lz / ly));
                m.m30((float) (lx * h / ly));
                m.m31((float) h);
                m.m32((float) (lz * h / ly));
                pose.mulPoseMatrix(m);
                try {
                    var renderer = dispatcher.getRenderer(p);
                    renderer.render(p, Mth.lerp(pt, p.yRotO, p.getYRot()), pt, pose, src, 0xF000F0);
                } catch (Throwable ignored) {
                }
                pose.popPose();
            }
        } finally {
            rendering = false;
        }
        buffers.endBatch(ShadowType.SHADOW);
    }
}
