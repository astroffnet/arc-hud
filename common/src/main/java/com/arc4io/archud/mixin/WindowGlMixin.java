package com.arc4io.archud.mixin;

import com.arc4io.archud.client.gfx.GlContext;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.IntSupplier;

/**
 * Выбор версии OpenGL: подсказки GLFW прямо перед созданием окна игры.
 * Через @ModifyArg (аргумент не меняется): Mixin 0.8.5 у Forge не допускает @Inject внутрь конструктора.
 */
@Mixin(Window.class)
public abstract class WindowGlMixin {
    /** Ванила / Fabric: окно создаётся в конструкторе Window. */
    @ModifyArg(method = "<init>", require = 0, index = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private int archud$glHints(int width) {
        GlContext.applyHints();
        return width;
    }

    /** Forge: окно передаёт загрузчик (без окна загрузки — создаёт его здесь же с текущими подсказками). */
    @ModifyArg(method = "<init>", require = 0, index = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private IntSupplier archud$glHintsForge(IntSupplier width) {
        GlContext.applyHints();
        return width;
    }
}
