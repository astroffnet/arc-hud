package com.arc4io.archud.mixin;

import com.arc4io.archud.client.screen.ArcChatRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Заменяет отрисовку сообщений чата на стиль Arc HUD (геометрия строк та же — клики по ссылкам работают).
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void archud$render(GuiGraphics graphics, int tickCount, int mouseX, int mouseY, CallbackInfo ci) {
        if (ArcChatRenderer.enabled()) {
            ArcChatRenderer.render((ChatComponent) (Object) this, graphics, tickCount, mouseX, mouseY);
            ci.cancel();
        }
    }
}
