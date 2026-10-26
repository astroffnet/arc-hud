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
 * Имя файла — ник с учётом регистра (см. {@link #key(String)}). Если файла по нику ещё нет,
 * а по UUID есть — он копируется (оригинал остаётся как резервная копия).
 */
public final class NicknameStorage {
    private NicknameStorage() {}

    public static boolean enabled() {
        return ArcConfig.get().nicknameStorage;
    }

    /**
     * Ключ файла с учётом регистра ника: «Arc4io» и «arc4io» — разные игроки. Файловые системы Windows и macOS
     * не различают регистр в именах файлов, поэтому регистр кодируется явно: ник в нижнем регистре
     * + «@» + маска заглавных букв (hex). «arc4io» → arc4io, «Arc4io» → arc4io@1, «ARC» → arc@7.
     */
    public static String key(Player player) {
        return key(player.getGameProfile().getName());
    }

    public static String key(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "_");
        java.math.BigInteger mask = java.math.BigInteger.ZERO;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i))) mask = mask.setBit(i);
        }
        return mask.signum() == 0 ? lower : lower + "@" + mask.toString(16);
    }

    /** Ключ прошлой версии (ник в нижнем регистре, без учёта регистра) — для переноса. */
    public static String legacyKey(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "_");
    }

    /** Путь к файлу по нику; при необходимости переносит данные из файла по UUID. */
    public static Path byNick(Path uuidFile, Player player) {
        if (!enabled() || uuidFile == null || player == null) return uuidFile;
        String name = uuidFile.getFileName().toString();
        String uuid = player.getStringUUID();
        if (!name.startsWith(uuid)) return uuidFile;
        String suffix = name.substring(uuid.length());
        Path nick = uuidFile.resolveSibling(key(player) + suffix);
        migrate(uuidFile, nick);
        migrate(uuidFile.resolveSibling(legacyKey(player.getGameProfile().getName()) + suffix), nick);
        return nick;
    }

    public static File byNick(File uuidFile, Player player) {
        return uuidFile == null ? null : byNick(uuidFile.toPath(), player).toFile();
    }

    /** playerdata: перенести &lt;uuid&gt;.dat → &lt;ник&gt;.dat перед загрузкой. */
    public static void migratePlayerData(File dir, Player player) {
        if (!enabled() || dir == null) return;
        Path to = new File(dir, key(player) + ".dat").toPath();
        migrate(new File(dir, player.getStringUUID() + ".dat").toPath(), to);
        migrate(new File(dir, legacyKey(player.getGameProfile().getName()) + ".dat").toPath(), to);
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
