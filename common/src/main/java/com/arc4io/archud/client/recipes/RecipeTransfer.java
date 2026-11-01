package com.arc4io.archud.client.recipes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Перенос рецепта в открытое окно (кнопка «+» как в JEI): раскладывает нужные предметы из инвентаря по слотам
 * верстака, печи, кузнечного стола, наковальни, варочной стойки, станков модов — обычными кликами, поэтому работает
 * на любом сервере. Обработчики переноса из плагинов модов для JEI подключаются через мост.
 */
public final class RecipeTransfer {
    /** Итог проверки: можно ли перенести, чего не хватает (номера входов), подсказка и сам перенос. */
    public record Plan(boolean ok, Set<Integer> missing, List<Component> tooltip, Runnable run) {}

    private RecipeTransfer() {}

    /** План переноса рецепта в окно parent или null, если окно его не принимает. */
    public static Plan plan(Screen parent, RecipeIndex.Entry e, boolean max) {
        if (!(parent instanceof AbstractContainerScreen<?> cs) || Minecraft.getInstance().player == null) return null;
        Object viaJei = com.arc4io.archud.client.jei.JeiGate.call("transferPlan",
                new Class<?>[]{Screen.class, RecipeIndex.Entry.class, boolean.class}, parent, e, max);
        if (viaJei instanceof Plan p) return p;
        if (e.card() != null) return null;
        AbstractContainerMenu menu = cs.getMenu();
        List<Slot> targets = builtinTargets(menu, e);
        if (targets == null) return null;
        return clicks(menu, targets, e.inputs(), inventorySlots(menu), max);
    }

    // ------------------------------------------------------------------ куда класть (встроенные окна)

    public static List<Slot> inventorySlots(AbstractContainerMenu menu) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : menu.slots) if (s.container instanceof Inventory && s.getContainerSlot() < 36) out.add(s);
        return out;
    }

    /** Слоты сетки крафта окна (любого: верстак, инвентарь, верстаки модов на CraftingContainer) по порядку. */
    public static List<Slot> craftingSlots(AbstractContainerMenu menu) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : menu.slots) if (s.container instanceof CraftingContainer) out.add(s);
        out.sort(Comparator.comparingInt(Slot::getContainerSlot));
        return out;
    }

    /**
     * Слоты сетки для клеток рецепта 3×3 (cells[i] = номер входа или -1): рецепт сдвигается в левый верхний угол,
     * чтобы влезть в сетку 2×2. Возвращает список целей по номерам входов или null, если не помещается.
     */
    public static List<Slot> gridTargets(AbstractContainerMenu menu, int[] cells, int inputs) {
        List<Slot> grid = craftingSlots(menu);
        if (grid.isEmpty()) return null;
        int w = grid.get(0).container instanceof CraftingContainer cc ? cc.getWidth() : 3;
        int h = grid.get(0).container instanceof CraftingContainer cc2 ? cc2.getHeight() : 3;
        int minR = 3, minC = 3;
        for (int i = 0; i < 9; i++) if (cells[i] >= 0) {
            minR = Math.min(minR, i / 3);
            minC = Math.min(minC, i % 3);
        }
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < inputs; i++) out.add(null);
        for (int i = 0; i < 9; i++) {
            if (cells[i] < 0) continue;
            int r = i / 3 - minR, c = i % 3 - minC;
            if (r >= h || c >= w) return null;
            int idx = r * w + c;
            if (idx >= grid.size()) return null;
            out.set(cells[i], grid.get(idx));
        }
        return out;
    }

    private static List<Slot> builtinTargets(AbstractContainerMenu menu, RecipeIndex.Entry e) {
        String path = e.type().getNamespace().equals("minecraft") ? e.type().getPath() : "";
        List<List<ItemStack>> in = e.inputs();
        if (e.type().equals(RecipeIndex.BREWING) && menu instanceof BrewingStandMenu && in.size() == 2) {
            return List.of(menu.slots.get(0), menu.slots.get(3));
        }
        switch (path) {
            case "crafting" -> {
                int[] cells = new int[9];
                java.util.Arrays.fill(cells, -1);
                if (e.gridW() > 0) {
                    for (int i = 0; i < in.size(); i++) {
                        int r = i / e.gridW(), c = i % e.gridW();
                        if (r > 2 || c > 2) return null;
                        if (!in.get(i).isEmpty()) cells[r * 3 + c] = i;
                    }
                } else {
                    if (in.size() > 9) return null;
                    List<Slot> grid = craftingSlots(menu);
                    if (in.size() > grid.size()) return null;
                    List<Slot> out = new ArrayList<>();
                    for (int i = 0; i < in.size(); i++) out.add(grid.get(i));
                    return out;
                }
                return gridTargets(menu, cells, in.size());
            }
            case "smelting", "blasting", "smoking" -> {
                return menu instanceof AbstractFurnaceMenu && in.size() == 1 ? List.of(menu.slots.get(0)) : null;
            }
            case "smithing" -> {
                return menu instanceof SmithingMenu && in.size() <= 3 ? new ArrayList<>(menu.slots.subList(0, in.size())) : null;
            }
            case "anvil" -> {
                return menu instanceof AnvilMenu && in.size() <= 2 ? new ArrayList<>(menu.slots.subList(0, in.size())) : null;
            }
            case "grindstone" -> {
                return menu instanceof GrindstoneMenu && in.size() <= 2 ? new ArrayList<>(menu.slots.subList(0, in.size())) : null;
            }
            case "stonecutting" -> {
                return menu instanceof StonecutterMenu && in.size() == 1 ? List.of(menu.slots.get(0)) : null;
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ перенос кликами

    private static boolean matches(ItemStack have, List<ItemStack> alts) {
        for (ItemStack a : alts) {
            if (a.getItem() != have.getItem()) continue;
            if (!a.hasTag() || ItemStack.isSameItemSameTags(a, have)) return true;
        }
        return false;
    }

    private static int need(List<ItemStack> alts) {
        return alts.isEmpty() ? 0 : Math.max(1, alts.get(0).getCount());
    }

    /**
     * План переноса: targets[i] — слот для входа needs[i] (null — пропустить), inventory — откуда брать.
     * max — столько наборов, сколько позволяет инвентарь (до стопки).
     */
    public static Plan clicks(AbstractContainerMenu menu, List<Slot> targets, List<List<ItemStack>> needs, List<Slot> inventory, boolean max) {
        Map<Slot, Integer> avail = new IdentityHashMap<>();
        for (Slot s : inventory) if (s.hasItem()) avail.put(s, s.getItem().getCount());
        // то, что уже лежит в слотах рецепта, вернётся в инвентарь и тоже годится
        for (Slot t : targets) if (t != null && t.hasItem() && !avail.containsKey(t)) avail.put(t, t.getItem().getCount());
        Set<Integer> missing = new HashSet<>();
        List<Component> tip = new ArrayList<>();
        int sets = 1;
        if (max) {
            sets = 64;
            for (int i = 0; i < needs.size(); i++) {
                List<ItemStack> alts = needs.get(i);
                if (alts.isEmpty() || i >= targets.size() || targets.get(i) == null) continue;
                int total = 0, users = 0;
                for (Map.Entry<Slot, Integer> a : avail.entrySet()) if (matches(a.getKey().getItem(), alts)) total += a.getValue();
                for (List<ItemStack> o : needs) if (!o.isEmpty() && o.get(0).getItem() == alts.get(0).getItem()) users += need(o);
                int maxStack = alts.get(0).getMaxStackSize();
                sets = Math.min(sets, Math.min(maxStack, users == 0 ? 0 : total / users));
            }
            sets = Math.max(1, sets);
        }
        Map<Slot, Integer> left = new IdentityHashMap<>(avail);
        for (int i = 0; i < needs.size(); i++) {
            List<ItemStack> alts = needs.get(i);
            if (alts.isEmpty()) continue;
            if (i >= targets.size() || targets.get(i) == null) return null; // окно не знает, куда это положить
            int want = need(alts) * sets;
            for (Map.Entry<Slot, Integer> a : left.entrySet()) {
                if (want <= 0) break;
                if (!matches(a.getKey().getItem(), alts)) continue;
                int take = Math.min(want, a.getValue());
                a.setValue(a.getValue() - take);
                want -= take;
            }
            if (want > 0) {
                missing.add(i);
                ItemStack shown = alts.get(0);
                tip.add(Component.literal("• ").append(shown.getHoverName()).withStyle(s -> s.withColor(0xFF6B6B)));
            }
        }
        if (!missing.isEmpty()) {
            tip.add(0, Component.translatable("archud.recipes.transfer.missing").withStyle(s -> s.withColor(0xFF6B6B)));
            return new Plan(false, missing, tip, null);
        }
        List<Component> ok = new ArrayList<>();
        ok.add(Component.translatable("archud.recipes.transfer"));
        ok.add(Component.translatable("archud.recipes.transfer.max").withStyle(s -> s.withColor(0x8A93A0)));
        int count = sets;
        return new Plan(true, Set.of(), ok, () -> execute(menu, targets, needs, inventory, count));
    }

    private static void click(AbstractContainerMenu menu, Slot s, int button, ClickType type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, button, type, mc.player);
    }

    private static void execute(AbstractContainerMenu menu, List<Slot> targets, List<List<ItemStack>> needs, List<Slot> inventory, int sets) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !menu.getCarried().isEmpty()) return;
        // освобождаем слоты рецепта
        for (Slot t : targets) if (t != null && t.hasItem()) click(menu, t, 0, ClickType.QUICK_MOVE);
        for (int i = 0; i < needs.size() && i < targets.size(); i++) {
            List<ItemStack> alts = needs.get(i);
            Slot target = targets.get(i);
            if (alts.isEmpty() || target == null) continue;
            int want = need(alts) * sets;
            for (Slot src : inventory) {
                if (want <= 0) break;
                if (!src.hasItem() || !matches(src.getItem(), alts)) continue;
                click(menu, src, 0, ClickType.PICKUP); // взяли стопку
                int carried = menu.getCarried().getCount();
                if (carried <= want) {
                    click(menu, target, 0, ClickType.PICKUP); // положили всё
                    want -= carried;
                } else {
                    for (int k = 0; k < want; k++) click(menu, target, 1, ClickType.PICKUP); // по одному
                    want = 0;
                }
                if (!menu.getCarried().isEmpty()) click(menu, src, 0, ClickType.PICKUP); // остаток обратно
            }
        }
    }

    static boolean isType(RecipeIndex.Entry e, String path) {
        ResourceLocation t = e.type();
        return t.getNamespace().equals("minecraft") && t.getPath().equals(path);
    }
}
