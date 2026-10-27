package com.arc4io.archud.client.jei;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.recipes.ArcRecipeScreen;
import com.arc4io.archud.client.recipes.InfoCard;
import com.arc4io.archud.client.recipes.RecipeIndex;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiClickableArea;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
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
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Мост для плагинов модов, написанных для JEI: Arc HUD сам выполняет их регистрацию (категории, рецепты,
 * станки, подсказки «информация», области GUI и кликабельные стрелки) и показывает всё в своём окне рецептов.
 * Работает, только если самого JEI нет — иначе всем занимается JEI.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class JeiBridge {
    public static final ResourceLocation INFO = new ResourceLocation("jei", "information");

    private static List<IModPlugin> plugins;
    private static IJeiHelpers helpers;
    private static final Map<ResourceLocation, IRecipeCategory> CATEGORIES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, List<Object>> RECIPES = new LinkedHashMap<>();
    private static final List<Object[]> CONTAINER_HANDLERS = new ArrayList<>();
    private static final List<IGlobalGuiHandler> GLOBAL_HANDLERS = new ArrayList<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static boolean runtimeSent;

    private JeiBridge() {}

    private static void call(IModPlugin p, String what, Consumer<IModPlugin> c) {
        try {
            c.accept(p);
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

    /** Выполнить регистрацию всех плагинов и добавить их рецепты в индекс Arc HUD. */
    public static int load() {
        List<IModPlugin> list = plugins();
        if (list.isEmpty()) return 0;
        long t0 = System.nanoTime();
        CATEGORIES.clear();
        RECIPES.clear();
        CONTAINER_HANDLERS.clear();
        GLOBAL_HANDLERS.clear();
        JeiHelpers.INGREDIENT_RENDERERS.clear();
        helpers = JeiHelpers.create(JeiBridge::recipeType);

        List<Object[]> infos = new ArrayList<>();
        Map<ResourceLocation, List<ItemStack>> catalysts = new LinkedHashMap<>();

        ISubtypeRegistration subtypes = Stub.empty(ISubtypeRegistration.class);
        IModIngredientRegistration ingredients = Stub.of(IModIngredientRegistration.class)
                .on("register", a -> {
                    if (a.length >= 4 && a[0] instanceof IIngredientType type) {
                        JeiHelpers.INGREDIENT_RENDERERS.put(type.getIngredientClass(), a[3]);
                        JeiHelpers.INGREDIENT_HELPERS.put(type.getIngredientClass(), a[2]);
                        JeiHelpers.INGREDIENT_TYPES.add(type);
                    }
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
        IVanillaCategoryExtensionRegistration ext = Stub.of(IVanillaCategoryExtensionRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers).build();
        IVanillaRecipeFactory factory = JeiHelpers.vanillaFactory();
        IRecipeRegistration recipes = Stub.of(IRecipeRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("getIngredientManager", 0, a -> helpers.getIngredientManager())
                .on("getVanillaRecipeFactory", 0, a -> factory)
                .on("getIngredientVisibility", 0, a -> helpers.getIngredientVisibility())
                .on("addRecipes", 2, a -> {
                    RecipeType<?> type = (RecipeType<?>) a[0];
                    RECIPES.computeIfAbsent(type.getUid(), k -> new ArrayList<>()).addAll((Collection<?>) a[1]);
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
                    List<ItemStack> l = catalysts.computeIfAbsent(type.getUid(), k -> new ArrayList<>());
                    for (Object o : (Object[]) a[1]) {
                        if (o instanceof ItemLike il) l.add(new ItemStack(il));
                        else if (o instanceof ItemStack s) l.add(s);
                    }
                    return null;
                })
                .on("addRecipeCatalysts", 3, a -> {
                    RecipeType<?> type = (RecipeType<?>) a[0];
                    for (Object o : (List<?>) a[2]) if (o instanceof ItemStack s) catalysts.computeIfAbsent(type.getUid(), k -> new ArrayList<>()).add(s);
                    return null;
                })
                .on("addRecipeCatalyst", 2, a -> {
                    if (a[0] instanceof RecipeType<?> type && a[1] instanceof Consumer<?> c) {
                        List<Object> got = new ArrayList<>();
                        ((Consumer) c).accept(JeiHelpers.collector(got));
                        for (Object o : got) if (o instanceof ItemStack s) catalysts.computeIfAbsent(type.getUid(), k -> new ArrayList<>()).add(s);
                    } else if (a[1] instanceof Object[] types) {
                        ItemStack s = a[0] instanceof ItemLike il ? new ItemStack(il) : a[0] instanceof ItemStack st ? st : ItemStack.EMPTY;
                        for (Object t : types) if (!s.isEmpty()) catalysts.computeIfAbsent(((RecipeType<?>) t).getUid(), k -> new ArrayList<>()).add(s);
                    }
                    return null;
                })
                .on("addRecipeCatalyst", 3, a -> {
                    if (a[1] instanceof ItemStack s) {
                        for (Object t : (Object[]) a[2]) catalysts.computeIfAbsent(((RecipeType<?>) t).getUid(), k -> new ArrayList<>()).add(s);
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
                .build();
        IRecipeTransferRegistration transfer = Stub.of(IRecipeTransferRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers).build();
        IAdvancedRegistration advanced = Stub.of(IAdvancedRegistration.class)
                .on("getJeiHelpers", 0, a -> helpers).build();
        IExtraIngredientRegistration extra = Stub.empty(IExtraIngredientRegistration.class);
        IIngredientAliasRegistration alias = Stub.empty(IIngredientAliasRegistration.class);
        IModInfoRegistration modInfo = Stub.empty(IModInfoRegistration.class);

        for (IModPlugin p : list) call(p, "registerItemSubtypes", q -> q.registerItemSubtypes(subtypes));
        for (IModPlugin p : list) call(p, "registerIngredients", q -> q.registerIngredients(ingredients));
        for (IModPlugin p : list) call(p, "registerExtraIngredients", q -> q.registerExtraIngredients(extra));
        for (IModPlugin p : list) call(p, "registerIngredientAliases", q -> q.registerIngredientAliases(alias));
        for (IModPlugin p : list) call(p, "registerModInfo", q -> q.registerModInfo(modInfo));
        for (IModPlugin p : list) call(p, "registerCategories", q -> q.registerCategories(cats));
        for (IModPlugin p : list) call(p, "registerVanillaCategoryExtensions", q -> q.registerVanillaCategoryExtensions(ext));
        for (IModPlugin p : list) call(p, "registerRecipes", q -> q.registerRecipes(recipes));
        for (IModPlugin p : list) call(p, "registerRecipeTransferHandlers", q -> q.registerRecipeTransferHandlers(transfer));
        for (IModPlugin p : list) call(p, "registerRecipeCatalysts", q -> q.registerRecipeCatalysts(catalystReg));
        for (IModPlugin p : list) call(p, "registerGuiHandlers", q -> q.registerGuiHandlers(gui));
        for (IModPlugin p : list) call(p, "registerAdvanced", q -> q.registerAdvanced(advanced));
        IRuntimeRegistration rt = Stub.of(IRuntimeRegistration.class).on("getJeiHelpers", 0, a -> helpers).build();
        for (IModPlugin p : list) call(p, "registerRuntime", q -> q.registerRuntime(rt));

        // ---------- рецепты в индекс
        int added = 0;
        for (Map.Entry<ResourceLocation, List<Object>> e : RECIPES.entrySet()) {
            ResourceLocation uid = e.getKey();
            IRecipeCategory cat = CATEGORIES.get(uid);
            int n = 0;
            for (Object recipe : e.getValue()) {
                try {
                    RecipeIndex.Entry entry;
                    if (cat != null) {
                        JeiCard card = JeiCard.create(cat, recipe, helpers);
                        if (card == null) continue;
                        ResourceLocation id = null;
                        try {
                            id = cat.getRegistryName(recipe);
                        } catch (Throwable ignored) {
                        }
                        if (id == null) id = new ResourceLocation(uid.getNamespace(), uid.getPath() + "/" + n);
                        ItemStack icon = catalysts.getOrDefault(uid, List.of()).stream().findFirst().orElse(ItemStack.EMPTY);
                        entry = new RecipeIndex.Entry(id, uid, recipe instanceof Recipe<?> r ? r : null, card.items(RecipeIngredientRole.INPUT), 0,
                                card.items(RecipeIngredientRole.OUTPUT), "", icon, card);
                    } else if (recipe instanceof Recipe<?> r) {
                        entry = RecipeIndex.fromRecipe(r);
                        if (entry == null) continue;
                    } else continue;
                    RecipeIndex.addExternal(entry);
                    n++;
                } catch (Throwable t) {
                    if (FAILED.add("recipe:" + uid)) ArcHud.LOGGER.warn("Arc HUD: cannot show JEI recipe of {}: {}", uid, t.toString());
                }
            }
            added += n;
            if (cat != null && n > 0) RecipeIndex.replaceGenericType(uid);
        }
        // категории: название, иконка, станки
        for (Map.Entry<ResourceLocation, IRecipeCategory> e : CATEGORIES.entrySet()) {
            IRecipeCategory cat = e.getValue();
            try {
                RecipeIndex.TITLES.put(e.getKey(), cat.getTitle());
            } catch (Throwable ignored) {
            }
            RecipeIndex.ICON_DRAWERS.put(e.getKey(), (g, xy) -> {
                try {
                    var icon = cat.getIcon();
                    if (icon != null) icon.draw(g, xy[0] + (16 - icon.getWidth()) / 2, xy[1] + (16 - icon.getHeight()) / 2);
                } catch (Throwable ignored) {
                }
            });
        }
        for (Map.Entry<ResourceLocation, List<ItemStack>> e : catalysts.entrySet()) {
            RecipeIndex.CATALYSTS.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
        // страницы «Информация» (подсказки модов)
        int infoN = 0;
        for (Object[] info : infos) {
            List<ItemStack> items = new ArrayList<>();
            for (Object o : (List<?>) info[0]) if (o instanceof ItemStack s) items.add(s);
            if (items.isEmpty()) continue;
            List<Component> lines = new ArrayList<>();
            for (Object c : (Object[]) info[1]) if (c instanceof Component comp) lines.add(comp);
            InfoCard card = new InfoCard(items, lines);
            RecipeIndex.addExternal(new RecipeIndex.Entry(new ResourceLocation(INFO.getNamespace(), INFO.getPath() + "/" + (infoN++)), INFO, null,
                    List.of(), 0, List.of(items), "", new ItemStack(net.minecraft.world.item.Items.BOOK), card));
        }
        if (infoN > 0) RecipeIndex.TITLES.put(INFO, Component.translatable("archud.recipes.info"));

        if (!runtimeSent) {
            runtimeSent = true;
            IJeiRuntime runtime = runtime();
            for (IModPlugin p : list) call(p, "onRuntimeAvailable", q -> q.onRuntimeAvailable(runtime));
        }
        JeiGate.loaded = true;
        ArcHud.LOGGER.info("Arc HUD: JEI bridge — {} categories, {} recipes, {} info pages, {} GUI handlers in {} ms",
                CATEGORIES.size(), added, infoN, CONTAINER_HANDLERS.size(), (System.nanoTime() - t0) / 1_000_000);
        return added + infoN;
    }

    private static java.util.Optional<RecipeType<?>> recipeType(ResourceLocation uid) {
        IRecipeCategory c = CATEGORIES.get(uid);
        return c == null ? java.util.Optional.empty() : java.util.Optional.of(c.getRecipeType());
    }

    static Collection<IRecipeCategory> categories() {
        return CATEGORIES.values();
    }

    // ------------------------------------------------------------------ окно рецептов как IRecipesGui

    private static IRecipesGui recipesGui() {
        return Stub.of(IRecipesGui.class)
                .on("showTypes", 1, a -> {
                    List<ResourceLocation> uids = new ArrayList<>();
                    for (Object t : (List<?>) a[0]) uids.add(((RecipeType<?>) t).getUid());
                    ArcRecipeScreen.openTypes(Minecraft.getInstance().screen, uids);
                    return null;
                })
                .on("show", 1, a -> {
                    Object f = a[0] instanceof List<?> l ? (l.isEmpty() ? null : l.get(0)) : a[0];
                    if (f instanceof IFocus<?> focus && focus.getTypedValue().getIngredient() instanceof ItemStack s) {
                        ArcRecipeScreen.open(Minecraft.getInstance().screen, s, focus.getRole() == RecipeIngredientRole.INPUT);
                    }
                    return null;
                })
                .on("getParentScreen", 0, a -> java.util.Optional.ofNullable(Minecraft.getInstance().screen))
                .build();
    }

    private static IJeiRuntime runtime() {
        IRecipesGui gui = recipesGui();
        return Stub.of(IJeiRuntime.class)
                .on("getRecipesGui", 0, a -> gui)
                .on("getJeiHelpers", 0, a -> helpers)
                .on("getIngredientManager", 0, a -> helpers.getIngredientManager())
                .build();
    }

    // ------------------------------------------------------------------ GUI модов

    /** Прямоугольники экрана, которые GUI мода занимает кроме основного окна (боковые панели и т. п.). */
    public static List<int[]> extraAreas(Screen s) {
        List<int[]> out = new ArrayList<>();
        for (Object[] h : CONTAINER_HANDLERS) {
            if (!((Class<?>) h[0]).isInstance(s) || !(s instanceof AbstractContainerScreen<?> cs)) continue;
            try {
                for (Rect2i r : ((IGuiContainerHandler) h[1]).getGuiExtraAreas(cs)) out.add(new int[]{r.getX(), r.getY(), r.getWidth(), r.getHeight()});
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

    /** Приём ингредиентов (станки «addRecipeCatalyst(type, acceptor -> …)»). */
    static IIngredientAcceptor<?> acceptor(List<Object> out) {
        return JeiHelpers.collector(out);
    }

    static IIngredientType<ItemStack> items() {
        return VanillaTypes.ITEM_STACK;
    }
}
