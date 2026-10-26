package com.arc4io.archud.mixin;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Consumer;

@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccessor {
    @Accessor("reload")
    ReloadInstance archud$reload();

    @SuppressWarnings("rawtypes")
    @Accessor("onFinish")
    Consumer archud$onFinish();

    @Accessor("fadeIn")
    boolean archud$fadeIn();
}
