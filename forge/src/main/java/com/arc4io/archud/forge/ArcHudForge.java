package com.arc4io.archud.forge;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.item.BackpackItem;
import com.arc4io.archud.item.BackpackTier;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Arc HUD — точка входа Forge 1.20.1. Автор: arc4io
 */
@Mod(ArcHud.MODID)
public class ArcHudForge {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArcHud.MODID);
    public static final List<RegistryObject<Item>> BACKPACKS = new ArrayList<>();

    static {
        for (BackpackTier tier : BackpackTier.values()) {
            BACKPACKS.add(ITEMS.register(tier.itemName(), () -> new BackpackItem(tier, new Item.Properties())));
        }
    }

    public ArcHudForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ArcHud.init(new ForgePlatform());
        ITEMS.register(modBus);
        modBus.addListener(ArcHudForge::onCreativeTabs);
        ForgeNetwork.register();
        if (net.minecraftforge.fml.ModList.get().isLoaded("curios")) {
            try {
                com.arc4io.archud.forge.compat.CuriosCompat.registerKeepTempad();
            } catch (Throwable t) {
                ArcHud.LOGGER.warn("Arc HUD: Curios drop rules not registered: {}", t.toString());
            }
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ArcHudForge::onServerTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ArcHudForge::onLogout);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppingEvent e) ->
                com.arc4io.archud.server.ArcServer.onStop(e.getServer()));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClient.init(modBus);
        }
    }

    private static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        com.arc4io.archud.server.TempadServer.tick(server, ForgeNetwork::sendTo);
        com.arc4io.archud.server.ArcServer.tick(server);
    }

    private static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        com.arc4io.archud.server.TempadServer.forget(event.getEntity().getUUID());
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) com.arc4io.archud.server.ArcServer.onLogout(sp);
    }

    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            for (RegistryObject<Item> item : BACKPACKS) event.accept(item.get());
        }
    }
}
