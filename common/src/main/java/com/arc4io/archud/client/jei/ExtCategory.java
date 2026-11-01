package com.arc4io.archud.client.jei;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.vanilla.cooking.ICookingCategoryExtension;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;

import java.util.Locale;

/**
 * Встроенные категории JEI «верстак» и «печи», которые моды расширяют своими типами рецептов
 * (ICraftingCategoryExtension / ICookingCategoryExtension): раскладка как у JEI, рисует расширение мода.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class ExtCategory implements IRecipeCategory<Object> {
    private final RecipeType<Object> type;
    private final Object ext;
    private final boolean cooking;

    private ExtCategory(RecipeType<?> type, Object ext, boolean cooking) {
        this.type = (RecipeType<Object>) type;
        this.ext = ext;
        this.cooking = cooking;
    }

    /** Категория верстака для одного рецепта, показанного расширением мода. */
    static ExtCategory crafting(RecipeType<?> type, ICraftingCategoryExtension ext) {
        return new ExtCategory(type, ext, false);
    }

    static ExtCategory cooking(RecipeType<?> type, ICookingCategoryExtension ext) {
        return new ExtCategory(type, ext, true);
    }

    @Override
    public RecipeType<Object> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return com.arc4io.archud.client.recipes.ArcRecipeScreen.categoryName(JeiBridge.ours(type.getUid()));
    }

    @Override
    public IDrawable getIcon() {
        return JeiDraw.item(new ItemStack(cooking ? Items.FURNACE : Items.CRAFTING_TABLE));
    }

    @Override
    public int getWidth() {
        if (!cooking) {
            int w = ((ICraftingCategoryExtension) ext).getWidth();
            return w > 0 ? w : 116;
        }
        return 82;
    }

    @Override
    public int getHeight() {
        if (!cooking) {
            int h = ((ICraftingCategoryExtension) ext).getHeight();
            return h > 0 ? h : 54;
        }
        return 54;
    }

    @Override
    public boolean isHandled(Object recipe) {
        if (cooking) return ((ICookingCategoryExtension) ext).isHandled(recipe);
        return true;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Object recipe, IFocusGroup focuses) {
        if (cooking) {
            IRecipeSlotBuilder in = builder.addSlot(RecipeIngredientRole.INPUT, 1, 1);
            IRecipeSlotBuilder out = builder.addSlot(RecipeIngredientRole.OUTPUT, 61, 19);
            ((ICookingCategoryExtension) ext).setRecipe(recipe, builder, in, out, focuses);
        } else {
            ((ICraftingCategoryExtension) ext).setRecipe(builder, JeiHelpers.craftingGrid(), focuses);
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, Object recipe, IFocusGroup focuses) {
        if (cooking) ((ICookingCategoryExtension) ext).createRecipeExtras(recipe, builder, focuses);
        else ((ICraftingCategoryExtension) ext).createRecipeExtras(builder, JeiHelpers.craftingGrid(), focuses);
    }

    @Override
    public void draw(Object recipe, IRecipeSlotsView view, GuiGraphics g, double mx, double my) {
        if (cooking) {
            ICookingCategoryExtension c = (ICookingCategoryExtension) ext;
            int time = 200;
            float xp = 0;
            if (recipe instanceof AbstractCookingRecipe r) {
                time = r.getCookingTime();
                xp = r.getExperience();
            }
            time = c.getCookingTime(recipe, time);
            xp = c.getExperience(recipe, xp);
            JeiDraw.animated(JeiDraw.flame(true), JeiDraw.timer(300, true), IDrawableAnimated.StartDirection.TOP).draw(g, 1, 20);
            JeiDraw.animated(JeiDraw.arrow(true), JeiDraw.timer(Math.max(1, time), false), IDrawableAnimated.StartDirection.LEFT).draw(g, 24, 18);
            var font = Minecraft.getInstance().font;
            if (xp > 0) {
                String s = String.format(Locale.ROOT, "%.1f XP", xp);
                g.drawString(font, s, 82 - font.width(s), 0, 0xFF808080, false);
            }
            String t = String.format(Locale.ROOT, "%.1fs", time / 20F);
            g.drawString(font, t, 82 - font.width(t), 45, 0xFF808080, false);
        } else {
            JeiDraw.arrow(false).draw(g, 61, 19);
            ((ICraftingCategoryExtension) ext).drawInfo(getWidth(), getHeight(), g, mx, my);
        }
    }

    @Override
    public void getTooltip(ITooltipBuilder tooltip, Object recipe, IRecipeSlotsView view, double mx, double my) {
        if (!cooking) ((ICraftingCategoryExtension) ext).getTooltip(tooltip, mx, my);
    }

    @Override
    public ResourceLocation getRegistryName(Object recipe) {
        if (!cooking) {
            ResourceLocation id = ((ICraftingCategoryExtension) ext).getRegistryName();
            if (id != null) return id;
        }
        return IRecipeCategory.super.getRegistryName(recipe);
    }
}
