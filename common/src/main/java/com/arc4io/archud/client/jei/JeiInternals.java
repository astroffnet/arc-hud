package com.arc4io.archud.client.jei;

import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluid;

import java.lang.reflect.Constructor;

/**
 * Жидкости в API JEI (FluidStack на Forge, IJeiFluidIngredient на Fabric) и внутренние помощники JEI,
 * которые вызывают некоторые плагины модов (mezz.jei.common.platform.Services во вложенной копии API).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class JeiInternals {
    private static IPlatformFluidHelper fluids;
    private static Object platform;

    private JeiInternals() {}

    /** Тип ингредиента «жидкость» текущего загрузчика. */
    static IIngredientTypeWithSubtypes fluidType() {
        for (String c : new String[]{"mezz.jei.api.forge.ForgeTypes", "mezz.jei.api.fabric.constants.FabricTypes"}) {
            try {
                return (IIngredientTypeWithSubtypes) Class.forName(c).getField("FLUID_STACK").get(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Жидкость в виде ингредиента JEI текущего загрузчика (или наша запись, если его нет). */
    static Object createFluid(Fluid fluid, long amount, CompoundTag tag) {
        try {
            Class<?> fs = Class.forName("net.minecraftforge.fluids.FluidStack");
            Constructor<?> c = fs.getConstructor(Fluid.class, int.class, CompoundTag.class);
            return c.newInstance(fluid, (int) Math.min(Integer.MAX_VALUE, amount), tag);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> fi = Class.forName("mezz.jei.api.fabric.ingredients.fluids.JeiFluidIngredient");
            return fi.getConstructors()[0].newInstance(fluid, amount, tag);
        } catch (Throwable ignored) {
        }
        return new JeiSlot.FluidValue(fluid, amount);
    }

    static long bucket() {
        try {
            Class.forName("net.minecraftforge.fluids.FluidStack");
            return 1000;
        } catch (Throwable t) {
            return 81000; // Fabric: дроплеты
        }
    }

    static IPlatformFluidHelper fluidHelper() {
        if (fluids == null) {
            IIngredientTypeWithSubtypes type = fluidType();
            fluids = Stub.of(IPlatformFluidHelper.class)
                    .on("getFluidIngredientType", 0, a -> type)
                    .on("create", 2, a -> createFluid((Fluid) a[0], ((Number) a[1]).longValue(), null))
                    .on("create", 3, a -> createFluid((Fluid) a[0], ((Number) a[1]).longValue(), (CompoundTag) a[2]))
                    .on("bucketVolume", 0, a -> bucket())
                    .build();
        }
        return fluids;
    }

    /** mezz.jei.common.platform.Services.PLATFORM для плагинов, которые лезут во внутренности JEI. */
    public static Object platform() throws Exception {
        if (platform == null) {
            Class<?> helper = Class.forName("mezz.jei.common.platform.IPlatformHelper", true, JeiInternals.class.getClassLoader());
            Class<?> fluidInternal = Class.forName("mezz.jei.common.platform.IPlatformFluidHelperInternal", true, JeiInternals.class.getClassLoader());
            IPlatformFluidHelper base = fluidHelper();
            Object internal = Stub.of((Class<Object>) fluidInternal)
                    .on("getFluidIngredientType", 0, a -> base.getFluidIngredientType())
                    .on("create", 2, a -> base.create((Fluid) a[0], ((Number) a[1]).longValue()))
                    .on("create", 3, a -> base.create((Fluid) a[0], ((Number) a[1]).longValue(), (CompoundTag) a[2]))
                    .on("bucketVolume", 0, a -> base.bucketVolume())
                    .build();
            platform = Stub.of((Class<Object>) helper).on("getFluidHelper", 0, a -> internal).build();
        }
        return platform;
    }
}
