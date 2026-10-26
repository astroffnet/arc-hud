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
        modBus.addListener(ForgeClient::onAddPacks);
        modBus.addListener(ForgeClient::onRegisterModels);

        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ArcConfigScreen(parent)));

        com.arc4io.archud.network.ArcNet.setClientSide(
                buf -> ForgeNetwork.CHANNEL.sendToServer(new ForgeNetwork.RawC2S(com.arc4io.archud.network.ArcNet.bytes(buf))),
                () -> Minecraft.getInstance().getConnection() != null && ForgeNetwork.present(Minecraft.getInstance().getConnection().getConnection()));
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener(ForgeClient::onOverlayPre);
        bus.addListener(ForgeClient::onClientTick);
        bus.addListener(ForgeClient::onRenderLevel);
        // самый низкий приоритет: срабатываем ПОСЛЕ модов, меняющих меню (Aether), и решаем последними
        bus.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST, ForgeClient::onScreenOpening);
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
        // своё меню F3 (на Forge текст отладки рисует отдельный слой; он вызывается каждый кадр,
        // даже когда F3 закрыто, — поэтому проверяем, что F3 действительно открыто)
        if (id.equals(VanillaGuiOverlay.DEBUG_TEXT.id()) && com.arc4io.archud.client.hud.ArcDebugScreen.active()) {
            event.setCanceled(true);
            com.arc4io.archud.client.hud.ArcDebugScreen.render(event.getGuiGraphics());
            return;
        }
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
        if (Minecraft.getInstance().getConnection() != null && ForgeNetwork.present(Minecraft.getInstance().getConnection().getConnection())) ForgeNetwork.send(packet, arg);
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
        return ArcConfig.get().customModList ? new ArcModListScreen(parent) : new ModListScreen(parent);
    }

    @Override
    public Screen swapPlatformScreen(Screen next, Screen current) {
        if (ArcConfig.get().customModList && next != null && next.getClass() == ModListScreen.class) return new ArcModListScreen(current);
        return next;
    }

    private static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        com.arc4io.archud.client.map.WaypointWorldRenderer.capture(event.getPoseStack(), event.getProjectionMatrix(), event.getCamera());
    }

    /** Встроенные пакеты: «Родное слово» (русский перевод модов) и CIT (ресурспаки OptiFine). */
    private static void onAddPacks(net.minecraftforge.event.AddPackFindersEvent event) {
        if (event.getPackType() != net.minecraft.server.packs.PackType.CLIENT_RESOURCES) return;
        com.arc4io.archud.config.ArcConfig cfg = com.arc4io.archud.config.ArcConfig.get();
        event.addRepositorySource(consumer -> {
            var cit = net.minecraft.server.packs.repository.Pack.readMetaAndCreate(com.arc4io.archud.forge.cit.CitPack.ID,
                    net.minecraft.network.chat.Component.translatable("archud.cit.pack"), true, id -> new com.arc4io.archud.forge.cit.CitPack(),
                    net.minecraft.server.packs.PackType.CLIENT_RESOURCES, net.minecraft.server.packs.repository.Pack.Position.TOP,
                    net.minecraft.server.packs.repository.PackSource.BUILT_IN);
            if (cit != null) consumer.accept(cit);
            if (cfg.builtinRussianPack) {
                java.nio.file.Path path = net.minecraftforge.fml.ModList.get().getModFileById("archud").getFile().findResource("resourcepacks/rodnoe_slovo");
                var ru = net.minecraft.server.packs.repository.Pack.readMetaAndCreate(RU_PACK, net.minecraft.network.chat.Component.literal("Родное слово"), false,
                        id -> new net.minecraft.server.packs.PathPackResources(id, path, true), net.minecraft.server.packs.PackType.CLIENT_RESOURCES,
                        net.minecraft.server.packs.repository.Pack.Position.TOP, net.minecraft.server.packs.repository.PackSource.BUILT_IN);
                if (ru != null) consumer.accept(ru);
            }
        });
        // первый запуск: включить «Родное слово» (потом игрок может его выключить — больше не трогаем)
        if (cfg.builtinRussianPack && !cfg.builtinRussianPackOffered) {
            var options = net.minecraft.client.Minecraft.getInstance().options;
            if (options != null) {
                if (!options.resourcePacks.contains(RU_PACK)) options.resourcePacks.add(RU_PACK);
                cfg.builtinRussianPackOffered = true;
                com.arc4io.archud.config.ArcConfig.save();
            }
        }
    }

    private static final String RU_PACK = "archud_rodnoe_slovo";

    private static void onRegisterModels(net.minecraftforge.client.event.ModelEvent.RegisterAdditional event) {
        for (var loc : com.arc4io.archud.forge.cit.CitManager.models()) event.register(loc);
    }
}
