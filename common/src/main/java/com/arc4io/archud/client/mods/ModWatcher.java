package com.arc4io.archud.client.mods;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Следит за папкой mods. Forge и Fabric загружают код модов только при запуске (реестры замораживаются,
 * миксины применяются при загрузке классов), поэтому «подхватить» новый мод в работающей игре нельзя.
 * Вместо этого: как только в папке появился или исчез .jar, показываем уведомление, а в главном меню
 * и меню паузы — кнопку «Перезапустить», которая сохраняет мир, перезапускает игру тем же лаунчером
 * с теми же параметрами и сразу возвращает в тот же мир / на тот же сервер.
 */
public final class ModWatcher {
    private static final Pattern DISPLAY_NAME = Pattern.compile("displayName\\s*=\\s*\"([^\"]+)\"");

    private static Set<String> initial;
    private static Path dir;
    private static Thread thread;
    /** Изменения с момента запуска: имя файла → «+ Название» / «− Название». */
    private static final Map<String, String> changes = new LinkedHashMap<>();
    private static final Map<String, String> names = new LinkedHashMap<>();

    private ModWatcher() {}

    public static void tick(Minecraft mc) {
        if (thread != null || !ArcConfig.get().modWatcher) return;
        dir = mc.gameDirectory.toPath().resolve("mods");
        initial = list();
        for (String f : initial) names.put(f, readName(dir.resolve(f)));
        thread = new Thread(ModWatcher::loop, "ArcHud-ModWatcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Опрос раз в 2 секунды надёжнее WatchService (сетевые диски, OneDrive) и ничего не стоит. */
    private static void loop() {
        Set<String> last = initial;
        while (true) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            Set<String> now = list();
            if (now.equals(last)) continue;
            // файл ещё копируется — ждём, пока размер перестанет меняться
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
            now = list();
            Map<String, String> next = new LinkedHashMap<>();
            for (String f : now) {
                if (!initial.contains(f)) next.put(f, "+ " + names.computeIfAbsent(f, k -> readName(dir.resolve(k))));
            }
            for (String f : initial) {
                if (!now.contains(f)) next.put(f, "− " + names.getOrDefault(f, f));
            }
            List<String> added = new ArrayList<>();
            synchronized (changes) {
                for (Map.Entry<String, String> e : next.entrySet()) if (!changes.containsKey(e.getKey())) added.add(e.getValue());
                changes.clear();
                changes.putAll(next);
            }
            last = now;
            if (added.isEmpty()) continue;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("archud.mods.changed"), Component.literal(String.join(", ", added))));
        }
    }

    private static Set<String> list() {
        Set<String> out = new TreeSet<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p)) out.add(n);
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Название мода из fabric.mod.json / META-INF/mods.toml, иначе имя файла. */
    static String readName(Path jar) {
        String file = jar.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry fj = z.getEntry("fabric.mod.json");
            if (fj != null) {
                try (InputStream in = z.getInputStream(fj)) {
                    JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.has("name")) return o.get("name").getAsString();
                }
            }
            ZipEntry toml = z.getEntry("META-INF/mods.toml");
            if (toml != null) {
                try (InputStream in = z.getInputStream(toml)) {
                    Matcher m = DISPLAY_NAME.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (m.find()) return m.group(1);
                }
            }
        } catch (Exception ignored) {
        }
        return file;
    }

    public static boolean pending() {
        synchronized (changes) {
            return !changes.isEmpty();
        }
    }

    public static List<String> changes() {
        synchronized (changes) {
            return new ArrayList<>(changes.values());
        }
    }

    /** Подпись кнопки: «Перезапустить: +2 / −1». */
    public static Component buttonLabel() {
        int plus = 0, minus = 0;
        for (String c : changes()) {
            if (c.startsWith("+")) plus++;
            else minus++;
        }
        String d = (plus > 0 ? "+" + plus : "") + (plus > 0 && minus > 0 ? " / " : "") + (minus > 0 ? "−" + minus : "");
        return Component.translatable("archud.mods.restart", d);
    }

    public static Component tooltip() {
        StringBuilder sb = new StringBuilder(Component.translatable("archud.mods.restart_tip").getString());
        for (String c : changes()) sb.append('\n').append(c);
        if (!GameRestarter.supported()) sb.append("\n\n").append(Component.translatable("archud.mods.restart_manual").getString());
        return Component.literal(sb.toString());
    }

    /** Кнопка «Перезапустить» (если моды в папке менялись); x/y — левый верх, w — ширина или 0 (по тексту, по центру x). */
    public static void addButton(java.util.function.Consumer<com.arc4io.archud.client.screen.ArcButton> add, int x, int y, int w) {
        if (!pending()) return;
        Minecraft mc = Minecraft.getInstance();
        Component label = buttonLabel();
        if (w <= 0) {
            w = mc.font.width(label) + 24;
            x -= w / 2;
        }
        com.arc4io.archud.client.screen.ArcButton b = new com.arc4io.archud.client.screen.ArcButton(x, y, w, 18, label,
                btn -> GameRestarter.restart(mc));
        b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tooltip()));
        add.accept(b);
    }

    /** Число изменений — экран перестраивается, когда оно меняется. */
    public static int stamp() {
        synchronized (changes) {
            return changes.size() * 31 + changes.keySet().hashCode();
        }
    }

    static void log(String msg) {
        ArcHud.LOGGER.info("[ModWatcher] " + msg);
    }
}
