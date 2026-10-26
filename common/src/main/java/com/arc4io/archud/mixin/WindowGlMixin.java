package com.arc4io.archud.mixin;

import com.arc4io.archud.client.gfx.GlContext;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Выбор версии OpenGL: подсказки GLFW прямо перед созданием окна игры (ванила/Fabric и Forge без окна загрузки). */
@Mixin(Window.class)
public abstract class WindowGlMixin {
    @Inject(method = "<init>", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void archud$glHints(CallbackInfo ci) {
        GlContext.applyHints();
    }

    @Inject(method = "<init>", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private void archud$glHintsForge(CallbackInfo ci) {
        GlContext.applyHints();
    }
}
