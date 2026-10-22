package com.arc4io.archud.fabric;

import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.ArcKeys;
import com.arc4io.archud.client.ClientPlatform;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.screen.ChatStyler;
import com.arc4io.archud.client.screen.InventoryExtras;
import com.arc4io.archud.client.screen.RecipeChoiceUI;
import com.arc4io.archud.network.ArcPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Клиентская часть Fabric. Отключение ванильных полос — в mixin/GuiMixin, подмена экранов — в mixin/MinecraftMixin.
 */
public class ArcHudFabricClient implements ClientModInitializer, ClientPlatform {
    @Override
    public void onInitializeClient() {
        ArcClient.init(this);
        for (var key : ArcKeys.ALL) KeyBindingHelper.registerKeyBinding(key);

        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            if (!Minecraft.getInstance().options.hideGui) HudRenderer.render(graphics, tickDelta);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ArcClient::tick);
        ClientPlayNetworking.registerGlobalReceiver(com.arc4io.archud.network.ArcNet.S2C, (client, handler, buf, sender) -> {
            byte[] data = com.arc4io.archud.network.ArcNet.bytes(buf);
            client.execute(() -> com.arc4io.archud.network.ArcNet.receiveOnClient(com.arc4io.archud.network.ArcNet.wrap(data)));
        });
        com.arc4io.archud.network.ArcNet.setClientSide(buf -> ClientPlayNetworking.send(com.arc4io.archud.network.ArcNet.C2S, buf),
                () -> ClientPlayNetworking.canSend(com.arc4io.archud.network.ArcNet.C2S));
        ClientPlayNetworking.registerGlobalReceiver(com.arc4io.archud.network.TempadSyncPacket.ID, (client, handler, buf, sender) -> {
            var packet = com.arc4io.archud.network.TempadSyncPacket.read(buf);
            client.execute(() -> com.arc4io.archud.compat.TempadCompat.applySync(packet));
        });
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.LAST.register(context ->
                com.arc4io.archud.client.map.WaypointWorldRenderer.capture(context.matrixStack(), context.projectionMatrix(), context.camera()));

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ChatStyler.onInit(screen);
            InventoryExtras.onInit(screen, widget -> Screens.getButtons(screen).add(widget));
            ScreenEvents.beforeRender(screen).register((s, graphics, mouseX, mouseY, delta) -> ChatStyler.preRender(s, graphics));
            ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                ChatStyler.postRender(s, graphics);
                InventoryExtras.onRender(s, graphics);
                RecipeChoiceUI.onRender(s, graphics, mouseX, mouseY);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, mouseX, mouseY, button) -> !RecipeChoiceUI.onClick(s, mouseX, mouseY, button));
        });
    }

    // ---- ClientPlatform ----
    @Override
    public void sendToServer(ArcPacket packet, ResourceLocation arg) {
        if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(packet.id())) {
            FriendlyByteBuf buf = PacketByteBufs.create();
            ArcPacket.writeArg(buf, arg);
            ClientPlayNetworking.send(packet.id(), buf);
        }
    }

    @Override
    public Screen createModListScreen(Screen parent) {
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) return null;
        try {
            Class<?> cls = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
            return (Screen) cls.getConstructor(Screen.class).newInstance(parent);
        } catch (Throwable t) {
            return null;
        }
    }
}
