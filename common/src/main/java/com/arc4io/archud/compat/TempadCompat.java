package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.map.Waypoint;
import com.arc4io.archud.client.map.WaypointManager;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcPacket;
import com.arc4io.archud.network.TempadSyncPacket;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tempad: локации Tempad становятся метками на карте, компасе и в мире.
 *  - сервер с Arc HUD сам присылает список локаций (как tempadtomapx) — обновляется автоматически;
 *  - без серверной части список берётся при открытии экрана Tempad;
 *  - с карты можно открыть временную дверь к локации (сервер проверяет Tempad и топливо).
 */
public final class TempadCompat {
    private static final String SCREEN = "me.codexadrian.tempad.client.gui.ConsolidatedScreen";
    public static final int COLOR = 0xFFFF8A65;
    private static Method getName, getPos, getLevel, getId;
    private static final List<Waypoint> LOCATIONS = new ArrayList<>();
    private static boolean ready, providerRegistered, serverSynced;

    private TempadCompat() {}

    static void init() throws Exception {
        Class<?> data = Integrations.cls("me.codexadrian.tempad.common.data.LocationData");
        getName = data.getMethod("getName");
        getPos = data.getMethod("getBlockPos");
        getLevel = data.getMethod("getLevelKey");
        try {
            getId = data.getMethod("getId");
        } catch (NoSuchMethodException ignored) {
        }
        ready = true;
        registerProvider();
        ArcHud.LOGGER.info("Arc HUD: интеграция с Tempad включена");
    }

    private static void registerProvider() {
        if (providerRegistered) return;
        providerRegistered = true;
        WaypointManager.registerProvider(() -> {
            if (!ArcConfig.get().tempadIntegration) return List.of();
            synchronized (LOCATIONS) {
                return new ArrayList<>(LOCATIONS);
            }
        });
    }

    /** Список от сервера (клиентский поток). */
    public static void applySync(TempadSyncPacket packet) {
        registerProvider();
        serverSynced = true;
        List<Waypoint> out = new ArrayList<>();
        for (TempadSyncPacket.Entry e : packet.entries()) {
            Waypoint w = new Waypoint(e.name(), e.pos().getX(), e.pos().getY(), e.pos().getZ(), COLOR, e.dimension(), Waypoint.Source.TEMPAD);
            w.extId = e.id().toString();
            out.add(w);
        }
        synchronized (LOCATIONS) {
            LOCATIONS.clear();
            LOCATIONS.addAll(out);
        }
    }

    /** Выход из мира — сервер пришлёт свежий список. */
    public static void reset() {
        serverSynced = false;
        synchronized (LOCATIONS) {
            LOCATIONS.clear();
        }
    }

    public static boolean canOpenDoor(Waypoint w) {
        return w != null && w.source == Waypoint.Source.TEMPAD && w.extId != null && serverSynced;
    }

    /** Запросить у сервера временную дверь к локации. */
    public static void openDoor(Waypoint w) {
        if (w == null || w.extId == null || ArcClient.platform() == null) return;
        try {
            UUID.fromString(w.extId);
            ArcClient.platform().sendToServer(ArcPacket.TEMPAD_OPEN, ArcHud.id(w.extId));
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** Вызывается при открытии любого экрана: забираем список локаций из экрана Tempad. */
    public static void onScreenInit(Screen screen) {
        if (!ready || serverSynced || !screen.getClass().getName().equals(SCREEN)) return;
        try {
            Field f = screen.getClass().getDeclaredField("locations");
            f.setAccessible(true);
            List<?> list = (List<?>) f.get(screen);
            List<Waypoint> out = new ArrayList<>();
            for (Object loc : list) {
                BlockPos pos = (BlockPos) getPos.invoke(loc);
                ResourceKey<?> dim = (ResourceKey<?>) getLevel.invoke(loc);
                Waypoint w = new Waypoint(String.valueOf(getName.invoke(loc)), pos.getX(), pos.getY(), pos.getZ(), COLOR,
                        dim.location().toString(), Waypoint.Source.TEMPAD);
                if (getId != null) w.extId = String.valueOf(getId.invoke(loc));
                out.add(w);
            }
            synchronized (LOCATIONS) {
                LOCATIONS.clear();
                LOCATIONS.addAll(out);
            }
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: не удалось прочитать локации Tempad", t);
        }
    }
}
