package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.map.Waypoint;
import com.arc4io.archud.client.map.WaypointManager;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Waystones: все известные игроку вэйстоуны показываются метками на мини-карте, карте мира и компасе.
 */
public final class WaystonesCompat {
    private static Method getData, getWaystones, getName, getPos, getDim;
    private static Object clientEnv;
    private static final java.util.Set<String> KNOWN = new java.util.HashSet<>();
    private static boolean primed;

    /** Выход из мира. */
    public static void reset() {
        KNOWN.clear();
        primed = false;
    }

    private WaystonesCompat() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void init() throws Exception {
        Class<?> manager = Integrations.cls("net.blay09.mods.waystones.core.PlayerWaystoneManager");
        Class<?> env = Integrations.cls("net.blay09.mods.balm.api.BalmEnvironment");
        clientEnv = Enum.valueOf((Class) env, "CLIENT");
        getData = manager.getMethod("getPlayerWaystoneData", env);
        getWaystones = Integrations.cls("net.blay09.mods.waystones.core.IPlayerWaystoneData").getMethod("getWaystones", Player.class);
        Class<?> iw = Integrations.cls("net.blay09.mods.waystones.api.IWaystone");
        getName = iw.getMethod("getName");
        getPos = iw.getMethod("getPos");
        getDim = iw.getMethod("getDimension");
        WaypointManager.registerProvider(WaystonesCompat::waypoints);
        ArcHud.LOGGER.info("Arc HUD: интеграция с Waystones включена");
    }

    /** Тост «Открыт новый вэйстоун» (как xaeros_waystones_compatibility создаёт метку при активации). */
    private static void notifyNew(Minecraft mc, List<Waypoint> list) {
        boolean show = primed && ArcConfig.get().waystoneToasts;
        for (Waypoint w : list) {
            if (KNOWN.add(w.dimension + "|" + w.x + "|" + w.y + "|" + w.z) && show) {
                net.minecraft.client.gui.components.toasts.SystemToast.add(mc.getToasts(),
                        net.minecraft.client.gui.components.toasts.SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                        net.minecraft.network.chat.Component.translatable("archud.waystone.new"),
                        net.minecraft.network.chat.Component.literal(w.name + "  (" + w.x + ", " + w.y + ", " + w.z + ")"));
            }
        }
        if (!list.isEmpty() || mc.player.tickCount > 200) primed = true;
    }

    private static List<Waypoint> waypoints() {
        Minecraft mc = Minecraft.getInstance();
        if (!ArcConfig.get().waystonesIntegration || mc.player == null) return List.of();
        try {
            Object data = getData.invoke(null, clientEnv);
            List<?> list = (List<?>) getWaystones.invoke(data, mc.player);
            List<Waypoint> out = new ArrayList<>();
            for (Object w : list) {
                String name = String.valueOf(getName.invoke(w));
                BlockPos pos = (BlockPos) getPos.invoke(w);
                ResourceKey<?> dim = (ResourceKey<?>) getDim.invoke(w);
                out.add(new Waypoint(name.isBlank() ? "Waystone" : name, pos.getX(), pos.getY(), pos.getZ(), 0xFFB388FF,
                        dim.location().toString(), Waypoint.Source.WAYSTONE));
            }
            notifyNew(mc, out);
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }
}
