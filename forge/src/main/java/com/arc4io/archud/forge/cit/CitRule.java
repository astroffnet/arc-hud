package com.arc4io.archud.forge.cit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Одно правило CIT (OptiFine / MCPatcher, type=item): какие предметы и при каких условиях
 * (название, урон, количество, зачарования, NBT) получают свою модель.
 */
final class CitRule {
    final String source;
    final List<Item> items = new ArrayList<>();
    final int weight;
    ResourceLocation model; // minecraft:item/archud_cit/N
    private final int[][] damage;
    private final boolean damagePercent;
    private final int[][] stackSize;
    private final List<ResourceLocation> enchantments = new ArrayList<>();
    private final int[][] enchantLevels;
    private final List<String[]> nbt = new ArrayList<>(); // {путь, условие}

    CitRule(String source, Properties p) {
        this.source = source;
        for (String key : new String[]{"items", "matchItems"}) {
            String v = p.getProperty(key);
            if (v == null) continue;
            for (String id : v.trim().split("\\s+")) {
                if (id.isEmpty()) continue;
                ResourceLocation loc = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (loc != null && BuiltInRegistries.ITEM.containsKey(loc)) items.add(BuiltInRegistries.ITEM.get(loc));
            }
        }
        weight = parseInt(p.getProperty("weight"), 0);
        String dmg = p.getProperty("damage");
        damagePercent = dmg != null && dmg.contains("%");
        damage = ranges(dmg == null ? null : dmg.replace("%", ""));
        stackSize = ranges(p.getProperty("stackSize"));
        String ench = p.getProperty("enchantmentIDs", p.getProperty("enchantments"));
        if (ench != null) {
            for (String id : ench.trim().split("\\s+")) {
                if (id.isEmpty()) continue;
                ResourceLocation loc = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id.toLowerCase(Locale.ROOT));
                if (loc != null) enchantments.add(loc);
            }
        }
        enchantLevels = ranges(p.getProperty("enchantmentLevels"));
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("nbt.")) nbt.add(new String[]{key.substring(4), p.getProperty(key)});
        }
    }

    boolean valid() {
        return !items.isEmpty();
    }

    boolean matches(ItemStack s) {
        if (damage != null) {
            if (!s.isDamageableItem()) return false;
            int v = damagePercent ? s.getDamageValue() * 100 / Math.max(1, s.getMaxDamage()) : s.getDamageValue();
            if (!in(damage, v)) return false;
        }
        if (stackSize != null && !in(stackSize, s.getCount())) return false;
        if (!enchantments.isEmpty() || enchantLevels != null) {
            Map<Enchantment, Integer> map = EnchantmentHelper.getEnchantments(s);
            boolean any = false;
            for (Map.Entry<Enchantment, Integer> e : map.entrySet()) {
                ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(e.getKey());
                if (!enchantments.isEmpty() && !enchantments.contains(id)) continue;
                if (enchantLevels != null && !in(enchantLevels, e.getValue())) continue;
                any = true;
                break;
            }
            if (!any) return false;
        }
        for (String[] cond : nbt) if (!nbtMatches(s, cond[0], cond[1])) return false;
        return true;
    }

    private static boolean nbtMatches(ItemStack s, String path, String cond) {
        String value;
        if (path.equals("display.Name")) {
            if (!s.hasCustomHoverName()) return false;
            value = s.getHoverName().getString();
        } else {
            CompoundTag tag = s.getTag();
            if (tag == null) return false;
            Tag cur = tag;
            for (String part : path.split("\\.")) {
                if (!(cur instanceof CompoundTag c) || !c.contains(part)) return false;
                cur = c.get(part);
            }
            value = cur.getAsString();
            if (path.startsWith("display.Lore")) {
                try {
                    Component c = Component.Serializer.fromJson(value);
                    if (c != null) value = c.getString();
                } catch (Exception ignored) {
                }
            }
        }
        return text(value, cond);
    }

    static boolean text(String value, String cond) {
        if (cond.startsWith("pattern:")) return glob(cond.substring(8), false).matcher(value).matches();
        if (cond.startsWith("ipattern:")) return glob(cond.substring(9), true).matcher(value).matches();
        if (cond.startsWith("regex:")) return Pattern.compile(cond.substring(6)).matcher(value).matches();
        if (cond.startsWith("iregex:")) return Pattern.compile(cond.substring(7), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(value).matches();
        return value.equals(cond);
    }

    private static Pattern glob(String g, boolean ci) {
        StringBuilder b = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') b.append(".*");
            else if (c == '?') b.append('.');
            else b.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(b.toString(), ci ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    }

    private static int[][] ranges(String v) {
        if (v == null || v.isBlank()) return null;
        List<int[]> out = new ArrayList<>();
        for (String tok : v.trim().split("\\s+")) {
            try {
                int dash = tok.indexOf('-', 1);
                if (dash < 0) {
                    int n = Integer.parseInt(tok);
                    out.add(new int[]{n, n});
                } else {
                    String a = tok.substring(0, dash), b = tok.substring(dash + 1);
                    out.add(new int[]{a.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(a), b.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(b)});
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out.isEmpty() ? null : out.toArray(new int[0][]);
    }

    private static boolean in(int[][] r, int v) {
        for (int[] x : r) if (v >= x[0] && v <= x[1]) return true;
        return false;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
