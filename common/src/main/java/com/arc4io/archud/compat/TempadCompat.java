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
        com.arc4io.archud.client.hud.ArcHudApi.registerPanel(ArcHud.id("tempad"), net.minecraft.network.chat.Component.literal("Tempad"),
                0xFFFF8A65, TempadCompat::panel);
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

    // ------------------------------------------------------------------ сам Tempad (инвентарь или браслет Curios/Trinkets)
    public record Found(net.minecraft.world.item.ItemStack stack, boolean accessory) {}

    public static boolean isTempad(net.minecraft.world.item.ItemStack s) {
        if (s.isEmpty()) return false;
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        return id.getNamespace().equals("tempad") && id.getPath().contains("tempad");
    }

    /** Ищет Tempad: руки → инвентарь → слоты-аксессуары (браслет). */
    public static Found find(net.minecraft.world.entity.player.Player p) {
        if (isTempad(p.getMainHandItem())) return new Found(p.getMainHandItem(), false);
        if (isTempad(p.getOffhandItem())) return new Found(p.getOffhandItem(), false);
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isTempad(inv.getItem(i))) return new Found(inv.getItem(i), false);
        }
        try {
            net.minecraft.world.item.ItemStack acc = ArcHud.platform().findAccessory(p, TempadCompat::isTempad);
            if (!acc.isEmpty()) return new Found(acc, true);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Заряд в процентах (−1, если у варианта Tempad нет шкалы). */
    public static int charge(net.minecraft.world.item.ItemStack s) {
        return s.isBarVisible() ? Math.round(s.getBarWidth() * 100F / 13F) : -1;
    }

    private static List<com.arc4io.archud.client.hud.ArcHudApi.PanelRow> panel() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        ArcConfig cfg = ArcConfig.get();
        if (mc.player == null || !cfg.tempadIntegration || !cfg.tempadPanel) return null;
        Found f = find(mc.player);
        if (f == null) return null;
        List<com.arc4io.archud.client.hud.ArcHudApi.PanelRow> rows = new ArrayList<>();
        rows.add(new com.arc4io.archud.client.hud.ArcHudApi.PanelRow(
                net.minecraft.network.chat.Component.translatable("archud.tempad.where").getString(),
                net.minecraft.network.chat.Component.translatable(f.accessory() ? "archud.tempad.bracelet" : "archud.tempad.inventory").getString(),
                -1F, 0xFFFFB74D, false));
        boolean cd = mc.player.getCooldowns().isOnCooldown(f.stack().getItem());
        int ch = charge(f.stack());
        String state = cd ? net.minecraft.network.chat.Component.translatable("archud.tempad.cooldown").getString()
                : ch >= 0 ? ch + "%" : net.minecraft.network.chat.Component.translatable("archud.tempad.ready").getString();
        float prog = cd ? 1F - mc.player.getCooldowns().getCooldownPercent(f.stack().getItem(), 0F) : ch >= 0 ? ch / 100F : -1F;
        rows.add(new com.arc4io.archud.client.hud.ArcHudApi.PanelRow(
                net.minecraft.network.chat.Component.translatable("archud.tempad.charge").getString(), state, prog,
                cd ? 0xFFFF8A65 : 0xFF9CFF5A, cd));
        List<Waypoint> locs;
        synchronized (LOCATIONS) {
            locs = new ArrayList<>(LOCATIONS);
        }
        rows.add(new com.arc4io.archud.client.hud.ArcHudApi.PanelRow(
                net.minecraft.network.chat.Component.translatable("archud.tempad.locations").getString(), Integer.toString(locs.size()), -1F,
                0xFFFFFFFF, false));
        String dim = mc.level == null ? "" : mc.level.dimension().location().toString();
        Waypoint near = null;
        double best = Double.MAX_VALUE;
        for (Waypoint w : locs) {
            if (!w.dimension.equals(dim)) continue;
            double d = w.distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (d < best) {
                best = d;
                near = w;
            }
        }
        if (near != null) {
            rows.add(new com.arc4io.archud.client.hud.ArcHudApi.PanelRow("◆ " + near.name, Math.round(best) + " m", -1F, COLOR, false));
        }
        return rows;
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
