package com.arc4io.archud.client.fx;

import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/**
 * Осмотр предмета в руке «как в CS2»: по клавише оружие выносится ближе к центру, поворачивается боком,
 * переворачивается, приподнимается и возвращается. Отменяется сменой слота, ударом, использованием
 * предмета или повторным нажатием. Чисто визуально, только у вас на экране.
 */
public final class WeaponInspect {
    private static final int DURATION = 64; // тиков (~3.2 с)
    private static int age = -1;
    private static int slot;
    private static ItemStack item = ItemStack.EMPTY;

    /** Ключевые кадры: t, смещение x/y/z, поворот вокруг Y, X, Z (градусы). */
    private static final float[][] KEYS = {
            {0.00F, 0F, 0F, 0F, 0F, 0F, 0F},
            {0.14F, -0.22F, 0.10F, 0.06F, -40F, 8F, 12F},
            {0.38F, -0.26F, 0.16F, 0.08F, -105F, 4F, 18F},
            {0.58F, -0.24F, 0.14F, 0.06F, -100F, 6F, 198F},
            {0.74F, -0.20F, 0.20F, 0.04F, -30F, -28F, 190F},
            {0.86F, -0.12F, 0.08F, 0.02F, -10F, -8F, 360F},
            {1.00F, 0F, 0F, 0F, 0F, 0F, 360F},
    };

    private WeaponInspect() {}

    public static boolean active() {
        return age >= 0;
    }

    public static ItemStack item() {
        return item;
    }

    public static float progress(float partialTick) {
        return age < 0 ? 0F : Math.min(1F, (age + partialTick) / DURATION);
    }

    public static void start(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !ArcConfig.get().weaponInspect) return;
        if (active()) {
            stop();
            return;
        }
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || p.isUsingItem()) return;
        item = held.copy();
        slot = p.getInventory().selected;
        age = 0;
    }

    public static void stop() {
        age = -1;
        item = ItemStack.EMPTY;
    }

    public static void tick(Minecraft mc) {
        if (age < 0) return;
        LocalPlayer p = mc.player;
        if (p == null || p.getInventory().selected != slot || p.swinging || p.isUsingItem()
                || !ItemStack.isSameItem(p.getMainHandItem(), item) || mc.screen != null && !mc.screen.isPauseScreen()) {
            stop();
            return;
        }
        if (++age >= DURATION) stop();
    }

    /** Поворот предмета в руке (вызывается перед отрисовкой предмета от первого лица). */
    public static void apply(PoseStack pose, InteractionHand hand, LocalPlayer player, float partialTick) {
        if (age < 0 || hand != InteractionHand.MAIN_HAND) return;
        float t = progress(partialTick);
        int i = 0;
        while (i < KEYS.length - 2 && t > KEYS[i + 1][0]) i++;
        float[] a = KEYS[i], b = KEYS[i + 1];
        float f = (t - a[0]) / Math.max(1e-4F, b[0] - a[0]);
        f = f * f * (3 - 2 * f); // плавно
        float side = player.getMainArm() == HumanoidArm.RIGHT ? 1F : -1F;
        float x = lerp(a[1], b[1], f) * side, y = lerp(a[2], b[2], f), z = lerp(a[3], b[3], f);
        float ry = lerp(a[4], b[4], f) * side, rx = lerp(a[5], b[5], f), rz = lerp(a[6], b[6], f) * side;
        pose.translate(x, y, z);
        pose.mulPose(Axis.YP.rotationDegrees(ry));
        pose.mulPose(Axis.XP.rotationDegrees(rx));
        pose.mulPose(Axis.ZP.rotationDegrees(rz));
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }
}
