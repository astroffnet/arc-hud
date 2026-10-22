package com.arc4io.archud.forge;

import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.ArcKeys;
import com.arc4io.archud.client.ClientPlatform;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.screen.ArcConfigScreen;
import com.arc4io.archud.client.screen.ChatStyler;
import com.arc4io.archud.client.screen.InventoryExtras;
import com.arc4io.archud.client.screen.ScreenSwapper;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

import java.util.Set;

/**
 * Клиентская часть Forge: оверлей HUD, отключение ванильных полос, клавиши, подмена экранов.
 */
public final class ForgeClient implements ClientPlatform {
    private static final Set<ResourceLocation> STATUS_OVERLAYS = Set.of(
            VanillaGuiOverlay.PLAYER_HEALTH.id(),
            VanillaGuiOverlay.ARMOR_LEVEL.id(),
            VanillaGuiOverlay.FOOD_LEVEL.id(),
            VanillaGuiOverlay.AIR_LEVEL.id(),
            VanillaGuiOverlay.MOUNT_HEALTH.id(),
            VanillaGuiOverlay.JUMP_BAR.id(),
            VanillaGuiOverlay.EXPERIENCE_BAR.id());

    public static void init(IEventBus modBus) {
        ArcClient.init(new ForgeClient());
        modBus.addListener(ForgeClient::onRegisterKeys);
        modBus.addListener(ForgeClient::onRegisterOverlays);

        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ArcConfigScreen(parent)));

        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener(ForgeClient::onOverlayPre);
        bus.addListener(ForgeClient::onClientTick);
        bus.addListener(ForgeClient::onScreenOpening);
        bus.addListener(ForgeClient::onScreenInit);
        bus.addListener(ForgeClient::onScreenRenderPre);
        bus.addListener(ForgeClient::onScreenRenderPost);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        ArcKeys.ALL.forEach(event::register);
    }

    private static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerBelow(VanillaGuiOverlay.PLAYER_HEALTH.id(), "hud", ForgeClient::renderHud);
    }

    private static void renderHud(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        HudRenderer.render(g, partialTick);
        if (HudRenderer.replacesStatusBars()) {
            int occupied = HudRenderer.occupiedHeight();
            gui.leftHeight = Math.max(gui.leftHeight, occupied);
            gui.rightHeight = Math.max(gui.rightHeight, occupied);
        }
    }

    private static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        ResourceLocation id = event.getOverlay().id();
        if (ArcConfig.get().hiddenOverlays.contains(id.toString())) {
            event.setCanceled(true);
            return;
        }
        if (HudRenderer.replacesStatusBars() && STATUS_OVERLAYS.contains(id)) {
            event.setCanceled(true);
        } else if (HudRenderer.replacesHotbar() && id.equals(VanillaGuiOverlay.HOTBAR.id())) {
            event.setCanceled(true);
        } else if (HudRenderer.replacesEffects() && id.equals(VanillaGuiOverlay.POTION_ICONS.id())) {
            event.setCanceled(true);
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ArcClient.tick(Minecraft.getInstance());
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        Screen swapped = ScreenSwapper.swap(next, event.getCurrentScreen());
        if (swapped != next) event.setNewScreen(swapped);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        ChatStyler.onInit(event.getScreen());
        InventoryExtras.onInit(event.getScreen(), event::addListener);
    }

    private static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        ChatStyler.preRender(event.getScreen(), event.getGuiGraphics());
    }

    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        ChatStyler.postRender(event.getScreen(), event.getGuiGraphics());
        InventoryExtras.onRender(event.getScreen(), event.getGuiGraphics());
    }

    // ---- ClientPlatform ----
    @Override
    public void sendToServer(ArcPacket packet) {
        if (Minecraft.getInstance().getConnection() != null) ForgeNetwork.send(packet);
    }

    @Override
    public Screen createModListScreen(Screen parent) {
        return new ModListScreen(parent);
    }
}
