package com.arc4io.archud.client.bhop;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * BunnyHop в стиле Source-движка (CS / HL2 / TF2):
 *  - air strafing: в воздухе ускорение считается как в Quake/Source (проекция скорости на направление «желания»),
 *    поэтому стрейф (A/D) + поворот мышью в ту же сторону разгоняет игрока;
 *  - при приземлении с зажатым прыжком скорость не теряется на трение (кадр трения пропускается);
 *  - ограничение максимальной скорости (bhopMaxSpeed, блоков за тик), чтобы не ловить «moved too quickly».
 * Работает на клиенте (движение игрока в Minecraft считается клиентом), отключается клавишей (N).
 */
public final class BunnyHop {
    private static boolean wasOnGround = true;
    private static double lastAirSpeed;
    private static int chain;
    private static int groundTicks;

    private BunnyHop() {}

    public static int chain() {
        return chain;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ArcConfig cfg = ArcConfig.get();
        if (p == null) return;

        boolean allowed = cfg.bunnyHop && (!cfg.bhopSingleplayerOnly || mc.isLocalServer());
        if (!allowed || p.getAbilities().flying || p.isInWater() || p.isInLava() || p.onClimbable()
                || p.isPassenger() || p.isFallFlying() || p.isSpectator() || p.input == null) {
            chain = 0;
            wasOnGround = p.onGround();
            return;
        }

        boolean onGround = p.onGround();
        Vec3 v = p.getDeltaMovement();
        double hs = Math.hypot(v.x, v.z);

        if (!onGround) {
            // ---- Source air-strafe ----
            float fwd = p.input.forwardImpulse;
            float strafe = p.input.leftImpulse;
            if (fwd != 0 || strafe != 0) {
                double yaw = Math.toRadians(p.getYRot());
                double sin = Math.sin(yaw), cos = Math.cos(yaw);
                double wx = strafe * cos - fwd * sin;
                double wz = fwd * cos + strafe * sin;
                double len = Math.hypot(wx, wz);
                if (len > 1.0E-4) {
                    wx /= len;
                    wz /= len;
                    double current = v.x * wx + v.z * wz;
                    double add = cfg.bhopAirWishSpeed - current;
                    if (add > 0) {
                        double accel = Math.min(add, cfg.bhopAirAccel);
                        v = v.add(wx * accel, 0, wz * accel);
                    }
                }
            }
            // только что оттолкнулись от земли с зажатым прыжком — возвращаем скорость до трения
            if (wasOnGround && p.input.jumping && groundTicks <= 1 && lastAirSpeed > 0) {
                double cur = Math.hypot(v.x, v.z);
                double keep = lastAirSpeed * cfg.bhopLandingKeep;
                if (keep > cur && cur > 1.0E-4) {
                    double f = keep / cur;
                    v = new Vec3(v.x * f, v.y, v.z * f);
                }
                chain++;
            }
            hs = Math.hypot(v.x, v.z);
            if (hs > cfg.bhopMaxSpeed) {
                double f = cfg.bhopMaxSpeed / hs;
                v = new Vec3(v.x * f, v.y, v.z * f);
                hs = cfg.bhopMaxSpeed;
            }
            p.setDeltaMovement(v);
            lastAirSpeed = hs;
            groundTicks = 0;
        } else {
            groundTicks++;
            if (groundTicks > 2) {
                chain = 0;
                lastAirSpeed = 0;
            }
        }
        wasOnGround = onGround;
    }
}
