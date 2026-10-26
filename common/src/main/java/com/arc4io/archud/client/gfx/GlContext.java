package com.arc4io.archud.client.gfx;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Выбор версии контекста OpenGL (применяется при следующем запуске).
 * <ul>
 *   <li>3.2–4.6 — профиль Core, как у Minecraft 1.17+; на Forge версия выбирается через список
 *       {@code earlyWindowSkipGLVersions} в fml.toml (окно загрузки Forge перебирает 4.6…3.2);</li>
 *   <li>2.0–3.1 — «старый» контекст без профиля, как в Minecraft 1.16 (для старых видеокарт без 3.2).
 *       На Forge для этого отключается окно загрузки ({@code earlyWindowControl=false}).</li>
 * </ul>
 * Если игра с выбранной версией не запустилась, при следующем старте выбор сбрасывается на «Авто».
 */
public final class GlContext {
    /** Варианты: 0 — авто, дальше major*10+minor. */
    public static final int[] VERSIONS = {0, 20, 21, 30, 31, 32, 33, 40, 41, 42, 43, 44, 45, 46};
    private static final String[] FORGE_VERSIONS = {"4.6", "4.5", "4.4", "4.3", "4.2", "4.1", "4.0", "3.3", "3.2"};

    private static int requested = -1;
    private static boolean revertedAfterCrash;
    private static String current = "?", renderer = "?";
    private static int currentVersion; // major*10+minor созданного контекста

    private GlContext() {}

    private static Path dir() {
        Minecraft mc = Minecraft.getInstance();
        Path game = mc != null && mc.gameDirectory != null ? mc.gameDirectory.toPath() : Path.of(".");
        return game.resolve("config").resolve("archud");
    }

    private static Path file() {
        return dir().resolve("opengl.properties");
    }

    private static Path marker() {
        return dir().resolve("opengl.trying");
    }

    private static Properties load() {
        Properties p = new Properties();
        try {
            if (Files.exists(file())) try (var r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                p.load(r);
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private static void store(Properties p) {
        try {
            Files.createDirectories(dir());
            try (var w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                p.store(w, "Arc HUD: OpenGL context (version = 0 auto, 20 = 2.0 ... 46 = 4.6)");
            }
        } catch (IOException e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot save OpenGL settings", e);
        }
    }

    /** Выбранная версия (0 — авто). */
    public static int requested() {
        if (requested < 0) {
            try {
                requested = Integer.parseInt(load().getProperty("version", "0").trim());
            } catch (NumberFormatException e) {
                requested = 0;
            }
        }
        return requested;
    }

    public static boolean revertedAfterCrash() {
        return revertedAfterCrash;
    }

    // ------------------------------------------------------------------ окно (из миксина Window)
    /** Перед созданием окна игры: выставить подсказки GLFW под выбранную версию. */
    public static void applyHints() {
        try {
            if (Files.exists(marker())) {
                // прошлый запуск с этой версией не дошёл до главного меню — вернуть «Авто»
                Files.deleteIfExists(marker());
                if (requested() != 0) {
                    ArcHud.LOGGER.warn("Arc HUD: OpenGL {} failed last time, back to Auto", label(requested()));
                    set(0);
                    revertedAfterCrash = true;
                }
                return;
            }
            int v = requested();
            if (v == 0) return;
            Files.createDirectories(dir());
            Files.writeString(marker(), String.valueOf(v));
            int major = v / 10, minor = v % 10;
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, major);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, minor);
            if (v >= 32) {
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            } else {
                // как в 1.16: без профиля и без forward-compat — драйвер выдаёт совместимый контекст
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_ANY_PROFILE);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_FALSE);
            }
            ArcHud.LOGGER.info("Arc HUD: requesting OpenGL {} context", label(v));
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: OpenGL hints failed", t);
        }
    }

    /** Игра дошла до меню — выбранная версия работает. */
    public static void startupOk() {
        try {
            Files.deleteIfExists(marker());
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ сведения о контексте
    /** Прочитать версию созданного контекста (вызывать на потоке рендера). */
    public static void detect() {
        try {
            int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION), minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
            currentVersion = major * 10 + minor;
            String ver = GL11.glGetString(GL11.GL_VERSION);
            current = ver == null ? major + "." + minor : ver;
            String r = GL11.glGetString(GL11.GL_RENDERER);
            renderer = r == null ? "?" : r;
            Properties p = load();
            int prevMax = parse(p.getProperty("max", "0"));
            // максимум видеокарты: в Core-профиле драйвер обычно выдаёт наивысшую версию — запоминаем наибольшую виденную
            if (currentVersion > prevMax) {
                p.setProperty("max", String.valueOf(currentVersion));
                store(p);
            }
        } catch (Throwable t) {
            currentVersion = 0;
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String current() {
        return current;
    }

    public static String renderer() {
        return renderer;
    }

    public static int currentVersion() {
        return currentVersion;
    }

    /** Наивысшая версия, которую видеокарта точно поддерживает (0 — неизвестно). */
    public static int maxSupported() {
        return Math.max(currentVersion, parse(load().getProperty("max", "0")));
    }

    public static boolean available(int v) {
        if (v == 0) return true;
        int max = maxSupported();
        return max == 0 || v <= max;
    }

    public static String label(int v) {
        return v == 0 ? "Auto" : (v / 10) + "." + (v % 10);
    }

    /** Варианты для переключателя (без версий выше возможностей видеокарты). */
    public static List<Integer> choices() {
        List<Integer> out = new ArrayList<>();
        for (int v : VERSIONS) if (available(v)) out.add(v);
        return out;
    }

    // ------------------------------------------------------------------ сохранение выбора
    public static void set(int v) {
        requested = v;
        Properties p = load();
        p.setProperty("version", String.valueOf(v));
        boolean forge = ArcHud.platform().isModLoaded("forge");
        if (forge) applyForge(v, p);
        store(p);
    }

    /** Forge: окно создаёт загрузчик — версию задаём через fml.toml. */
    private static void applyForge(int v, Properties p) {
        Path fml = dir().getParent().resolve("fml.toml");
        try {
            String text = Files.exists(fml) ? Files.readString(fml, StandardCharsets.UTF_8) : "";
            List<String> skip = new ArrayList<>();
            if (v >= 32) for (String s : FORGE_VERSIONS) {
                int sv = Integer.parseInt(s.replace(".", ""));
                if (sv > v) skip.add('"' + s + '"');
            }
            text = setKey(text, "earlyWindowSkipGLVersions", "[" + String.join(", ", skip) + "]");
            boolean legacy = v != 0 && v < 32;
            if (legacy) {
                if (!p.containsKey("fmlEarlyWindowWas")) {
                    String was = readKey(text, "earlyWindowControl");
                    p.setProperty("fmlEarlyWindowWas", was == null ? "true" : was);
                }
                text = setKey(text, "earlyWindowControl", "false");
            } else if (p.containsKey("fmlEarlyWindowWas")) {
                text = setKey(text, "earlyWindowControl", p.getProperty("fmlEarlyWindowWas"));
                p.remove("fmlEarlyWindowWas");
            }
            Files.writeString(fml, text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot update fml.toml", e);
        }
    }

    private static String readKey(String text, String key) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key) && t.substring(key.length()).trim().startsWith("=")) return t.substring(t.indexOf('=') + 1).trim();
        }
        return null;
    }

    private static String setKey(String text, String key, String value) {
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (!found && t.startsWith(key) && t.substring(key.length()).trim().startsWith("=")) {
                out.append(key).append(" = ").append(value);
                found = true;
            } else out.append(line);
            out.append('\n');
        }
        String s = out.length() > 0 ? out.substring(0, out.length() - 1) : "";
        if (!found) s = s + (s.isEmpty() || s.endsWith("\n") ? "" : "\n") + key + " = " + value + "\n";
        return s;
    }

    // ------------------------------------------------------------------ Vulkan
    /** Vulkan в 1.20.1 — только через VulkanMod (Fabric): он целиком заменяет рендер игры. */
    public static boolean vulkan() {
        return ArcHud.platform().isModLoaded("vulkanmod");
    }
}
