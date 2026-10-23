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
    private static Method isDeletable;
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
            try {
                isDeletable = data.getMethod("isDeletable");
            } catch (NoSuchMethodException ignored) {
            }
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
    /** Предмет Tempad (по id мода), без рефлексии — для правил выпадения при смерти и т.п. */
    public static boolean isTempadStack(ItemStack s) {
        if (s.isEmpty()) return false;
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        return id.getNamespace().equals("tempad") && id.getPath().contains("tempad");
    }

    public static void tick(MinecraftServer server, BiConsumer<ServerPlayer, TempadSyncPacket> sender) {
        tick++;
        if (tick % 10 == 0 && com.arc4io.archud.config.ArcConfig.get().tempadNoCooldown && available()) {
            // без перезарядки: убираем таймер Tempad (в инвентаре и в браслете)
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (isTempadStack(s) && s.hasTag() && s.getTag().contains("Timer")) s.removeTagKey("Timer");
                }
                try {
                    ItemStack s = (ItemStack) findTempad.invoke(null, p);
                    if (s != null && s.hasTag() && s.getTag().contains("Timer")) s.removeTagKey("Timer");
                } catch (Throwable ignored) {
                }
            }
        }
        if (tick % PERIOD != 0 || !available()) return;
        Level overworld = server.overworld();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                Map<?, ?> map = (Map<?, ?>) getLocations.invoke(null, overworld, player.getUUID());
                List<TempadSyncPacket.Entry> list = new ArrayList<>();
                if (map != null) {
                    for (Object loc : map.values()) {
                        if (fromOtherMod(loc)) continue; // вэйстоуны и т.п. — у них свои метки
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
        com.arc4io.archud.network.ArcNet.onServer("tempad_door_player", (player, buf) -> {
            UUID target = buf.readUUID();
            if (!com.arc4io.archud.config.ArcConfig.get().serverTempadToPlayers || player.getServer() == null) return;
            ServerPlayer t = player.getServer().getPlayerList().getPlayer(target);
            if (t == null || t == player) return;
            if (ArcServer.isHidden(t.getUUID())) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.player_hidden"), true);
                return;
            }
            openDoorTo(player, t.getGameProfile().getName(), t.getBlockX(), t.getBlockY(), t.getBlockZ(),
                    t.level().dimension().location().toString());
        });
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
            // всегда ищем безопасное место: твёрдый пол, не пустота, не лава (чанк при необходимости загружается)
            BlockPos safe = TeleportSafety.find(player.getServer().getLevel(key), x, y, z);
            if (safe == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tp.unsafe"), true);
                return;
            }
            x = safe.getX();
            y = safe.getY();
            z = safe.getZ();
            ItemStack stack = (ItemStack) findTempad.invoke(null, player);
            if (stack == null || stack.isEmpty() || !tempadItem.isInstance(stack.getItem())) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_tempad"), true);
                return;
            }
            Object option = getOption.invoke(stack.getItem());
            if (com.arc4io.archud.config.ArcConfig.get().tempadNoCooldown) stack.removeTagKey("Timer");
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
            if (com.arc4io.archud.config.ArcConfig.get().tempadNoCooldown) stack.removeTagKey("Timer");
            summon.invoke(null, loc, player, 0xFF8800);
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: Tempad door to waypoint failed", t);
        }
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

    /** Локации, которые Tempad берёт из других модов (Waystones): помечены как неудаляемые — у них уже есть свои метки. */
    private static boolean fromOtherMod(Object loc) {
        try {
            return isDeletable != null && !(boolean) isDeletable.invoke(loc);
        } catch (Throwable t) {
            return false;
        }
    }
}
