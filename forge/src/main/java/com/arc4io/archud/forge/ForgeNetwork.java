package com.arc4io.archud.forge;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.network.ArcPacket;
import net.minecraft.resources.ResourceLocation;
import com.arc4io.archud.network.TempadSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ForgeNetwork {
    private static final String PROTOCOL = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ArcHud.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public record Msg(ArcPacket packet, ResourceLocation arg) {}

    private ForgeNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(Msg.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> {
                    buf.writeEnum(msg.packet());
                    ArcPacket.writeArg(buf, msg.arg());
                })
                .decoder(buf -> new Msg(buf.readEnum(ArcPacket.class), ArcPacket.readArg(buf)))
                .consumerMainThread((msg, ctx) -> msg.packet().handle(ctx.get().getSender(), msg.arg()))
                .add();
        CHANNEL.messageBuilder(TempadSyncPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TempadSyncPacket::write)
                .decoder(TempadSyncPacket::read)
                .consumerMainThread((msg, ctx) -> ClientHandlers.tempadSync(msg))
                .add();
    }

    public static void sendTo(ServerPlayer player, TempadSyncPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Отдельный класс, чтобы клиентский код не загружался на выделенном сервере. */
    static final class ClientHandlers {
        static void tempadSync(TempadSyncPacket msg) {
            com.arc4io.archud.compat.TempadCompat.applySync(msg);
        }
    }

    public static void send(ArcPacket packet, ResourceLocation arg) {
        CHANNEL.sendToServer(new Msg(packet, arg));
    }
}
