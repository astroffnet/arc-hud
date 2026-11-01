package com.arc4io.archud.client.jei;

import com.arc4io.archud.client.recipes.RecipeIndex;
import com.arc4io.archud.client.recipes.RecipeTransfer;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Перенос рецептов и ghost-ингредиенты из плагинов модов для JEI: обработчики переноса модов (их пакеты идут
 * на сервер мода), простые описания слотов (переносим кликами сами) и перетаскивание предметов из списка
 * в слоты-фильтры GUI модов.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class JeiTransfer {
    private static final List<IRecipeTransferInfo> INFOS = new ArrayList<>();
    private static final Map<ResourceLocation, List<IRecipeTransferHandler>> HANDLERS = new HashMap<>();
    private static final List<Object> UNIVERSAL = new ArrayList<>();

    private JeiTransfer() {}

    static void clear() {
        INFOS.clear();
        HANDLERS.clear();
        UNIVERSAL.clear();
    }

    static int count() {
        int n = INFOS.size() + UNIVERSAL.size();
        for (List<IRecipeTransferHandler> l : HANDLERS.values()) n += l.size();
        return n;
    }

    // ------------------------------------------------------------------ регистрация

    private static IRecipeTransferInfo basicInfo(Class<?> containerClass, MenuType<?> menuType, RecipeType<?> type, int slotStart, int slotCount,
                                                 int invStart, int invCount) {
        return Stub.of(IRecipeTransferInfo.class)
                .on("getContainerClass", 0, a -> containerClass)
                .on("getMenuType", 0, a -> Optional.ofNullable(menuType))
                .on("getRecipeType", 0, a -> type)
                .on("canHandle", 2, a -> true)
                .on("getRecipeSlots", 2, a -> {
                    AbstractContainerMenu m = (AbstractContainerMenu) a[0];
                    return new ArrayList<>(m.slots.subList(slotStart, Math.min(m.slots.size(), slotStart + slotCount)));
                })
                .on("getInventorySlots", 2, a -> {
                    AbstractContainerMenu m = (AbstractContainerMenu) a[0];
                    return new ArrayList<>(m.slots.subList(invStart, Math.min(m.slots.size(), invStart + invCount)));
                })
                .on("requireCompleteSets", 2, a -> true)
                .build();
    }

    static IRecipeTransferError error(IRecipeTransferError.Type type, Component message) {
        return Stub.of(IRecipeTransferError.class)
                .on("getType", 0, a -> type)
                .on("getTooltip", 0, a -> message == null ? new ArrayList<>() : new ArrayList<>(List.of(message)))
                .on("getTooltip", 1, a -> {
                    if (message != null) ((ITooltipBuilder) a[0]).add(message);
                    return null;
                })
                .on("getButtonHighlightColor", 0, a -> 0x80FF0000)
                .build();
    }

    /** Наш обработчик по описанию слотов (вместо серверного обработчика JEI — клики работают на любом сервере). */
    private static IRecipeTransferHandler clickHandler(IRecipeTransferInfo info) {
        return Stub.of(IRecipeTransferHandler.class)
                .on("getContainerClass", 0, a -> info.getContainerClass())
                .on("getMenuType", 0, a -> info.getMenuType())
                .on("getRecipeType", 0, a -> info.getRecipeType())
                .on("transferRecipe", 6, a -> {
                    AbstractContainerMenu menu = (AbstractContainerMenu) a[0];
                    RecipeTransfer.Plan p = planFromView(menu, info, a[1], (IRecipeSlotsView) a[2], (Boolean) a[4]);
                    if (p == null) return error(IRecipeTransferError.Type.INTERNAL, null);
                    if (!p.ok()) return error(IRecipeTransferError.Type.USER_FACING, p.tooltip().isEmpty() ? null : p.tooltip().get(0));
                    if ((Boolean) a[5] && p.run() != null) p.run().run();
                    return null;
                })
                .build();
    }

    static IRecipeTransferRegistration registration() {
        IRecipeTransferHandlerHelper helper = Stub.of(IRecipeTransferHandlerHelper.class)
                .on("createInternalError", 0, a -> error(IRecipeTransferError.Type.INTERNAL, null))
                .on("createUserErrorWithTooltip", 1, a -> error(IRecipeTransferError.Type.USER_FACING, (Component) a[0]))
                .on("createUserErrorForMissingSlots", 2, a -> error(IRecipeTransferError.Type.USER_FACING, (Component) a[0]))
                .on("createBasicRecipeTransferInfo", 7, a -> basicInfo((Class<?>) a[0], (MenuType<?>) a[1], (RecipeType<?>) a[2],
                        (Integer) a[3], (Integer) a[4], (Integer) a[5], (Integer) a[6]))
                .on("createUnregisteredRecipeTransferHandler", 1, a -> clickHandler((IRecipeTransferInfo) a[0]))
                .on("recipeTransferHasServerSupport", 0, a -> true)
                .on("createRecipeSlotsView", 1, a -> {
                    List<?> views = (List<?>) a[0];
                    return Stub.of(IRecipeSlotsView.class).on("getSlotViews", 0, b -> views).build();
                })
                .on("getGuiSlotIndexToIngredientMap", 1, a -> gridMap((CraftingRecipe) a[0]))
                .build();
        return Stub.of(IRecipeTransferRegistration.class)
                .on("getJeiHelpers", 0, a -> JeiBridge.helpers)
                .on("getTransferHelper", 0, a -> helper)
                .on("addRecipeTransferHandler", 7, a -> {
                    INFOS.add(basicInfo((Class<?>) a[0], (MenuType<?>) a[1], (RecipeType<?>) a[2], (Integer) a[3], (Integer) a[4], (Integer) a[5], (Integer) a[6]));
                    return null;
                })
                .on("addRecipeTransferHandler", 1, a -> {
                    INFOS.add((IRecipeTransferInfo) a[0]);
                    return null;
                })
                .on("addRecipeTransferHandler", 2, a -> {
                    HANDLERS.computeIfAbsent(((RecipeType<?>) a[1]).getUid(), k -> new ArrayList<>()).add((IRecipeTransferHandler) a[0]);
                    return null;
                })
                .on("addUniversalRecipeTransferHandler", 1, a -> {
                    UNIVERSAL.add(a[0]);
                    return null;
                })
                .build();
    }

    private static Map<Integer, Ingredient> gridMap(CraftingRecipe r) {
        Map<Integer, Ingredient> m = new HashMap<>();
        var ings = r.getIngredients();
        int w = r instanceof ShapedRecipe s ? s.getWidth() : 3;
        for (int i = 0; i < ings.size(); i++) {
            if (ings.get(i).isEmpty()) continue;
            int idx = r instanceof ShapedRecipe ? (i / w) * 3 + i % w : i;
            m.put(idx + 1, ings.get(i));
        }
        return m;
    }

    // ------------------------------------------------------------------ подбор обработчика

    private static boolean fits(Class<?> c, Optional<?> menuType, AbstractContainerMenu menu) {
        if (c == null || !c.isInstance(menu)) return false;
        if (menuType == null || menuType.isEmpty()) return true;
        try {
            return menuType.get() == menu.getType();
        } catch (Throwable t) {
            return true; // инвентарь игрока: getType() бросает исключение
        }
    }

    static Optional<IRecipeTransferHandler> handlerFor(Object container, IRecipeCategory cat) {
        if (!(container instanceof AbstractContainerMenu menu) || cat == null) return Optional.empty();
        ResourceLocation uid = cat.getRecipeType().getUid();
        for (IRecipeTransferHandler h : HANDLERS.getOrDefault(uid, List.of())) if (fits(h.getContainerClass(), h.getMenuType(), menu)) return Optional.of(h);
        for (IRecipeTransferInfo i : INFOS) {
            if (i.getRecipeType().getUid().equals(uid) && fits(i.getContainerClass(), i.getMenuType(), menu)) return Optional.of(clickHandler(i));
        }
        return Optional.empty();
    }

    /** Слоты рецепта (как видит их плагин) для записи без карточки: сетка 3×3 и результаты. */
    private static List<JeiSlot> slotsOf(RecipeIndex.Entry e) {
        List<JeiSlot> slots = new ArrayList<>();
        boolean grid = e.type().getPath().equals("crafting");
        if (grid) {
            List<List<ItemStack>> cells = new ArrayList<>();
            for (int i = 0; i < 9; i++) cells.add(List.of());
            List<List<ItemStack>> in = e.inputs();
            for (int i = 0; i < in.size(); i++) {
                int idx = e.gridW() > 0 ? (i / e.gridW()) * 3 + i % e.gridW() : i;
                if (idx < 9) cells.set(idx, in.get(i));
            }
            for (int i = 0; i < 9; i++) {
                JeiSlot s = new JeiSlot(RecipeIngredientRole.INPUT);
                s.x = 1 + (i % 3) * 18;
                s.y = 1 + (i / 3) * 18;
                for (ItemStack st : cells.get(i)) s.add(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, st);
                slots.add(s);
            }
        } else {
            for (List<ItemStack> l : e.inputs()) {
                JeiSlot s = new JeiSlot(RecipeIngredientRole.INPUT);
                for (ItemStack st : l) s.add(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, st);
                slots.add(s);
            }
        }
        for (List<ItemStack> l : e.outputs()) {
            JeiSlot s = new JeiSlot(RecipeIngredientRole.OUTPUT);
            for (ItemStack st : l) s.add(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, st);
            slots.add(s);
        }
        return slots;
    }

    private static IRecipeSlotsView viewOf(List<JeiSlot> slots) {
        List<IRecipeSlotView> views = new ArrayList<>();
        for (JeiSlot s : slots) views.add(s.view());
        return Stub.of(IRecipeSlotsView.class).on("getSlotViews", 0, a -> views).build();
    }

    /** Предметы входных слотов вида рецепта по порядку (пустые слоты — пустые списки). */
    private static List<List<ItemStack>> inputs(IRecipeSlotsView view) {
        List<List<ItemStack>> out = new ArrayList<>();
        for (IRecipeSlotView v : view.getSlotViews()) {
            if (v.getRole() != RecipeIngredientRole.INPUT) continue;
            List<ItemStack> l = new ArrayList<>();
            v.getAllIngredients().forEach(t -> {
                if (t.getIngredient() instanceof ItemStack s && !s.isEmpty()) l.add(s);
            });
            out.add(l);
        }
        return out;
    }

    private static RecipeTransfer.Plan planFromView(AbstractContainerMenu menu, IRecipeTransferInfo info, Object recipe, IRecipeSlotsView view, boolean max) {
        try {
            if (!info.canHandle(menu, recipe)) return null;
            List<Slot> targets = info.getRecipeSlots(menu, recipe);
            List<Slot> inv = info.getInventorySlots(menu, recipe);
            return RecipeTransfer.clicks(menu, targets, inputs(view), inv, max);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ для окна рецептов (через JeiGate)

    /** План переноса для записи (карточка мода или обычный рецепт) в окно parent; null — плагины не помогают. */
    public static RecipeTransfer.Plan transferPlan(Screen parent, RecipeIndex.Entry e, boolean max) {
        if (!(parent instanceof AbstractContainerScreen<?> cs)) return null;
        AbstractContainerMenu menu = cs.getMenu();
        Object recipe;
        ResourceLocation uid;
        IRecipeSlotsView view;
        List<JeiSlot> slots;
        if (e.card() instanceof JeiCard card) {
            recipe = card.recipe();
            uid = card.category().getRecipeType().getUid();
            view = card.slotsView();
            slots = card.slots();
        } else {
            recipe = e.recipe();
            uid = JeiBridge.jeiUid(e.type());
            slots = slotsOf(e);
            view = viewOf(slots);
        }
        if (recipe != null) {
            for (IRecipeTransferHandler h : HANDLERS.getOrDefault(uid, List.of())) {
                if (!fits(h.getContainerClass(), h.getMenuType(), menu)) continue;
                RecipeTransfer.Plan p = viaHandler(cs, h, recipe, view, max);
                if (p != null) return p;
            }
            for (IRecipeTransferInfo info : INFOS) {
                if (!info.getRecipeType().getUid().equals(uid) || !fits(info.getContainerClass(), info.getMenuType(), menu)) continue;
                RecipeTransfer.Plan p = planFromView(menu, info, recipe, view, max);
                if (p != null) return p;
            }
            for (Object u : UNIVERSAL) {
                RecipeTransfer.Plan p = viaUniversal(cs, u, recipe, view, max);
                if (p != null) return p;
            }
        }
        // карточка мода в категории верстака — раскладываем по сетке любого окна с CraftingContainer
        if (e.card() instanceof JeiCard && uid.equals(RecipeTypes.CRAFTING.getUid())) {
            int[] cells = new int[9];
            java.util.Arrays.fill(cells, -1);
            List<List<ItemStack>> needs = new ArrayList<>();
            for (JeiSlot s : slots) {
                if (s.role != RecipeIngredientRole.INPUT || s.invisible) continue;
                int c = Math.max(0, (s.x - 1) / 18), r = Math.max(0, (s.y - 1) / 18);
                if (c > 2 || r > 2) return null;
                List<ItemStack> st = s.itemStacks();
                if (!st.isEmpty()) cells[r * 3 + c] = needs.size();
                needs.add(st);
            }
            List<Slot> targets = RecipeTransfer.gridTargets(menu, cells, needs.size());
            if (targets == null) return null;
            return RecipeTransfer.clicks(menu, targets, needs, RecipeTransfer.inventorySlots(menu), max);
        }
        return null;
    }

    private static RecipeTransfer.Plan fromError(IRecipeTransferError err, Runnable run) {
        if (err == null || err.getType() == IRecipeTransferError.Type.COSMETIC) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.translatable("archud.recipes.transfer"));
            tip.add(Component.translatable("archud.recipes.transfer.max").withStyle(s -> s.withColor(0x8A93A0)));
            return new RecipeTransfer.Plan(true, Set.of(), tip, run);
        }
        if (err.getType() == IRecipeTransferError.Type.INTERNAL || err.getType() == null) return null;
        List<Component> tip = new ArrayList<>();
        try {
            err.getTooltip(JeiCard.tooltipBuilder(tip));
        } catch (Throwable ignored) {
        }
        if (tip.isEmpty()) tip.add(Component.translatable("archud.recipes.transfer.missing"));
        return new RecipeTransfer.Plan(false, Set.of(), tip, null);
    }

    private static Object context(AbstractContainerScreen<?> cs, IRecipeTransferHandler h, Object recipe, IRecipeSlotsView view, boolean max) {
        return Stub.of(mezz.jei.api.recipe.transfer.IRecipeTransferContext.class)
                .on("getContainer", 0, a -> cs.getMenu())
                .on("getScreen", 0, a -> cs)
                .on("getRecipe", 0, a -> recipe)
                .on("getRecipeType", 0, a -> h.getRecipeType())
                .on("getRecipeSlots", 0, a -> view)
                .on("getPlayer", 0, a -> Minecraft.getInstance().player)
                .on("isMaxTransfer", 0, a -> max)
                .on("getTransferId", 0, a -> 0)
                .build();
    }

    private static RecipeTransfer.Plan viaHandler(AbstractContainerScreen<?> cs, IRecipeTransferHandler h, Object recipe, IRecipeSlotsView view, boolean max) {
        try {
            IRecipeTransferError err = h.transferRecipe((mezz.jei.api.recipe.transfer.IRecipeTransferContext) context(cs, h, recipe, view, max), false);
            return fromError(err, () -> {
                try {
                    h.transferRecipe((mezz.jei.api.recipe.transfer.IRecipeTransferContext) context(cs, h, recipe, view, max), true);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private static RecipeTransfer.Plan viaUniversal(AbstractContainerScreen<?> cs, Object u, Object recipe, IRecipeSlotsView view, boolean max) {
        try {
            var player = Minecraft.getInstance().player;
            if (u instanceof IUniversalRecipeTransferHandler uh) {
                if (!fits(uh.getContainerClass(), uh.getMenuType(), cs.getMenu())) return null;
                IRecipeTransferError err = uh.transferRecipe(cs.getMenu(), recipe, view, player, max, false);
                return fromError(err, () -> uh.transferRecipe(cs.getMenu(), recipe, view, player, max, true));
            }
            if (u instanceof IRecipeTransferHandler h) {
                if (!fits(h.getContainerClass(), h.getMenuType(), cs.getMenu())) return null;
                return viaHandler(cs, h, recipe, view, max);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ ghost-ингредиенты (перетаскивание из списка)

    static List<IGhostIngredientHandler> ghostHandlers(Screen s) {
        List<IGhostIngredientHandler> out = new ArrayList<>();
        for (Object[] h : JeiBridge.GHOST_HANDLERS) if (((Class<?>) h[0]).isInstance(s)) out.add((IGhostIngredientHandler) h[1]);
        return out;
    }

    /** Есть ли у окна приёмники перетаскиваемых предметов (слоты-фильтры). */
    public static boolean ghostSupported(Screen s) {
        return !ghostHandlers(s).isEmpty();
    }

    /** Области, куда можно бросить предмет: [x, y, w, h]. */
    public static List<int[]> ghostAreas(Screen s, ItemStack stack, boolean start) {
        List<int[]> out = new ArrayList<>();
        for (Object t : targets(s, stack, start)) {
            Rect2i r = ((IGhostIngredientHandler.Target<?>) t).getArea();
            out.add(new int[]{r.getX(), r.getY(), r.getWidth(), r.getHeight()});
        }
        return out;
    }

    private static List<Object> targets(Screen s, ItemStack stack, boolean start) {
        List<Object> out = new ArrayList<>();
        var typed = JeiHelpers.typed(mezz.jei.api.constants.VanillaTypes.ITEM_STACK, stack);
        for (IGhostIngredientHandler h : ghostHandlers(s)) {
            try {
                out.addAll(h.getTargetsTyped(s, typed, start));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** Бросить предмет в точке: true, если какой-то приёмник его взял. */
    public static boolean ghostDrop(Screen s, ItemStack stack, double mx, double my) {
        boolean done = false;
        for (Object t : targets(s, stack, false)) {
            IGhostIngredientHandler.Target target = (IGhostIngredientHandler.Target) t;
            Rect2i r = target.getArea();
            if (mx >= r.getX() && mx < r.getX() + r.getWidth() && my >= r.getY() && my < r.getY() + r.getHeight()) {
                try {
                    target.accept(stack.copy());
                    done = true;
                } catch (Throwable ignored) {
                }
                break;
            }
        }
        for (IGhostIngredientHandler h : ghostHandlers(s)) {
            try {
                h.onComplete();
            } catch (Throwable ignored) {
            }
        }
        return done;
    }

    static Collection<IRecipeTransferInfo> infos() {
        return INFOS;
    }
}
