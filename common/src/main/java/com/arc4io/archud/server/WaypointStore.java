package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcNet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Метки игроков на сервере (выделенном или «Открыть для сети»).
 * Клиент присылает свой список меток (сжатый JSON, частями по ~28 КБ) при каждом изменении,
 * сервер хранит его в папке мира: data/archud_waypoints/&lt;ник&gt;.json.gz
 * и при входе отдаёт обратно — метки игрока доступны с любого компьютера и после переустановки.
 */
public final class WaypointStore {
    /** Предел размера сжатого списка меток одного игрока. */
    private static final int MAX_BYTES = 512 * 1024;
    private static final Map<UUID, ByteArrayOutputStream> PARTS = new HashMap<>();

    private WaypointStore() {}

    public static void init() {
        ArcNet.onServer("wp_sync", WaypointStore::onSync);
    }

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("archud_waypoints");
    }

    /**
     * Файл меток игрока — по НИКУ (без учёта регистра): на пиратских серверах и при смене аккаунта
     * UUID может меняться, а ник остаётся. Старый файл по UUID переименовывается при первом входе.
     */
    private static Path file(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        String nick = player.getGameProfile().getName().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "_");
        Path f = dir(server).resolve(nick + ".json.gz");
        Path legacy = dir(server).resolve(player.getUUID() + ".json.gz");
        if (!Files.exists(f) && Files.exists(legacy)) {
            try {
                Files.move(legacy, f);
            } catch (Exception e) {
                ArcHud.LOGGER.warn("Arc HUD: cannot rename {} -> {}", legacy.getFileName(), f.getFileName(), e);
            }
        }
        return f;
    }

    /** Пакет: часть (varint), всего частей (varint), байты. */
    private static void onSync(ServerPlayer player, FriendlyByteBuf buf) {
        if (!ArcConfig.get().serverWaypointSync || player.getServer() == null) return;
        int part = buf.readVarInt(), total = buf.readVarInt();
        byte[] data = buf.readByteArray(ArcNet.C2S_LIMIT);
        if (total <= 0 || total > MAX_BYTES / 1000 || part < 0 || part >= total) return;
        ByteArrayOutputStream acc = part == 0 ? new ByteArrayOutputStream() : PARTS.get(player.getUUID());
        if (acc == null) return;
        if (acc.size() + data.length > MAX_BYTES) {
            PARTS.remove(player.getUUID());
            return;
        }
        acc.writeBytes(data);
        if (part + 1 < total) {
            PARTS.put(player.getUUID(), acc);
            return;
        }
        PARTS.remove(player.getUUID());
        try {
            Path f = file(player);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, acc.toByteArray());
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ArcHud.LOGGER.info("Arc HUD: stored waypoints of {} -> {} ({} bytes)", player.getGameProfile().getName(), f.getFileName(), acc.size());
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot save waypoints of {}", player.getGameProfile().getName(), e);
        }
    }

    /** При входе: отправить игроку его сохранённые метки (или «пусто» — тогда клиент пришлёт свои). */
    public static void sendSaved(ServerPlayer player) {
        if (!ArcConfig.get().serverWaypointSync || player.getServer() == null) return;
        byte[] data = null;
        try {
            Path f = file(player);
            if (Files.exists(f) && Files.size(f) <= MAX_BYTES) data = Files.readAllBytes(f);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot read waypoints of {}", player.getGameProfile().getName(), e);
        }
        FriendlyByteBuf w = ArcNet.create("wp_saved");
        w.writeBoolean(data != null);
        if (data != null) w.writeByteArray(data);
        ArcNet.sendToPlayer(player, w);
    }

    public static void onLogout(ServerPlayer player) {
        PARTS.remove(player.getUUID());
    }
}
