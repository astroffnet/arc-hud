package com.arc4io.archud.client.jei;

import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Слот рецепта из плагина JEI: роль, позиция, ингредиенты (предметы, жидкости, ингредиенты модов), фон. */
@SuppressWarnings({"rawtypes", "unchecked"})
final class JeiSlot {
    /** Жидкость без привязки к Forge/Fabric. */
    record FluidValue(Fluid fluid, long amount) {}

    final RecipeIngredientRole role;
    int x, y, w = 16, h = 16;
    final List<Object> values = new ArrayList<>();
    final List<IIngredientType<?>> types = new ArrayList<>();
    String name;
    IDrawable background;
    int bgX = -1, bgY = -1;
    boolean standardBackground, outputBackground;
    IDrawable overlay;
    int ovX, ovY;
    long fluidCapacity;
    boolean invisible;
    final List<Object> tooltipCallbacks = new ArrayList<>();
    IRecipeSlotView view;

    JeiSlot(RecipeIngredientRole role) {
        this.role = role;
    }

    /** Показываемый сейчас ингредиент (варианты перебираются раз в секунду). */
    Object displayed() {
        if (values.isEmpty()) return null;
        return values.get((int) ((Util.getMillis() / 1000) % values.size()));
    }

    void add(IIngredientType<?> type, Object v) {
        if (v == null) return;
        if (v instanceof ItemStack s && s.isEmpty()) return;
        values.add(v);
        types.add(type);
    }

    List<ItemStack> itemStacks() {
        List<ItemStack> out = new ArrayList<>();
        for (Object v : values) if (v instanceof ItemStack s) out.add(s);
        return out;
    }

    // ------------------------------------------------------------------ строитель слота

    IRecipeSlotBuilder builder() {
        IRecipeSlotBuilder[] self = new IRecipeSlotBuilder[1];
        self[0] = Stub.of(IRecipeSlotBuilder.class)
                .on("addIngredients", 2, a -> {
                    if (a[0] instanceof IIngredientType<?> t) for (Object o : (List<?>) a[1]) add(t, o);
                    else if (a[0] instanceof net.minecraft.world.item.crafting.Ingredient ing) {
                        for (ItemStack s : ing.getItems()) add(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, s);
                    }
                    return self[0];
                })
                .on("addIngredients", 1, a -> {
                    for (ItemStack s : ((net.minecraft.world.item.crafting.Ingredient) a[0]).getItems()) add(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, s);
                    return self[0];
                })
                .on("addIngredient", 2, a -> {
                    add((IIngredientType<?>) a[0], a[1]);
                    return self[0];
                })
                .on("addIngredientsUnsafe", 1, a -> {
                    for (Object o : (List<?>) a[0]) add(JeiHelpers.typeOf(o), o);
                    return self[0];
                })
                .on("addTypedIngredients", 1, a -> {
                    for (Object o : (List<?>) a[0]) {
                        ITypedIngredient<?> t = (ITypedIngredient<?>) o;
                        add(t.getType(), t.getIngredient());
                    }
                    return self[0];
                })
                .on("addOptionalTypedIngredients", 1, a -> {
                    for (Object o : (List<?>) a[0]) ((Optional<?>) o).ifPresent(t -> add(((ITypedIngredient<?>) t).getType(), ((ITypedIngredient<?>) t).getIngredient()));
                    return self[0];
                })
                .on("addFluidStack", a -> {
                    add(null, new FluidValue((Fluid) a[0], a.length > 1 ? (Long) a[1] : 1000L));
                    return self[0];
                })
                .on("setPosition", 2, a -> {
                    x = (Integer) a[0];
                    y = (Integer) a[1];
                    return self[0];
                })
                .on("setPosition", 6, a -> {
                    // в области (x,y,w,h) с выравниванием — ставим по центру
                    int ax = (Integer) a[0], ay = (Integer) a[1], aw = (Integer) a[2], ah = (Integer) a[3];
                    x = ax + (aw - w) / 2;
                    y = ay + (ah - h) / 2;
                    return self[0];
                })
                .on("getWidth", 0, a -> w)
                .on("getHeight", 0, a -> h)
                .on("setSlotName", 1, a -> {
                    name = (String) a[0];
                    return self[0];
                })
                .on("setStandardSlotBackground", 0, a -> {
                    standardBackground = true;
                    return self[0];
                })
                .on("setOutputSlotBackground", 0, a -> {
                    outputBackground = true;
                    return self[0];
                })
                .on("setBackground", 3, a -> {
                    background = (IDrawable) a[0];
                    bgX = (Integer) a[1];
                    bgY = (Integer) a[2];
                    return self[0];
                })
                .on("setOverlay", 3, a -> {
                    overlay = (IDrawable) a[0];
                    ovX = (Integer) a[1];
                    ovY = (Integer) a[2];
                    return self[0];
                })
                .on("setFluidRenderer", a -> {
                    fluidCapacity = (Long) a[0];
                    w = (Integer) a[2];
                    h = (Integer) a[3];
                    return self[0];
                })
                .on("addTooltipCallback", 1, a -> {
                    tooltipCallbacks.add(a[0]);
                    return self[0];
                })
                .on("addRichTooltipCallback", 1, a -> {
                    tooltipCallbacks.add(a[0]);
                    return self[0];
                })
                .build();
        return self[0];
    }

    /** Вид слота для плагина (draw, подсказки): что в нём лежит. */
    IRecipeSlotView view() {
        if (view != null) return view;
        view = Stub.of(IRecipeSlotView.class)
                .on("getAllIngredients", 0, a -> typedList().stream())
                .on("getAllIngredientsList", 0, a -> typedList())
                .on("getDisplayedIngredient", 0, a -> {
                    Object d = displayed();
                    return d == null ? Optional.empty() : Optional.of(JeiHelpers.typed((IIngredientType) typeFor(d), d));
                })
                .on("getDisplayedIngredients", 0, a -> {
                    Object d = displayed();
                    return d == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(JeiHelpers.typed((IIngredientType) typeFor(d), d));
                })
                .on("getTagKey", 0, a -> Optional.empty())
                .on("getRole", 0, a -> role)
                .on("drawHighlight", 2, a -> null)
                .on("getSlotName", 0, a -> Optional.ofNullable(name))
                .build();
        return view;
    }

    private IIngredientType<?> typeFor(Object v) {
        int i = values.indexOf(v);
        IIngredientType<?> t = i >= 0 && i < types.size() ? types.get(i) : null;
        return t != null ? t : JeiHelpers.typeOf(v);
    }

    private List<ITypedIngredient<?>> typedList() {
        List<ITypedIngredient<?>> out = new ArrayList<>();
        for (Object v : values) out.add(JeiHelpers.typed((IIngredientType) typeFor(v), v));
        return out;
    }

    // ------------------------------------------------------------------ строитель раскладки

    static IRecipeLayoutBuilder layout(List<JeiSlot> slots, boolean[] shapeless) {
        return Stub.of(IRecipeLayoutBuilder.class)
                .on("addSlot", 1, a -> {
                    JeiSlot s = new JeiSlot((RecipeIngredientRole) a[0]);
                    slots.add(s);
                    return s.builder();
                })
                .on("addSlotToWidget", 2, a -> {
                    JeiSlot s = new JeiSlot((RecipeIngredientRole) a[0]);
                    slots.add(s);
                    return s.builder();
                })
                .on("addInvisibleIngredients", 1, a -> {
                    JeiSlot s = new JeiSlot((RecipeIngredientRole) a[0]);
                    s.invisible = true;
                    slots.add(s);
                    return s.builder();
                })
                .on("setShapeless", a -> {
                    shapeless[0] = true;
                    return null;
                })
                .build();
    }

    static IIngredientAcceptor<?> acceptorOf(JeiSlot s) {
        return s.builder();
    }
}
