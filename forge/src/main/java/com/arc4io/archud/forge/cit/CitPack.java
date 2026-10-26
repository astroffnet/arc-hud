package com.arc4io.archud.forge.cit;

import com.arc4io.archud.ArcHud;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TieredItem;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Виртуальный ресурспак, который превращает CIT-пакеты OptiFine (assets/minecraft/optifine/cit и mcpatcher/cit)
 * в обычные ресурсы игры: текстуры попадают в textures/item/archud_cit/ (атлас блоков их подхватывает),
 * для каждого правила создаётся модель models/item/archud_cit/N.json. Сами правила — в {@link CitManager}.
 */
public final class CitPack implements PackResources {
    public static final String ID = "archud_cit";
    private static final String[] ROOTS = {"optifine/cit", "mcpatcher/cit"};

    private final Map<String, byte[]> files = new LinkedHashMap<>(); // путь от assets/minecraft/ → данные
    private boolean scanned;

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        return null;
    }

    private synchronized void scan() {
        if (scanned) return;
        scanned = true;
        List<CitRule> rules = new ArrayList<>();
        try {
            if (!com.arc4io.archud.config.ArcConfig.get().citSupport) return;
            Map<String, IoSupplier<InputStream>> all = new HashMap<>(); // все файлы cit-папок, верхние пакеты перекрывают нижние
            List<Pack> packs = new ArrayList<>(Minecraft.getInstance().getResourcePackRepository().getSelectedPacks());
            List<PackResources> opened = new ArrayList<>();
            try {
                for (Pack pack : packs) {
                    if (pack.getId().equals(ID)) continue;
                    PackResources res = pack.open();
                    opened.add(res);
                    for (String root : ROOTS) {
                        res.listResources(PackType.CLIENT_RESOURCES, "minecraft", root, (loc, sup) -> all.put(loc.getPath(), sup));
                    }
                }
                List<String> props = new ArrayList<>();
                for (String path : all.keySet()) if (path.endsWith(".properties")) props.add(path);
                props.sort(Comparator.naturalOrder());
                int n = 0;
                for (String path : props) {
                    Properties p = new Properties();
                    try (InputStream in = all.get(path).get(); InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        p.load(r);
                    } catch (Exception e) {
                        continue;
                    }
                    String type = p.getProperty("type", "item").trim();
                    if (!type.equals("item")) continue;
                    CitRule rule = new CitRule(path, p);
                    if (!rule.valid()) continue;
                    String dir = path.substring(0, path.lastIndexOf('/') + 1);
                    String base = path.substring(dir.length(), path.length() - ".properties".length());
                    String name = "archud_cit/" + n;
                    String modelPath = p.getProperty("model");
                    boolean ok;
                    if (modelPath != null) ok = exportModel(all, dir, modelPath.trim(), name);
                    else {
                        String tex = p.getProperty("texture", base).trim();
                        ok = exportTexture(all, dir, tex, name) != null;
                        if (ok) files.put("models/item/" + name + ".json", generated(rule.items.get(0), name).getBytes(StandardCharsets.UTF_8));
                    }
                    if (!ok) continue;
                    rule.model = new ResourceLocation("minecraft", "item/" + name);
                    rules.add(rule);
                    n++;
                }
            } finally {
                for (PackResources r : opened) {
                    try {
                        r.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!rules.isEmpty()) ArcHud.LOGGER.info("Arc HUD: loaded {} CIT rules", rules.size());
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: CIT scan failed", t);
        } finally {
            CitManager.publish(rules);
        }
    }

    private static String generated(Item item, String tex) {
        String parent = item instanceof FishingRodItem ? "minecraft:item/handheld_rod" : item instanceof TieredItem ? "minecraft:item/handheld" : "minecraft:item/generated";
        return "{\"parent\":\"" + parent + "\",\"textures\":{\"layer0\":\"minecraft:item/" + tex + "\"}}";
    }

    /** Путь к файлу по правилам OptiFine: относительно папки .properties, «./», «~/», полный путь. */
    private static String resolve(Map<String, IoSupplier<InputStream>> all, String dir, String ref, String ext) {
        String r = ref.replace('\\', '/');
        if (r.startsWith("minecraft:")) r = r.substring(10);
        if (r.startsWith("./")) r = dir + r.substring(2);
        else if (r.startsWith("~/")) r = "optifine/" + r.substring(2);
        String[] tries = r.contains("/") ? new String[]{r, dir + r} : new String[]{dir + r, r};
        for (String t : tries) {
            if (all.containsKey(t)) return t;
            if (all.containsKey(t + ext)) return t + ext;
        }
        return null;
    }

    /** Скопировать текстуру (+ .mcmeta анимации) в textures/item/NAME.png; null — не найдена. */
    private String exportTexture(Map<String, IoSupplier<InputStream>> all, String dir, String ref, String name) {
        String path = resolve(all, dir, ref, ".png");
        if (path == null) return null;
        byte[] data = read(all.get(path));
        if (data == null) return null;
        files.put("textures/item/" + name + ".png", data);
        IoSupplier<InputStream> meta = all.get(path + ".mcmeta");
        if (meta != null) {
            byte[] m = read(meta);
            if (m != null) files.put("textures/item/" + name + ".png.mcmeta", m);
        }
        return name;
    }

    /** Скопировать модель, переписав ссылки на текстуры из cit-папок. */
    private boolean exportModel(Map<String, IoSupplier<InputStream>> all, String dir, String ref, String name) {
        String path = resolve(all, dir, ref, ".json");
        if (path == null) return false;
        byte[] data = read(all.get(path));
        if (data == null) return false;
        try {
            JsonObject json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            String mdir = path.substring(0, path.lastIndexOf('/') + 1);
            if (json.has("textures")) {
                JsonObject tex = json.getAsJsonObject("textures");
                int k = 0;
                for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
                    String v = e.getValue().getAsString();
                    if (v.startsWith("#")) continue;
                    String p = resolve(all, mdir, v, ".png");
                    if (p == null) continue; // обычная текстура игры — оставляем
                    String tn = name + "_" + (k++);
                    if (exportTexture(all, mdir, v, tn) != null) e.setValue(new com.google.gson.JsonPrimitive("minecraft:item/" + tn));
                }
            }
            if (json.has("parent")) {
                String parent = json.get("parent").getAsString();
                String pp = resolve(all, mdir, parent, ".json");
                if (pp != null) json.addProperty("parent", "minecraft:item/generated"); // вложенные модели из cit — упрощаем
            }
            files.put("models/item/" + name + ".json", json.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] read(IoSupplier<InputStream> sup) {
        try (InputStream in = sup.get()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation loc) {
        if (type != PackType.CLIENT_RESOURCES || !loc.getNamespace().equals("minecraft")) return null;
        if (!loc.getPath().contains("archud_cit/")) return null;
        scan();
        byte[] data = files.get(loc.getPath());
        return data == null ? null : () -> new ByteArrayInputStream(data);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out) {
        if (type != PackType.CLIENT_RESOURCES || !namespace.equals("minecraft")) return;
        if (!(path.startsWith("textures") || path.startsWith("models"))) return;
        scan();
        String prefix = path.endsWith("/") ? path : path + "/";
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            byte[] data = e.getValue();
            out.accept(new ResourceLocation("minecraft", e.getKey()), () -> new ByteArrayInputStream(data));
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of("minecraft") : Set.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        if (serializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(Component.translatable("archud.cit.pack_desc"), 15);
        }
        return null;
    }

    @Override
    public String packId() {
        return ID;
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public void close() {}
}
