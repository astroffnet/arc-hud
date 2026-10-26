package com.arc4io.archud.client.profile;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Персонаж на главном экране: модель игрока со скином по нику (скин загружается по имени),
 * голова следит за курсором, лёгкое «дыхание» и покачивание рук. Работает без загруженного мира.
 */
public final class PlayerPreview {
    private static final Map<String, GameProfile> PROFILES = new HashMap<>();
    private static PlayerModel<AbstractClientPlayer> wide, slim;

    private PlayerPreview() {}

    private static GameProfile profile(String name) {
        GameProfile cached = PROFILES.get(name);
        if (cached != null) return cached;
        GameProfile start = new GameProfile(null, name);
        PROFILES.put(name, start);
        try {
            SkullBlockEntity.updateGameprofile(start, filled -> {
                if (filled != null) PROFILES.put(name, filled);
            });
        } catch (Throwable ignored) {
        }
        return start;
    }

    /** {скин, slim?} */
    private static Object[] skin(String name) {
        Minecraft mc = Minecraft.getInstance();
        GameProfile p = profile(name);
        UUID offline = UUIDUtil.createOfflinePlayerUUID(name);
        if (p.getId() != null && !p.getProperties().isEmpty()) {
            try {
                Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> info = mc.getSkinManager().getInsecureSkinInformation(p);
                MinecraftProfileTexture t = info.get(MinecraftProfileTexture.Type.SKIN);
                if (t != null) {
                    ResourceLocation loc = mc.getSkinManager().registerTexture(t, MinecraftProfileTexture.Type.SKIN);
                    return new Object[]{loc, "slim".equals(t.getMetadata("model"))};
                }
            } catch (Throwable ignored) {
            }
        }
        UUID id = p.getId() != null ? p.getId() : offline;
        return new Object[]{DefaultPlayerSkin.getDefaultSkin(id), "slim".equals(DefaultPlayerSkin.getSkinModelName(id))};
    }

    private static PlayerModel<AbstractClientPlayer> model(boolean isSlim) {
        Minecraft mc = Minecraft.getInstance();
        if (isSlim) {
            if (slim == null) slim = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            return slim;
        }
        if (wide == null) wide = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        return wide;
    }

    /** Нарисовать персонажа: (x, feetY) — точка между ступнями, size — масштаб (~ высота / 1.5). */
    public static void render(GuiGraphics g, String name, int x, int feetY, float size, int mouseX, int mouseY) {
        Object[] s = skin(name);
        ResourceLocation tex = (ResourceLocation) s[0];
        PlayerModel<AbstractClientPlayer> m = model((Boolean) s[1]);
        float t = (Util.getMillis() % 1_000_000L) / 1000F;
        float headY = feetY - size * 1.6F;
        float yaw = Mth.clamp((mouseX - x) / 120F, -1F, 1F);
        float pitch = Mth.clamp((mouseY - headY) / 120F, -1F, 1F);
        m.young = false;
        m.crouching = false;
        m.riding = false;
        m.attackTime = 0;
        resetPart(m);
        m.head.yRot = yaw * 0.9F;
        m.head.xRot = pitch * 0.6F;
        m.body.yRot = yaw * 0.25F;
        float breathe = Mth.sin(t * 1.4F);
        m.rightArm.zRot = 0.08F + breathe * 0.03F;
        m.leftArm.zRot = -0.08F - breathe * 0.03F;
        m.rightArm.xRot = Mth.sin(t * 0.9F) * 0.05F;
        m.leftArm.xRot = -Mth.sin(t * 0.9F) * 0.05F;
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);

        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, feetY + breathe * 0.6F, 150);
        pose.scale(size, size, -size);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw * 25F));
        pose.translate(0, -1.5F, 0);
        Lighting.setupForEntityInInventory();
        VertexConsumer vc = g.bufferSource().getBuffer(m.renderType(tex));
        m.renderToBuffer(pose, vc, 0xF000F0, OverlayTexture.NO_OVERLAY, 1F, 1F, 1F, 1F);
        g.flush();
        Lighting.setupFor3DItems();
        pose.popPose();
    }

    private static void resetPart(PlayerModel<?> m) {
        m.head.resetPose();
        m.body.resetPose();
        m.rightArm.resetPose();
        m.leftArm.resetPose();
        m.rightLeg.resetPose();
        m.leftLeg.resetPose();
    }
}
