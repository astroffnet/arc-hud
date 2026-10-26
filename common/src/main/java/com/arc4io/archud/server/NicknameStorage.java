package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.world.entity.player.Player;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Хранение данных игрока по НИКУ, а не по UUID: инвентарь и всё остальное из playerdata (вместе с данными
 * модов, которые лежат там же — Curios, рюкзаки и т.п.), достижения (advancements) и статистика (stats).
 * На серверах без лицензии, в «Открыть для сети» и при смене лаунчера UUID игрока может меняться —
 * тогда ванилла создаёт «нового» игрока с пустым инвентарём и без достижений. По нику этого не происходит.
 *
 * Имя файла — ник в нижнем регистре (ники в Minecraft не различают регистр). Если файла по нику ещё нет,
 * а по UUID есть — он копируется (оригинал остаётся как резервная копия).
 */
public final class NicknameStorage {
    private NicknameStorage() {}

    public static boolean enabled() {
        return ArcConfig.get().nicknameStorage;
    }

    /** Ключ файла: ник в нижнем регистре, только безопасные для имени файла символы. */
    public static String key(Player player) {
        return player.getGameProfile().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "_");
    }

    /** Путь к файлу по нику; при необходимости переносит данные из файла по UUID. */
    public static Path byNick(Path uuidFile, Player player) {
        if (!enabled() || uuidFile == null || player == null) return uuidFile;
        String name = uuidFile.getFileName().toString();
        String uuid = player.getStringUUID();
        if (!name.startsWith(uuid)) return uuidFile;
        Path nick = uuidFile.resolveSibling(key(player) + name.substring(uuid.length()));
        migrate(uuidFile, nick);
        return nick;
    }

    public static File byNick(File uuidFile, Player player) {
        return uuidFile == null ? null : byNick(uuidFile.toPath(), player).toFile();
    }

    /** playerdata: перенести &lt;uuid&gt;.dat → &lt;ник&gt;.dat перед загрузкой. */
    public static void migratePlayerData(File dir, Player player) {
        if (!enabled() || dir == null) return;
        migrate(new File(dir, player.getStringUUID() + ".dat").toPath(), new File(dir, key(player) + ".dat").toPath());
    }

    private static void migrate(Path from, Path to) {
        try {
            if (!Files.exists(to) && Files.isRegularFile(from)) {
                Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                ArcHud.LOGGER.info("Arc HUD: {} -> {} (data now stored by nickname)", from.getFileName(), to.getFileName());
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot copy {} -> {}", from, to, e);
        }
    }
}
