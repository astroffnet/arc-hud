package com.arc4io.archud.mixin;

import net.minecraft.client.gui.screens.ShareToLanScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShareToLanScreen.class)
public interface ShareToLanScreenAccessor {
    @Accessor("port")
    void archud$setPort(int port);
}
