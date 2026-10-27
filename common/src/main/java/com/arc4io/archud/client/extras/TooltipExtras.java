package com.arc4io.archud.client.extras;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Locale;

/** Подсказки предметов: прочность числом, сытость, штраф наковальни и мод, из которого предмет. */
public final class TooltipExtras {
    private static Boolean recipeViewer;

    private TooltipExtras() {}

    public static void append(ItemStack stack, TooltipFlag flag, List<Component> lines) {
        if (stack.isEmpty() || lines == null) return;
        ArcConfig cfg = ArcConfig.get();
        try {
            if (cfg.tooltipDurability && stack.isDamageableItem() && !flag.isAdvanced()) {
                int left = stack.getMaxDamage() - stack.getDamageValue();
                float f = left / (float) stack.getMaxDamage();
                ChatFormatting c = f > 0.5F ? ChatFormatting.GREEN : f > 0.2F ? ChatFormatting.YELLOW : ChatFormatting.RED;
                lines.add(Component.translatable("archud.tip.durability", left, stack.getMaxDamage()).withStyle(c));
            }
            if (cfg.tooltipFood && stack.isEdible() && !ArcHud.platform().isModLoaded("appleskin")) {
                FoodProperties food = stack.getItem().getFoodProperties();
                if (food != null) {
                    float sat = food.getNutrition() * food.getSaturationModifier() * 2F;
                    lines.add(Component.translatable("archud.tip.food", food.getNutrition(), String.format(Locale.ROOT, "%.1f", sat))
                            .withStyle(ChatFormatting.GOLD));
                }
            }
            if (cfg.tooltipRepairCost && stack.getBaseRepairCost() > 0) {
                lines.add(Component.translatable("archud.tip.repair", stack.getBaseRepairCost()).withStyle(ChatFormatting.DARK_PURPLE));
            }
            if (cfg.tooltipModName && !hasRecipeViewer()) {
                String ns = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
                lines.add(Component.literal(ArcHud.platform().modName(ns)).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
            }
        } catch (Exception ignored) {
        }
    }

    /** JEI / REI / EMI сами подписывают мод — не дублируем. */
    private static boolean hasRecipeViewer() {
        if (recipeViewer == null) {
            var p = ArcHud.platform();
            recipeViewer = com.arc4io.archud.client.jei.JeiGate.realJei() || p.isModLoaded("roughlyenoughitems") || p.isModLoaded("emi");
        }
        return recipeViewer;
    }
}
