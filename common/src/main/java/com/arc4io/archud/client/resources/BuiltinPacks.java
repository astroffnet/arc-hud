package com.arc4io.archud.client.resources;

import com.arc4io.archud.ArcHud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Встроенные ресурспаки распаковываются из jar мода в config/archud/builtin/&lt;имя&gt; (заново — только если
 * содержимое пака в моде изменилось).
 * Пакет из обычной папки работает с любыми модами, в том числе с OptiFine, который читает файлы пакетов
 * как файлы на диске (путь внутри jar вызывал краш «Path not associated with default file system»).
 */
public final class BuiltinPacks {
    private BuiltinPacks() {}

    /** Папка с распакованным паком или null, если распаковать не удалось. */
    public static Path extract(Path source, String name) {
        if (source == null) return null;
        Path dir = ArcHud.platform().configDir().resolve("archud").resolve("builtin").resolve(name);
        Path marker = dir.resolve(".archud-version");
        try {
            String version = signature(source);
            if (Files.exists(marker) && Files.readString(marker).trim().equals(version)) return dir;
            deleteTree(dir);
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.walk(source)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String rel = source.relativize(p).toString().replace('\\', '/');
                    if (rel.isEmpty()) continue;
                    Path target = dir.resolve(rel);
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Files.writeString(marker, version);
            ArcHud.LOGGER.info("Arc HUD: built-in pack {} extracted to {}", name, dir);
            return dir;
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot extract built-in pack {}", name, e);
            return null;
        }
    }

    /** Подпись содержимого: число файлов и их общий размер. */
    private static String signature(Path source) throws Exception {
        long count = 0, size = 0;
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isDirectory(p)) continue;
                count++;
                size += Files.size(p);
            }
        }
        return count + ":" + size;
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
}
