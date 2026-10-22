package com.arc4io.archud.fabric;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.item.BackpackItem;
import com.arc4io.archud.item.BackpackTier;
import com.arc4io.archud.network.ArcPacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Arc HUD — точка входа Fabric 1.20.1. Автор: arc4io
 */
public class ArcHudFabric implements ModInitializer {
    public static final List<Item> BACKPACKS = new ArrayList<>();

    @Override
    public void onInitialize() {
        ArcHud.init(new FabricPlatform());
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("trinkets")) {
            try {
                com.arc4io.archud.fabric.compat.TrinketsCompat.registerKeepTempad();
            } catch (Throwable t) {
                ArcHud.LOGGER.warn("Arc HUD: Trinkets drop rule not registered: {}", t.toString());
            }
        }

        for (BackpackTier tier : BackpackTier.values()) {
            BACKPACKS.add(Registry.register(BuiltInRegistries.ITEM, ArcHud.id(tier.itemName()), new BackpackItem(tier, new Item.Properties())));
        }
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            for (Item item : BACKPACKS) entries.accept(item);
        });

        for (ArcPacket packet : ArcPacket.values()) {
            ServerPlayNetworking.registerGlobalReceiver(packet.id(),
                    (server, player, handler, buf, sender) -> {
                        var arg = ArcPacket.readArg(buf);
                        server.execute(() -> packet.handle(player, arg));
                    });
        }
        ServerPlayNetworking.registerGlobalReceiver(com.arc4io.archud.network.ArcNet.C2S, (server, player, handler, buf, sender) -> {
            byte[] data = com.arc4io.archud.network.ArcNet.bytes(buf);
            server.execute(() -> com.arc4io.archud.network.ArcNet.receiveOnServer(player, com.arc4io.archud.network.ArcNet.wrap(data)));
        });
        com.arc4io.archud.network.ArcNet.setServerSide(
                (player, buf) -> ServerPlayNetworking.send(player, com.arc4io.archud.network.ArcNet.S2C, buf),
                player -> ServerPlayNetworking.canSend(player, com.arc4io.archud.network.ArcNet.S2C));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(com.arc4io.archud.server.ArcServer::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(com.arc4io.archud.server.ArcServer::onStop);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server ->
                com.arc4io.archud.server.TempadServer.tick(server, (player, packet) -> {
                    if (!ServerPlayNetworking.canSend(player, com.arc4io.archud.network.TempadSyncPacket.ID)) return;
                    var buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
                    packet.write(buf);
                    ServerPlayNetworking.send(player, com.arc4io.archud.network.TempadSyncPacket.ID, buf);
                }));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            com.arc4io.archud.server.TempadServer.forget(handler.player.getUUID());
            com.arc4io.archud.server.ArcServer.onLogout(handler.player);
        });
    }
}
