package com.arc4io.archud.client.resources;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.ReloadableResourceManagerAccessor;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Быстрая смена языка и языковых ресурспаков.
 *
 * Ванилла при смене языка (и при включении любого пакета) перезагружает ВСЕ ресурсы: текстуры, модели,
 * звуки, шейдеры — с модами это десятки секунд. Для языка нужны только тексты, поэтому:
 *  - смена языка перечитывает только переводы (мгновенно);
 *  - если включили / выключили / переставили только пакеты, в которых нет ничего, кроме переводов
 *    (assets/.../lang/), набор пакетов подменяется и перечитываются только переводы;
 *  - всё остальное (текстуры, модели, звуки, книги модов) — обычная полная перезагрузка.
 * Отключается в настройках («Быстрая смена языка и языковых пакетов»).
 */
public final class FastReload {
    /** Кэш «пакет состоит только из переводов»: путь + дата изменения → ответ. */
    private static final Map<String, Boolean> LANG_ONLY = new HashMap<>();

    private FastReload() {}

    public static boolean enabled() {
        return ArcConfig.get().fastLanguageReload;
    }

    /** Смена языка без полной перезагрузки ресурсов. */
    public static void applyLanguage(Minecraft mc) {
        if (!enabled()) {
            mc.reloadResourcePacks();
            return;
        }
        long t = Util.getMillis();
        mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
        ArcHud.LOGGER.info("Arc HUD: language switched in {} ms (fast)", Util.getMillis() - t);
    }

    /**
     * Замена Options.updateResourcePacks (любой экран пакетов, в т.ч. ванильный и из модов).
     */
    public static void applyPacks(Minecraft mc, PackRepository repo) {
        dedupeBuiltin(repo);
        Options options = mc.options;
        List<String> before = new ArrayList<>(options.resourcePacks);
        options.resourcePacks.clear();
        options.incompatibleResourcePacks.clear();
        for (Pack pack : repo.getSelectedPacks()) {
            if (pack.isFixedPosition()) continue;
            options.resourcePacks.add(pack.getId());
            if (!pack.getCompatibility().isCompatible()) options.incompatibleResourcePacks.add(pack.getId());
        }
        options.save();
        List<String> after = new ArrayList<>(options.resourcePacks);
        if (after.equals(before)) return; // ничего не изменилось
        if (enabled() && onlyLanguagePacksChanged(mc, before, after) && swapPacks(mc, repo)) {
            SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("archud.fast.title"), Component.translatable("archud.fast.packs"));
            return;
        }
        mc.reloadResourcePacks();
    }

    /**
     * «Родное слово» встроено в мод. Если игрок включил тот же пак из папки resourcepacks (zip), встроенная
     * копия выключается: два одинаковых пака с книгами Patchouli / Mantle одновременно — лишняя нагрузка
     * и источник конфликтов при перезагрузке ресурсов.
     */
    private static void dedupeBuiltin(PackRepository repo) {
        try {
            String builtin = null;
            boolean fileCopy = false;
            for (Pack p : repo.getSelectedPacks()) {
                if (p.getId().contains("rodnoe_slovo") && !p.getId().startsWith("file/")) builtin = p.getId();
                else if (p.getId().startsWith("file/") && p.getDescription().getString().contains("Родное слово")) fileCopy = true;
            }
            if (builtin != null && fileCopy) {
                List<String> ids = new ArrayList<>(repo.getSelectedIds());
                ids.remove(builtin);
                repo.setSelected(ids);
                ArcHud.LOGGER.info("Arc HUD: «Родное слово» enabled from resourcepacks — built-in copy disabled");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Без языковых пакетов оба списка совпадают — значит, менялись только переводы. */
    private static boolean onlyLanguagePacksChanged(Minecraft mc, List<String> a, List<String> b) {
        List<String> ra = new ArrayList<>(), rb = new ArrayList<>();
        for (String id : a) if (!langOnly(mc, id)) ra.add(id);
        for (String id : b) if (!langOnly(mc, id)) rb.add(id);
        return ra.equals(rb);
    }

    /** Пакет из папки resourcepacks, в котором только pack.mcmeta, pack.png и assets/&lt;мод&gt;/lang/*. */
    public static boolean langOnly(Minecraft mc, String id) {
        if (!id.startsWith("file/")) return false;
        Path path = mc.getResourcePackDirectory().resolve(id.substring(5));
        try {
            String key = path + "@" + Files.getLastModifiedTime(path).toMillis();
            Boolean cached = LANG_ONLY.get(key);
            if (cached != null) return cached;
            boolean r = Files.isDirectory(path) ? scanDir(path) : scanZip(path);
            LANG_ONLY.put(key, r);
            return r;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean allowed(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty() || n.endsWith("/")) return true; // папки
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.equals("pack.mcmeta") || lower.equals("pack.png") || lower.startsWith("__macosx/")) return true;
        if (!lower.contains("/") && (lower.endsWith(".txt") || lower.endsWith(".md"))) return true; // readme, лицензия
        String[] p = n.split("/");
        return p.length >= 4 && p[0].equals("assets") && p[2].equals("lang");
    }

    private static boolean scanZip(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) if (!allowed(en.nextElement().getName())) return false;
        }
        return true;
    }

    private static boolean scanDir(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).allMatch(p -> allowed(root.relativize(p).toString()));
        }
    }

    /** Подменить набор пакетов в менеджере ресурсов и перечитать только переводы. */
    private static boolean swapPacks(Minecraft mc, PackRepository repo) {
        try {
            long t = Util.getMillis();
            ReloadableResourceManager rm = (ReloadableResourceManager) mc.getResourceManager();
            ReloadableResourceManagerAccessor acc = (ReloadableResourceManagerAccessor) rm;
            CloseableResourceManager old = acc.archud$resources();
            acc.archud$setResources(new MultiPackResourceManager(PackType.CLIENT_RESOURCES, repo.openAllSelected()));
            old.close();
            mc.getLanguageManager().onResourceManagerReload(rm);
            ArcHud.LOGGER.info("Arc HUD: language packs applied in {} ms (fast)", Util.getMillis() - t);
            return true;
        } catch (Throwable e) {
            ArcHud.LOGGER.warn("Arc HUD: fast pack swap failed, doing a full reload", e);
            return false;
        }
    }
}
