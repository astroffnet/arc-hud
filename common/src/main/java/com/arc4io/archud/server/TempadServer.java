package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.network.TempadSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Серверная часть интеграции с Tempad (через рефлексию, Tempad — необязательная зависимость):
 *  - раз в 5 секунд отправляет игроку его локации Tempad, если они изменились;
 *  - открывает временную дверь к локации по запросу с карты (с теми же проверками, что и сам Tempad:
 *    Tempad в инвентаре, топливо/заряд, разрешение на межпространственные переходы).
 */
public final class TempadServer {
    private static final int PERIOD = 100;
    private static Boolean available;
    private static Method getLocations, getLocation, getName, getPos, getLevel, getId;
    private static Method findTempad, mayTeleport, getOption, canOpen, onOpen, summon;
    private static Class<?> tempadItem;
    private static java.lang.reflect.Constructor<?> newLocation;
    private static final Map<UUID, Integer> LAST_HASH = new HashMap<>();
    private static int tick;

    private TempadServer() {}

    private static boolean available() {
        if (available != null) return available;
        try {
            ClassLoader cl = TempadServer.class.getClassLoader();
            Class<?> handler = Class.forName("me.codexadrian.tempad.common.data.TempadLocationHandler", false, cl);
            Class<?> data = Class.forName("me.codexadrian.tempad.common.data.LocationData", false, cl);
            Class<?> utils = Class.forName("me.codexadrian.tempad.common.utils.TeleportUtils", false, cl);
            tempadItem = Class.forName("me.codexadrian.tempad.common.items.TempadItem", false, cl);
            Class<?> option = Class.forName("me.codexadrian.tempad.api.options.TempadOption", false, cl);
            getLocations = handler.getMethod("getLocations", Level.class, UUID.class);
            getLocation = handler.getMethod("getLocation", Level.class, UUID.class, UUID.class);
            getName = data.getMethod("getName");
            getPos = data.getMethod("getBlockPos");
            getLevel = data.getMethod("getLevelKey");
            getId = data.getMethod("getId");
            findTempad = utils.getMethod("findTempad", net.minecraft.world.entity.player.Player.class);
            mayTeleport = utils.getMethod("mayTeleport", ResourceKey.class, net.minecraft.world.entity.player.Player.class);
            getOption = tempadItem.getMethod("getOption");
            canOpen = option.getMethod("canTimedoorOpen", net.minecraft.world.entity.player.Player.class, ItemStack.class);
            onOpen = option.getMethod("onTimedoorOpen", net.minecraft.world.entity.player.Player.class);
            summon = tempadItem.getMethod("summonTimeDoor", data, net.minecraft.world.entity.player.Player.class, int.class);
            newLocation = data.getConstructor(String.class, ResourceKey.class, BlockPos.class, UUID.class);
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        return available;
    }

    /** Вызывается каждый серверный тик (Forge / Fabric). */
    public static void tick(MinecraftServer server, BiConsumer<ServerPlayer, TempadSyncPacket> sender) {
        if (++tick % PERIOD != 0 || !available()) return;
        Level overworld = server.overworld();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                Map<?, ?> map = (Map<?, ?>) getLocations.invoke(null, overworld, player.getUUID());
                List<TempadSyncPacket.Entry> list = new ArrayList<>();
                if (map != null) {
                    for (Object loc : map.values()) {
                        ResourceKey<?> key = (ResourceKey<?>) getLevel.invoke(loc);
                        list.add(new TempadSyncPacket.Entry((UUID) getId.invoke(loc), String.valueOf(getName.invoke(loc)),
                                (BlockPos) getPos.invoke(loc), key.location().toString()));
                    }
                }
                int hash = list.hashCode();
                Integer prev = LAST_HASH.put(player.getUUID(), hash);
                if (prev == null || prev != hash) sender.accept(player, new TempadSyncPacket(list));
            } catch (Throwable t) {
                ArcHud.LOGGER.debug("Arc HUD: Tempad sync failed", t);
            }
        }
    }

    /** Игрок переподключился — отправить заново. */
    public static void forget(UUID player) {
        LAST_HASH.remove(player);
    }

    public static void init() {
        com.arc4io.archud.network.ArcNet.onServer("tempad_door", (player, buf) -> {
            String name = buf.readUtf(64);
            int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
            String dim = buf.readUtf(128);
            openDoorTo(player, name, x, y, z, dim);
        });
    }

    /** Есть ли на сервере Tempad (для клиента: можно ли открывать двери к любым меткам). */
    public static boolean isAvailable() {
        return available();
    }

    /**
     * Дверь Tempad к любой точке (метке с карты Arc HUD) в любом измерении — с теми же проверками, что у Tempad:
     * Tempad в инвентаре или браслете, заряд/топливо, разрешение на межпространственные переходы.
     */
    public static void openDoorTo(ServerPlayer player, String name, int x, int y, int z, String dim) {
        if (!available()) return;
        try {
            ResourceLocation dimId = ResourceLocation.tryParse(dim);
            if (dimId == null || player.getServer() == null) return;
            ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimId);
            if (player.getServer().getLevel(key) == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_level"), true);
                return;
            }
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return;
            if (y == Integer.MIN_VALUE) y = safeY(player.getServer().getLevel(key), x, z);
            ItemStack stack = (ItemStack) findTempad.invoke(null, player);
            if (stack == null || stack.isEmpty() || !tempadItem.isInstance(stack.getItem())) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_tempad"), true);
                return;
            }
            Object option = getOption.invoke(stack.getItem());
            if (!(boolean) canOpen.invoke(option, player, stack)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_fuel"), true);
                return;
            }
            if (!(boolean) mayTeleport.invoke(null, key, player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_dimension"), true);
                return;
            }
            String n = name == null || name.isBlank() ? "Arc HUD" : name.length() > 48 ? name.substring(0, 48) : name;
            Object loc = newLocation.newInstance(n, key, new BlockPos(x, y, z), UUID.randomUUID());
            onOpen.invoke(option, player);
            summon.invoke(null, loc, player, 0xFF8800);
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: Tempad door to waypoint failed", t);
        }
    }

    /** Безопасная высота для точки с карты (без известной Y): поверхность или, в измерениях с потолком, первая пещера. */
    private static int safeY(net.minecraft.server.level.ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        if (!level.dimensionType().hasCeiling()) {
            return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int min = level.getMinBuildHeight() + 5, max = Math.min(level.getMaxBuildHeight() - 5, level.getMinBuildHeight() + level.getLogicalHeight() - 5);
        for (int yy = min; yy < max; yy++) {
            boolean floor = level.getBlockState(m.set(x, yy - 1, z)).isSolid();
            if (floor && level.getBlockState(m.set(x, yy, z)).isAir() && level.getBlockState(m.set(x, yy + 1, z)).isAir()) return yy;
        }
        return 64;
    }

    /** Открыть временную дверь к локации (запрос с карты Arc HUD). */
    public static void openDoor(ServerPlayer player, ResourceLocation arg) {
        if (arg == null || !available()) return;
        try {
            UUID id = UUID.fromString(arg.getPath());
            ItemStack stack = (ItemStack) findTempad.invoke(null, player);
            if (stack == null || stack.isEmpty() || !tempadItem.isInstance(stack.getItem())) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_tempad"), true);
                return;
            }
            Object loc = getLocation.invoke(null, player.level(), player.getUUID(), id);
            if (loc == null) return;
            Object option = getOption.invoke(stack.getItem());
            if (!(boolean) canOpen.invoke(option, player, stack)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_fuel"), true);
                return;
            }
            if (!(boolean) mayTeleport.invoke(null, getLevel.invoke(loc), player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_dimension"), true);
                return;
            }
            onOpen.invoke(option, player);
            summon.invoke(null, loc, player, 0xFF8800);
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: Tempad door failed", t);
        }
    }
}
