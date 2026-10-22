package com.arc4io.archud.client.screen;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcPacket;
import com.arc4io.archud.server.RecipeSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Панель вариантов крафта: если на текущую раскладку подходит несколько рецептов,
 * над окном крафта появляется ряд результатов — клик выбирает нужный.
 */
public final class RecipeChoiceUI {
    private static final int CELL = 20;
    private static String lastKey = "";
    private static List<CraftingRecipe> candidates = List.of();
    private static List<ItemStack> outputs = List.of();
    private static int panelX, panelY, panelW;

    private RecipeChoiceUI() {}

    private static AbstractContainerMenu menuOf(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return null;
        AbstractContainerMenu menu = acs.getMenu();
        return RecipeSelector.isCraftingMenu(menu) ? menu : null;
    }

    private static void refresh(AbstractContainerMenu menu) {
        Minecraft mc = Minecraft.getInstance();
        CraftingContainer craft = (CraftingContainer) menu.slots.get(1).container;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < craft.getContainerSize(); i++) {
            ItemStack s = craft.getItem(i);
            key.append(BuiltInRegistries.ITEM.getKey(s.getItem())).append(s.hasTag() ? s.getTag().hashCode() : 0).append(';');
        }
        String k = key.toString();
        if (k.equals(lastKey)) return;
        lastKey = k;
        List<CraftingRecipe> list = mc.level.getRecipeManager().getRecipesFor(RecipeType.CRAFTING, craft, mc.level);
        if (list.size() < 2) {
            candidates = List.of();
            outputs = List.of();
            return;
        }
        candidates = list;
        List<ItemStack> outs = new ArrayList<>();
        for (CraftingRecipe r : list) outs.add(r.assemble(craft, mc.level.registryAccess()));
        outputs = outs;
    }

    private static int leftPos(Screen screen, int imageWidth) {
        if (screen instanceof RecipeUpdateListener rul && rul.getRecipeBookComponent().isVisible()) {
            return rul.getRecipeBookComponent().updateScreenPosition(screen.width, imageWidth);
        }
        return (screen.width - imageWidth) / 2;
    }

    public static void onRender(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
        if (!ArcConfig.get().recipeChooser) return;
        AbstractContainerMenu menu = menuOf(screen);
        Minecraft mc = Minecraft.getInstance();
        if (menu == null || mc.level == null) {
            lastKey = "";
            return;
        }
        refresh(menu);
        if (candidates.size() < 2) return;

        int left = leftPos(screen, 176);
        int top = (screen.height - 166) / 2;
        int accent = Theme.accent();
        ItemStack current = menu.slots.get(0).getItem();
        int resultX = left + menu.slots.get(0).x + 8;

        panelW = candidates.size() * CELL + 6;
        panelX = Math.max(2, Math.min(screen.width - panelW - 2, resultX - panelW / 2));
        panelY = top - CELL - 10;
        if (panelY < 12) panelY = top + 2;

        Component label = Component.translatable("archud.recipe.choose", candidates.size());
        Draw.panel(g, panelX, panelY - 10, Math.max(panelW, Math.round(mc.font.width(label) * 0.75F) + 8), 10, 0xE0101419, Draw.alpha(accent, 0x90));
        Draw.small(g, mc.font, label.getString(), panelX + 4, panelY - 8, Draw.alpha(accent, 0xFF), 0.75F);
        Draw.panel(g, panelX, panelY, panelW, CELL + 6, 0xE0101419, Draw.alpha(accent, 0xC0));

        ItemStack hovered = ItemStack.EMPTY;
        CraftingRecipe hoveredRecipe = null;
        for (int i = 0; i < candidates.size(); i++) {
            int cx = panelX + 3 + i * CELL;
            int cy = panelY + 3;
            ItemStack out = outputs.get(i);
            boolean selected = ItemStack.isSameItemSameTags(out, current) && out.getCount() == current.getCount();
            boolean hov = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
            if (selected) {
                g.fill(cx, cy, cx + CELL, cy + CELL, Draw.alpha(accent, 0x60));
                Draw.outline(g, cx, cy, CELL, CELL, Draw.alpha(accent, 0xFF));
            } else if (hov) {
                g.fill(cx, cy, cx + CELL, cy + CELL, 0x30FFFFFF);
            }
            g.renderItem(out, cx + 2, cy + 2);
            g.renderItemDecorations(mc.font, out, cx + 2, cy + 2);
            if (hov) {
                hovered = out;
                hoveredRecipe = candidates.get(i);
            }
        }
        if (!hovered.isEmpty() && hoveredRecipe != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(hovered.getHoverName());
            String ns = hoveredRecipe.getId().getNamespace();
            tip.add(Component.literal(ArcHud.platform().modName(ns)).withStyle(s -> s.withColor(0x7FA7C9).withItalic(true)));
            tip.add(Component.literal(hoveredRecipe.getId().toString()).withStyle(s -> s.withColor(0x6A7380)));
            tip.add(Component.translatable("archud.recipe.click").withStyle(s -> s.withColor(0xFFE082)));
            g.renderComponentTooltip(mc.font, tip, mouseX, mouseY);
        }
    }

    /** true — клик обработан (не передавать дальше в экран). */
    public static boolean onClick(Screen screen, double mx, double my, int button) {
        if (!ArcConfig.get().recipeChooser || button != 0 || candidates.size() < 2 || menuOf(screen) == null) return false;
        if (my < panelY + 3 || my >= panelY + 3 + CELL || mx < panelX + 3) return false;
        int idx = (int) ((mx - panelX - 3) / CELL);
        if (idx < 0 || idx >= candidates.size()) return false;
        ArcClient.platform().sendToServer(ArcPacket.SELECT_RECIPE, candidates.get(idx).getId());
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.2F));
        return true;
    }
}
