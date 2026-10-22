package com.arc4io.archud.forge;

import com.arc4io.archud.client.ArcClient;
import com.arc4io.archud.client.ArcKeys;
import com.arc4io.archud.client.hud.ArcHudApi;
import com.arc4io.archud.forge.compat.LegendsCompat;
import com.arc4io.archud.client.ClientPlatform;
import com.arc4io.archud.client.hud.HudRenderer;
import com.arc4io.archud.client.screen.ArcConfigScreen;
import com.arc4io.archud.client.screen.ChatStyler;
import com.arc4io.archud.client.screen.InventoryExtras;
import com.arc4io.archud.client.screen.RecipeChoiceUI;
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
import net.minecraftforge.client.gui.overlay.GuiOverlayManager;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

import java.util.List;
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

    /**
     * Полосы других модов, которые дублируют то, что уже показывает Arc HUD (HP / броня).
     * Legends Mod рисует свои HP и броню вместо ванильных — их скрываем, остальные его полосы
     * (выносливость, мана, скорость...) Forge поднимет над Arc HUD через leftHeight/rightHeight.
     */
    private static final List<String> DUPLICATE_BARS = List.of(
            "legendsmod:health", "legendsmod:legends_armor");

    public static void init(IEventBus modBus) {
        ArcClient.init(new ForgeClient());
        modBus.addListener(ForgeClient::onRegisterKeys);
        modBus.addListener(ForgeClient::onRegisterOverlays);
        modBus.addListener(ForgeClient::onClientSetup);

        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ArcConfigScreen(parent)));

        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener(ForgeClient::onOverlayPre);
        bus.addListener(ForgeClient::onClientTick);
        bus.addListener(ForgeClient::onScreenOpening);
        bus.addListener(ForgeClient::onScreenInit);
        bus.addListener(ForgeClient::onScreenRenderPre);
        bus.addListener(ForgeClient::onScreenRenderPost);
        bus.addListener(ForgeClient::onScreenMouse);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("legendsmod")) LegendsCompat.init();
        });
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

    private static void reserveSpace() {
        if (HudRenderer.replacesStatusBars() && Minecraft.getInstance().gui instanceof ForgeGui gui) {
            int occupied = HudRenderer.occupiedHeight();
            gui.leftHeight = Math.max(gui.leftHeight, occupied);
            gui.rightHeight = Math.max(gui.rightHeight, occupied);
        }
    }

    private static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        ResourceLocation id = event.getOverlay().id();
        // место под Arc HUD резервируем ДО полос других модов (они регистрируются над хотбаром)
        if (id.equals(VanillaGuiOverlay.HOTBAR.id()) || id.equals(VanillaGuiOverlay.VIGNETTE.id())) reserveSpace();
        if (ArcConfig.get().hiddenOverlays.contains(id.toString())
                || (ArcConfig.get().hideDuplicateModBars && HudRenderer.replacesStatusBars() && DUPLICATE_BARS.contains(id.toString()))
                || (ArcConfig.get().addonBars && HudRenderer.replacesStatusBars() && ArcHudApi.isReplaced(id.toString()))) {
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
        RecipeChoiceUI.onRender(event.getScreen(), event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    private static void onScreenMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (RecipeChoiceUI.onClick(event.getScreen(), event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    // ---- ClientPlatform ----
    @Override
    public void sendToServer(ArcPacket packet, ResourceLocation arg) {
        if (Minecraft.getInstance().getConnection() != null) ForgeNetwork.send(packet, arg);
    }

    @Override
    public List<String> hudOverlayIds() {
        List<String> out = new java.util.ArrayList<>();
        for (NamedGuiOverlay o : GuiOverlayManager.getOverlays()) {
            String ns = o.id().getNamespace();
            if (!ns.equals("minecraft") && !ns.equals("forge") && !ns.equals(com.arc4io.archud.ArcHud.MODID)) out.add(o.id().toString());
        }
        return out;
    }

    @Override
    public Screen createModListScreen(Screen parent) {
        return new ModListScreen(parent);
    }
}
