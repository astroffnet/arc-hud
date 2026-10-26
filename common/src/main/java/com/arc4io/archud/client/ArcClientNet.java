package com.arc4io.archud.client;

import com.arc4io.archud.client.map.MapCache;
import com.arc4io.archud.client.map.Waypoint;
import com.arc4io.archud.client.map.WaypointManager;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcNet;
import com.arc4io.archud.network.MapCodec;
import com.arc4io.archud.server.ArcServer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Клиентская часть сети Arc HUD: общая карта, игроки за пределами прорисовки, обмен метками.
 */
public final class ArcClientNet {
    /** Игрок, о котором сообщил сервер (может быть далеко или в другом измерении). */
    public record RemotePlayer(UUID id, String name, String dimension, double x, double y, double z, float yaw, float health) {}

    /** Сервер — мод Arc HUD (Forge/Fabric), а не плагин Bukkit: доступны все серверные функции. */
    private static boolean modServer;

    public static boolean modServer() {
        return welcomed && modServer;
    }

    private static boolean helloSent, welcomed, mapSync, positions, sharing, tempadDoors;
    private static int viewDistance = 10;
    private static long lastPlayers;
    private static final Map<UUID, RemotePlayer> PLAYERS = new LinkedHashMap<>();
    private static final java.util.Set<UUID> HIDDEN = new java.util.HashSet<>();
    private static final Map<String, Integer> SENT_HASH = new HashMap<>();
    private static final Map<String, MapCodec.Chunk> OUTGOING = new LinkedHashMap<>();
    private static int tick;
    private static Object connection;

    private ArcClientNet() {}

    public static void init() {
        ArcNet.onClient("welcome", buf -> {
            int protocol = buf.readVarInt();
            welcomed = protocol == ArcServer.PROTOCOL;
            mapSync = buf.readBoolean();
            positions = buf.readBoolean();
            sharing = buf.readBoolean();
            tempadDoors = buf.isReadable() && buf.readBoolean();
            modServer = buf.isReadable() && buf.readBoolean();
            if (ArcConfig.get().hideFromMap) sendPrivacy(true);
        });
        ArcNet.onClient("map_chunks", buf -> {
            List<MapCodec.Chunk> list = MapCodec.read(buf, 4096);
            if (!ArcConfig.get().mapSharing) return;
            String world = WaypointManager.worldKey();
            for (MapCodec.Chunk c : list) {
                int cx = (int) (c.key() >> 32), cz = (int) c.key();
                MapCache.putRemote(world, c.dim(), cx, cz, c.data());
                SENT_HASH.put(c.dim() + "|" + c.key(), Arrays.hashCode(c.data()));
            }
        });
        ArcNet.onClient("players", ArcClientNet::onPlayers);
        ArcNet.onClient("respawn", buf -> {
            if (buf.readBoolean()) {
                String dim = buf.readUtf(128);
                net.minecraft.core.BlockPos pos = buf.readBlockPos();
                boolean anchor = buf.readBoolean();
                com.arc4io.archud.client.map.WaypointManager.setRespawn(dim, pos, anchor, true);
            } else {
                com.arc4io.archud.client.map.WaypointManager.setRespawn(null, null, false, true);
            }
        });
        ArcNet.onClient("wp_shared", ArcClientNet::onShared);
        ArcNet.onClient("admin_allowed", buf -> adminAllowed = buf.readBoolean());
        ArcNet.onClient("admin_state", buf -> {
            try {
                adminState = com.google.gson.JsonParser.parseString(new String(buf.readByteArray(1 << 21), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                adminStateAt = System.currentTimeMillis();
            } catch (Exception e) {
                com.arc4io.archud.ArcHud.LOGGER.warn("Arc HUD: bad admin state", e);
            }
        });
        ArcNet.onClient("admin_result", buf -> {
            adminResult = buf.readUtf(256);
            adminResultAt = System.currentTimeMillis();
        });
        ArcNet.onClient("wp_saved", buf -> {
            String json = null;
            if (buf.readBoolean()) {
                try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(buf.readByteArray(1 << 20)))) {
                    json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    com.arc4io.archud.ArcHud.LOGGER.warn("Arc HUD: bad waypoints from server", e);
                }
            }
            int added = WaypointManager.mergeFromServer(json);
            waypointSyncReady = true;
            waypointSyncAt = System.currentTimeMillis(); // отправить объединённый список обратно
            if (added > 0) {
                Minecraft mc = Minecraft.getInstance();
                SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                        Component.translatable("archud.wp.synced_title"), Component.translatable("archud.wp.synced", added));
            }
        });
    }

    // ------------------------------------------------------------------ админ-панель
    private static boolean adminAllowed;
    private static com.google.gson.JsonObject adminState;
    private static long adminStateAt, adminResultAt;
    private static String adminResult = "";

    /** Сервер разрешил этому игроку админ-панель (оператор, вошёл в аккаунт). */
    public static boolean adminAllowed() {
        return welcomed && adminAllowed;
    }

    public static com.google.gson.JsonObject adminState() {
        return adminState;
    }

    public static long adminStateAt() {
        return adminStateAt;
    }

    public static String adminResult() {
        return System.currentTimeMillis() - adminResultAt < 4000 ? adminResult : "";
    }

    public static void adminRequest() {
        ArcNet.sendToServer(ArcNet.create(adminState == null ? "admin_open" : "admin_refresh"));
    }

    public static void adminAction(String action, String target, String arg) {
        FriendlyByteBuf buf = ArcNet.create("admin_action");
        buf.writeUtf(action, 64);
        buf.writeUtf(target == null ? "" : target, 64);
        buf.writeUtf(arg == null ? "" : (arg.length() > 256 ? arg.substring(0, 256) : arg), 256);
        ArcNet.sendToServer(buf);
    }

    /** Сервер прислал сохранённые метки — с этого момента наши изменения отправляются ему. */
    private static boolean waypointSyncReady;
    /** Когда отправить список меток на сервер (0 — нечего). */
    private static long waypointSyncAt;

    /** Метки изменились (вызывается из сохранения) — отправить на сервер через секунду (изменения подряд склеиваются). */
    public static void waypointsChanged() {
        if (waypointSyncReady) waypointSyncAt = System.currentTimeMillis() + 1000;
    }

    private static void pushWaypoints() {
        byte[] data;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
                gz.write(WaypointManager.exportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            data = bos.toByteArray();
        } catch (Exception e) {
            return;
        }
        int partSize = 28000;
        int total = Math.max(1, (data.length + partSize - 1) / partSize);
        for (int i = 0; i < total; i++) {
            FriendlyByteBuf buf = ArcNet.create("wp_sync");
            buf.writeVarInt(i);
            buf.writeVarInt(total);
            buf.writeByteArray(Arrays.copyOfRange(data, i * partSize, Math.min(data.length, (i + 1) * partSize)));
            ArcNet.sendToServer(buf);
        }
    }

    public static void tick(Minecraft mc) {
        Object conn = mc.getConnection();
        if (conn != connection) {
            connection = conn;
            reset();
        }
        if (conn == null || mc.player == null) return;
        if (!helloSent && ArcNet.canSendToServer()) {
            helloSent = true;
            FriendlyByteBuf buf = ArcNet.create("hello");
            buf.writeVarInt(ArcServer.PROTOCOL);
            ArcNet.sendToServer(buf);
        }
        if (++tick % 5 == 0 && welcomed && mapSync && ArcConfig.get().mapSharing && !OUTGOING.isEmpty()) {
            List<MapCodec.Chunk> batch = new ArrayList<>();
            var it = OUTGOING.values().iterator();
            while (it.hasNext() && batch.size() < 16) {
                batch.add(it.next());
                it.remove();
            }
            FriendlyByteBuf buf = ArcNet.create("map_chunks");
            MapCodec.write(buf, batch);
            ArcNet.sendToServer(buf);
        }
        if (System.currentTimeMillis() - lastPlayers > 5000) PLAYERS.clear();
        if (waypointSyncReady && waypointSyncAt != 0 && System.currentTimeMillis() >= waypointSyncAt) {
            waypointSyncAt = 0;
            pushWaypoints();
        }
    }

    private static void reset() {
        helloSent = welcomed = mapSync = positions = sharing = tempadDoors = modServer = false;
        waypointSyncReady = false;
        waypointSyncAt = 0;
        adminAllowed = false;
        adminState = null;
        PLAYERS.clear();
        HIDDEN.clear();
        SENT_HASH.clear();
        OUTGOING.clear();
        WaypointManager.clearShared();
    }

    /** Новый отсканированный чанк поверхности — отправить на сервер, если он изменился. */
    public static void offerChunk(String dim, int cx, int cz, int[] data) {
        if (!welcomed || !mapSync || !ArcConfig.get().mapSharing) return;
        long key = MapCodec.key(cx, cz);
        String k = dim + "|" + key;
        int hash = Arrays.hashCode(data);
        Integer prev = SENT_HASH.put(k, hash);
        if (prev != null && prev == hash) return;
        OUTGOING.put(k, new MapCodec.Chunk(dim, key, data.clone()));
        if (OUTGOING.size() > 2000) {
            var it = OUTGOING.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    // ------------------------------------------------------------------ игроки
    private static void onPlayers(FriendlyByteBuf buf) {
        viewDistance = buf.readVarInt();
        int n = buf.readVarInt();
        PLAYERS.clear();
        for (int i = 0; i < n; i++) {
            RemotePlayer p = new RemotePlayer(buf.readUUID(), buf.readUtf(32), buf.readUtf(128), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readFloat(), buf.readFloat());
            PLAYERS.put(p.id(), p);
        }
        HIDDEN.clear();
        if (buf.isReadable()) {
            int h = buf.readVarInt();
            for (int i = 0; i < h; i++) HIDDEN.add(buf.readUUID());
        }
        lastPlayers = System.currentTimeMillis();
    }

    public static Collection<RemotePlayer> players() {
        return PLAYERS.values();
    }

    public static int viewDistance() {
        return viewDistance;
    }

    /** Метки этого игрока хранятся на сервере. */
    public static boolean waypointsOnServer() {
        return waypointSyncReady;
    }

    public static boolean connected() {
        return welcomed;
    }

    public static boolean mapSyncActive() {
        return welcomed && mapSync && ArcConfig.get().mapSharing;
    }

    public static boolean positionsActive() {
        return welcomed && positions;
    }

    /** Скрыт ли игрок с карт (он сам так решил). */
    public static boolean isHidden(UUID id) {
        return HIDDEN.contains(id);
    }

    /** «Скрыть меня с карты» — сервер перестаёт показывать вас другим игрокам. */
    public static void setHidden(boolean hidden) {
        ArcConfig.get().hideFromMap = hidden;
        ArcConfig.save();
        if (welcomed) sendPrivacy(hidden);
    }

    private static void sendPrivacy(boolean hidden) {
        FriendlyByteBuf buf = ArcNet.create("privacy");
        buf.writeBoolean(hidden);
        ArcNet.sendToServer(buf);
    }

    /** Телепорт с карты на безопасное место: через сервер Arc HUD, иначе ванильный /spreadplayers (ставит на поверхность). */
    public static void safeTeleport(String dim, int x, int yHint, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (welcomed) {
            FriendlyByteBuf buf = ArcNet.create("safe_tp");
            buf.writeUtf(dim, 128);
            buf.writeInt(x);
            buf.writeInt(yHint);
            buf.writeInt(z);
            ArcNet.sendToServer(buf);
            return;
        }
        if (mc.getConnection() == null) return;
        boolean ceiling = dim.equals("minecraft:the_nether");
        mc.getConnection().sendCommand("execute in " + dim + " run spreadplayers " + x + " " + z + " 0 1 "
                + (ceiling ? "under 120 " : "") + "false @s");
    }

    public static void tempadDoorToPlayer(UUID id) {
        FriendlyByteBuf buf = ArcNet.create("tempad_door_player");
        buf.writeUUID(id);
        ArcNet.sendToServer(buf);
    }

    /** Сервер умеет открывать двери Tempad к любым точкам. */
    public static boolean tempadDoors() {
        return welcomed && tempadDoors;
    }

    public static void tempadDoorTo(Waypoint w) {
        FriendlyByteBuf buf = ArcNet.create("tempad_door");
        buf.writeUtf(w.name.length() > 64 ? w.name.substring(0, 64) : w.name, 64);
        buf.writeInt(w.x);
        buf.writeInt(w.y);
        buf.writeInt(w.z);
        buf.writeUtf(w.dimension, 128);
        ArcNet.sendToServer(buf);
    }

    // ------------------------------------------------------------------ метки
    public static boolean canShare() {
        return welcomed && sharing;
    }

    public static void shareWaypoint(Waypoint w, String target) {
        FriendlyByteBuf buf = ArcNet.create("wp_share");
        buf.writeUtf(target, 32);
        buf.writeUtf(w.name.length() > 64 ? w.name.substring(0, 64) : w.name, 64);
        buf.writeInt(w.x);
        buf.writeInt(w.y);
        buf.writeInt(w.z);
        buf.writeUtf(w.dimension, 128);
        buf.writeInt(w.color);
        buf.writeUtf(w.icon == null ? "" : w.icon, 32);
        ArcNet.sendToServer(buf);
    }

    private static void onShared(FriendlyByteBuf buf) {
        String from = buf.readUtf(32);
        String name = buf.readUtf(64);
        int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
        String dim = buf.readUtf(128);
        int color = buf.readInt();
        String icon = buf.isReadable() ? buf.readUtf(32) : "";
        if (!ArcConfig.get().acceptSharedWaypoints) return;
        Waypoint w = new Waypoint(name, x, y, z, color | 0xFF000000, dim, Waypoint.Source.SHARED);
        if (!icon.isEmpty()) w.icon = icon;
        w.sharedBy = from;
        WaypointManager.addShared(w);
        Minecraft mc = Minecraft.getInstance();
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.translatable("archud.share.received", from), Component.literal("◆ " + name + "  (" + x + ", " + y + ", " + z + ")"));
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("archud.share.received_chat", from, name, x, y, z)
                    .withStyle(ChatFormatting.AQUA), false);
        }
    }
}
