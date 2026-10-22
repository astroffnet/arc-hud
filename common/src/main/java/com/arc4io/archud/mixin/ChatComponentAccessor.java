package com.arc4io.archud.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> archud$getTrimmedMessages();

    @Accessor("allMessages")
    List<GuiMessage> archud$getAllMessages();

    @Accessor("chatScrollbarPos")
    int archud$getScrollPos();

    @Accessor("newMessageSinceScroll")
    boolean archud$hasNewSinceScroll();
}
