package com.arc4io.archud.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Универсальное чтение энергии из NBT предмета.
 * Подходит для модов, которые хранят заряд в тегах (IC2-подобные "charge", "Energy" и т.п.).
 */
public final class NbtEnergy {
    private static final String[] VALUE_KEYS = {"Energy", "energy", "charge", "Charge", "stored_energy", "StoredEnergy", "power", "Power"};
    private static final String[] MAX_KEYS = {"MaxEnergy", "maxEnergy", "max_energy", "maxCharge", "MaxCharge", "capacity", "Capacity"};

    private NbtEnergy() {}

    public static long[] read(ItemStack stack) {
        if (stack.isEmpty()) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        for (String key : VALUE_KEYS) {
            if (tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
                long value = (long) tag.getDouble(key);
                long max = 0;
                for (String mk : MAX_KEYS) {
                    if (tag.contains(mk, Tag.TAG_ANY_NUMERIC)) {
                        max = (long) tag.getDouble(mk);
                        break;
                    }
                }
                return new long[]{value, max};
            }
        }
        return null;
    }

    public static String format(long v) {
        if (v >= 1_000_000_000L) return String.format("%.1fG", v / 1e9);
        if (v >= 1_000_000L) return String.format("%.1fM", v / 1e6);
        if (v >= 10_000L) return String.format("%.1fk", v / 1e3);
        return Long.toString(v);
    }
}
