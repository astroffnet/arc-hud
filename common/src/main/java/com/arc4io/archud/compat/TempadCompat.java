package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.map.Waypoint;
import com.arc4io.archud.client.map.WaypointManager;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Tempad: локации, сохранённые в Tempad, становятся метками на картах и компасе
 * (обновляются каждый раз, когда открываешь Tempad). Заряд Tempad — фишка у хотбара (см. HudRenderer).
 */
public final class TempadCompat {
    private static final String SCREEN = "me.codexadrian.tempad.client.gui.ConsolidatedScreen";
    private static Method getName, getPos, getLevel;
    private static final List<Waypoint> LOCATIONS = new ArrayList<>();
    private static boolean ready;

    private TempadCompat() {}

    static void init() throws Exception {
        Class<?> data = Integrations.cls("me.codexadrian.tempad.common.data.LocationData");
        getName = data.getMethod("getName");
        getPos = data.getMethod("getBlockPos");
        getLevel = data.getMethod("getLevelKey");
        ready = true;
        WaypointManager.registerProvider(() -> ArcConfig.get().tempadIntegration ? new ArrayList<>(LOCATIONS) : List.of());
        ArcHud.LOGGER.info("Arc HUD: интеграция с Tempad включена");
    }

    /** Вызывается при открытии любого экрана: забираем список локаций из экрана Tempad. */
    public static void onScreenInit(Screen screen) {
        if (!ready || !screen.getClass().getName().equals(SCREEN)) return;
        try {
            Field f = screen.getClass().getDeclaredField("locations");
            f.setAccessible(true);
            List<?> list = (List<?>) f.get(screen);
            List<Waypoint> out = new ArrayList<>();
            for (Object loc : list) {
                BlockPos pos = (BlockPos) getPos.invoke(loc);
                ResourceKey<?> dim = (ResourceKey<?>) getLevel.invoke(loc);
                out.add(new Waypoint(String.valueOf(getName.invoke(loc)), pos.getX(), pos.getY(), pos.getZ(), 0xFFFF8A65,
                        dim.location().toString(), Waypoint.Source.TEMPAD));
            }
            LOCATIONS.clear();
            LOCATIONS.addAll(out);
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: не удалось прочитать локации Tempad", t);
        }
    }
}
