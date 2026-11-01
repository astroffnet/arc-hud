package com.arc4io.archud.client.jei;

import com.arc4io.archud.client.recipes.RecipeIndex;
import com.arc4io.archud.client.recipes.RecipeOverlay;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IScreenHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Рабочий IJeiRuntime для плагинов модов: менеджер рецептов (поиск, скрытие, добавление, отрисовка рецептов
 * в чужих GUI — например, в книгах-справочниках), фильтр и список ингредиентов, помощник экранов, перенос.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class JeiRuntime {
    private static IRecipeManager manager;

    private JeiRuntime() {}

    static IJeiRuntime create() {
        IRecipeManager rm = recipeManager();
        IIngredientFilter filter = filter();
        IIngredientListOverlay list = listOverlay();
        IBookmarkOverlay bookmarks = Stub.of(IBookmarkOverlay.class)
                .on("getIngredientUnderMouse", 0, a -> Optional.empty())
                .on("getIngredientUnderMouse", 1, a -> null)
                .build();
        IScreenHelper screens = screenHelper();
        IRecipeTransferManager transfer = Stub.of(IRecipeTransferManager.class)
                .on("getRecipeTransferHandler", 2, a -> JeiTransfer.handlerFor(a[0], (IRecipeCategory) a[1]))
                .build();
        return Stub.of(IJeiRuntime.class)
                .on("getRecipeManager", 0, a -> rm)
                .on("getRecipesGui", 0, a -> JeiBridge.recipesGui())
                .on("getIngredientFilter", 0, a -> filter)
                .on("getIngredientListOverlay", 0, a -> list)
                .on("getBookmarkOverlay", 0, a -> bookmarks)
                .on("getJeiHelpers", 0, a -> JeiBridge.helpers)
                .on("getIngredientManager", 0, a -> JeiBridge.helpers.getIngredientManager())
                .on("getScreenHelper", 0, a -> screens)
                .on("getRecipeTransferManager", 0, a -> transfer)
                .build();
    }

    // ------------------------------------------------------------------ рецепты

    /** Рецепт подходит фокусу: предмет есть среди входов (INPUT/CATALYST) или выходов (OUTPUT). */
    private static boolean matches(Object recipe, Collection<? extends IFocus<?>> focuses) {
        if (focuses == null || focuses.isEmpty()) return true;
        RecipeIndex.Entry e = JeiBridge.ENTRY_OF.get(recipe);
        if (e == null) return false;
        for (IFocus<?> f : focuses) {
            Object v = f.getTypedValue().getIngredient();
            ItemStack s = v instanceof ItemStack st ? st : JeiCard.bucketOf(v);
            if (s.isEmpty()) continue;
            List<List<ItemStack>> side = f.getRole() == RecipeIngredientRole.OUTPUT ? e.outputs() : e.inputs();
            for (List<ItemStack> alt : side) for (ItemStack x : alt) if (x.getItem() == s.getItem()) return true;
        }
        return false;
    }

    static IRecipeManager recipeManager() {
        if (manager != null) return manager;
        manager = Stub.of(IRecipeManager.class)
                .on("createRecipeLookup", 1, a -> lookup((RecipeType<?>) a[0]))
                .on("createRecipeCategoryLookup", 0, a -> categoryLookup())
                .on("getRecipeCategory", 1, a -> JeiBridge.category((RecipeType<?>) a[0]))
                .on("createRecipeCatalystLookup", 1, a -> catalystLookup((RecipeType<?>) a[0]))
                .on("hideRecipes", 2, a -> {
                    RecipeIndex.hide(entries((Collection<?>) a[1]), true);
                    return null;
                })
                .on("unhideRecipes", 2, a -> {
                    RecipeIndex.hide(entries((Collection<?>) a[1]), false);
                    return null;
                })
                .on("addRecipes", 2, a -> {
                    ResourceLocation uid = ((RecipeType<?>) a[0]).getUid();
                    for (Object r : (List<?>) a[1]) {
                        if (JeiBridge.convert(uid, r, 0) != null) JeiBridge.RAW.computeIfAbsent(uid, k -> new ArrayList<>()).add(r);
                    }
                    return null;
                })
                .on("hideRecipeCategory", 1, a -> {
                    RecipeIndex.hideType(JeiBridge.ours(((RecipeType<?>) a[0]).getUid()), true);
                    return null;
                })
                .on("unhideRecipeCategory", 1, a -> {
                    RecipeIndex.hideType(JeiBridge.ours(((RecipeType<?>) a[0]).getUid()), false);
                    return null;
                })
                .on("createRecipeLayoutDrawable", a -> layoutDrawable((IRecipeCategory) a[0], a[1]))
                .on("createRecipeLayoutDrawableOrShowError", a -> layoutDrawable((IRecipeCategory) a[0], a[1]).orElse(null))
                .on("getRecipeType", 1, a -> JeiBridge.recipeType((ResourceLocation) a[0]))
                .on("getRecipeType", 2, a -> JeiBridge.recipeType((ResourceLocation) a[0]))
                .build();
        return manager;
    }

    private static List<RecipeIndex.Entry> entries(Collection<?> recipes) {
        List<RecipeIndex.Entry> out = new ArrayList<>();
        for (Object r : recipes) {
            RecipeIndex.Entry e = JeiBridge.ENTRY_OF.get(r);
            if (e != null) out.add(e);
        }
        return out;
    }

    private static Object lookup(RecipeType<?> type) {
        List<IFocus<?>> focuses = new ArrayList<>();
        Object[] self = new Object[1];
        self[0] = Stub.of(mezz.jei.api.recipe.IRecipeLookup.class)
                .on("limitFocus", 1, a -> {
                    for (Object f : (Collection<?>) a[0]) focuses.add((IFocus<?>) f);
                    return self[0];
                })
                .on("includeHidden", 0, a -> self[0])
                .on("get", 0, a -> {
                    List<Object> all = JeiBridge.RAW.getOrDefault(type.getUid(), List.of());
                    List<Object> out = new ArrayList<>();
                    for (Object r : all) if (type.getRecipeClass().isInstance(r) && matches(r, focuses)) out.add(r);
                    return out.stream();
                })
                .build();
        return self[0];
    }

    private static Object categoryLookup() {
        List<RecipeType<?>> limit = new ArrayList<>();
        List<IFocus<?>> focuses = new ArrayList<>();
        Object[] self = new Object[1];
        self[0] = Stub.of(mezz.jei.api.recipe.IRecipeCategoriesLookup.class)
                .on("limitTypes", 1, a -> {
                    for (Object t : (Collection<?>) a[0]) limit.add((RecipeType<?>) t);
                    return self[0];
                })
                .on("limitFocus", 1, a -> {
                    for (Object f : (Collection<?>) a[0]) focuses.add((IFocus<?>) f);
                    return self[0];
                })
                .on("includeHidden", 0, a -> self[0])
                .on("get", 0, a -> {
                    List<IRecipeCategory<?>> out = new ArrayList<>();
                    for (RecipeType<?> t : limit.isEmpty() ? JeiBridge.allTypes() : limit) {
                        if (!focuses.isEmpty()) {
                            boolean any = false;
                            for (Object r : JeiBridge.RAW.getOrDefault(t.getUid(), List.of())) if (matches(r, focuses)) {
                                any = true;
                                break;
                            }
                            if (!any) continue;
                        }
                        out.add(JeiBridge.category(t));
                    }
                    return out.stream();
                })
                .build();
        return self[0];
    }

    private static Object catalystLookup(RecipeType<?> type) {
        List<ItemStack> items = RecipeIndex.catalysts(JeiBridge.ours(type.getUid()));
        Object[] self = new Object[1];
        self[0] = Stub.of(mezz.jei.api.recipe.IRecipeCatalystLookup.class)
                .on("includeHidden", 0, a -> self[0])
                .on("get", 0, a -> items.stream().map(s -> (ITypedIngredient<?>) JeiHelpers.typed(VanillaTypes.ITEM_STACK, s)))
                .on("get", 1, a -> a[0] == VanillaTypes.ITEM_STACK ? items.stream() : Stream.empty())
                .on("getGroups", 0, a -> items.stream().map(s -> (Consumer<Object>) acc ->
                        ((mezz.jei.api.gui.builder.IIngredientAcceptor) acc).addIngredient(VanillaTypes.ITEM_STACK, s)))
                .build();
        return self[0];
    }

    /** Рецепт для отрисовки в чужом окне (книги-справочники модов рисуют рецепты через JEI). */
    private static Optional<IRecipeLayoutDrawable<?>> layoutDrawable(IRecipeCategory cat, Object recipe) {
        JeiCard card;
        try {
            card = JeiCard.create(cat, recipe, JeiBridge.helpers);
        } catch (Throwable t) {
            return Optional.empty();
        }
        if (card == null) return Optional.empty();
        int[] pos = {0, 0};
        IRecipeLayoutDrawable d = Stub.of(IRecipeLayoutDrawable.class)
                .on("setPosition", 2, a -> {
                    pos[0] = (Integer) a[0];
                    pos[1] = (Integer) a[1];
                    return null;
                })
                .on("drawRecipe", 3, a -> {
                    GuiGraphics g = (GuiGraphics) a[0];
                    g.pose().pushPose();
                    g.pose().translate(pos[0], pos[1], 0);
                    card.render(g, (Integer) a[1] - pos[0], (Integer) a[2] - pos[1]);
                    g.pose().popPose();
                    return null;
                })
                .on("drawOverlays", 3, a -> {
                    GuiGraphics g = (GuiGraphics) a[0];
                    int mx = (Integer) a[1] - pos[0], my = (Integer) a[2] - pos[1];
                    if (mx < 0 || my < 0 || mx >= card.width() || my >= card.height()) return null;
                    List<net.minecraft.network.chat.Component> tip = card.tooltip(mx, my);
                    if (tip != null && !tip.isEmpty()) g.renderComponentTooltip(net.minecraft.client.Minecraft.getInstance().font, tip, (Integer) a[1], (Integer) a[2]);
                    return null;
                })
                .on("isMouseOver", 2, a -> {
                    double mx = (Double) a[0] - pos[0], my = (Double) a[1] - pos[1];
                    return mx >= 0 && my >= 0 && mx < card.width() && my < card.height();
                })
                .on("getItemStackUnderMouse", 2, a -> {
                    ItemStack s = card.stackAt((Integer) a[0] - pos[0], (Integer) a[1] - pos[1]);
                    return s.isEmpty() ? Optional.empty() : Optional.of(s);
                })
                .on("getIngredientUnderMouse", 3, a -> {
                    ItemStack s = card.stackAt((Integer) a[0] - pos[0], (Integer) a[1] - pos[1]);
                    return a[2] == VanillaTypes.ITEM_STACK && !s.isEmpty() ? Optional.of(s) : Optional.empty();
                })
                .on("getRect", 0, a -> new Rect2i(pos[0], pos[1], card.width(), card.height()))
                .on("getRectWithBorder", 0, a -> new Rect2i(pos[0] - 4, pos[1] - 4, card.width() + 8, card.height() + 8))
                .on("getRecipeTransferButtonArea", 0, a -> new Rect2i(pos[0] + card.width() + 2, pos[1] + card.height() - 13, 13, 13))
                .on("getRecipeBookmarkButtonArea", 0, a -> new Rect2i(pos[0] + card.width() + 2, pos[1] + card.height() - 28, 13, 13))
                .on("getRecipeSlotsView", 0, a -> card.slotsView())
                .on("getRecipeCategory", 0, a -> cat)
                .on("getRecipe", 0, a -> recipe)
                .on("getSourceRecipeIds", 0, a -> List.of())
                .build();
        return Optional.of(d);
    }

    // ------------------------------------------------------------------ список ингредиентов

    static IIngredientFilter filter() {
        return Stub.of(IIngredientFilter.class)
                .on("setFilterText", 1, a -> {
                    RecipeOverlay.setQuery((String) a[0]);
                    return null;
                })
                .on("getFilterText", 0, a -> RecipeOverlay.query())
                .on("getFilteredItemStacks", 0, a -> new ArrayList<>(RecipeOverlay.filteredItems()))
                .on("getFilteredIngredients", 1, a -> a[0] == VanillaTypes.ITEM_STACK ? new ArrayList<>(RecipeOverlay.filteredItems())
                        : new ArrayList<>(JeiBridge.INGREDIENTS.getOrDefault((IIngredientType<?>) a[0], List.of())))
                .build();
    }

    static IIngredientListOverlay listOverlay() {
        return Stub.of(IIngredientListOverlay.class)
                .on("getIngredientUnderMouse", 0, a -> {
                    ItemStack s = RecipeOverlay.hoveredItem();
                    return s.isEmpty() ? Optional.empty() : Optional.of(JeiHelpers.typed(VanillaTypes.ITEM_STACK, s));
                })
                .on("getIngredientUnderMouse", 1, a -> {
                    ItemStack s = RecipeOverlay.hoveredItem();
                    return a[0] == VanillaTypes.ITEM_STACK && !s.isEmpty() ? s : null;
                })
                .on("isListDisplayed", 0, a -> RecipeOverlay.shown())
                .on("hasKeyboardFocus", 0, a -> RecipeOverlay.searchFocused())
                .on("getVisibleIngredients", 1, a -> a[0] == VanillaTypes.ITEM_STACK ? new ArrayList<>(RecipeOverlay.filteredItems()) : new ArrayList<>())
                .build();
    }

    // ------------------------------------------------------------------ экраны

    private static IScreenHelper screenHelper() {
        return Stub.of(IScreenHelper.class)
                .on("getClickableIngredientUnderMouse", 3, a -> {
                    ItemStack s = JeiBridge.ingredientUnder((Screen) a[0], (Double) a[1], (Double) a[2]);
                    if (s.isEmpty()) return Stream.empty();
                    return JeiHelpers.ingredientManager().createClickableIngredient(VanillaTypes.ITEM_STACK, s,
                            new Rect2i((int) (double) (Double) a[1], (int) (double) (Double) a[2], 1, 1), false).stream();
                })
                .on("getGuiExclusionAreas", 1, a -> JeiBridge.extraAreas((Screen) a[0]).stream().map(r -> new Rect2i(r[0], r[1], r[2], r[3])))
                .on("getGhostIngredientHandlers", 1, a -> JeiTransfer.ghostHandlers((Screen) a[0]))
                .build();
    }

    static List<IRecipeSlotDrawable> none() {
        return List.of();
    }
}
