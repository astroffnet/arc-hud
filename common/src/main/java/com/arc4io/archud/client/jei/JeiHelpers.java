package com.arc4io.archud.client.jei;

import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.recipes.RecipeIndex;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.helpers.IStackHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.vanilla.IJeiBrewingRecipe;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Помощники, которые JEI даёт плагинам: картинки, ингредиенты, фокусы, сетка крафта, фабрика ванильных рецептов. */
@SuppressWarnings({"rawtypes", "unchecked"})
final class JeiHelpers {
    /** Рендеры и помощники типов ингредиентов, зарегистрированных модами (газы, энергия, эссенции…). */
    static final Map<Class<?>, Object> INGREDIENT_RENDERERS = new HashMap<>();
    static final Map<Class<?>, Object> INGREDIENT_HELPERS = new HashMap<>();
    static final List<IIngredientType<?>> INGREDIENT_TYPES = new ArrayList<>();

    private JeiHelpers() {}

    // ------------------------------------------------------------------ типы и ингредиенты

    static IIngredientType<?> typeOf(Object v) {
        if (v instanceof ItemStack) return VanillaTypes.ITEM_STACK;
        for (IIngredientType<?> t : INGREDIENT_TYPES) if (t.getIngredientClass().isInstance(v)) return t;
        Class<?> c = v == null ? Object.class : v.getClass();
        return Stub.of(IIngredientType.class)
                .on("getIngredientClass", 0, a -> c)
                .on("getUid", 0, a -> c.getName())
                .build();
    }

    static <T> ITypedIngredient<T> typed(IIngredientType<T> type, T value) {
        ITypedIngredient<T>[] self = new ITypedIngredient[1];
        self[0] = Stub.of(ITypedIngredient.class)
                .on("getType", 0, a -> type)
                .on("getIngredient", 0, a -> value)
                .on("normalize", 1, a -> self[0])
                .build();
        return self[0];
    }

    /** Приёмник ингредиентов, складывающий всё в список (для станков и невидимых ингредиентов). */
    static IIngredientAcceptor<?> collector(List<Object> out) {
        IIngredientAcceptor[] self = new IIngredientAcceptor[1];
        self[0] = Stub.of(IIngredientAcceptor.class)
                .on("addIngredients", 2, a -> {
                    out.addAll((List<?>) a[1]);
                    return self[0];
                })
                .on("addIngredient", 2, a -> {
                    out.add(a[1]);
                    return self[0];
                })
                .on("addIngredientsUnsafe", 1, a -> {
                    out.addAll((List<?>) a[0]);
                    return self[0];
                })
                .on("addTypedIngredients", 1, a -> {
                    for (Object o : (List<?>) a[0]) out.add(((ITypedIngredient<?>) o).getIngredient());
                    return self[0];
                })
                .on("addOptionalTypedIngredients", 1, a -> {
                    for (Object o : (List<?>) a[0]) ((Optional<?>) o).ifPresent(t -> out.add(((ITypedIngredient<?>) t).getIngredient()));
                    return self[0];
                })
                .on("addFluidStack", a -> {
                    out.add(new JeiSlot.FluidValue((net.minecraft.world.level.material.Fluid) a[0], a.length > 1 ? (Long) a[1] : 1000L));
                    return self[0];
                })
                .build();
        return self[0];
    }

    static IFocusGroup emptyFocus() {
        return Stub.of(IFocusGroup.class)
                .on("isEmpty", 0, a -> true)
                .on("getAllFocuses", 0, a -> List.of())
                .on("getFocuses", a -> Stream.empty())
                .build();
    }

    static <V> IFocus<V> focus(RecipeIngredientRole role, ITypedIngredient<V> v) {
        return Stub.of(IFocus.class)
                .on("getTypedValue", 0, a -> v)
                .on("getRole", 0, a -> role)
                .on("checkedCast", 1, a -> Optional.empty())
                .build();
    }

    // ------------------------------------------------------------------ IJeiHelpers

    static IJeiHelpers create(Function<ResourceLocation, Optional<RecipeType<?>>> types) {
        IGuiHelper gui = guiHelper();
        IIngredientManager ingredients = ingredientManager();
        IFocusFactory focus = Stub.of(IFocusFactory.class)
                .on("createFocus", 3, a -> focus((RecipeIngredientRole) a[0], typed((IIngredientType) a[1], a[2])))
                .on("createFocus", 2, a -> focus((RecipeIngredientRole) a[0], (ITypedIngredient) a[1]))
                .on("createFocusGroup", 1, a -> emptyFocus())
                .on("getEmptyFocusGroup", 0, a -> emptyFocus())
                .build();
        IModIdHelper modIds = Stub.of(IModIdHelper.class)
                .on("getModNameForModId", 1, a -> com.arc4io.archud.ArcHud.platform().modName((String) a[0]))
                .on("getFormattedModNameForModId", 1, a -> "§9§o" + com.arc4io.archud.ArcHud.platform().modName((String) a[0]))
                .on("isDisplayingModNameEnabled", 0, a -> true)
                .on("addModNameToIngredientTooltip", a -> a[0])
                .build();
        IStackHelper stacks = Stub.of(IStackHelper.class)
                .on("isEquivalent", 3, a -> ItemStack.isSameItemSameTags((ItemStack) a[0], (ItemStack) a[1]))
                .on("getUniqueIdentifierForStack", 2, a -> BuiltInRegistries.ITEM.getKey(((ItemStack) a[0]).getItem()).toString())
                .build();
        IColorHelper colors = Stub.empty(IColorHelper.class);
        IPlatformFluidHelper fluids = Stub.empty(IPlatformFluidHelper.class);
        IVanillaRecipeFactory vanilla = vanillaFactory();
        IIngredientVisibility visibility = Stub.of(IIngredientVisibility.class).on("isIngredientVisible", a -> true).build();
        return Stub.of(IJeiHelpers.class)
                .on("getGuiHelper", 0, a -> gui)
                .on("getStackHelper", 0, a -> stacks)
                .on("getModIdHelper", 0, a -> modIds)
                .on("getFocusFactory", 0, a -> focus)
                .on("getColorHelper", 0, a -> colors)
                .on("getPlatformFluidHelper", 0, a -> fluids)
                .on("getRecipeType", 2, a -> types.apply((ResourceLocation) a[0]))
                .on("getRecipeType", 1, a -> types.apply((ResourceLocation) a[0]))
                .on("getAllRecipeTypes", 0, a -> JeiBridge.categories().stream().map(c -> c.getRecipeType()))
                .on("getIngredientManager", 0, a -> ingredients)
                .on("getVanillaRecipeFactory", 0, a -> vanilla)
                .on("getIngredientVisibility", 0, a -> visibility)
                .build();
    }

    static IIngredientManager ingredientManager() {
        return Stub.of(IIngredientManager.class)
                .on("getAllIngredients", 1, a -> a[0] == VanillaTypes.ITEM_STACK ? RecipeIndex.items() : List.of())
                .on("getIngredientHelper", 1, a -> {
                    Class<?> c = a[0] instanceof IIngredientType<?> t ? t.getIngredientClass() : a[0] == null ? Object.class : a[0].getClass();
                    Object h = INGREDIENT_HELPERS.get(c);
                    return h != null ? h : itemHelper();
                })
                .on("getIngredientRenderer", 1, a -> {
                    Class<?> c = a[0] instanceof IIngredientType<?> t ? t.getIngredientClass() : a[0] == null ? Object.class : a[0].getClass();
                    return INGREDIENT_RENDERERS.get(c);
                })
                .on("getRegisteredIngredientTypes", 0, a -> {
                    List<IIngredientType<?>> l = new ArrayList<>(INGREDIENT_TYPES);
                    l.add(0, VanillaTypes.ITEM_STACK);
                    return l;
                })
                .on("getIngredientTypeChecked", 1, a -> Optional.of(a[0] instanceof Class<?> c && ItemStack.class.isAssignableFrom(c)
                        ? VanillaTypes.ITEM_STACK : typeOf(a[0])))
                .on("createTypedIngredient", 2, a -> Optional.of(typed((IIngredientType) a[0], a[1])))
                .on("normalizeTypedIngredient", 1, a -> a[0])
                .build();
    }

    static IIngredientHelper<ItemStack> itemHelper() {
        return Stub.of(IIngredientHelper.class)
                .on("getIngredientType", 0, a -> VanillaTypes.ITEM_STACK)
                .on("getDisplayName", 1, a -> ((ItemStack) a[0]).getHoverName().getString())
                .on("getUniqueId", 2, a -> BuiltInRegistries.ITEM.getKey(((ItemStack) a[0]).getItem()).toString())
                .on("getResourceLocation", 1, a -> BuiltInRegistries.ITEM.getKey(((ItemStack) a[0]).getItem()))
                .on("copyIngredient", 1, a -> ((ItemStack) a[0]).copy())
                .on("getErrorInfo", 1, a -> String.valueOf(a[0]))
                .build();
    }

    /** Ванильные «рецепты» JEI от модов: варка зелий попадает в нашу категорию варочной стойки. */
    static IVanillaRecipeFactory vanillaFactory() {
        return Stub.of(IVanillaRecipeFactory.class)
                .on("createBrewingRecipe", a -> {
                    List<ItemStack> ingredients = (List<ItemStack>) a[0];
                    List<ItemStack> inputs = a[1] instanceof List<?> l ? (List<ItemStack>) l : List.of((ItemStack) a[1]);
                    ItemStack out = (ItemStack) a[2];
                    for (ItemStack in : inputs) {
                        RecipeIndex.addExternal(new RecipeIndex.Entry(new ResourceLocation("archud", "jei_brewing/" + System.identityHashCode(in)),
                                RecipeIndex.BREWING, null, List.of(List.of(in), new ArrayList<>(ingredients)), 0, List.of(List.of(out)), "",
                                new ItemStack(net.minecraft.world.item.Items.BREWING_STAND)));
                    }
                    return Stub.empty(IJeiBrewingRecipe.class);
                })
                .build();
    }

    // ------------------------------------------------------------------ IGuiHelper

    static IGuiHelper guiHelper() {
        return Stub.of(IGuiHelper.class)
                .on("drawableBuilder", 5, a -> JeiDraw.builder((ResourceLocation) a[0], (Integer) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4]))
                .on("createDrawableSprite", 2, a -> JeiDraw.sprite((TextureAtlas) a[0], (ResourceLocation) a[1], 16, 16))
                .on("createDrawableSprite", 4, a -> JeiDraw.sprite((TextureAtlas) a[0], (ResourceLocation) a[1], (Integer) a[2], (Integer) a[3]))
                .on("createScalableDrawableSprite", 2, a -> JeiDraw.scalableSprite((TextureAtlas) a[0], (ResourceLocation) a[1]))
                .on("createAnimatedDrawable", 4, a -> JeiDraw.animated((IDrawableStatic) a[0], JeiDraw.timer((Integer) a[1], (Boolean) a[3]),
                        (IDrawableAnimated.StartDirection) a[2]))
                .on("createAnimatedDrawable", 3, a -> JeiDraw.animated((IDrawableStatic) a[0], (mezz.jei.api.gui.ITickTimer) a[1],
                        (IDrawableAnimated.StartDirection) a[2]))
                .on("getSlotDrawable", 0, a -> JeiDraw.slot())
                .on("getOutputSlot", 0, a -> JeiDraw.outputSlot())
                .on("getRecipeArrow", 0, a -> JeiDraw.arrow(false))
                .on("getRecipeArrowFilled", 0, a -> JeiDraw.arrow(true))
                .on("createAnimatedRecipeArrow", 1, a -> JeiDraw.animated(JeiDraw.arrow(true), JeiDraw.timer((Integer) a[0], false),
                        IDrawableAnimated.StartDirection.LEFT))
                .on("getRecipePlusSign", 0, a -> JeiDraw.plus())
                .on("getRecipeFlameFilled", 0, a -> JeiDraw.flame(true))
                .on("getRecipeFlameEmpty", 0, a -> JeiDraw.flame(false))
                .on("createAnimatedRecipeFlame", 1, a -> JeiDraw.animated(JeiDraw.flame(true), JeiDraw.timer((Integer) a[0], true),
                        IDrawableAnimated.StartDirection.TOP))
                .on("createBlankDrawable", 2, a -> JeiDraw.blank((Integer) a[0], (Integer) a[1]))
                .on("createDrawableIngredient", 2, a -> a[1] instanceof ItemStack s ? JeiDraw.item(s) : JeiDraw.blank(16, 16))
                .on("createDrawableIngredient", 1, a -> ((ITypedIngredient<?>) a[0]).getIngredient() instanceof ItemStack s ? JeiDraw.item(s) : JeiDraw.blank(16, 16))
                .on("createCraftingGridHelper", 0, a -> craftingGrid())
                .on("createTickTimer", 3, a -> JeiDraw.tickTimer((Integer) a[0], (Integer) a[1], (Boolean) a[2]))
                .on("getScrollBoxScrollbarExtraWidth", 0, a -> 6)
                .build();
    }

    /** Сетка крафта 3×3 как у JEI: входы с (1,1) шагом 18, результат в (95,19). */
    static ICraftingGridHelper craftingGrid() {
        return Stub.of(ICraftingGridHelper.class)
                .on("getCraftingIndex", 3, a -> {
                    int i = (Integer) a[0], w = (Integer) a[1], h = (Integer) a[2];
                    int index;
                    if (w == 1) index = h == 3 ? i * 3 + 1 : h == 2 ? i * 3 + 1 : 4;
                    else if (h == 1) index = i + 3;
                    else if (w == 2) index = h == 2 ? (i % 2) + (i / 2) * 3 : (i % 2) + (i / 2) * 3;
                    else index = i;
                    return index;
                })
                .on("createAndSetInputs", 5, a -> grid((IRecipeLayoutBuilder) a[0], (IIngredientType) a[1], (List) a[2], (Integer) a[3], (Integer) a[4]))
                .on("createAndSetIngredients", 4, a -> {
                    List<List<ItemStack>> lists = new ArrayList<>();
                    for (Object o : (List<?>) a[1]) lists.add(o == null ? List.of() : List.of(((Ingredient) o).getItems()));
                    grid((IRecipeLayoutBuilder) a[0], VanillaTypes.ITEM_STACK, (List) lists, (Integer) a[2], (Integer) a[3]);
                    return null;
                })
                .on("createAndSetOutputs", 3, a -> ((IRecipeLayoutBuilder) a[0]).addSlot(RecipeIngredientRole.OUTPUT, 95, 19)
                        .addIngredients((IIngredientType) a[1], (List) a[2]))
                .on("setInputs", 5, a -> {
                    List<IRecipeSlotBuilder> slots = (List<IRecipeSlotBuilder>) a[0];
                    List<List<?>> inputs = (List<List<?>>) a[2];
                    for (int i = 0; i < inputs.size() && i < slots.size(); i++) {
                        if (inputs.get(i) != null) slots.get(i).addIngredients((IIngredientType) a[1], (List) inputs.get(i));
                    }
                    return null;
                })
                .build();
    }

    private static List<IRecipeSlotBuilder> grid(IRecipeLayoutBuilder b, IIngredientType type, List<List<?>> inputs, int w, int h) {
        List<IRecipeSlotBuilder> slots = new ArrayList<>();
        int width = w <= 0 ? 3 : w;
        for (int i = 0; i < 9; i++) {
            slots.add(b.addSlot(RecipeIngredientRole.INPUT, 1 + (i % 3) * 18, 1 + (i / 3) * 18));
        }
        for (int i = 0; i < inputs.size(); i++) {
            int col = i % width, row = i / width;
            int idx = row * 3 + col;
            if (idx < 9 && inputs.get(i) != null) slots.get(idx).addIngredients(type, (List) inputs.get(i));
        }
        return slots;
    }

    static Collection<ItemStack> items() {
        return RecipeIndex.items();
    }
}
