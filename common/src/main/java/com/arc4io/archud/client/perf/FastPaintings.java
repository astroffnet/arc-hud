package com.arc4io.archud.client.perf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Быстрые картины (как fast-paintings, но с тем же видом, что у ванилы). Ванила рисует картину кусками 1×1 блок,
 * и у каждого куска 6 граней — лицо, тыльная сторона и 4 торца, из которых внутренние торцы вообще не видны.
 * Картина 4×4 = 96 четырёхугольников. Здесь:
 * <ul>
 *   <li>лицевые куски с одинаковым освещением сливаются в полосы (при ровном свете вся картина — 1 четырёхугольник);</li>
 *   <li>торцы — только по внешнему контуру;</li>
 *   <li>тыльная сторона — только если камера сзади (например, картина висит на стекле).</li>
 * </ul>
 * Картина 4×4 при ровном свете: 96 → 17 четырёхугольников; освещение и текстуры совпадают с ванилой.
 */
public final class FastPaintings {
    private FastPaintings() {}

    public static boolean enabled() {
        return Perf.on("x_fast_paintings");
    }

    public static void render(PoseStack pose, VertexConsumer vc, Painting e, int width, int height, TextureAtlasSprite front, TextureAtlasSprite back) {
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        Matrix3f n = last.normal();
        float f = -width / 2F, f1 = -height / 2F;
        float bu0 = back.getU0(), bu1 = back.getU1(), bv0 = back.getV0(), bv1 = back.getV1();
        float ev1 = back.getV(1.0D), eu1 = back.getU(1.0D);
        int i = width / 16, j = height / 16;
        if (i <= 0 || j <= 0) return;
        double d0 = 16.0D / i, d1 = 16.0D / j;
        int[] light = new int[i * j];
        Direction dir = e.getDirection();
        for (int k = 0; k < i; k++) {
            for (int l = 0; l < j; l++) {
                float xa = f + (k + 1) * 16, xb = f + k * 16, ya = f1 + (l + 1) * 16, yb = f1 + l * 16;
                int bx = e.getBlockX(), by = Mth.floor(e.getY() + (ya + yb) / 2F / 16F), bz = e.getBlockZ();
                double off = (xa + xb) / 2F / 16F;
                if (dir == Direction.NORTH) bx = Mth.floor(e.getX() + off);
                if (dir == Direction.WEST) bz = Mth.floor(e.getZ() - off);
                if (dir == Direction.SOUTH) bx = Mth.floor(e.getX() - off);
                if (dir == Direction.EAST) bz = Mth.floor(e.getZ() + off);
                light[k * j + l] = LevelRenderer.getLightColor(e.level(), new BlockPos(bx, by, bz));
            }
        }
        // лицо: в каждой строке сливаем соседние куски с одинаковым светом
        for (int l = 0; l < j; l++) {
            int k0 = 0;
            while (k0 < i) {
                int lc = light[k0 * j + l];
                int k1 = k0;
                while (k1 + 1 < i && light[(k1 + 1) * j + l] == lc) k1++;
                float xMax = f + (k1 + 1) * 16, xMin = f + k0 * 16, yMax = f1 + (l + 1) * 16, yMin = f1 + l * 16;
                float uMax = front.getU(d0 * (i - (k1 + 1))), uMin = front.getU(d0 * (i - k0));
                float vMin = front.getV(d1 * (j - l)), vMax = front.getV(d1 * (j - (l + 1)));
                v(m, n, vc, xMax, yMin, uMax, vMin, -0.5F, 0, 0, -1, lc);
                v(m, n, vc, xMin, yMin, uMin, vMin, -0.5F, 0, 0, -1, lc);
                v(m, n, vc, xMin, yMax, uMin, vMax, -0.5F, 0, 0, -1, lc);
                v(m, n, vc, xMax, yMax, uMax, vMax, -0.5F, 0, 0, -1, lc);
                k0 = k1 + 1;
            }
        }
        boolean showBack = cameraBehind(e);
        for (int k = 0; k < i; k++) {
            for (int l = 0; l < j; l++) {
                int lc = light[k * j + l];
                float xa = f + (k + 1) * 16, xb = f + k * 16, ya = f1 + (l + 1) * 16, yb = f1 + l * 16;
                if (showBack) {
                    v(m, n, vc, xa, ya, bu1, bv0, 0.5F, 0, 0, 1, lc);
                    v(m, n, vc, xb, ya, bu0, bv0, 0.5F, 0, 0, 1, lc);
                    v(m, n, vc, xb, yb, bu0, bv1, 0.5F, 0, 0, 1, lc);
                    v(m, n, vc, xa, yb, bu1, bv1, 0.5F, 0, 0, 1, lc);
                }
                if (l == j - 1) { // верхний торец
                    v(m, n, vc, xa, ya, bu0, bv0, -0.5F, 0, 1, 0, lc);
                    v(m, n, vc, xb, ya, bu1, bv0, -0.5F, 0, 1, 0, lc);
                    v(m, n, vc, xb, ya, bu1, ev1, 0.5F, 0, 1, 0, lc);
                    v(m, n, vc, xa, ya, bu0, ev1, 0.5F, 0, 1, 0, lc);
                }
                if (l == 0) { // нижний торец
                    v(m, n, vc, xa, yb, bu0, bv0, 0.5F, 0, -1, 0, lc);
                    v(m, n, vc, xb, yb, bu1, bv0, 0.5F, 0, -1, 0, lc);
                    v(m, n, vc, xb, yb, bu1, ev1, -0.5F, 0, -1, 0, lc);
                    v(m, n, vc, xa, yb, bu0, ev1, -0.5F, 0, -1, 0, lc);
                }
                if (k == i - 1) { // боковой торец (x = xa)
                    v(m, n, vc, xa, ya, eu1, bv0, 0.5F, -1, 0, 0, lc);
                    v(m, n, vc, xa, yb, eu1, bv1, 0.5F, -1, 0, 0, lc);
                    v(m, n, vc, xa, yb, bu0, bv1, -0.5F, -1, 0, 0, lc);
                    v(m, n, vc, xa, ya, bu0, bv0, -0.5F, -1, 0, 0, lc);
                }
                if (k == 0) { // боковой торец (x = xb)
                    v(m, n, vc, xb, ya, eu1, bv0, -0.5F, 1, 0, 0, lc);
                    v(m, n, vc, xb, yb, eu1, bv1, -0.5F, 1, 0, 0, lc);
                    v(m, n, vc, xb, yb, bu0, bv1, 0.5F, 1, 0, 0, lc);
                    v(m, n, vc, xb, ya, bu0, bv0, 0.5F, 1, 0, 0, lc);
                }
            }
        }
    }

    /** Камера за картиной (со стороны стены)? */
    private static boolean cameraBehind(Painting e) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Direction d = e.getDirection();
        double dot = (cam.x - e.getX()) * d.getStepX() + (cam.z - e.getZ()) * d.getStepZ();
        return dot < 0.05;
    }

    private static void v(Matrix4f m, Matrix3f n, VertexConsumer vc, float x, float y, float u, float vv, float z, int nx, int ny, int nz, int light) {
        vc.vertex(m, x, y, z).color(255, 255, 255, 255).uv(u, vv).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, nx, ny, nz).endVertex();
    }
}
