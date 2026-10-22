package com.arc4io.archud.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * След игрока («хлебные крошки») по измерениям — рисуется на мини-карте и карте мира.
 */
public final class TrailManager {
    private static final int MAX = 800;
    private static final Map<String, Deque<int[]>> TRAILS = new HashMap<>();
    private static int tick;

    private TrailManager() {}

    public static Deque<int[]> trail(String dim) {
        return TRAILS.computeIfAbsent(dim, k -> new ArrayDeque<>());
    }

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || ++tick % 10 != 0) return;
        Deque<int[]> t = trail(mc.level.dimension().location().toString());
        int x = p.getBlockX(), z = p.getBlockZ();
        int[] last = t.peekLast();
        if (last != null) {
            int dx = x - last[0], dz = z - last[1];
            if (dx * dx + dz * dz < 9) return;
            if (dx * dx + dz * dz > 64 * 64) t.clear(); // телепорт — новый след
        }
        t.addLast(new int[]{x, z});
        while (t.size() > MAX) t.removeFirst();
    }

    public static void clear() {
        TRAILS.clear();
    }
}
