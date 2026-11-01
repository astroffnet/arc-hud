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
                        List<List<ItemStack>> outputs, String note, ItemStack catalyst, RecipeCard card) {
        public Entry(ResourceLocation id, ResourceLocation type, Recipe<?> recipe, List<List<ItemStack>> inputs, int gridW,
                     List<List<ItemStack>> outputs, String note, ItemStack catalyst) {
            this(id, type, recipe, inputs, gridW, outputs, note, catalyst, null);
        }
    }

    /** Категория — тип рецепта или особый раздел (топливо, зелья). */
    public record Category(ResourceLocation id, ItemStack icon) {}

    private static RecipeManager manager;
    private static int managerSize = -1;
    private static final Map<Item, List<Entry>> BY_OUTPUT = new HashMap<>();
    private static final Map<Item, List<Entry>> BY_INPUT = new HashMap<>();
    private static final Map<ResourceLocation, ItemStack> TYPE_ICON = new LinkedHashMap<>();
    private static final Map<ResourceLocation, List<Entry>> BY_TYPE = new LinkedHashMap<>();
    /** Категории модов (через API JEI): название, иконка, станки-катализаторы. */
    public static final Map<ResourceLocation, net.minecraft.network.chat.Component> TITLES = new HashMap<>();
    public static final Map<ResourceLocation, java.util.function.BiConsumer<net.minecraft.client.gui.GuiGraphics, int[]>> ICON_DRAWERS = new HashMap<>();
    public static final Map<ResourceLocation, List<ItemStack>> CATALYSTS = new LinkedHashMap<>();
    private static final Map<Class<?>, List<Field>> FIELDS = new IdentityHashMap<>();
    private static List<ItemStack> allItems = List.of();
    /** Добавленные/скрытые плагинами JEI предметы списка и скрытые рецепты/категории. */
    private static final List<ItemStack> EXTRA_ITEMS = new ArrayList<>();
    private static final List<ItemStack> HIDDEN_ITEMS = new ArrayList<>();
    private static final Set<Entry> HIDDEN = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<ResourceLocation> HIDDEN_TYPES = new java.util.HashSet<>();
    /** Рецепты «по запросу» (динамические плагины JEI): предмет, uses → рецепты. */
    public static java.util.function.BiFunction<ItemStack, Boolean, List<Entry>> dynamic;

    public static final ResourceLocation FUEL = ArcHud.id("fuel");
    public static final ResourceLocation BREWING = ArcHud.id("brewing");
    public static final ResourceLocation ANVIL = new ResourceLocation("anvil");
    public static final ResourceLocation COMPOST = new ResourceLocation("compostable");
    public static final ResourceLocation GRINDSTONE = new ResourceLocation("grindstone");
    public static final ResourceLocation CRAFTING = new ResourceLocation("crafting");
    public static final ResourceLocation SMITHING = new ResourceLocation("smithing");

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
        BY_TYPE.clear();
        TYPE_ICON.clear();
        TITLES.clear();
        ICON_DRAWERS.clear();
        CATALYSTS.clear();
        EXTRA_ITEMS.clear();
        HIDDEN_ITEMS.clear();
        HIDDEN.clear();
        HIDDEN_TYPES.clear();
        dynamic = null;
        vanillaCatalysts();
        RegistryAccess ra = mc.level.registryAccess();
        long t0 = System.nanoTime();
        int ok = 0;
        for (Recipe<?> r : recipes) {
            try {
                Entry e = build(r, ra);
                if (e == null) continue;
                ok++;
                index(e);
            } catch (Throwable t) {
                // рецепт мода, который не умеет показываться без своего мира — пропускаем
            }
        }
        try {
            ok += addBrewing();
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: cannot read brewing recipes", t);
        }
        rebuildItems(mc);
        // то, что JEI показывает «из коробки»: наковальня, компостер, всё топливо, особые крафты, отделка брони
        try {
            ok += VanillaRecipes.addAll(recipes, ra);
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: built-in recipe categories failed", t);
        }
        // плагины модов, написанные для JEI (если самого JEI нет — их понимает Arc HUD)
        int jei = com.arc4io.archud.client.jei.JeiGate.load();
        if (jei > 0) ok += jei;
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

    private static boolean sameStack(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && (!b.hasTag() || ItemStack.isSameItemSameTags(a, b));
    }

    /** Плагин JEI добавил предметы в список (книги всех уровней, заполненные предметы модов…). */
    public static void addItems(Collection<ItemStack> stacks) {
        List<ItemStack> list = new ArrayList<>(allItems);
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            boolean dup = false;
            for (ItemStack e : list) if (ItemStack.isSameItemSameTags(e, s)) {
                dup = true;
                break;
            }
            if (!dup) {
                list.add(s.copyWithCount(1));
                EXTRA_ITEMS.add(s);
            }
        }
        allItems = list;
    }

    /** Плагин JEI убрал предметы из списка. */
    public static void removeItems(Collection<ItemStack> stacks) {
        List<ItemStack> list = new ArrayList<>(allItems);
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            list.removeIf(e -> ItemStack.isSameItemSameTags(e, s));
            HIDDEN_ITEMS.add(s);
        }
        allItems = list;
    }

    public static void hide(Collection<Entry> entries, boolean hidden) {
        if (hidden) HIDDEN.addAll(entries);
        else entries.forEach(HIDDEN::remove);
    }

    public static void hideType(ResourceLocation type, boolean hidden) {
        if (hidden) HIDDEN_TYPES.add(type);
        else HIDDEN_TYPES.remove(type);
    }

    private static boolean visible(Entry e) {
        return !HIDDEN.contains(e) && !HIDDEN_TYPES.contains(e.type());
    }

    public static List<ItemStack> items() {
        ensure();
        return allItems;
    }

    // ------------------------------------------------------------------ зелья (варочная стойка)

    private static Object unwrap(Object o) {
        if (o instanceof net.minecraft.core.Holder<?> h) return h.value();
        if (o instanceof java.util.function.Supplier<?> sp) return sp.get();
        return o;
    }

    /** Рецепт только в списке категории (без поиска по предметам — например, всё топливо для вкладки печи). */
    static void addTypeOnly(Entry e) {
        BY_TYPE.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
        TYPE_ICON.putIfAbsent(e.type(), e.catalyst());
    }

    static List<ItemStack> alternatives(Ingredient ing) {
        return alts(ing);
    }

    static List<Field> fieldsOf(Class<?> c) {
        return fields(c);
    }

    /** Рецепт из внешнего источника (плагин JEI, страница-подсказка). */
    public static void addExternal(Entry e) {
        index(e);
    }

    /** Категории, которые мод показывает сам (через API JEI): обычный разбор рецептов этого типа больше не нужен. */
    public static void replaceGenericType(ResourceLocation type) {
        List<Entry> list = BY_TYPE.get(type);
        if (list == null) return;
        List<Entry> generic = new ArrayList<>();
        for (Entry e : list) if (e.card() == null) generic.add(e);
        if (generic.size() == list.size()) return; // своих карточек мод не дал — оставляем наши
        list.removeAll(generic);
        Set<Entry> drop = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        drop.addAll(generic);
        for (List<Entry> l : BY_OUTPUT.values()) l.removeIf(drop::contains);
        for (List<Entry> l : BY_INPUT.values()) l.removeIf(drop::contains);
    }

    public static List<Entry> entriesOf(ResourceLocation type) {
        ensure();
        List<Entry> l = BY_TYPE.getOrDefault(type, List.of());
        if (HIDDEN.isEmpty() && HIDDEN_TYPES.isEmpty()) return l;
        List<Entry> out = new ArrayList<>();
        for (Entry e : l) if (visible(e)) out.add(e);
        return out;
    }

    /** Все категории, где есть рецепты (для API JEI). */
    public static Set<ResourceLocation> types() {
        ensure();
        return BY_TYPE.keySet();
    }

    /** Убрать наш общий разбор рецепта с этим id (его показывает расширение мода) и поставить новый. */
    public static void replace(ResourceLocation id, Entry with) {
        List<Entry> l = BY_TYPE.get(with.type());
        if (l != null) {
            Set<Entry> drop = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (Entry e : l) if (e.id().equals(id) && e.card() == null) drop.add(e);
            if (!drop.isEmpty()) {
                l.removeAll(drop);
                for (List<Entry> x : BY_OUTPUT.values()) x.removeIf(drop::contains);
                for (List<Entry> x : BY_INPUT.values()) x.removeIf(drop::contains);
            }
        }
        index(with);
    }

    private static void vanillaCatalysts() {
        CATALYSTS.put(new ResourceLocation("crafting"), new ArrayList<>(List.of(new ItemStack(Items.CRAFTING_TABLE))));
        CATALYSTS.put(new ResourceLocation("smelting"), new ArrayList<>(List.of(new ItemStack(Items.FURNACE))));
        CATALYSTS.put(new ResourceLocation("blasting"), new ArrayList<>(List.of(new ItemStack(Items.BLAST_FURNACE))));
        CATALYSTS.put(new ResourceLocation("smoking"), new ArrayList<>(List.of(new ItemStack(Items.SMOKER))));
        CATALYSTS.put(new ResourceLocation("campfire_cooking"), new ArrayList<>(List.of(new ItemStack(Items.CAMPFIRE), new ItemStack(Items.SOUL_CAMPFIRE))));
        CATALYSTS.put(new ResourceLocation("stonecutting"), new ArrayList<>(List.of(new ItemStack(Items.STONECUTTER))));
        CATALYSTS.put(new ResourceLocation("smithing"), new ArrayList<>(List.of(new ItemStack(Items.SMITHING_TABLE))));
        CATALYSTS.put(BREWING, new ArrayList<>(List.of(new ItemStack(Items.BREWING_STAND))));
        CATALYSTS.put(FUEL, new ArrayList<>(List.of(new ItemStack(Items.FURNACE), new ItemStack(Items.BLAST_FURNACE), new ItemStack(Items.SMOKER))));
        CATALYSTS.put(ANVIL, new ArrayList<>(List.of(new ItemStack(Items.ANVIL), new ItemStack(Items.CHIPPED_ANVIL), new ItemStack(Items.DAMAGED_ANVIL))));
        CATALYSTS.put(COMPOST, new ArrayList<>(List.of(new ItemStack(Items.COMPOSTER))));
        CATALYSTS.put(GRINDSTONE, new ArrayList<>(List.of(new ItemStack(Items.GRINDSTONE))));
    }

    public static List<ItemStack> catalysts(ResourceLocation type) {
        return CATALYSTS.getOrDefault(type, List.of());
    }

    static void index(Entry e) {
        BY_TYPE.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
        TYPE_ICON.putIfAbsent(e.type(), e.catalyst());
        Set<Item> outs = new LinkedHashSet<>();
        for (List<ItemStack> alt : e.outputs()) for (ItemStack st : alt) outs.add(st.getItem());
        for (Item i : outs) BY_OUTPUT.computeIfAbsent(i, k -> new ArrayList<>()).add(e);
        Set<Item> ins = new LinkedHashSet<>();
        for (List<ItemStack> alt : e.inputs()) for (ItemStack st : alt) ins.add(st.getItem());
        for (Item i : ins) BY_INPUT.computeIfAbsent(i, k -> new ArrayList<>()).add(e);
    }

    private static ItemStack potion(Item container, net.minecraft.world.item.alchemy.Potion p) {
        return net.minecraft.world.item.alchemy.PotionUtils.setPotion(new ItemStack(container), p);
    }

    private static int brewCounter;

    private static void brew(ItemStack in, List<ItemStack> ingredient, ItemStack out) {
        if (in.isEmpty() || out.isEmpty() || ingredient.isEmpty()) return;
        Entry e = new Entry(new ResourceLocation(ArcHud.MODID, "brewing/" + (brewCounter++)), BREWING, null,
                List.of(List.of(in), ingredient), 0, List.of(List.of(out)),
                net.minecraft.network.chat.Component.translatable("archud.recipes.brew_note").getString(), new ItemStack(Items.BREWING_STAND));
        index(e);
    }

    /** Ванильные смеси зелий (и добавленные модами в тот же список), смены колбы и рецепты Forge BrewingRecipeRegistry. */
    private static int addBrewing() throws Exception {
        brewCounter = 0;
        Class<?> pb = net.minecraft.world.item.alchemy.PotionBrewing.class;
        List<Object[]> potionMixes = new ArrayList<>(), containerMixes = new ArrayList<>();
        for (Field f : pb.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object v = f.get(null);
            if (!(v instanceof List<?> list)) continue;
            for (Object mix : list) {
                if (mix == null || mix instanceof Ingredient) continue;
                Object from = null, to = null;
                Ingredient ing = null;
                for (Field mf : fields(mix.getClass())) {
                    Object x = mf.get(mix);
                    if (x instanceof Ingredient i) ing = i;
                    else if (from == null) from = unwrap(x);
                    else if (to == null) to = unwrap(x);
                }
                if (ing == null || from == null || to == null) continue;
                if (from instanceof net.minecraft.world.item.alchemy.Potion) potionMixes.add(new Object[]{from, ing, to});
                else if (from instanceof Item) containerMixes.add(new Object[]{from, ing, to});
            }
        }
        Item[] containers = {Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION};
        for (Object[] m : potionMixes) {
            var from = (net.minecraft.world.item.alchemy.Potion) m[0];
            var to = (net.minecraft.world.item.alchemy.Potion) m[2];
            for (Item c : containers) brew(potion(c, from), alts((Ingredient) m[1]), potion(c, to));
        }
        for (Object[] m : containerMixes) {
            for (var p : BuiltInRegistries.POTION) {
                if (p == net.minecraft.world.item.alchemy.Potions.EMPTY) continue;
                brew(potion((Item) m[0], p), alts((Ingredient) m[1]), potion((Item) m[2], p));
            }
        }
        // Forge: рецепты модов через BrewingRecipeRegistry (класс BrewingRecipe: вход, ингредиент, результат)
        try {
            Class<?> reg = Class.forName("net.minecraftforge.common.brewing.BrewingRecipeRegistry");
            Object list = reg.getMethod("getRecipes").invoke(null);
            if (list instanceof List<?> l) {
                for (Object r : l) {
                    if (!r.getClass().getName().equals("net.minecraftforge.common.brewing.BrewingRecipe")) continue;
                    Ingredient in = (Ingredient) r.getClass().getMethod("getInput").invoke(r);
                    Ingredient ing = (Ingredient) r.getClass().getMethod("getIngredient").invoke(r);
                    ItemStack out = (ItemStack) r.getClass().getMethod("getOutput").invoke(r);
                    for (ItemStack st : alts(in)) brew(st, alts(ing), out);
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Fabric
        }
        return brewCounter;
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

    /** Разобрать рецепт в общий вид (для рецептов, которые плагины JEI добавили в стандартные категории). */
    public static Entry fromRecipe(Recipe<?> r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        try {
            return build(r, mc.level.registryAccess());
        } catch (Throwable t) {
            return null;
        }
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
        return group(filter(withDynamic(BY_OUTPUT.getOrDefault(stack.getItem(), List.of()), stack, false), stack, true), stack, false);
    }

    public static Map<ResourceLocation, List<Entry>> usesOf(ItemStack stack) {
        ensure();
        return group(filter(withDynamic(BY_INPUT.getOrDefault(stack.getItem(), List.of()), stack, true), stack, false), stack, true);
    }

    private static List<Entry> withDynamic(List<Entry> base, ItemStack stack, boolean uses) {
        List<Entry> list = base;
        if (dynamic != null) {
            try {
                List<Entry> more = dynamic.apply(stack, uses);
                if (more != null && !more.isEmpty()) {
                    list = new ArrayList<>(base);
                    list.addAll(more);
                }
            } catch (Throwable t) {
                ArcHud.LOGGER.debug("Arc HUD: dynamic recipes failed", t);
            }
        }
        if (HIDDEN.isEmpty() && HIDDEN_TYPES.isEmpty()) return list;
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (visible(e)) out.add(e);
        return out;
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
            // предмет — станок категории (верстак, печь, станки модов): показать все её рецепты, как в JEI
            for (Map.Entry<ResourceLocation, List<ItemStack>> c : CATALYSTS.entrySet()) {
                boolean is = false;
                for (ItemStack cs : c.getValue()) is |= cs.getItem() == stack.getItem();
                List<Entry> all = BY_TYPE.get(c.getKey());
                if (is && all != null && !all.isEmpty() && !map.containsKey(c.getKey())) map.put(c.getKey(), new ArrayList<>(all));
            }
            // топливо — в конце, после рецептов и станков
            List<Entry> f = map.remove(FUEL);
            if (f != null) map.put(FUEL, f);
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
        List<ItemStack> cat = CATALYSTS.get(type);
        if (cat != null && !cat.isEmpty()) return cat.get(0);
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
