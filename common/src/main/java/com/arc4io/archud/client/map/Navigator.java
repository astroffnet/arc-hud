package com.arc4io.archud.client.map;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Навигатор: ведёт к выбранной метке. Стрелка и расстояние в HUD, подсвеченный луч в мире,
 * время в пути по текущей скорости, перевод координат Верхний мир ↔ Незер, звук и сообщение по прибытии.
 */
public final class Navigator {
    private static Waypoint target;
    private static long startedAt;
    private static double startDistance;

    private Navigator() {}

    public static Waypoint target() {
        return target;
    }

    public static boolean isTarget(Waypoint w) {
        return target != null && w != null && target == w;
    }

    public static void set(Waypoint w) {
        target = w;
        startedAt = System.currentTimeMillis();
        LocalPlayer p = Minecraft.getInstance().player;
        startDistance = p == null || w == null ? 0 : w.horizontalDistance(p.getX(), p.getZ());
        if (p != null && w != null) {
            p.displayClientMessage(Component.translatable("archud.nav.started", w.name).withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** Временная цель по координатам (с карты мира / контекстного меню). */
    public static void navigateTo(int x, int y, int z, String dimension) {
        Waypoint w = new Waypoint(Component.translatable("archud.nav.point").getString(), x, y, z, 0xFF26C6DA, dimension, Waypoint.Source.TEMP);
        set(w);
    }

    /** Навигация к игроку: метка движется за ним (по сущности рядом или по позициям с сервера Arc HUD). */
    public static void navigateToPlayer(java.util.UUID id, String name, double x, double y, double z, String dimension) {
        Waypoint w = new Waypoint(name, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), 0xFF9CFF5A, dimension, Waypoint.Source.TEMP);
        w.followPlayer = id;
        set(w);
    }

    /** Обновить координаты метки-игрока. */
    private static void follow(Minecraft mc) {
        if (target == null || target.followPlayer == null || mc.level == null) return;
        var p = mc.level.getPlayerByUUID(target.followPlayer);
        if (p != null) {
            target.x = (int) Math.floor(p.getX());
            target.y = (int) Math.floor(p.getY());
            target.z = (int) Math.floor(p.getZ());
            target.dimension = mc.level.dimension().location().toString();
            return;
        }
        for (var rp : com.arc4io.archud.client.ArcClientNet.players()) {
            if (!rp.id().equals(target.followPlayer)) continue;
            target.x = (int) Math.floor(rp.x());
            target.y = (int) Math.floor(rp.y());
            target.z = (int) Math.floor(rp.z());
            target.dimension = rp.dimension();
            return;
        }
    }

    public static void clear() {
        target = null;
    }

    public static double progress(LocalPlayer p) {
        if (target == null || startDistance <= 1) return 0;
        return Math.max(0, Math.min(1, 1 - target.horizontalDistance(p.getX(), p.getZ()) / startDistance));
    }

    /** Цель в другом измерении: координаты в текущем (Незер = /8). Возвращает {x, z} или null. */
    public static double[] convertedTarget(String currentDim) {
        if (target == null) return null;
        if (target.dimension.equals(currentDim)) return new double[]{target.x + 0.5, target.z + 0.5};
        if (!ArcConfig.get().netherCoordinates) return null;
        boolean fromOver = target.dimension.equals("minecraft:overworld"), toNether = currentDim.equals("minecraft:the_nether");
        boolean fromNether = target.dimension.equals("minecraft:the_nether"), toOver = currentDim.equals("minecraft:overworld");
        if (fromOver && toNether) return new double[]{target.x / 8.0, target.z / 8.0};
        if (fromNether && toOver) return new double[]{target.x * 8.0, target.z * 8.0};
        return null;
    }

    public static void tick(Minecraft mc) {
        if (target == null || mc.player == null || mc.level == null) return;
        follow(mc);
        String dim = mc.level.dimension().location().toString();
        if (!target.dimension.equals(dim)) return;
        if (target.distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ()) < 4.5) {
            mc.player.displayClientMessage(Component.translatable("archud.nav.arrived", target.name).withStyle(ChatFormatting.GREEN), true);
            mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.6F, 1.6F);
            if (target.source == Waypoint.Source.DEATH && ArcConfig.get().navigateToDeath) {
                WaypointManager.remove(target);
            }
            target = null;
        }
    }

    public static long startedAt() {
        return startedAt;
    }
}
