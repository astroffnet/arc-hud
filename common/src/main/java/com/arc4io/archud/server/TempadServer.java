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
    /** Двери Tempad к игроку: дверь → за кем она следует (место назначения обновляется, пока дверь открыта). */
    private static final Map<net.minecraft.world.entity.Entity, UUID> FOLLOW = new java.util.WeakHashMap<>();
    private static Class<?> doorClass;
    private static Method doorSetLocation;
    private static Method getFavorite;
    private static Object channel;
    private static Method channelSend;
    private static java.lang.reflect.Constructor<?> newScreenPacket;

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
        if (tick % 5 == 0 && !FOLLOW.isEmpty()) updateFollowing(server);
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
            net.minecraft.world.entity.Entity door = openDoorTo(player, t.getGameProfile().getName(), t.getBlockX(), t.getBlockY(), t.getBlockZ(),
                    t.level().dimension().location().toString());
            // дверь ведёт к самому игроку: пока она открыта, место назначения следует за ним
            if (door != null) FOLLOW.put(door, t.getUUID());
        });
        com.arc4io.archud.network.ArcNet.onServer("tempad_screen", (player, buf) -> openScreen(player));
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
    public static net.minecraft.world.entity.Entity openDoorTo(ServerPlayer player, String name, int x, int y, int z, String dim) {
        if (!available()) return null;
        try {
            ResourceLocation dimId = ResourceLocation.tryParse(dim);
            if (dimId == null || player.getServer() == null) return null;
            ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimId);
            if (player.getServer().getLevel(key) == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_level"), true);
                return null;
            }
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return null;
            // всегда ищем безопасное место: твёрдый пол, не пустота, не лава (чанк при необходимости загружается)
            BlockPos safe = TeleportSafety.find(player.getServer().getLevel(key), x, y, z);
            if (safe == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tp.unsafe"), true);
                return null;
            }
            x = safe.getX();
            y = safe.getY();
            z = safe.getZ();
            ItemStack stack = (ItemStack) findTempad.invoke(null, player);
            if (stack == null || stack.isEmpty() || !tempadItem.isInstance(stack.getItem())) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_tempad"), true);
                return null;
            }
            Object option = getOption.invoke(stack.getItem());
            if (com.arc4io.archud.config.ArcConfig.get().tempadNoCooldown) stack.removeTagKey("Timer");
            if (!(boolean) canOpen.invoke(option, player, stack)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_fuel"), true);
                return null;
            }
            if (!(boolean) mayTeleport.invoke(null, key, player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_dimension"), true);
                return null;
            }
            String n = name == null || name.isBlank() ? "Arc HUD" : name.length() > 48 ? name.substring(0, 48) : name;
            Object loc = newLocation.newInstance(n, key, new BlockPos(x, y, z), UUID.randomUUID());
            onOpen.invoke(option, player);
            if (com.arc4io.archud.config.ArcConfig.get().tempadNoCooldown) stack.removeTagKey("Timer");
            summon.invoke(null, loc, player, 0xFF8800);
            return findDoor(player, loc);
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: Tempad door to waypoint failed", t);
        }
        return null;
    }

    /** Только что открытая дверь (по её месту назначения). */
    private static net.minecraft.world.entity.Entity findDoor(ServerPlayer player, Object loc) {
        try {
            if (doorClass == null) {
                doorClass = Class.forName("me.codexadrian.tempad.common.entity.TimedoorEntity", false, TempadServer.class.getClassLoader());
                doorSetLocation = doorClass.getMethod("setLocation", newLocation.getDeclaringClass());
            }
            Method getLoc = doorClass.getMethod("getLocation");
            for (net.minecraft.world.entity.Entity e : player.level().getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                    player.getBoundingBox().inflate(6), doorClass::isInstance)) {
                if (getLoc.invoke(e) == loc) return e;
            }
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: Tempad door lookup failed", t);
        }
        return null;
    }

    /** Двери к игрокам: переносим место назначения туда, где игрок сейчас (в т.ч. в другое измерение). */
    private static void updateFollowing(MinecraftServer server) {
        FOLLOW.entrySet().removeIf(en -> {
            net.minecraft.world.entity.Entity door = en.getKey();
            if (door == null || door.isRemoved()) return true;
            ServerPlayer t = server.getPlayerList().getPlayer(en.getValue());
            if (t == null || ArcServer.isHidden(t.getUUID())) return false;
            try {
                net.minecraft.server.level.ServerLevel lvl = t.serverLevel();
                BlockPos safe = TeleportSafety.find(lvl, t.getBlockX(), t.getBlockY(), t.getBlockZ());
                if (safe == null) safe = t.blockPosition();
                Object loc = newLocation.newInstance(t.getGameProfile().getName(), lvl.dimension(), safe, UUID.randomUUID());
                doorSetLocation.invoke(door, loc);
            } catch (Throwable ex) {
                return true;
            }
            return false;
        });
    }

    /** Экран Tempad из инвентаря Arc HUD: Tempad может лежать где угодно в инвентаре или в браслете. */
    public static void openScreen(ServerPlayer player) {
        if (!available()) return;
        try {
            ItemStack stack = (ItemStack) findTempad.invoke(null, player);
            if (stack == null || stack.isEmpty()) {
                for (int i = 0; i < player.getInventory().getContainerSize() && (stack == null || stack.isEmpty()); i++) {
                    if (tempadItem.isInstance(player.getInventory().getItem(i).getItem())) stack = player.getInventory().getItem(i);
                }
            }
            if (stack == null || stack.isEmpty() || !tempadItem.isInstance(stack.getItem())) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("archud.tempad.no_tempad"), true);
                return;
            }
            if (newScreenPacket == null) {
                ClassLoader cl = TempadServer.class.getClassLoader();
                Class<?> handler = Class.forName("me.codexadrian.tempad.common.data.TempadLocationHandler", false, cl);
                getFavorite = handler.getMethod("getFavorite", Level.class, UUID.class);
                Class<?> pkt = Class.forName("me.codexadrian.tempad.common.network.messages.s2c.OpenTempadScreenPacket", false, cl);
                newScreenPacket = pkt.getConstructor(List.class, UUID.class);
                channel = Class.forName("me.codexadrian.tempad.common.network.NetworkHandler", false, cl).getField("CHANNEL").get(null);
                for (Method m : channel.getClass().getMethods()) {
                    if (m.getName().equals("sendToPlayer") && m.getParameterCount() == 2) channelSend = m;
                }
            }
            Map<?, ?> map = (Map<?, ?>) getLocations.invoke(null, player.level(), player.getUUID());
            Object packet = newScreenPacket.newInstance(new ArrayList<>(map == null ? List.of() : map.values()),
                    getFavorite.invoke(null, player.level(), player.getUUID()));
            channelSend.invoke(channel, packet, player);
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: cannot open Tempad screen", t);
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
