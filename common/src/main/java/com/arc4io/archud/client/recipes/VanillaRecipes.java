package com.arc4io.archud.client.recipes;

import com.arc4io.archud.ArcHud;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SuspiciousStewItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.ArmorDyeRecipe;
import net.minecraft.world.item.crafting.BookCloningRecipe;
import net.minecraft.world.item.crafting.DecoratedPotRecipe;
import net.minecraft.world.item.crafting.FireworkRocketRecipe;
import net.minecraft.world.item.crafting.FireworkStarRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.MapCloningRecipe;
import net.minecraft.world.item.crafting.MapExtendingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShulkerBoxColoring;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.SuspiciousStewRecipe;
import net.minecraft.world.item.crafting.TippedArrowRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SuspiciousEffectHolder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Категории, которые JEI показывает «из коробки» (его встроенный плагин): ремонт и зачарование на наковальне,
 * компостер, всё топливо, особые рецепты верстака (стрелы с зельями, окраска, рагу, карты, фейерверки, горшки)
 * и отделка брони на кузнечном столе — со всеми сочетаниями.
 */
final class VanillaRecipes {
    private VanillaRecipes() {}

    private static int n;

    static int addAll(Collection<Recipe<?>> recipes, RegistryAccess ra) {
        n = 0;
        Set<Class<?>> special = new HashSet<>();
        for (Recipe<?> r : recipes) {
            special.add(r.getClass());
            if (r instanceof SmithingTrimRecipe t) safe(() -> trim(t, ra));
        }
        List<ItemStack> items = RecipeIndex.items();
        safe(() -> anvil(items));
        safe(VanillaRecipes::compost);
        safe(() -> fuel(items));
        if (special.contains(TippedArrowRecipe.class)) safe(VanillaRecipes::tippedArrows);
        if (special.contains(ShulkerBoxColoring.class)) safe(VanillaRecipes::shulkers);
        if (special.contains(SuspiciousStewRecipe.class)) safe(VanillaRecipes::stews);
        if (special.contains(MapCloningRecipe.class)) craft("map_cloning", List.of(one(Items.FILLED_MAP), one(Items.MAP)), 0, new ItemStack(Items.FILLED_MAP, 2));
        if (special.contains(MapExtendingRecipe.class)) {
            List<List<ItemStack>> g = new ArrayList<>();
            for (int i = 0; i < 9; i++) g.add(one(i == 4 ? Items.FILLED_MAP : Items.PAPER));
            craft("map_extending", g, 3, new ItemStack(Items.FILLED_MAP));
        }
        if (special.contains(BookCloningRecipe.class)) craft("book_cloning", List.of(one(Items.WRITTEN_BOOK), one(Items.WRITABLE_BOOK)), 0, new ItemStack(Items.WRITTEN_BOOK, 2));
        if (special.contains(FireworkRocketRecipe.class)) safe(VanillaRecipes::rockets);
        if (special.contains(FireworkStarRecipe.class)) safe(VanillaRecipes::stars);
        if (special.contains(ArmorDyeRecipe.class)) safe(() -> dyedArmor(items));
        if (special.contains(DecoratedPotRecipe.class)) safe(VanillaRecipes::pots);
        return n;
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: built-in recipes part failed", t);
        }
    }

    private static List<ItemStack> one(ItemLike i) {
        return List.of(new ItemStack(i));
    }

    private static void add(RecipeIndex.Entry e) {
        RecipeIndex.index(e);
        n++;
    }

    private static void craft(String id, List<List<ItemStack>> inputs, int gridW, ItemStack out) {
        add(new RecipeIndex.Entry(ArcHud.id("special/" + id + "/" + n), RecipeIndex.CRAFTING, null, inputs, gridW, List.of(List.of(out)), "",
                new ItemStack(Items.CRAFTING_TABLE)));
    }

    // ------------------------------------------------------------------ наковальня

    private static Ingredient repairMaterial(Item item) {
        if (item instanceof TieredItem t) return t.getTier().getRepairIngredient();
        if (item instanceof ArmorItem a) return a.getMaterial().getRepairIngredient();
        if (item instanceof ElytraItem) return Ingredient.of(Items.PHANTOM_MEMBRANE);
        if (item instanceof ShieldItem) return Ingredient.of(ItemTags.PLANKS);
        return Ingredient.EMPTY;
    }

    private static ItemStack damaged(ItemStack s, float part) {
        ItemStack c = s.copyWithCount(1);
        c.setDamageValue(Math.round(c.getMaxDamage() * part));
        return c;
    }

    private static void anvil(List<ItemStack> items) {
        Set<Item> seen = new HashSet<>();
        List<Enchantment> enchants = new ArrayList<>();
        for (Enchantment e : BuiltInRegistries.ENCHANTMENT) enchants.add(e);
        for (ItemStack base : items) {
            Item item = base.getItem();
            if (base.hasTag() || !seen.add(item)) continue;
            if (base.isDamageableItem()) {
                Ingredient mat = Ingredient.EMPTY;
                try {
                    mat = repairMaterial(item);
                } catch (Throwable ignored) {
                }
                ItemStack d = damaged(base, 0.75F);
                if (mat != null && !mat.isEmpty()) {
                    List<ItemStack> m = RecipeIndex.alternatives(mat);
                    if (!m.isEmpty()) {
                        add(new RecipeIndex.Entry(ArcHud.id("anvil/repair/" + n), RecipeIndex.ANVIL, null, List.of(List.of(d), m), 0,
                                List.of(List.of(damaged(base, 0.5F))), "", new ItemStack(Items.ANVIL)));
                    }
                }
                add(new RecipeIndex.Entry(ArcHud.id("anvil/combine/" + n), RecipeIndex.ANVIL, null, List.of(List.of(d), List.of(d)), 0,
                        List.of(List.of(damaged(base, 0.38F))), "", new ItemStack(Items.ANVIL)));
            }
            // зачарование книгой: все уровни по очереди (левый слот, книга и результат меняются вместе)
            if (base.isEnchantable() && item != Items.BOOK) {
                for (Enchantment e : enchants) {
                    if (!e.canEnchant(base)) continue;
                    List<ItemStack> left = new ArrayList<>(), books = new ArrayList<>(), outs = new ArrayList<>();
                    for (int lvl = e.getMinLevel(); lvl <= e.getMaxLevel(); lvl++) {
                        left.add(base.copyWithCount(1));
                        books.add(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(e, lvl)));
                        ItemStack o = base.copyWithCount(1);
                        o.enchant(e, lvl);
                        outs.add(o);
                    }
                    if (left.isEmpty()) continue;
                    add(new RecipeIndex.Entry(ArcHud.id("anvil/book/" + n), RecipeIndex.ANVIL, null, List.of(left, books), 0, List.of(outs), "",
                            new ItemStack(Items.ANVIL)));
                }
            }
        }
    }

    // ------------------------------------------------------------------ компостер и топливо

    private static void compost() {
        for (var e : ComposterBlock.COMPOSTABLES.object2FloatEntrySet()) {
            Item item = e.getKey().asItem();
            if (item == Items.AIR) continue;
            String note = Component.translatable("archud.recipes.compost", Math.round(e.getFloatValue() * 100)).getString();
            add(new RecipeIndex.Entry(ArcHud.id("compost/" + BuiltInRegistries.ITEM.getKey(item).toString().replace(':', '/')), RecipeIndex.COMPOST,
                    null, List.of(List.of(new ItemStack(item))), 0, List.of(), note, new ItemStack(Items.COMPOSTER)));
        }
    }

    private static void fuel(List<ItemStack> items) {
        Set<Item> seen = new HashSet<>();
        for (ItemStack s : items) {
            if (!seen.add(s.getItem())) continue;
            int burn = RecipeIndex.burnTime(s);
            if (burn <= 0) continue;
            String note = Component.translatable("archud.recipes.burn", String.format(Locale.ROOT, "%.1f", burn / 20F),
                    String.format(Locale.ROOT, "%.1f", burn / 200F)).getString();
            RecipeIndex.addTypeOnly(new RecipeIndex.Entry(ArcHud.id("fuel/" + BuiltInRegistries.ITEM.getKey(s.getItem()).toString().replace(':', '/')),
                    RecipeIndex.FUEL, null, List.of(List.of(s.copyWithCount(1))), 0, List.of(), note, new ItemStack(Items.FURNACE)));
        }
    }

    // ------------------------------------------------------------------ особые рецепты верстака

    private static void tippedArrows() {
        for (Potion p : BuiltInRegistries.POTION) {
            if (p == Potions.EMPTY || p.getEffects().isEmpty()) continue;
            List<List<ItemStack>> g = new ArrayList<>();
            for (int i = 0; i < 9; i++) g.add(i == 4 ? List.of(PotionUtils.setPotion(new ItemStack(Items.LINGERING_POTION), p)) : one(Items.ARROW));
            craft("tipped_arrow", g, 3, PotionUtils.setPotion(new ItemStack(Items.TIPPED_ARROW, 8), p));
        }
    }

    private static void shulkers() {
        for (DyeColor c : DyeColor.values()) {
            craft("shulker", List.of(one(Items.SHULKER_BOX), one(DyeItem.byColor(c))), 0, new ItemStack(ShulkerBoxBlock.getBlockByColor(c)));
        }
    }

    private static void stews() {
        for (SuspiciousEffectHolder h : SuspiciousEffectHolder.getAllEffectHolders()) {
            if (!(h instanceof ItemLike il) || il.asItem() == Items.AIR) continue;
            ItemStack out = new ItemStack(Items.SUSPICIOUS_STEW);
            MobEffect effect = h.getSuspiciousEffect();
            SuspiciousStewItem.saveMobEffect(out, effect, h.getEffectDuration());
            craft("stew", List.of(one(Items.BOWL), one(Items.BROWN_MUSHROOM), one(Items.RED_MUSHROOM), one(il)), 0, out);
        }
    }

    private static void rockets() {
        for (int flight = 1; flight <= 3; flight++) {
            List<List<ItemStack>> in = new ArrayList<>();
            in.add(one(Items.PAPER));
            for (int i = 0; i < flight; i++) in.add(one(Items.GUNPOWDER));
            ItemStack out = new ItemStack(Items.FIREWORK_ROCKET, 3);
            out.getOrCreateTagElement("Fireworks").putByte("Flight", (byte) flight);
            craft("rocket", in, 0, out);
        }
    }

    private static void stars() {
        for (DyeColor c : DyeColor.values()) {
            ItemStack out = new ItemStack(Items.FIREWORK_STAR);
            var explosion = out.getOrCreateTagElement("Explosion");
            explosion.putIntArray("Colors", new int[]{c.getFireworkColor()});
            explosion.putByte("Type", (byte) 0);
            craft("star", List.of(one(Items.GUNPOWDER), one(DyeItem.byColor(c))), 0, out);
        }
    }

    private static void dyedArmor(List<ItemStack> items) {
        Set<Item> seen = new HashSet<>();
        for (ItemStack s : items) {
            if (!(s.getItem() instanceof DyeableLeatherItem) || !seen.add(s.getItem())) continue;
            for (DyeColor c : DyeColor.values()) {
                DyeItem dye = DyeItem.byColor(c);
                ItemStack out = DyeableLeatherItem.dyeArmor(s.copyWithCount(1), List.of(dye));
                craft("dye", List.of(List.of(s.copyWithCount(1)), one(dye)), 0, out);
            }
        }
    }

    private static void pots() {
        List<ItemStack> sides = RecipeIndex.alternatives(Ingredient.of(ItemTags.DECORATED_POT_INGREDIENTS));
        if (sides.isEmpty()) return;
        List<List<ItemStack>> g = new ArrayList<>();
        for (int i = 0; i < 9; i++) g.add(i == 1 || i == 3 || i == 5 || i == 7 ? sides : List.of());
        craft("pot", g, 3, new ItemStack(Items.DECORATED_POT));
    }

    // ------------------------------------------------------------------ отделка брони

    private static void trim(SmithingTrimRecipe r, RegistryAccess ra) {
        List<Ingredient> ings = new ArrayList<>();
        for (Field f : RecipeIndex.fieldsOf(r.getClass())) {
            try {
                if (f.get(r) instanceof Ingredient i) ings.add(i);
            } catch (IllegalAccessException ignored) {
            }
        }
        if (ings.size() < 3) return;
        List<ItemStack> templates = RecipeIndex.alternatives(ings.get(0));
        List<ItemStack> bases = RecipeIndex.alternatives(ings.get(1));
        List<ItemStack> adds = RecipeIndex.alternatives(ings.get(2));
        if (templates.isEmpty() || bases.isEmpty() || adds.isEmpty()) return;
        // все сочетания «броня × материал»; списки одной длины меняются синхронно
        List<ItemStack> t = new ArrayList<>(), b = new ArrayList<>(), a = new ArrayList<>(), out = new ArrayList<>();
        for (ItemStack base : bases) {
            for (ItemStack add : adds) {
                ItemStack res;
                try {
                    res = r.assemble(new SimpleContainer(templates.get(0).copy(), base.copy(), add.copy()), ra);
                } catch (Throwable e) {
                    continue;
                }
                if (res == null || res.isEmpty()) continue;
                t.add(templates.get(0));
                b.add(base);
                a.add(add);
                out.add(res);
                if (out.size() >= 400) break;
            }
            if (out.size() >= 400) break;
        }
        if (out.isEmpty()) return;
        add(new RecipeIndex.Entry(r.getId(), RecipeIndex.SMITHING, r, List.of(t, b, a), 0, List.of(out), "", new ItemStack(Items.SMITHING_TABLE)));
    }
}
