package com.arc4io.archud.client.map;

import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Метки в мире: столб света (как у маяка) и подпись с именем и расстоянием, видимая сквозь стены.
 * Дальние метки рисуются на фиксированном расстоянии в их направлении, чтобы подпись была видна всегда.
 */
public final class WaypointWorldRenderer {
    private static final float LABEL_MAX_DIST = 96F;

    private WaypointWorldRenderer() {}

    public static void render(PoseStack pose, Camera camera, float partialTick) {
        ArcConfig cfg = ArcConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.worldWaypoints || mc.level == null || mc.player == null || mc.options.hideGui) return;
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        long gameTime = mc.level.getGameTime();

        for (Waypoint w : WaypointManager.inDimension()) {
            double wx = w.x + 0.5, wy = w.y, wz = w.z + 0.5;
            double dx = wx - cam.x, dy = wy - cam.y, dz = wz - cam.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (cfg.waypointMaxDistance > 0 && dist > cfg.waypointMaxDistance && !Navigator.isTarget(w)) continue;
            boolean target = Navigator.isTarget(w);
            float[] rgb = {((w.color >> 16) & 255) / 255F, ((w.color >> 8) & 255) / 255F, (w.color & 255) / 255F};

            // луч
            double hd = Math.sqrt(dx * dx + dz * dz);
            if (cfg.waypointBeams && hd < mc.options.getEffectiveRenderDistance() * 16 && hd > 2) {
                pose.pushPose();
                pose.translate(dx - 0.5, -cam.y + mc.level.getMinBuildHeight(), dz - 0.5);
                int height = mc.level.getHeight();
                BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1F, gameTime, 0, height,
                        rgb, target ? 0.25F : 0.15F, target ? 0.3F : 0.2F);
                pose.popPose();
            }

            // подпись
            if (!cfg.waypointLabels) continue;
            double lx = dx, ly = dy + 1.5, lz = dz;
            double ld = Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (ld > LABEL_MAX_DIST) {
                double k = LABEL_MAX_DIST / ld;
                lx *= k;
                ly *= k;
                lz *= k;
                ld = LABEL_MAX_DIST;
            }
            float scale = (float) (0.025F * Math.max(1.0, ld / 8.0));
            pose.pushPose();
            pose.translate(lx, ly, lz);
            pose.mulPose(camera.rotation());
            pose.scale(-scale, -scale, scale);
            Matrix4f m = pose.last().pose();
            String title = "◆ " + w.name;
            String sub = Math.round(dist) + " m";
            int bg = target ? 0x9026C6DA : 0x70000000;
            int color = 0xFF000000 | w.color;
            font.drawInBatch(title, -font.width(title) / 2F, -10, color, false, m, buffers, Font.DisplayMode.SEE_THROUGH, bg, LightTexture.FULL_BRIGHT);
            font.drawInBatch(sub, -font.width(sub) / 2F, 0, 0xFFFFFFFF, false, m, buffers, Font.DisplayMode.SEE_THROUGH, 0x50000000, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        buffers.endBatch();
    }
}
