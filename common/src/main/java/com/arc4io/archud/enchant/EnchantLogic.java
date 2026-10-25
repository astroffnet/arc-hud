package com.arc4io.archud.enchant;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantmentTableBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Правила «выборочного» зачарования (общие для клиента и сервера, чтобы цена совпадала):
 *  - доступны только обычные зачарования стола (без сокровищ, проклятий и недоступных для стола);
 *  - максимальный уровень зависит от силы книжных полок (15 полок — полный уровень);
 *  - цена: каждый уровень стоит 2/3/5/8 «очков» по редкости (обычное → очень редкое).
 *    Нужно столько уровней опыта, сколько очков; списывается пятая часть (как у стола: требование больше расхода),
 *    лазурита — десятая часть (минимум 1).
 */
public final class EnchantLogic {
    public static final int MAX_POWER = 15;

    public record Option(Enchantment enchantment, int maxLevel) {}

    public record Cost(int required, int consumed, int lapis) {}

    private EnchantLogic() {}

    public static int rarityPoints(Enchantment e) {
        return switch (e.getRarity()) {
            case COMMON -> 2;
            case UNCOMMON -> 3;
            case RARE -> 5;
            case VERY_RARE -> 8;
        };
    }

    public static Cost cost(Map<Enchantment, Integer> chosen) {
        int req = 0;
        for (Map.Entry<Enchantment, Integer> e : chosen.entrySet()) {
            if (e.getValue() > 0) req += e.getValue() * rarityPoints(e.getKey());
        }
        if (req <= 0) return new Cost(0, 0, 0);
        return new Cost(req, Math.max(1, (req + 4) / 5), Math.min(64, Math.max(1, (req + 9) / 10)));
    }

    /** Сила полок вокруг стола (0..15), как у ванильного стола. */
    public static int power(Level level, BlockPos table) {
        int n = 0;
        for (BlockPos off : EnchantmentTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantmentTableBlock.isValidBookShelf(level, table, off)) n++;
        }
        return Math.min(MAX_POWER, n);
    }

    public static boolean allowedOnTable(Enchantment e) {
        return !e.isTreasureOnly() && !e.isCurse() && e.isDiscoverable();
    }

    public static boolean fits(Enchantment e, ItemStack stack) {
        if (stack.is(Items.BOOK)) return true;
        return e.category != null && e.category.canEnchant(stack.getItem());
    }

    public static int maxLevelFor(Enchantment e, int power) {
        int max = e.getMaxLevel();
        int lvl = Math.round(max * Math.min(MAX_POWER, power) / (float) MAX_POWER);
        return Math.max(e.getMinLevel(), Math.min(max, lvl));
    }

    /** Все зачарования, которые можно выбрать для предмета при данной силе полок. */
    public static List<Option> options(ItemStack stack, int power) {
        List<Option> out = new ArrayList<>();
        if (stack.isEmpty() || !stack.isEnchantable()) return out;
        for (Enchantment e : BuiltInRegistries.ENCHANTMENT) {
            if (!allowedOnTable(e) || !fits(e, stack)) continue;
            out.add(new Option(e, maxLevelFor(e, power)));
        }
        out.sort(Comparator.comparing(o -> net.minecraft.network.chat.Component.translatable(o.enchantment().getDescriptionId()).getString()));
        return out;
    }

    /** Проверка выбора. null — всё в порядке, иначе ключ перевода причины. */
    public static String validate(ItemStack stack, int power, Map<Enchantment, Integer> chosen) {
        if (stack.isEmpty() || !stack.isEnchantable()) return "archud.ench.cannot";
        List<Enchantment> picked = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> en : chosen.entrySet()) {
            int lvl = en.getValue();
            if (lvl <= 0) continue;
            Enchantment e = en.getKey();
            if (!allowedOnTable(e) || !fits(e, stack)) return "archud.ench.err_item";
            if (lvl > maxLevelFor(e, power)) return "archud.ench.err_power";
            for (Enchantment o : picked) if (!o.isCompatibleWith(e)) return "archud.ench.err_conflict";
            picked.add(e);
        }
        return picked.isEmpty() ? "archud.ench.err_empty" : null;
    }
}
