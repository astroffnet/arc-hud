package com.arc4io.archud.compat;

import com.arc4io.archud.ArcHud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * AppleSkin: значения еды (с учётом модов через API AppleSkin) для предпросмотра на полосе голода Arc HUD
 * и истощение (exhaustion), которое AppleSkin синхронизирует с клиентом. Без AppleSkin — ванильные значения еды.
 */
public final class AppleSkinCompat {
    private static Boolean present;
    private static Method getValues;
    private static Field hunger, satMod;

    /** {голод, прибавка насыщенности}. */
    public record Food(int hunger, float saturation) {}

    private AppleSkinCompat() {}

    public static boolean present() {
        if (present == null) {
            present = ArcHud.platform().isModLoaded("appleskin");
            if (present) {
                try {
                    Class<?> helper = Integrations.cls("squeek.appleskin.helpers.FoodHelper");
                    getValues = helper.getMethod("getModifiedFoodValues", ItemStack.class, Player.class);
                    Class<?> values = getValues.getReturnType();
                    hunger = values.getField("hunger");
                    satMod = values.getField("saturationModifier");
                    ArcHud.LOGGER.info("Arc HUD: интеграция с AppleSkin включена");
                } catch (Throwable t) {
                    getValues = null;
                }
            }
        }
        return present;
    }

    public static Food food(ItemStack stack, Player player) {
        if (stack.isEmpty()) return null;
        if (present() && getValues != null) {
            try {
                Object v = getValues.invoke(null, stack, player);
                if (v != null) {
                    int h = hunger.getInt(v);
                    float m = satMod.getFloat(v);
                    if (h > 0 || m > 0) return new Food(h, h * m * 2F);
                }
            } catch (Throwable ignored) {
            }
        }
        FoodProperties fp = stack.getItem().getFoodProperties();
        if (fp == null) return null;
        return new Food(fp.getNutrition(), fp.getNutrition() * fp.getSaturationModifier() * 2F);
    }

    public static boolean canEat(ItemStack stack, Player player) {
        FoodProperties fp = stack.getItem().getFoodProperties();
        return player.canEat(fp != null && fp.canAlwaysEat()) || player.isCreative();
    }
}
