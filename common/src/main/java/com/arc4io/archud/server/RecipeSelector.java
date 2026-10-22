package com.arc4io.archud.server;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выбор рецепта при конфликте: если на одну раскладку подходит несколько рецептов
 * (например от разных модов), игрок выбирает нужный, и выбор запоминается.
 * Ванильный результат вычисляется как обычно, затем (из миксина в конце
 * CraftingMenu.slotChangedCraftingGrid) подменяется выбранным рецептом.
 */
public final class RecipeSelector {
    /** Предпочтения игрока: для каждого конфликта запоминается выбранный рецепт. */
    private static final Map<UUID, Set<ResourceLocation>> CHOSEN = new ConcurrentHashMap<>();

    private RecipeSelector() {}

    public static boolean isCraftingMenu(AbstractContainerMenu menu) {
        return (menu instanceof CraftingMenu || menu instanceof InventoryMenu)
                && menu.slots.size() > 1
                && menu.slots.get(0).container instanceof ResultContainer
                && menu.slots.get(1).container instanceof CraftingContainer;
    }

    /** Пакет от клиента: игрок выбрал рецепт. */
    public static void select(ServerPlayer player, ResourceLocation recipeId) {
        if (!ArcConfig.get().recipeChooser) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (!isCraftingMenu(menu)) return;
        CraftingContainer craft = (CraftingContainer) menu.slots.get(1).container;
        ResultContainer result = (ResultContainer) menu.slots.get(0).container;
        Level level = player.level();
        List<CraftingRecipe> recipes = level.getRecipeManager().getRecipesFor(RecipeType.CRAFTING, craft, level);
        Set<ResourceLocation> prefs = CHOSEN.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        for (CraftingRecipe r : recipes) prefs.remove(r.getId());
        prefs.add(recipeId);
        apply(menu, level, player, craft, result);
    }

    /** Вызывается миксином после ванильного пересчёта результата крафта. */
    public static void afterVanilla(AbstractContainerMenu menu, Level level, Player player, CraftingContainer craft, ResultContainer result) {
        if (level.isClientSide || !(player instanceof ServerPlayer sp) || !ArcConfig.get().recipeChooser) return;
        if (!CHOSEN.containsKey(sp.getUUID())) return;
        apply(menu, level, sp, craft, result);
    }

    private static void apply(AbstractContainerMenu menu, Level level, ServerPlayer player, CraftingContainer craft, ResultContainer result) {
        Set<ResourceLocation> prefs = CHOSEN.get(player.getUUID());
        if (prefs == null || prefs.isEmpty()) return;
        List<CraftingRecipe> recipes = level.getRecipeManager().getRecipesFor(RecipeType.CRAFTING, craft, level);
        if (recipes.size() < 2) return;
        for (CraftingRecipe recipe : recipes) {
            if (!prefs.contains(recipe.getId())) continue;
            if (!result.setRecipeUsed(level, player, recipe)) return;
            ItemStack out = recipe.assemble(craft, level.registryAccess());
            if (!out.isItemEnabled(level.enabledFeatures())) out = ItemStack.EMPTY;
            result.setItem(0, out);
            menu.setRemoteSlot(0, out);
            player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), 0, out));
            return;
        }
    }

    public static void forget(UUID player) {
        CHOSEN.remove(player);
    }
}
