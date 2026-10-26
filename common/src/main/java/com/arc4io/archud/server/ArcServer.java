package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcNet;
import com.arc4io.archud.network.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Серверная часть Arc HUD (работает и на выделенном сервере, и в «Открыть для сети»):
 *  - общая карта: игроки присылают отсканированные чанки, сервер хранит их в мире и раздаёт всем
 *    (каждый видит места, которые исследовали другие — как mapsyncer);
 *  - позиции и зоны прогрузки всех игроков для мини-карты / карты мира;
 *  - обмен метками между игроками.
 */
public final class ArcServer {
    public static final int PROTOCOL = 1;
    private static final Set<UUID> ARC_PLAYERS = new HashSet<>();
    private static final Map<String, Map<Long, int[]>> MAP = new HashMap<>();
    private static final Map<UUID, Deque<long[]>> UPLOAD_QUEUE = new HashMap<>(); // {dimIndex, key}
    private static final List<String> DIM_INDEX = new ArrayList<>();
    private static final List<Pending> RELAY = new ArrayList<>();
    private static final Map<UUID, Long> LAST_SHARE = new HashMap<>();
    /** Игроки, скрывшие себя с карт других. */
    private static final Set<UUID> HIDDEN = new HashSet<>();
    /** Последняя отправленная точка возрождения игрока (кровать / якорь). */
    private static final Map<UUID, String> LAST_RESPAWN = new HashMap<>();
    private static MinecraftServer current;
    private static boolean dirty;
    private static int tick;

    private record Pending(String dim, long key, int[] data, UUID source) {}

    private ArcServer() {}

    public static void init() {
        ArcNet.onServer("hello", ArcServer::onHello);
        ArcNet.onServer("map_chunks", ArcServer::onChunks);
        ArcNet.onServer("wp_share", ArcServer::onShare);
        TempadServer.init();
        WaypointStore.init();
        EnchantServer.init();
        ArcNet.onServer("privacy", (player, buf) -> {
            if (buf.readBoolean()) HIDDEN.add(player.getUUID());
            else HIDDEN.remove(player.getUUID());
        });
        ArcNet.onServer("safe_tp", ArcServer::onSafeTp);
    }

    // ------------------------------------------------------------------ жизненный цикл
    public static void tick(MinecraftServer server) {
        if (current != server) {
            reset();
            current = server;
            load(server);
        }
        tick++;
        ArcConfig cfg = ArcConfig.get();
        // рассылка новых чанков
        if (!RELAY.isEmpty()) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!ARC_PLAYERS.contains(p.getUUID())) continue;
                List<MapCodec.Chunk> out = new ArrayList<>();
                for (Pending pd : RELAY) {
                    if (!pd.source().equals(p.getUUID())) out.add(new MapCodec.Chunk(pd.dim(), pd.key(), pd.data()));
                }
                sendChunks(p, out);
            }
            RELAY.clear();
        }
        // первичная выгрузка карты новому игроку (порциями)
        if (!UPLOAD_QUEUE.isEmpty()) {
            UPLOAD_QUEUE.entrySet().removeIf(e -> {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p == null) return true;
                List<MapCodec.Chunk> out = new ArrayList<>();
                while (out.size() < 32 && !e.getValue().isEmpty()) {
                    long[] k = e.getValue().poll();
                    String dim = DIM_INDEX.get((int) k[0]);
                    int[] data = MAP.getOrDefault(dim, Map.of()).get(k[1]);
                    if (data != null) out.add(new MapCodec.Chunk(dim, k[1], data));
                }
                sendChunks(p, out);
                return e.getValue().isEmpty();
            });
        }
        // позиции игроков
        if (tick % 20 == 0 && cfg.serverPlayerPositions) {
            List<ServerPlayer> all = server.getPlayerList().getPlayers();
            int vd = server.getPlayerList().getViewDistance();
            for (ServerPlayer p : all) {
                if (!ARC_PLAYERS.contains(p.getUUID())) continue;
                FriendlyByteBuf buf = ArcNet.create("players");
                buf.writeVarInt(vd);
                List<ServerPlayer> visible = new ArrayList<>();
                for (ServerPlayer o : all) if (o != p && !HIDDEN.contains(o.getUUID())) visible.add(o);
                buf.writeVarInt(visible.size());
                for (ServerPlayer o : visible) {
                    buf.writeUUID(o.getUUID());
                    buf.writeUtf(o.getGameProfile().getName(), 32);
                    buf.writeUtf(o.level().dimension().location().toString(), 128);
                    buf.writeDouble(o.getX());
                    buf.writeDouble(o.getY());
                    buf.writeDouble(o.getZ());
                    buf.writeFloat(o.getYRot());
                    buf.writeFloat(o.getHealth());
                }
                // скрытые — чтобы клиенты не рисовали их на мини-карте и рядом (по сущностям)
                List<UUID> hidden = new ArrayList<>();
                for (UUID h : HIDDEN) if (!h.equals(p.getUUID())) hidden.add(h);
                buf.writeVarInt(hidden.size());
                for (UUID h : hidden) buf.writeUUID(h);
                ArcNet.sendToPlayer(p, buf);
            }
        }
        // точка возрождения (кровать / якорь возрождения) — клиент сам её не знает
        if (tick % 40 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!ARC_PLAYERS.contains(p.getUUID())) continue;
                net.minecraft.core.BlockPos pos = p.getRespawnPosition();
                String dim = p.getRespawnDimension().location().toString();
                boolean anchor = false;
                if (pos != null) {
                    net.minecraft.server.level.ServerLevel lvl = server.getLevel(p.getRespawnDimension());
                    anchor = lvl != null && lvl.isLoaded(pos) && lvl.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock;
                }
                String key = pos == null ? "none" : dim + "|" + pos.asLong() + "|" + anchor;
                if (key.equals(LAST_RESPAWN.get(p.getUUID()))) continue;
                LAST_RESPAWN.put(p.getUUID(), key);
                FriendlyByteBuf buf = ArcNet.create("respawn");
                buf.writeBoolean(pos != null);
                if (pos != null) {
                    buf.writeUtf(dim, 128);
                    buf.writeBlockPos(pos);
                    buf.writeBoolean(anchor);
                }
                ArcNet.sendToPlayer(p, buf);
            }
        }
        if (dirty && tick % (20 * 120) == 0) save(server);
    }

    public static boolean isHidden(UUID id) {
        return HIDDEN.contains(id);
    }

    /** Телепорт с карты (для операторов) — на безопасное место, а не в пустоту. */
    private static void onSafeTp(ServerPlayer player, FriendlyByteBuf buf) {
        String dim = buf.readUtf(128);
        int x = buf.readInt(), yHint = buf.readInt(), z = buf.readInt();
        if (!player.hasPermissions(2) || player.getServer() == null) {
            player.displayClientMessage(Component.translatable("archud.tp.no_permission"), true);
            return;
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(dim);
        if (id == null) return;
        net.minecraft.server.level.ServerLevel level = player.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id));
        if (level == null || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return;
        net.minecraft.core.BlockPos pos = TeleportSafety.find(level, x, yHint, z);
        if (pos == null) {
            player.displayClientMessage(Component.translatable("archud.tp.unsafe"), true);
            return;
        }
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.fallDistance = 0;
    }

    public static void onLogout(ServerPlayer player) {
        HIDDEN.remove(player.getUUID());
        LAST_RESPAWN.remove(player.getUUID());
        ARC_PLAYERS.remove(player.getUUID());
        UPLOAD_QUEUE.remove(player.getUUID());
        LAST_SHARE.remove(player.getUUID());
        WaypointStore.onLogout(player);
    }

    public static void onStop(MinecraftServer server) {
        if (current == server) save(server);
        reset();
    }

    private static void reset() {
        ARC_PLAYERS.clear();
        HIDDEN.clear();
        MAP.clear();
        UPLOAD_QUEUE.clear();
        DIM_INDEX.clear();
        RELAY.clear();
        current = null;
        dirty = false;
    }

    // ------------------------------------------------------------------ пакеты
    private static void onHello(ServerPlayer player, FriendlyByteBuf buf) {
        int protocol = buf.readVarInt();
        ARC_PLAYERS.add(player.getUUID());
        LAST_RESPAWN.remove(player.getUUID()); // отправить точку возрождения заново
        ArcConfig cfg = ArcConfig.get();
        FriendlyByteBuf w = ArcNet.create("welcome");
        w.writeVarInt(PROTOCOL);
        w.writeBoolean(cfg.serverMapSync);
        w.writeBoolean(cfg.serverPlayerPositions);
        w.writeBoolean(cfg.serverWaypointSharing);
        w.writeBoolean(TempadServer.isAvailable());
        ArcNet.sendToPlayer(player, w);
        WaypointStore.sendSaved(player);
        if (protocol != PROTOCOL || !cfg.serverMapSync) return;
        Deque<long[]> q = new ArrayDeque<>();
        // сначала измерение игрока
        String own = player.level().dimension().location().toString();
        List<String> dims = new ArrayList<>(MAP.keySet());
        dims.sort((a, b) -> a.equals(own) ? -1 : b.equals(own) ? 1 : a.compareTo(b));
        for (String d : dims) {
            int idx = dimIndex(d);
            for (Long k : MAP.get(d).keySet()) q.add(new long[]{idx, k});
        }
        if (!q.isEmpty()) UPLOAD_QUEUE.put(player.getUUID(), q);
    }

    private static void onChunks(ServerPlayer player, FriendlyByteBuf buf) {
        if (!ArcConfig.get().serverMapSync) return;
        List<MapCodec.Chunk> list = MapCodec.read(buf, 64);
        String dim = player.level().dimension().location().toString();
        int pcx = player.getBlockX() >> 4, pcz = player.getBlockZ() >> 4;
        for (MapCodec.Chunk c : list) {
            if (!c.dim().equals(dim)) continue;
            int cx = (int) (c.key() >> 32), cz = (int) c.key();
            if (Math.abs(cx - pcx) > 40 || Math.abs(cz - pcz) > 40) continue; // только около себя
            int[] old = MAP.computeIfAbsent(dim, k -> new HashMap<>()).put(c.key(), c.data());
            if (old != null && java.util.Arrays.equals(old, c.data())) continue;
            dirty = true;
            RELAY.add(new Pending(dim, c.key(), c.data(), player.getUUID()));
        }
    }

    private static void onShare(ServerPlayer player, FriendlyByteBuf buf) {
        if (!ArcConfig.get().serverWaypointSharing) return;
        long now = System.currentTimeMillis();
        Long last = LAST_SHARE.get(player.getUUID());
        if (last != null && now - last < 750) return;
        LAST_SHARE.put(player.getUUID(), now);
        String target = buf.readUtf(32);
        String name = buf.readUtf(64);
        int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
        String dim = buf.readUtf(128);
        int color = buf.readInt();
        MinecraftServer server = player.getServer();
        if (server == null) return;
        int sent = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p == player) continue;
            if (!target.isEmpty() && !p.getGameProfile().getName().equalsIgnoreCase(target)) continue;
            if (ARC_PLAYERS.contains(p.getUUID())) {
                FriendlyByteBuf w = ArcNet.create("wp_shared");
                w.writeUtf(player.getGameProfile().getName(), 32);
                w.writeUtf(name, 64);
                w.writeInt(x);
                w.writeInt(y);
                w.writeInt(z);
                w.writeUtf(dim, 128);
                w.writeInt(color);
                ArcNet.sendToPlayer(p, w);
            } else {
                p.sendSystemMessage(Component.literal("[" + player.getGameProfile().getName() + "] ◆ " + name + " — x:" + x + " y:" + y + " z:" + z
                        + " (" + dim + ")").withStyle(ChatFormatting.AQUA));
            }
            sent++;
        }
        player.displayClientMessage(Component.translatable(sent > 0 ? "archud.share.sent" : "archud.share.nobody", sent)
                .withStyle(sent > 0 ? ChatFormatting.GREEN : ChatFormatting.RED), true);
    }

    private static void sendChunks(ServerPlayer p, List<MapCodec.Chunk> chunks) {
        for (int i = 0; i < chunks.size(); i += 32) {
            FriendlyByteBuf buf = ArcNet.create("map_chunks");
            MapCodec.write(buf, chunks.subList(i, Math.min(chunks.size(), i + 32)));
            ArcNet.sendToPlayer(p, buf);
        }
    }

    private static int dimIndex(String dim) {
        int i = DIM_INDEX.indexOf(dim);
        if (i >= 0) return i;
        DIM_INDEX.add(dim);
        return DIM_INDEX.size() - 1;
    }

    // ------------------------------------------------------------------ хранение
    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("archud_map");
    }

    private static void load(MinecraftServer server) {
        Path d = dir(server);
        if (!Files.isDirectory(d)) return;
        try (var files = Files.list(d)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".bin")) continue;
                String dim = fn.substring(0, fn.length() - 4).replace('~', ':');
                Map<Long, int[]> map = new HashMap<>();
                try (InputStream in = new GZIPInputStream(Files.newInputStream(f)); DataInputStream data = new DataInputStream(in)) {
                    int n = data.readInt();
                    for (int i = 0; i < n; i++) {
                        long key = data.readLong();
                        int[] px = new int[256];
                        for (int j = 0; j < 256; j++) px[j] = data.readInt();
                        map.put(key, px);
                    }
                }
                MAP.put(dim, map);
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось загрузить общую карту", e);
        }
    }

    private static void save(MinecraftServer server) {
        if (!dirty) return;
        dirty = false;
        Path d = dir(server);
        try {
            Files.createDirectories(d);
            for (Map.Entry<String, Map<Long, int[]>> e : MAP.entrySet()) {
                Path f = d.resolve(e.getKey().replace(':', '~') + ".bin");
                Path tmp = d.resolve(f.getFileName() + ".tmp");
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp)); DataOutputStream data = new DataOutputStream(out)) {
                    data.writeInt(e.getValue().size());
                    for (Map.Entry<Long, int[]> c : e.getValue().entrySet()) {
                        data.writeLong(c.getKey());
                        for (int v : c.getValue()) data.writeInt(v);
                    }
                }
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            ArcHud.LOGGER.warn("Arc HUD: не удалось сохранить общую карту", ex);
        }
    }
}
