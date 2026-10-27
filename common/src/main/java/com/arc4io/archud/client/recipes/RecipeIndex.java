package com.arc4io.archud.client.recipes;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Все рецепты, которые сервер прислал клиенту (ванильные и любых модов — верстаки, печи, кузнечный стол,
 * камнерез и собственные типы модов), разобранные в единый вид: входы, выходы и подпись.
 * Входы и выходы модовых рецептов, которые не отдают их через стандартные методы, ищутся по полям рецепта.
 */
public final class RecipeIndex {
    /** Рецепт в общем виде. alternatives: каждая ячейка — список вариантов (теги, «любая доска»…). */
    public record Entry(ResourceLocation id, ResourceLocation type, Recipe<?> recipe, List<List<ItemStack>> inputs, int gridW,
                        List<List<ItemStack>> outputs, String note, ItemStack catalyst) {}

    /** Категория — тип рецепта или особый раздел (топливо, зелья). */
    public record Category(ResourceLocation id, ItemStack icon) {}

    private static RecipeManager manager;
    private static int managerSize = -1;
    private static final Map<Item, List<Entry>> BY_OUTPUT = new HashMap<>();
    private static final Map<Item, List<Entry>> BY_INPUT = new HashMap<>();
    private static final Map<ResourceLocation, ItemStack> TYPE_ICON = new LinkedHashMap<>();
    private static final Map<Class<?>, List<Field>> FIELDS = new IdentityHashMap<>();
    private static List<ItemStack> allItems = List.of();

    public static final ResourceLocation FUEL = ArcHud.id("fuel");

    private RecipeIndex() {}

    public static void ensure() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RecipeManager rm = mc.level.getRecipeManager();
        Collection<Recipe<?>> recipes = rm.getRecipes();
        if (rm == manager && recipes.size() == managerSize) return;
        manager = rm;
        managerSize = recipes.size();
        BY_OUTPUT.clear();
        BY_INPUT.clear();
        TYPE_ICON.clear();
        RegistryAccess ra = mc.level.registryAccess();
        long t0 = System.nanoTime();
        int ok = 0;
        for (Recipe<?> r : recipes) {
            try {
                Entry e = build(r, ra);
                if (e == null) continue;
                ok++;
                TYPE_ICON.putIfAbsent(e.type(), e.catalyst());
                Set<Item> outs = new LinkedHashSet<>();
                for (List<ItemStack> alt : e.outputs()) for (ItemStack s : alt) outs.add(s.getItem());
                for (Item i : outs) BY_OUTPUT.computeIfAbsent(i, k -> new ArrayList<>()).add(e);
                Set<Item> ins = new LinkedHashSet<>();
                for (List<ItemStack> alt : e.inputs()) for (ItemStack s : alt) ins.add(s.getItem());
                for (Item i : ins) BY_INPUT.computeIfAbsent(i, k -> new ArrayList<>()).add(e);
            } catch (Throwable t) {
                // рецепт мода, который не умеет показываться без своего мира — пропускаем
            }
        }
        rebuildItems(mc);
        ArcHud.LOGGER.info("Arc HUD: recipe index — {} of {} recipes, {} items in {} ms", ok, recipes.size(), allItems.size(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static void rebuildItems(Minecraft mc) {
        List<ItemStack> list = new ArrayList<>();
        try {
            if (mc.player != null) {
                CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),
                        mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks(), mc.level.registryAccess());
                for (ItemStack s : CreativeModeTabs.searchTab().getDisplayItems()) list.add(s);
            }
        } catch (Throwable ignored) {
        }
        if (list.isEmpty()) {
            for (Item i : BuiltInRegistries.ITEM) if (i != Items.AIR) list.add(new ItemStack(i));
        }
        allItems = list;
    }

    public static List<ItemStack> items() {
        ensure();
        return allItems;
    }

    // ------------------------------------------------------------------ разбор рецепта

    private static List<ItemStack> alts(Ingredient ing) {
        List<ItemStack> l = new ArrayList<>();
        for (ItemStack s : ing.getItems()) if (!s.isEmpty()) l.add(s);
        return l;
    }

    private static List<Field> fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                List<Field> own = new ArrayList<>();
                for (Field f : x.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        own.add(f);
                    } catch (Throwable ignored) {
                    }
                }
                out.addAll(0, own);
            }
            return out;
        });
    }

    private static Entry build(Recipe<?> r, RegistryAccess ra) throws Exception {
        if (r.isSpecial()) return null; // рецепты-«процедуры» (окраска брони, копирование карт…) без предметов
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        if (type == null) type = new ResourceLocation("unknown", "unknown");
        List<List<ItemStack>> inputs = new ArrayList<>();
        int gridW = 0;
        NonNullList<Ingredient> ings = r.getIngredients();
        if (r instanceof ShapedRecipe sr) {
            gridW = sr.getWidth();
            for (Ingredient i : ings) inputs.add(alts(i));
        } else if (!ings.isEmpty()) {
            for (Ingredient i : ings) if (!i.isEmpty()) inputs.add(alts(i));
        }
        List<List<ItemStack>> outputs = new ArrayList<>();
        ItemStack result = ItemStack.EMPTY;
        try {
            result = r.getResultItem(ra);
        } catch (Throwable ignored) {
        }
        if (result != null && !result.isEmpty()) outputs.add(List.of(result));
        // модовые рецепты: входы/выходы в собственных полях
        if (inputs.isEmpty() || outputs.isEmpty()) {
            for (Field f : fields(r.getClass())) {
                Object v = f.get(r);
                if (v == null) continue;
                if (inputs.isEmpty() || !(r instanceof ShapedRecipe)) {
                    if (v instanceof Ingredient ing && !ing.isEmpty() && ings.isEmpty()) inputs.add(alts(ing));
                }
                if (v instanceof ItemStack st && !st.isEmpty() && outputs.stream().noneMatch(o -> ItemStack.matches(o.get(0), st))) {
                    if (outputs.isEmpty() || !inputs.isEmpty()) outputs.add(List.of(st));
                }
                if (v instanceof Collection<?> col && !col.isEmpty()) {
                    Object first = col.iterator().next();
                    if (first instanceof Ingredient && ings.isEmpty()) {
                        for (Object o : col) if (o instanceof Ingredient ing && !ing.isEmpty()) inputs.add(alts(ing));
                    } else if (first instanceof ItemStack && outputs.isEmpty()) {
                        for (Object o : col) if (o instanceof ItemStack st && !st.isEmpty()) outputs.add(List.of(st));
                    }
                }
            }
        }
        inputs.removeIf(List::isEmpty);
        if (r instanceof ShapedRecipe) {
            // в форменном рецепте пустые клетки важны — восстанавливаем сетку
            inputs = new ArrayList<>();
            for (Ingredient i : ings) inputs.add(alts(i));
        }
        if (outputs.isEmpty() && inputs.isEmpty()) return null;
        String note = "";
        if (r instanceof AbstractCookingRecipe c) {
            note = net.minecraft.network.chat.Component.translatable("archud.recipes.cook", String.format(java.util.Locale.ROOT, "%.1f", c.getCookingTime() / 20F), String.format(java.util.Locale.ROOT, "%.1f", c.getExperience())).getString();
        }
        ItemStack cat = ItemStack.EMPTY;
        try {
            cat = r.getToastSymbol();
        } catch (Throwable ignored) {
        }
        if (cat == null || cat.isEmpty() || (cat.is(Items.CRAFTING_TABLE) && !type.getPath().equals("crafting"))) {
            // иконка категории: блок с именем типа рецепта (create:mixing → нет; minecraft:smithing → кузнечный стол)
            Item byName = BuiltInRegistries.ITEM.get(type);
            if (byName != Items.AIR) cat = new ItemStack(byName);
            else if (type.getPath().equals("smithing")) cat = new ItemStack(Items.SMITHING_TABLE);
            else if (type.getPath().equals("stonecutting")) cat = new ItemStack(Items.STONECUTTER);
            else if (cat == null || cat.isEmpty()) cat = outputs.isEmpty() ? new ItemStack(Items.BOOK) : outputs.get(0).get(0);
        }
        return new Entry(r.getId(), type, r, inputs, gridW, outputs, note, cat);
    }

    // ------------------------------------------------------------------ запросы

    public static Map<ResourceLocation, List<Entry>> recipesFor(ItemStack stack) {
        ensure();
        return group(filter(BY_OUTPUT.getOrDefault(stack.getItem(), List.of()), stack, true), stack, false);
    }

    public static Map<ResourceLocation, List<Entry>> usesOf(ItemStack stack) {
        ensure();
        return group(filter(BY_INPUT.getOrDefault(stack.getItem(), List.of()), stack, false), stack, true);
    }

    /** Предметы с NBT (зачарованные книги, зелья) — только рецепты именно с ними. */
    private static List<Entry> filter(List<Entry> list, ItemStack stack, boolean output) {
        if (!stack.hasTag()) return list;
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) {
            boolean match = false;
            for (List<ItemStack> alt : output ? e.outputs() : e.inputs()) {
                for (ItemStack s : alt) {
                    if (s.getItem() == stack.getItem() && (!s.hasTag() || ItemStack.isSameItemSameTags(s, stack))) match = true;
                }
            }
            if (match) out.add(e);
        }
        return out.isEmpty() ? list : out;
    }

    private static Map<ResourceLocation, List<Entry>> group(List<Entry> list, ItemStack stack, boolean uses) {
        Map<ResourceLocation, List<Entry>> map = new LinkedHashMap<>();
        // сначала — верстак, потом печи и остальное в порядке появления типов
        for (Entry e : list) if (e.type().getPath().equals("crafting")) map.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
        for (Entry e : list) map.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
        for (List<Entry> l : map.values()) {
            List<Entry> dedup = new ArrayList<>(new LinkedHashSet<>(l));
            l.clear();
            l.addAll(dedup);
        }
        if (uses) {
            int burn = burnTime(stack);
            if (burn > 0) {
                Entry fuel = new Entry(FUEL, FUEL, null, List.of(List.of(stack.copyWithCount(1))), 0, List.of(),
                        net.minecraft.network.chat.Component.translatable("archud.recipes.burn", String.format(java.util.Locale.ROOT, "%.1f", burn / 20F), String.format(java.util.Locale.ROOT, "%.1f", burn / 200F)).getString(), new ItemStack(Items.FURNACE));
                map.put(FUEL, new ArrayList<>(List.of(fuel)));
            }
        }
        return map;
    }

    public static int burnTime(ItemStack stack) {
        try {
            Integer v = AbstractFurnaceBlockEntity.getFuel().get(stack.getItem());
            if (v != null && v > 0) return v;
        } catch (Throwable ignored) {
        }
        return com.arc4io.archud.client.ArcClient.platform().burnTime(stack);
    }

    public static ItemStack icon(ResourceLocation type) {
        if (type.equals(FUEL)) return new ItemStack(Items.FURNACE);
        ItemStack s = TYPE_ICON.get(type);
        return s == null ? new ItemStack(Items.BOOK) : s;
    }

    public static boolean hasRecipes(ItemStack s) {
        ensure();
        return BY_OUTPUT.containsKey(s.getItem());
    }

    /** Список всех рецептов для поиска «эта ячейка — что угодно из тега». */
    public static void invalidate() {
        manager = null;
        managerSize = -1;
    }
}
