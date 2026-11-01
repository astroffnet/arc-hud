package com.arc4io.archud.client.jei;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.recipes.ArcRecipeScreen;
import com.arc4io.archud.client.recipes.InfoCard;
import com.arc4io.archud.client.recipes.RecipeIndex;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiClickableArea;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.vanilla.brewing.IBrewingCategoryExtension;
import mezz.jei.api.recipe.category.extensions.vanilla.cooking.ICookingCategoryExtension;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.recipe.category.extensions.vanilla.smithing.ISmithingCategoryExtension;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.api.recipe.vanilla.IJeiBrewingRecipe;
import mezz.jei.api.recipe.vanilla.IJeiCompostingRecipe;
import mezz.jei.api.recipe.vanilla.IJeiFuelingRecipe;
import mezz.jei.api.recipe.vanilla.IJeiGrindstoneRecipe;
import mezz.jei.api.recipe.vanilla.IJeiIngredientInfoRecipe;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IIngredientAliasRegistration;
import mezz.jei.api.registration.IModInfoRegistration;
import mezz.jei.api.registration.IModIngredientRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.IRuntimeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Мост для плагинов модов, написанных для JEI: Arc HUD сам выполняет их регистрацию (категории, рецепты, станки,
 * подсказки «информация», расширения встроенных категорий, динамические рецепты, декораторы, области GUI,
 * кликабельные стрелки, перенос рецептов, ghost-ингредиенты) и отдаёт плагинам рабочий IJeiRuntime.
 * Работает, только если самого JEI нет — иначе всем занимается JEI.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class JeiBridge {
    public static final ResourceLocation INFO = new ResourceLocation("jei", "information");

    private static List<IModPlugin> plugins;
    static IJeiHelpers helpers;
    private static final Map<ResourceLocation, IRecipeCategory> CATEGORIES = new LinkedHashMap<>();
    /** Все рецепты по типу JEI (как их видит плагин: объекты рецептов) и их вид у нас. */
    static final Map<ResourceLocation, List<Object>> RAW = new LinkedHashMap<>();
    static final Map<Object, RecipeIndex.Entry> ENTRY_OF = new IdentityHashMap<>();
    static final Map<ResourceLocation, List<ItemStack>> CATALYSTS = new LinkedHashMap<>();
    static final List<Object[]> CONTAINER_HANDLERS = new ArrayList<>();
    static final List<IGlobalGuiHandler> GLOBAL_HANDLERS = new ArrayList<>();
    static final List<Object[]> GHOST_HANDLERS = new ArrayList<>();
    private static final List<Object[]> CRAFT_EXT = new ArrayList<>();
    private static final Map<ResourceLocation, List<Object[]>> COOK_EXT = new HashMap<>();
    private static final List<Object[]> SMITH_EXT = new ArrayList<>();
    private static final List<Object[]> BREW_EXT = new ArrayList<>();
    private static final List<IRecipeManagerPlugin> DYNAMIC = new ArrayList<>();
    private static final Map<ResourceLocation, List<ISimpleRecipeManagerPlugin>> SIMPLE = new HashMap<>();
    private static final Map<ResourceLocation, List<Object>> DECORATORS = new HashMap<>();
    /** Ингредиенты модов по типам (газы, энергия, жидкости…). */
    static final Map<IIngredientType<?>, List<Object>> INGREDIENTS = new LinkedHashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static boolean runtimeSent;
    private static IJeiRuntime runtime;
    private static int infoN;

    private JeiBridge() {}

    static void call(Object p, String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            String key = p.getClass().getName() + "#" + what;
            if (FAILED.add(key)) ArcHud.LOGGER.warn("Arc HUD: JEI plugin {} failed in {}: {}", p.getClass().getName(), what, t.toString());
        }
    }

    private static List<IModPlugin> plugins() {
        if (plugins == null) {
            plugins = new ArrayList<>();
            for (Object o : ArcClient.platform().jeiPlugins()) {
                if (o instanceof IModPlugin p) plugins.add(p);
            }
            ArcHud.LOGGER.info("Arc HUD: {} JEI plugins found: {}", plugins.size(),
                    plugins.stream().map(p -> p.getClass().getSimpleName()).toList());
        }
        return plugins;
    }

    // ------------------------------------------------------------------ типы рецептов JEI ↔ наши категории

    private static final Map<ResourceLocation, RecipeType<?>> VANILLA_TYPES = new HashMap<>();

    static {
        for (java.lang.reflect.Field f : RecipeTypes.class.getFields()) {
            try {
                if (f.get(null) instanceof RecipeType<?> t) VANILLA_TYPES.put(t.getUid(), t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Наша категория для типа рецептов JEI (у JEI печь — minecraft:furnace, у игры — minecraft:smelting и т. п.). */
    public static ResourceLocation ours(ResourceLocation uid) {
        if (!uid.getNamespace().equals("minecraft")) return uid;
        return switch (uid.getPath()) {
            case "furnace" -> new ResourceLocation("smelting");
            case "campfire" -> new ResourceLocation("campfire_cooking");
            case "fuel" -> RecipeIndex.FUEL;
            case "brewing" -> RecipeIndex.BREWING;
            default -> uid;
        };
    }

    /** Тип JEI для нашей категории. */
    static ResourceLocation jeiUid(ResourceLocation ours) {
        if (ours.equals(RecipeIndex.FUEL)) return RecipeTypes.FUELING.getUid();
        if (ours.equals(RecipeIndex.BREWING)) return RecipeTypes.BREWING.getUid();
        if (ours.equals(new ResourceLocation("smelting"))) return RecipeTypes.SMELTING.getUid();
        if (ours.equals(new ResourceLocation("campfire_cooking"))) return RecipeTypes.CAMPFIRE_COOKING.getUid();
        return ours;
    }

    static Optional<RecipeType<?>> recipeType(ResourceLocation uid) {
        IRecipeCategory c = CATEGORIES.get(uid);
        if (c != null) return Optional.of(c.getRecipeType());
        RecipeType<?> v = VANILLA_TYPES.get(uid);
        if (v != null) return Optional.of(v);
        v = VANILLA_TYPES.get(jeiUid(uid));
        return Optional.ofNullable(v);
    }

    static Collection<IRecipeCategory> categories() {
        return CATEGORIES.values();
    }

    /** Все типы рецептов (модовые и встроенные), о которых знает мост. */
    static List<RecipeType<?>> allTypes() {
        List<RecipeType<?>> out = new ArrayList<>();
        for (IRecipeCategory c : CATEGORIES.values()) out.add(c.getRecipeType());
        for (RecipeType<?> t : VANILLA_TYPES.values()) if (!CATEGORIES.containsKey(t.getUid())) out.add(t);
        return out;
    }

    /** Категория для типа: модовая или «встроенная» (заглушка с названием и иконкой — для плагинов, которые её спрашивают). */
    static IRecipeCategory category(RecipeType<?> type) {
        IRecipeCategory c = CATEGORIES.get(type.getUid());
        if (c != null) return c;
        ResourceLocation ours = ours(type.getUid());
        return Stub.of(IRecipeCategory.class)
                .on("getRecipeType", 0, a -> type)
                .on("getTitle", 0, a -> ArcRecipeScreen.categoryName(ours))
                .on("getIcon", 0, a -> JeiDraw.item(RecipeIndex.icon(ours)))
                .on("getWidth", 0, a -> 116)
                .on("getHeight", 0, a -> 54)
                .on("isHandled", 1, a -> true)
                .build();
    }

    static List<Object> decorators(IRecipeCategory cat) {
        try {
            return DECORATORS.getOrDefault(cat.getRecipeType().getUid(), List.of());
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ загрузка

    /** Выполнить регистрацию всех плагинов и добавить их рецепты в индекс Arc HUD. */
    public static int load() {
        List<IModPlugin> list = plugins();
        if (list.isEmpty()) return 0;
        long t0 = System.nanoTime();
        CATEGORIES.clear();
        RAW.clear();
        ENTRY_OF.clear();
        CATALYSTS.clear();
        CONTAINER_HANDLERS.clear();
        GLOBAL_HANDLERS.clear();
        GHOST_HANDLERS.clear();
        CRAFT_EXT.clear();
        COOK_EXT.clear();
        SMITH_EXT.clear();
        BREW_EXT.clear();
        DYNAMIC.clear();
        SIMPLE.clear();
        DECORATORS.clear();
        INGREDIENTS.clear();
        JeiTransfer.clear();
        JeiHelpers.INGREDIENT_RENDERERS.clear();
        JeiHelpers.INGREDIENT_HELPERS.clear();
        JeiHelpers.INGREDIENT_TYPES.clear();
        infoN = 0;
        helpers = JeiHelpers.create(JeiBridge::recipeType);

        List<Object[]> infos = new ArrayList<>();
        Map<ResourceLocation, List<Object>> registered = new LinkedHashMap<>();
        List<ItemStack> extraItems = new ArrayList<>();

        ISubtypeRegistration subtypes = Stub.empty(ISubtypeRegistration.class);
        IModIngredientRegistration ingredients = Stub.of(IModIngredientRegistration.class)
                .on("register", a -> {
                    if (a.length >= 4 && a[0] instanceof IIngredientType type) {
                        JeiHelpers.INGREDIENT_RENDERERS.put(type.getIngredientClass(), a[3]);
                        JeiHelpers.INGREDIENT_HELPERS.put(type.getIngredientClass(), a[2]);
                        if (!JeiHelpers.INGREDIENT_TYPES.contains(type)) JeiHelpers.INGREDIENT_TYPES.add(type);
                        if (a[1] instanceof Collection<?> c) {
                            if (type == VanillaTypes.ITEM_STACK) for (Object o : c) extraItems.add((ItemStack) o);
                            else INGREDIENTS.computeIfAbsent(type, k -> new ArrayList<>()).addAll(c);
                        }
                    }
                    return null;
                })
                .on("getColorHelper", 0, a -> helpers.getColorHelper())
                .build();
        IExtraIngredientRegistration extra = Stub.of(IExtraIngredientRegistration.class)
                .on("addExtraIngredients", 2, a -> {
                    if (a[0] == VanillaTypes.ITEM_STACK) for (Object o : (Collection<?>) a[1]) extraItems.add((ItemStack) o);
                    else if (a[0] instanceof IIngredientType t) INGREDIENTS.computeIfAbsent(t, k -> new ArrayList<>()).addAll((Collection<?>) a[1]);
                    return null;
                })
                .build();
        IRecipeCategoryRegistration cats = Stub.of(IRecipeCategoryRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("addRecipeCategories", 1, a -> {
                    for (Object c : (Object[]) a[0]) {
                        IRecipeCategory cat = (IRecipeCategory) c;
                        CATEGORIES.put(cat.getRecipeType().getUid(), cat);
                    }
                    return null;
                })
                .build();
        IVanillaCategoryExtensionRegistration ext = vanillaExtensions();
        IVanillaRecipeFactory factory = JeiHelpers.vanillaFactory();
        IRecipeRegistration recipes = Stub.of(IRecipeRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("getIngredientManager", 0, a -> helpers.getIngredientManager())
                .on("getVanillaRecipeFactory", 0, a -> factory)
                .on("getIngredientVisibility", 0, a -> helpers.getIngredientVisibility())
                .on("addRecipes", 2, a -> {
                    RecipeType<?> type = (RecipeType<?>) a[0];
                    registered.computeIfAbsent(type.getUid(), k -> new ArrayList<>()).addAll((Collection<?>) a[1]);
                    return null;
                })
                .on("addIngredientInfo", 3, a -> {
                    List<Object> values = a[0] instanceof List<?> l ? new ArrayList<>(l) : List.of(a[0]);
                    infos.add(new Object[]{values, a[2]});
                    return null;
                })
                .build();
        IRecipeCatalystRegistration catalystReg = Stub.of(IRecipeCatalystRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("getIngredientManager", 0, a -> helpers.getIngredientManager())
                .on("addRecipeCatalysts", 2, a -> {
                    RecipeType<?> type = (RecipeType<?>) a[0];
                    for (Object o : (Object[]) a[1]) catalyst(type, o);
                    return null;
                })
                .on("addRecipeCatalysts", 3, a -> {
                    RecipeType<?> type = (RecipeType<?>) a[0];
                    for (Object o : (List<?>) a[2]) catalyst(type, o);
                    return null;
                })
                .on("addRecipeCatalyst", 2, a -> {
                    if (a[0] instanceof RecipeType<?> type && a[1] instanceof Consumer<?> c) {
                        List<Object> got = new ArrayList<>();
                        ((Consumer) c).accept(JeiHelpers.collector(got));
                        for (Object o : got) catalyst(type, o);
                    } else if (a[1] instanceof Object[] types) {
                        for (Object t : types) catalyst((RecipeType<?>) t, a[0]);
                    }
                    return null;
                })
                .on("addRecipeCatalyst", 3, a -> {
                    if (a[2] instanceof Object[] types) for (Object t : types) catalyst((RecipeType<?>) t, a[1]);
                    return null;
                })
                .on("addCraftingStation", a -> {
                    if (a[0] instanceof RecipeType<?> type) for (int i = 1; i < a.length; i++) {
                        if (a[i] instanceof Object[] arr) for (Object o : arr) catalyst(type, o);
                        else catalyst(type, a[i]);
                    }
                    return null;
                })
                .build();
        IGuiHandlerRegistration gui = Stub.of(IGuiHandlerRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("addGuiContainerHandler", 2, a -> {
                    CONTAINER_HANDLERS.add(new Object[]{a[0], a[1]});
                    return null;
                })
                .on("addGenericGuiContainerHandler", 2, a -> {
                    CONTAINER_HANDLERS.add(new Object[]{a[0], a[1]});
                    return null;
                })
                .on("addGlobalGuiHandler", 1, a -> {
                    GLOBAL_HANDLERS.add((IGlobalGuiHandler) a[0]);
                    return null;
                })
                .on("addGhostIngredientHandler", 2, a -> {
                    GHOST_HANDLERS.add(new Object[]{a[0], a[1]});
                    return null;
                })
                .build();
        IRecipeTransferRegistration transfer = JeiTransfer.registration();
        IAdvancedRegistration advanced = Stub.of(IAdvancedRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("addRecipeManagerPlugin", 1, a -> {
                    DYNAMIC.add((IRecipeManagerPlugin) a[0]);
                    return null;
                })
                .on("addTypedRecipeManagerPlugin", 2, a -> {
                    SIMPLE.computeIfAbsent(((RecipeType<?>) a[0]).getUid(), k -> new ArrayList<>()).add((ISimpleRecipeManagerPlugin) a[1]);
                    return null;
                })
                .on("addRecipeCategoryDecorator", 2, a -> {
                    DECORATORS.computeIfAbsent(((RecipeType<?>) a[0]).getUid(), k -> new ArrayList<>()).add(a[1]);
                    return null;
                })
                .build();
        IIngredientAliasRegistration alias = Stub.empty(IIngredientAliasRegistration.class);
        IModInfoRegistration modInfo = Stub.empty(IModInfoRegistration.class);

        for (IModPlugin p : list) call(p, "registerItemSubtypes", () -> p.registerItemSubtypes(subtypes));
        for (IModPlugin p : list) call(p, "registerIngredients", () -> p.registerIngredients(ingredients));
        for (IModPlugin p : list) call(p, "registerExtraIngredients", () -> p.registerExtraIngredients(extra));
        for (IModPlugin p : list) call(p, "registerIngredientAliases", () -> p.registerIngredientAliases(alias));
        for (IModPlugin p : list) call(p, "registerModInfo", () -> p.registerModInfo(modInfo));
        for (IModPlugin p : list) call(p, "registerCategories", () -> p.registerCategories(cats));
        for (IModPlugin p : list) call(p, "registerVanillaCategoryExtensions", () -> p.registerVanillaCategoryExtensions(ext));
        for (IModPlugin p : list) call(p, "registerRecipes", () -> p.registerRecipes(recipes));
        for (IModPlugin p : list) call(p, "registerRecipeTransferHandlers", () -> p.registerRecipeTransferHandlers(transfer));
        for (IModPlugin p : list) call(p, "registerRecipeCatalysts", () -> p.registerRecipeCatalysts(catalystReg));
        for (IModPlugin p : list) call(p, "registerGuiHandlers", () -> p.registerGuiHandlers(gui));
        for (IModPlugin p : list) call(p, "registerAdvanced", () -> p.registerAdvanced(advanced));
        IRuntimeRegistration rt = Stub.of(IRuntimeRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("getRecipeManager", 0, a -> JeiRuntime.recipeManager())
                .on("getIngredientManager", 0, a -> helpers.getIngredientManager())
                .on("getIngredientFilter", 0, a -> JeiRuntime.filter())
                .on("getIngredientListOverlay", 0, a -> JeiRuntime.listOverlay())
                .on("getRecipesGui", 0, a -> recipesGui())
                .build();
        for (IModPlugin p : list) call(p, "registerRuntime", () -> p.registerRuntime(rt));

        // ---------- предметы, добавленные плагинами в список
        if (!extraItems.isEmpty()) RecipeIndex.addItems(extraItems);

        // ---------- встроенные типы: рецепты игры, которые видит плагин через IRecipeManager
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (Recipe<?> r : mc.level.getRecipeManager().getRecipes()) {
                ResourceLocation t = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
                if (t == null) continue;
                RAW.computeIfAbsent(jeiUid(t), k -> new ArrayList<>()).add(r);
            }
            for (ResourceLocation type : new ArrayList<>(RecipeIndex.types())) {
                for (RecipeIndex.Entry e : RecipeIndex.entriesOf(type)) if (e.recipe() != null) ENTRY_OF.putIfAbsent(e.recipe(), e);
            }
        }

        // ---------- рецепты из плагинов в индекс
        int added = 0;
        for (Map.Entry<ResourceLocation, List<Object>> e : registered.entrySet()) {
            ResourceLocation uid = e.getKey();
            int n = 0;
            for (Object recipe : e.getValue()) {
                RecipeIndex.Entry entry = convert(uid, recipe, n);
                if (entry == null) continue;
                RAW.computeIfAbsent(uid, k -> new ArrayList<>()).add(recipe);
                n++;
            }
            added += n;
            if (CATEGORIES.containsKey(uid) && n > 0) RecipeIndex.replaceGenericType(ours(uid));
        }

        // ---------- расширения встроенных категорий (свои классы рецептов модов в верстаке, печах, кузнице, зельях)
        added += applyExtensions();

        // ---------- все рецепты динамических плагинов (для просмотра категории целиком)
        for (IRecipeCategory cat : CATEGORIES.values()) {
            ResourceLocation uid = cat.getRecipeType().getUid();
            for (IRecipeManagerPlugin p : DYNAMIC) {
                try {
                    for (Object r : p.getRecipes(cat)) {
                        if (convert(uid, r, 0) == null) continue;
                        RAW.computeIfAbsent(uid, k -> new ArrayList<>()).add(r);
                        added++;
                    }
                } catch (Throwable t) {
                    if (FAILED.add("dyn:" + p.getClass().getName())) ArcHud.LOGGER.warn("Arc HUD: JEI recipe plugin {} failed: {}", p.getClass().getName(), t.toString());
                }
            }
        }
        for (Map.Entry<ResourceLocation, List<ISimpleRecipeManagerPlugin>> e : SIMPLE.entrySet()) {
            for (ISimpleRecipeManagerPlugin p : e.getValue()) {
                try {
                    for (Object r : p.getAllRecipes()) {
                        if (convert(e.getKey(), r, 0) == null) continue;
                        RAW.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(r);
                        added++;
                    }
                } catch (Throwable t) {
                    if (FAILED.add("simple:" + p.getClass().getName())) ArcHud.LOGGER.warn("Arc HUD: JEI recipe plugin {} failed: {}", p.getClass().getName(), t.toString());
                }
            }
        }
        RecipeIndex.dynamic = JeiBridge::dynamicRecipes;

        // ---------- категории: название, иконка, станки
        for (Map.Entry<ResourceLocation, IRecipeCategory> e : CATEGORIES.entrySet()) {
            IRecipeCategory cat = e.getValue();
            ResourceLocation ours = ours(e.getKey());
            try {
                RecipeIndex.TITLES.put(ours, cat.getTitle());
            } catch (Throwable ignored) {
            }
            RecipeIndex.ICON_DRAWERS.put(ours, (g, xy) -> {
                try {
                    var icon = cat.getIcon();
                    if (icon != null) icon.draw(g, xy[0] + (16 - icon.getWidth()) / 2, xy[1] + (16 - icon.getHeight()) / 2);
                } catch (Throwable ignored) {
                }
            });
        }
        for (Map.Entry<ResourceLocation, List<ItemStack>> e : CATALYSTS.entrySet()) {
            List<ItemStack> l = RecipeIndex.CATALYSTS.computeIfAbsent(ours(e.getKey()), k -> new ArrayList<>());
            for (ItemStack s : e.getValue()) if (l.stream().noneMatch(x -> ItemStack.isSameItemSameTags(x, s))) l.add(s);
        }
        // ---------- страницы «Информация»
        for (Object[] info : infos) addInfo((List<?>) info[0], (Object[]) info[1]);
        if (infoN > 0) RecipeIndex.TITLES.put(INFO, Component.translatable("archud.recipes.info"));

        if (!runtimeSent) {
            runtimeSent = true;
            IJeiRuntime r = runtime();
            for (IModPlugin p : list) call(p, "onRuntimeAvailable", () -> p.onRuntimeAvailable(r));
        }
        JeiGate.loaded = true;
        ArcHud.LOGGER.info("Arc HUD: JEI bridge — {} categories, {} recipes, {} info pages, {} GUI handlers, {} transfer handlers, {} dynamic plugins in {} ms",
                CATEGORIES.size(), added, infoN, CONTAINER_HANDLERS.size(), JeiTransfer.count(), DYNAMIC.size() + SIMPLE.size(),
                (System.nanoTime() - t0) / 1_000_000);
        return added + infoN;
    }

    private static void catalyst(RecipeType<?> type, Object o) {
        ItemStack s = o instanceof ItemLike il ? new ItemStack(il) : o instanceof ItemStack st ? st
                : o instanceof ITypedIngredient<?> ti && ti.getIngredient() instanceof ItemStack st2 ? st2 : ItemStack.EMPTY;
        if (!s.isEmpty()) CATALYSTS.computeIfAbsent(type.getUid(), k -> new ArrayList<>()).add(s);
    }

    private static void addInfo(List<?> values, Object[] text) {
        List<ItemStack> items = new ArrayList<>();
        for (Object o : values) {
            if (o instanceof ItemStack s) items.add(s);
            else {
                ItemStack b = JeiCard.bucketOf(o);
                if (!b.isEmpty()) items.add(b);
            }
        }
        if (items.isEmpty()) return;
        List<Component> lines = new ArrayList<>();
        for (Object c : text) if (c instanceof Component comp) lines.add(comp);
        InfoCard card = new InfoCard(items, lines);
        RecipeIndex.addExternal(new RecipeIndex.Entry(new ResourceLocation(INFO.getNamespace(), INFO.getPath() + "/" + (infoN++)), INFO, null,
                List.of(), 0, List.of(items), "", new ItemStack(Items.BOOK), card));
    }

    // ------------------------------------------------------------------ рецепт плагина → наша карточка

    /**
     * Рецепт любого типа JEI в виде нашей записи (и в индекс): категории модов — их собственной отрисовкой,
     * встроенные типы JEI (наковальня, зелья, топливо, компостер, точило, информация) — нашими карточками.
     */
    static RecipeIndex.Entry convert(ResourceLocation uid, Object recipe, int n) {
        if (recipe == null) return null;
        RecipeIndex.Entry cached = ENTRY_OF.get(recipe);
        // наш общий разбор рецепта игры не мешает мод-категории показать его по-своему
        if (cached != null && (cached.card() != null || !CATEGORIES.containsKey(uid))) return cached;
        RecipeIndex.Entry entry = null;
        try {
            IRecipeCategory cat = CATEGORIES.get(uid);
            ResourceLocation ours = ours(uid);
            if (cat != null) {
                entry = cardEntry(cat, ours, recipe, n, null);
            } else if (recipe instanceof IJeiAnvilRecipe r) {
                entry = simple(r.getUid(), RecipeIndex.ANVIL, List.of(r.getLeftInputs(), r.getRightInputs()), List.of(r.getOutputs()), "", Items.ANVIL);
            } else if (recipe instanceof IJeiGrindstoneRecipe r) {
                entry = simple(r.getUid(), RecipeIndex.GRINDSTONE, List.of(r.getTopInputs(), r.getBottomInputs()), List.of(r.getOutputs()),
                        r.getMaxXpReward() > 0 ? r.getMinXpReward() + "–" + r.getMaxXpReward() + " XP" : "", Items.GRINDSTONE);
            } else if (recipe instanceof IJeiBrewingRecipe r) {
                entry = simple(r.getUid(), RecipeIndex.BREWING, List.of(r.getPotionInputs(), r.getIngredients()), List.of(List.of(r.getPotionOutput())), "",
                        Items.BREWING_STAND);
            } else if (recipe instanceof IJeiFuelingRecipe r) {
                String note = Component.translatable("archud.recipes.burn", String.format(java.util.Locale.ROOT, "%.1f", r.getBurnTime() / 20F),
                        String.format(java.util.Locale.ROOT, "%.1f", r.getBurnTime() / 200F)).getString();
                entry = simple(null, RecipeIndex.FUEL, List.of(r.getInputs()), List.of(), note, Items.FURNACE);
            } else if (recipe instanceof IJeiCompostingRecipe r) {
                entry = simple(r.getUid(), RecipeIndex.COMPOST, List.of(r.getInputs()), List.of(),
                        Component.translatable("archud.recipes.compost", Math.round(r.getChance() * 100)).getString(), Items.COMPOSTER);
            } else if (recipe instanceof IJeiIngredientInfoRecipe r) {
                List<Object> vals = new ArrayList<>();
                for (Object ti : r.getIngredients()) vals.add(((ITypedIngredient<?>) ti).getIngredient());
                addInfo(vals, r.getDescription().toArray());
                return null;
            } else if (recipe instanceof Recipe<?> r) {
                entry = extensionEntry(r);
                if (entry == null) {
                    entry = RecipeIndex.fromRecipe(r);
                    if (entry == null) return null;
                }
            }
        } catch (Throwable t) {
            if (FAILED.add("recipe:" + uid)) ArcHud.LOGGER.warn("Arc HUD: cannot show JEI recipe of {}: {}", uid, t.toString());
            return null;
        }
        if (entry == null) return null;
        ENTRY_OF.put(recipe, entry);
        RecipeIndex.addExternal(entry);
        return entry;
    }

    private static RecipeIndex.Entry simple(ResourceLocation id, ResourceLocation type, List<List<ItemStack>> in, List<List<ItemStack>> out, String note, ItemLike icon) {
        List<List<ItemStack>> ins = new ArrayList<>();
        for (List<ItemStack> l : in) if (l != null && !l.isEmpty()) ins.add(l);
        List<List<ItemStack>> outs = new ArrayList<>();
        for (List<ItemStack> l : out) if (l != null && !l.isEmpty()) outs.add(l);
        if (id == null) id = ArcHud.id("jei/" + type.getPath() + "/" + System.identityHashCode(in));
        return new RecipeIndex.Entry(id, type, null, ins, 0, outs, note, new ItemStack(icon));
    }

    /** Запись с карточкой категории (своя отрисовка мода). */
    private static RecipeIndex.Entry cardEntry(IRecipeCategory cat, ResourceLocation ours, Object recipe, int n, ResourceLocation forcedId) {
        JeiCard card = JeiCard.create(cat, recipe, helpers);
        if (card == null) return null;
        ResourceLocation id = forcedId;
        if (id == null) {
            try {
                id = cat.getRegistryName(recipe);
            } catch (Throwable ignored) {
            }
        }
        if (id == null) id = new ResourceLocation(ours.getNamespace(), ours.getPath() + "/" + n + "_" + System.identityHashCode(recipe));
        List<ItemStack> cats = CATALYSTS.getOrDefault(cat.getRecipeType().getUid(), List.of());
        ItemStack icon = cats.isEmpty() ? RecipeIndex.icon(ours) : cats.get(0);
        return new RecipeIndex.Entry(id, ours, recipe instanceof Recipe<?> r ? r : null, card.items(RecipeIngredientRole.INPUT), 0,
                card.items(RecipeIngredientRole.OUTPUT), "", icon, card);
    }

    // ------------------------------------------------------------------ расширения встроенных категорий

    private static IVanillaCategoryExtensionRegistration vanillaExtensions() {
        Object crafting = Stub.of(mezz.jei.api.recipe.category.extensions.IExtendableRecipeCategory.class)
                .on("addCategoryExtension", 2, a -> {
                    CRAFT_EXT.add(new Object[]{a[0], null, a[1]});
                    return null;
                })
                .on("addCategoryExtension", 3, a -> {
                    CRAFT_EXT.add(new Object[]{a[0], a[1], a[2]});
                    return null;
                })
                .on("getRecipeType", 0, a -> RecipeTypes.CRAFTING)
                .on("getTitle", 0, a -> ArcRecipeScreen.categoryName(RecipeIndex.CRAFTING))
                .on("getWidth", 0, a -> 116)
                .on("getHeight", 0, a -> 54)
                .build();
        Function<RecipeType<?>, Object> cooking = t -> Stub.of(mezz.jei.api.recipe.category.extensions.vanilla.cooking.IExtendableCookingRecipeCategory.class)
                .on("addExtension", 2, a -> {
                    COOK_EXT.computeIfAbsent(t.getUid(), k -> new ArrayList<>()).add(new Object[]{a[0], a[1]});
                    return null;
                })
                .build();
        Object smithing = Stub.of(mezz.jei.api.recipe.category.extensions.vanilla.smithing.IExtendableSmithingRecipeCategory.class)
                .on("addExtension", 2, a -> {
                    SMITH_EXT.add(new Object[]{a[0], a[1]});
                    return null;
                })
                .build();
        Object brewing = Stub.of(mezz.jei.api.recipe.category.extensions.vanilla.brewing.IExtendableBrewingRecipeCategory.class)
                .on("addExtension", 2, a -> {
                    BREW_EXT.add(new Object[]{a[0], a[1]});
                    return null;
                })
                .build();
        Object smelt = cooking.apply(RecipeTypes.SMELTING), blast = cooking.apply(RecipeTypes.BLASTING),
                smoke = cooking.apply(RecipeTypes.SMOKING), camp = cooking.apply(RecipeTypes.CAMPFIRE_COOKING);
        return Stub.of(IVanillaCategoryExtensionRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("getCraftingCategory", 0, a -> crafting)
                .on("getSmithingCategory", 0, a -> smithing)
                .on("getBrewingCategory", 0, a -> brewing)
                .on("getSmeltingCategory", 0, a -> smelt)
                .on("getBlastingCategory", 0, a -> blast)
                .on("getSmokingCategory", 0, a -> smoke)
                .on("getCampfireCookingCategory", 0, a -> camp)
                .build();
    }

    /** Рецепт игры, который показывает расширение мода (или null — показываем сами). */
    private static RecipeIndex.Entry extensionEntry(Recipe<?> r) {
        if (r instanceof CraftingRecipe) {
            for (Object[] x : CRAFT_EXT) {
                if (!((Class<?>) x[0]).isInstance(r)) continue;
                if (x[1] != null && !((Predicate) x[1]).test(r)) continue;
                Object ext = ((Function) x[2]).apply(r);
                if (!(ext instanceof ICraftingCategoryExtension ce)) continue;
                return cardEntry(ExtCategory.crafting(RecipeTypes.CRAFTING, ce), RecipeIndex.CRAFTING, r, 0, r.getId());
            }
        }
        if (r instanceof AbstractCookingRecipe) {
            ResourceLocation t = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
            if (t != null) {
                ResourceLocation uid = jeiUid(t);
                for (Object[] x : COOK_EXT.getOrDefault(uid, List.of())) {
                    if (!((Class<?>) x[0]).isInstance(r)) continue;
                    ICookingCategoryExtension ce = (ICookingCategoryExtension) x[1];
                    return cardEntry(ExtCategory.cooking(VANILLA_TYPES.getOrDefault(uid, RecipeTypes.SMELTING), ce), t, r, 0, r.getId());
                }
            }
        }
        if (r instanceof SmithingRecipe sr) {
            for (Object[] x : SMITH_EXT) {
                if (!((Class<?>) x[0]).isInstance(r)) continue;
                ISmithingCategoryExtension se = (ISmithingCategoryExtension) x[1];
                List<Object> t = new ArrayList<>(), b = new ArrayList<>(), ad = new ArrayList<>(), out = new ArrayList<>();
                se.setTemplate(sr, (mezz.jei.api.gui.builder.IIngredientAcceptor) JeiHelpers.collector(t));
                se.setBase(sr, (mezz.jei.api.gui.builder.IIngredientAcceptor) JeiHelpers.collector(b));
                se.setAddition(sr, (mezz.jei.api.gui.builder.IIngredientAcceptor) JeiHelpers.collector(ad));
                se.setOutput(sr, (mezz.jei.api.gui.builder.IIngredientAcceptor) JeiHelpers.collector(out));
                return new RecipeIndex.Entry(r.getId(), RecipeIndex.SMITHING, r, List.of(stacks(t), stacks(b), stacks(ad)), 0, List.of(stacks(out)), "",
                        new ItemStack(Items.SMITHING_TABLE));
            }
        }
        return null;
    }

    private static List<ItemStack> stacks(List<Object> l) {
        List<ItemStack> out = new ArrayList<>();
        for (Object o : l) if (o instanceof ItemStack s && !s.isEmpty()) out.add(s);
        return out;
    }

    private static int applyExtensions() {
        if (CRAFT_EXT.isEmpty() && COOK_EXT.isEmpty() && SMITH_EXT.isEmpty() && BREW_EXT.isEmpty()) return 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        int n = 0;
        IVanillaRecipeFactory factory = JeiHelpers.vanillaFactory();
        for (Recipe<?> r : mc.level.getRecipeManager().getRecipes()) {
            try {
                RecipeIndex.Entry e = extensionEntry(r);
                if (e != null) {
                    ENTRY_OF.put(r, e);
                    RecipeIndex.replace(r.getId(), e);
                    n++;
                }
                for (Object[] x : BREW_EXT) {
                    if (!((Class<?>) x[0]).isInstance(r)) continue;
                    for (Object b : ((IBrewingCategoryExtension) x[1]).getBrewingRecipes(r, factory)) {
                        if (convert(RecipeTypes.BREWING.getUid(), b, n) != null) n++;
                    }
                }
            } catch (Throwable t) {
                if (FAILED.add("ext:" + r.getClass().getName())) ArcHud.LOGGER.warn("Arc HUD: JEI category extension failed for {}: {}", r.getId(), t.toString());
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ динамические рецепты (IRecipeManagerPlugin)

    private static List<RecipeIndex.Entry> dynamicRecipes(ItemStack stack, boolean uses) {
        if (DYNAMIC.isEmpty() && SIMPLE.isEmpty()) return List.of();
        RecipeIngredientRole role = uses ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT;
        ITypedIngredient<ItemStack> typed = JeiHelpers.typed(VanillaTypes.ITEM_STACK, stack);
        IFocus<ItemStack> focus = JeiHelpers.focus(role, typed);
        List<RecipeIndex.Entry> out = new ArrayList<>();
        for (IRecipeManagerPlugin p : DYNAMIC) {
            try {
                for (Object t : p.getRecipeTypes(focus)) {
                    RecipeType<?> type = (RecipeType<?>) t;
                    IRecipeCategory cat = category(type);
                    for (Object r : p.getRecipes(cat, focus)) {
                        RecipeIndex.Entry e = convert(type.getUid(), r, 0);
                        if (e != null) out.add(e);
                    }
                }
            } catch (Throwable t) {
                if (FAILED.add("dynf:" + p.getClass().getName())) ArcHud.LOGGER.warn("Arc HUD: JEI recipe plugin {} failed: {}", p.getClass().getName(), t.toString());
            }
        }
        for (Map.Entry<ResourceLocation, List<ISimpleRecipeManagerPlugin>> e : SIMPLE.entrySet()) {
            for (ISimpleRecipeManagerPlugin p : e.getValue()) {
                try {
                    List<?> rs = uses ? (p.isHandledInput(typed) ? p.getRecipesForInput(typed) : List.of())
                            : (p.isHandledOutput(typed) ? p.getRecipesForOutput(typed) : List.of());
                    for (Object r : rs) {
                        RecipeIndex.Entry en = convert(e.getKey(), r, 0);
                        if (en != null) out.add(en);
                    }
                } catch (Throwable t) {
                    if (FAILED.add("simplef:" + p.getClass().getName())) ArcHud.LOGGER.warn("Arc HUD: JEI recipe plugin {} failed: {}", p.getClass().getName(), t.toString());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ окно рецептов как IRecipesGui

    static IRecipesGui recipesGui() {
        return Stub.of(IRecipesGui.class)
                .on("showTypes", 1, a -> {
                    List<ResourceLocation> uids = new ArrayList<>();
                    for (Object t : (List<?>) a[0]) uids.add(ours(((RecipeType<?>) t).getUid()));
                    ArcRecipeScreen.openTypes(Minecraft.getInstance().screen, uids);
                    return null;
                })
                .on("show", 1, a -> {
                    Object f = a[0] instanceof List<?> l ? (l.isEmpty() ? null : l.get(0)) : a[0];
                    if (f instanceof IFocus<?> focus) {
                        Object v = focus.getTypedValue().getIngredient();
                        ItemStack s = v instanceof ItemStack st ? st : JeiCard.bucketOf(v);
                        if (!s.isEmpty()) ArcRecipeScreen.open(Minecraft.getInstance().screen, s, focus.getRole() == RecipeIngredientRole.INPUT
                                || focus.getRole() == RecipeIngredientRole.CATALYST);
                    }
                    return null;
                })
                .on("getParentScreen", 0, a -> Optional.ofNullable(Minecraft.getInstance().screen))
                .build();
    }

    static IJeiRuntime runtime() {
        if (runtime == null) runtime = JeiRuntime.create();
        return runtime;
    }

    // ------------------------------------------------------------------ GUI модов

    /** Прямоугольники экрана, которые GUI мода занимает кроме основного окна (боковые панели и т. п.). */
    public static List<int[]> extraAreas(Screen s) {
        List<int[]> out = new ArrayList<>();
        for (Object[] h : CONTAINER_HANDLERS) {
            if (!((Class<?>) h[0]).isInstance(s) || !(s instanceof AbstractContainerScreen<?> cs)) continue;
            try {
                for (Object o : ((IGuiContainerHandler) h[1]).getGuiExtraAreas(cs)) {
                    Rect2i r = (Rect2i) o;
                    out.add(new int[]{r.getX(), r.getY(), r.getWidth(), r.getHeight()});
                }
            } catch (Throwable ignored) {
            }
        }
        for (IGlobalGuiHandler g : GLOBAL_HANDLERS) {
            try {
                for (Rect2i r : g.getGuiExtraAreas()) out.add(new int[]{r.getX(), r.getY(), r.getWidth(), r.getHeight()});
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** Клик по кликабельной области (стрелка прогресса в печи мода и т. п.) — открыть её рецепты. */
    public static boolean clickArea(Screen s, double mx, double my) {
        if (!(s instanceof AbstractContainerScreen<?> cs)) return false;
        var acc = (com.arc4io.archud.mixin.ContainerScreenAccessor) cs;
        double rx = mx - acc.archud$left(), ry = my - acc.archud$top();
        for (Object[] h : CONTAINER_HANDLERS) {
            if (!((Class<?>) h[0]).isInstance(s)) continue;
            try {
                for (Object o : ((IGuiContainerHandler) h[1]).getGuiClickableAreas(cs, rx, ry)) {
                    IGuiClickableArea area = (IGuiClickableArea) o;
                    Rect2i r = area.getArea();
                    if (rx >= r.getX() && rx < r.getX() + r.getWidth() && ry >= r.getY() && ry < r.getY() + r.getHeight()) {
                        area.onClick(helpers.getFocusFactory(), recipesGui());
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Ингредиент под мышью, который GUI мода отдаёт JEI (бак, панель машины) — предметом или ведром жидкости. */
    public static ItemStack ingredientUnder(Screen s, double mx, double my) {
        List<Optional<?>> found = new ArrayList<>();
        if (s instanceof AbstractContainerScreen<?> cs) {
            for (Object[] h : CONTAINER_HANDLERS) {
                if (!((Class<?>) h[0]).isInstance(s)) continue;
                try {
                    found.add(((IGuiContainerHandler) h[1]).getClickableIngredientUnderMouse(cs, mx, my));
                } catch (Throwable ignored) {
                }
            }
        }
        for (IGlobalGuiHandler g : GLOBAL_HANDLERS) {
            try {
                found.add(g.getClickableIngredientUnderMouse(mx, my));
            } catch (Throwable ignored) {
            }
        }
        for (Optional<?> o : found) {
            if (o == null || o.isEmpty() || !(o.get() instanceof IClickableIngredient<?> ci)) continue;
            Object v = ci.getTypedIngredient().getIngredient();
            if (v instanceof ItemStack st && !st.isEmpty()) return st;
            ItemStack b = JeiCard.bucketOf(v);
            if (!b.isEmpty()) return b;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ перенос и ghost-ингредиенты (для JeiGate)

    public static com.arc4io.archud.client.recipes.RecipeTransfer.Plan transferPlan(Screen parent, RecipeIndex.Entry e, boolean max) {
        return JeiTransfer.transferPlan(parent, e, max);
    }

    public static boolean ghostSupported(Screen s) {
        return JeiTransfer.ghostSupported(s);
    }

    public static List<int[]> ghostAreas(Screen s, ItemStack stack, boolean start) {
        return JeiTransfer.ghostAreas(s, stack, start);
    }

    public static boolean ghostDrop(Screen s, ItemStack stack, double mx, double my) {
        return JeiTransfer.ghostDrop(s, stack, mx, my);
    }
}
