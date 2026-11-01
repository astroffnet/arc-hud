package com.arc4io.archud.client.mods;

import com.arc4io.archud.client.ArcClient;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Выключение и включение модов из списка модов: jar переименовывается в «.jar.disabled» (как в лаунчерах)
 * и обратно. Изменения действуют после перезапуска игры. Нельзя выключить Fabric API, e4mc, сам Arc HUD,
 * загрузчик и вложенные (jar-in-jar) моды.
 */
public final class ModToggle {
    private static final Set<String> PROTECTED = Set.of("archud", "fabric-api", "fabric", "e4mc", "e4mc_minecraft", "minecraft", "forge",
            "fabricloader", "java", "neoforge");
    public static final String SUFFIX = ".disabled";

    /** Отложенные переименования (откуда → куда) до перезапуска. */
    private static final Map<Path, Path> PENDING = new LinkedHashMap<>();

    /** Выключенный мод (файл .jar.disabled в папке mods). */
    public record Disabled(Path file, String id, String name, String version, String description) {}

    private ModToggle() {}

    public static boolean isProtected(String id) {
        return id == null || PROTECTED.contains(id.toLowerCase(Locale.ROOT)) || id.startsWith("fabric-");
    }

    /** Jar мода в папке mods (только такие можно выключить). */
    public static Path jar(String id) {
        if (isProtected(id) || ArcClient.platform() == null) return null;
        Path f = ArcClient.platform().modFile(id);
        if (f == null) return null;
        try {
            Path mods = ArcClient.platform().modsDir().toAbsolutePath().normalize();
            Path abs = f.toAbsolutePath().normalize();
            if (!abs.getParent().equals(mods) || !abs.getFileName().toString().endsWith(".jar")) return null;
            return abs;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean canToggle(String id) {
        return jar(id) != null;
    }

    /** Выключить мод (после перезапуска его не будет). */
    public static boolean disable(String id) {
        Path j = jar(id);
        if (j == null) return false;
        Path to = j.resolveSibling(j.getFileName() + SUFFIX);
        if (PENDING.containsKey(to)) { // выключили и тут же включили обратно
            PENDING.remove(to);
            return true;
        }
        PENDING.put(j, to);
        return true;
    }

    /** Мод уже помечен на выключение. */
    public static boolean pendingDisable(String id) {
        Path j = jar(id);
        return j != null && PENDING.containsKey(j);
    }

    public static void undoDisable(String id) {
        Path j = jar(id);
        if (j != null) PENDING.remove(j);
    }

    /** Включить выключенный мод: файл не открыт игрой — переименовывается сразу. */
    public static boolean enable(Disabled d) {
        String n = d.file().getFileName().toString();
        Path to = d.file().resolveSibling(n.substring(0, n.length() - SUFFIX.length()));
        try {
            Files.move(d.file(), to);
            return true;
        } catch (Exception e) {
            PENDING.put(d.file(), to);
            return true;
        }
    }

    public static boolean hasChanges() {
        return !PENDING.isEmpty() || enabledNow;
    }

    private static boolean enabledNow;

    public static void markEnabled() {
        enabledNow = true;
    }

    /** Выключенные моды в папке mods. */
    public static List<Disabled> disabled() {
        List<Disabled> out = new ArrayList<>();
        if (ArcClient.platform() == null) return out;
        try (var files = Files.list(ArcClient.platform().modsDir())) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (!n.toLowerCase(Locale.ROOT).endsWith(".jar" + SUFFIX) || !Files.isRegularFile(f)) continue;
                out.add(read(f));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Имя и версия выключенного мода — из fabric.mod.json или META-INF/mods.toml. */
    private static Disabled read(Path f) {
        String file = f.getFileName().toString();
        String base = file.substring(0, file.length() - (".jar" + SUFFIX).length());
        String id = base, name = base, version = "", desc = "";
        try (ZipFile z = new ZipFile(f.toFile())) {
            var fe = z.getEntry("fabric.mod.json");
            if (fe != null) {
                try (InputStream in = z.getInputStream(fe)) {
                    var o = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.has("id")) id = o.get("id").getAsString();
                    if (o.has("name")) name = o.get("name").getAsString();
                    if (o.has("version")) version = o.get("version").getAsString();
                    if (o.has("description")) desc = o.get("description").getAsString();
                }
            }
            var te = z.getEntry("META-INF/mods.toml");
            if (fe == null && te != null) {
                try (InputStream in = z.getInputStream(te)) {
                    for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                        String l = line.trim();
                        String v = l.contains("=") ? l.substring(l.indexOf('=') + 1).trim().replaceAll("^[\"']+|[\"']+$", "") : "";
                        if (l.startsWith("modId") && id.equals(base)) id = v;
                        else if (l.startsWith("displayName") && name.equals(base)) name = v;
                        else if (l.startsWith("version") && version.isEmpty() && !v.startsWith("$")) version = v;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new Disabled(f, id, name, version, desc);
    }

    /**
     * Применить изменения и перезапустить игру. Переименования, которые нельзя сделать сразу (Windows держит jar
     * открытым), выполняет маленький отдельный процесс после закрытия игры — он же и запускает её снова.
     */
    public static void applyAndRestart(Minecraft mc) {
        Map<Path, Path> later = new LinkedHashMap<>();
        for (Map.Entry<Path, Path> e : PENDING.entrySet()) {
            try {
                Files.move(e.getKey(), e.getValue());
            } catch (Exception ex) {
                later.put(e.getKey(), e.getValue());
            }
        }
        PENDING.clear();
        if (later.isEmpty()) {
            GameRestarter.restart(mc);
            return;
        }
        try {
            Path dir = mc.gameDirectory.toPath().resolve("config").resolve("archud").resolve("helper");
            Path cls = dir.resolve("com/arc4io/archud/client/mods/ModToggleHelper.class");
            Files.createDirectories(cls.getParent());
            try (InputStream in = ModToggle.class.getResourceAsStream("/com/arc4io/archud/client/mods/ModToggleHelper.class")) {
                if (in == null) throw new IllegalStateException("helper class missing");
                Files.write(cls, in.readAllBytes());
            }
            List<String> moves = new ArrayList<>();
            for (Map.Entry<Path, Path> e : later.entrySet()) moves.add(e.getKey() + "\t" + e.getValue());
            Path list = dir.resolve("moves.txt");
            Files.write(list, moves, StandardCharsets.UTF_8);
            List<String> game = GameRestarter.supported() ? GameRestarter.command() : null;
            Path cmdFile = dir.resolve("command.txt");
            if (game != null) {
                List<String> lines = new ArrayList<>();
                lines.add(System.getProperty("user.dir"));
                lines.addAll(game);
                Files.write(cmdFile, lines, StandardCharsets.UTF_8);
                GameRestarter.writeResume(mc);
            }
            String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java").toString());
            List<String> helper = List.of(java, "-cp", dir.toString(), "com.arc4io.archud.client.mods.ModToggleHelper",
                    Long.toString(ProcessHandle.current().pid()), list.toString(), game != null ? cmdFile.toString() : "-");
            new ProcessBuilder(helper).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            GameRestarter.stopGame(mc);
        } catch (Exception e) {
            ModWatcher.log("mod toggle helper failed: " + e);
            GameRestarter.restart(mc);
        }
    }
}
