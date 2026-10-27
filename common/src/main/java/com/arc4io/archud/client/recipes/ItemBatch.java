package com.arc4io.archud.client.recipes;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Быстрая отрисовка множества предметов (список предметов, рецепты). Обычный renderItem на каждый предмет
 * сбрасывает буфер — сотни вызовов отрисовки за кадр. Здесь всё собирается в один буфер и рисуется двумя
 * проходами: плоские предметы и объёмные (блоки), — как в JEI/EMI.
 */
public final class ItemBatch {
    private final List<ItemStack> stacks = new ArrayList<>();
    private final List<int[]> pos = new ArrayList<>();

    public void add(ItemStack s, int x, int y) {
        if (s == null || s.isEmpty()) return;
        stacks.add(s);
        pos.add(new int[]{x, y});
    }

    public void clear() {
        stacks.clear();
        pos.clear();
    }

    public void draw(GuiGraphics g, float z) {
        if (stacks.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer ir = mc.getItemRenderer();
        MultiBufferSource.BufferSource buf = g.bufferSource();
        BakedModel[] models = new BakedModel[stacks.size()];
        for (int i = 0; i < stacks.size(); i++) {
            try {
                models[i] = ir.getModel(stacks.get(i), mc.level, mc.player, 0);
            } catch (Throwable t) {
                models[i] = null;
            }
        }
        PoseStack pose = g.pose();
        g.flush();
        for (int pass = 0; pass < 2; pass++) {
            boolean blockLight = pass == 1;
            if (blockLight) Lighting.setupFor3DItems();
            else Lighting.setupForFlatItems();
            boolean any = false;
            for (int i = 0; i < stacks.size(); i++) {
                BakedModel m = models[i];
                if (m == null || m.usesBlockLight() != blockLight) continue;
                int[] p = pos.get(i);
                pose.pushPose();
                pose.translate(p[0] + 8, p[1] + 8, z);
                pose.mulPoseMatrix(new Matrix4f().scaling(1F, -1F, 1F));
                pose.scale(16F, 16F, 16F);
                try {
                    ir.render(stacks.get(i), ItemDisplayContext.GUI, false, pose, buf, 0xF000F0, OverlayTexture.NO_OVERLAY, m);
                } catch (Throwable ignored) {
                }
                pose.popPose();
                any = true;
            }
            if (any) buf.endBatch();
        }
        Lighting.setupFor3DItems();
    }
}
