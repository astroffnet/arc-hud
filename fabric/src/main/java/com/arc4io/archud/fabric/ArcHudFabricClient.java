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
        // встроенный пакет «Родное слово» (русский перевод модов), включён по умолчанию, можно выключить
        if (com.arc4io.archud.config.ArcConfig.get().builtinRussianPack) {
            net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("archud").ifPresent(c ->
                    net.fabricmc.fabric.api.resource.ResourceManagerHelper.registerBuiltinResourcePack(
                            new net.minecraft.resources.ResourceLocation("archud", "rodnoe_slovo"), c,
                            net.minecraft.network.chat.Component.literal("Родное слово"),
                            net.fabricmc.fabric.api.resource.ResourcePackActivationType.DEFAULT_ENABLED));
        }
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
                if (!(s instanceof com.arc4io.archud.client.recipes.ArcRecipeScreen)) {
                    com.arc4io.archud.client.recipes.RecipeOverlay.render(s, graphics, mouseX, mouseY);
                }
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
        // свой список модов; Mod Menu — только если он установлен и в настройках выключен список Arc HUD
        if (!com.arc4io.archud.config.ArcConfig.get().customModList && FabricLoader.getInstance().isModLoaded("modmenu")) {
            try {
                Class<?> cls = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
                return (Screen) cls.getConstructor(Screen.class).newInstance(parent);
            } catch (Throwable ignored) {
            }
        }
        return new com.arc4io.archud.client.mods.ArcModListScreen(parent);
    }

    @Override
    public java.util.List<Object> jeiPlugins() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (var c : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("jei_mod_plugin", Object.class)) {
            try {
                out.add(c.getEntrypoint());
            } catch (Throwable t) {
                com.arc4io.archud.ArcHud.LOGGER.warn("Arc HUD: JEI plugin from {} not loaded: {}", c.getProvider().getMetadata().getId(), t.toString());
            }
        }
        return out;
    }

    @Override
    public net.minecraft.resources.ResourceLocation fluidSprite(net.minecraft.world.level.material.Fluid fluid) {
        try {
            var h = net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry.INSTANCE.get(fluid);
            if (h == null) return null;
            var sp = h.getFluidSprites(null, null, fluid.defaultFluidState());
            return sp != null && sp.length > 0 && sp[0] != null ? sp[0].contents().name() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int fluidTint(net.minecraft.world.level.material.Fluid fluid) {
        try {
            var h = net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry.INSTANCE.get(fluid);
            return h == null ? 0xFFFFFFFF : h.getFluidColor(null, null, fluid.defaultFluidState()) | 0xFF000000;
        } catch (Throwable t) {
            return 0xFFFFFFFF;
        }
    }

    @Override
    public int burnTime(net.minecraft.world.item.ItemStack stack) {
        try {
            Integer v = net.fabricmc.fabric.api.registry.FuelRegistry.INSTANCE.get(stack.getItem());
            return v == null ? 0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public boolean classicInventory() {
        return FabricLoader.getInstance().isModLoaded("trinkets");
    }

    @Override
    public java.util.List<com.arc4io.archud.client.mods.ModEntry> mods() {
        return FabricMods.mods();
    }

    @Override
    public java.nio.file.Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }
}
