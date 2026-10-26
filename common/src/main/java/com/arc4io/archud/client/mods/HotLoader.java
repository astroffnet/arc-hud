package com.arc4io.archud.client.mods;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Подгрузка нового мода без перезапуска — для модов без кода (только ресурсы: текстуры, модели, звуки, переводы,
 * шрифты, шейдеры). Ресурсы мода распаковываются во временный ресурспак resourcepacks/archud-hot-&lt;имя&gt; и
 * включаются с фоновой перезагрузкой. После перезапуска мод грузится обычным способом, а временный пак удаляется.
 * <p>
 * Моды с кодом так подгрузить нельзя ни в Forge, ни в Fabric: миксины применяются только при загрузке классов,
 * реестры блоков/предметов закрываются после запуска. Для них — «Перезапустить» с возвратом в тот же мир.
 */
public final class HotLoader {
    public static final String PREFIX = "archud-hot-";

    private HotLoader() {}

    /** В jar нет классов, но есть ресурсы клиента (assets/). */
    public static boolean resourceOnly(Path jar) {
        boolean assets = false;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.endsWith(".class")) return false;
                if (n.startsWith("assets/")) assets = true;
            }
        } catch (Exception ex) {
            return false;
        }
        return assets;
    }

    /** Распаковать ресурсы мода во временный пак и включить его (на главном потоке). */
    public static void load(Path jar, String name) {
        Minecraft mc = Minecraft.getInstance();
        String folder = PREFIX + jar.getFileName().toString().replaceFirst("(?i)\\.jar$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        Path dir = mc.getResourcePackDirectory().resolve(folder);
        try {
            deleteTree(dir);
            Files.createDirectories(dir);
            try (ZipFile z = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> e = z.entries();
                while (e.hasMoreElements()) {
                    ZipEntry en = e.nextElement();
                    String n = en.getName();
                    if (en.isDirectory() || !(n.startsWith("assets/") || n.equals("pack.png")) || n.contains("..")) continue;
                    Path out = dir.resolve(n);
                    Files.createDirectories(out.getParent());
                    try (InputStream in = z.getInputStream(en)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\""
                    + name.replace("\\", "").replace("\"", "'") + " (Arc HUD: hot-loaded)\"}}", StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ArcHud.LOGGER.warn("Arc HUD: hot-load of {} failed", jar, ex);
            return;
        }
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        String id = "file/" + folder;
        if (repo.getPack(id) == null) {
            ArcHud.LOGGER.warn("Arc HUD: hot-loaded pack {} not found", id);
            return;
        }
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.remove(id);
        // сразу над ванилой и ресурсами модов, но ниже ресурспаков игрока — как обычный мод
        int pos = 0;
        for (int i = 0; i < selected.size(); i++) {
            if (!selected.get(i).startsWith("file/")) pos = i + 1;
        }
        selected.add(pos, id);
        repo.setSelected(selected);
        mc.options.updateResourcePacks(repo);
        ArcHud.LOGGER.info("Arc HUD: hot-loaded resources of {}", name);
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.translatable("archud.mods.hotloaded"), Component.literal(name));
    }

    /** При запуске: мод теперь загружен по-настоящему — временные паки больше не нужны. */
    public static void cleanup(Minecraft mc) {
        try {
            boolean changed = mc.options.resourcePacks.removeIf(p -> p.startsWith("file/" + PREFIX));
            changed |= mc.options.incompatibleResourcePacks.removeIf(p -> p.startsWith("file/" + PREFIX));
            Path packs = mc.getResourcePackDirectory();
            if (Files.isDirectory(packs)) {
                try (Stream<Path> s = Files.list(packs)) {
                    for (Path p : (Iterable<Path>) s::iterator) if (p.getFileName().toString().startsWith(PREFIX)) deleteTree(p);
                }
            }
            if (changed) mc.options.save();
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: hot-load cleanup failed", e);
        }
    }

    private static void deleteTree(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            List<Path> all = s.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path x : all) Files.deleteIfExists(x);
        }
    }
}
