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
    private static final String PROTOCOL = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ArcHud.id("main"), () -> PROTOCOL, ForgeNetwork::compatible, ForgeNetwork::compatible);

    /** Мод не обязателен на другой стороне: без него просто не работают сетевые функции. */
    private static boolean compatible(String v) {
        return PROTOCOL.equals(v) || NetworkRegistry.ABSENT.version().equals(v) || NetworkRegistry.ACCEPTVANILLA.equals(v);
    }

    public record RawC2S(byte[] data) {}

    public record RawS2C(byte[] data) {}

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
        CHANNEL.messageBuilder(RawC2S.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> buf.writeByteArray(msg.data()))
                .decoder(buf -> new RawC2S(buf.readByteArray(1 << 16)))
                .consumerMainThread((msg, ctx) -> com.arc4io.archud.network.ArcNet.receiveOnServer(ctx.get().getSender(),
                        com.arc4io.archud.network.ArcNet.wrap(msg.data())))
                .add();
        CHANNEL.messageBuilder(RawS2C.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> buf.writeByteArray(msg.data()))
                .decoder(buf -> new RawS2C(buf.readByteArray(1 << 22)))
                .consumerMainThread((msg, ctx) -> ClientHandlers.raw(msg))
                .add();
        com.arc4io.archud.network.ArcNet.setServerSide(
                (player, buf) -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RawS2C(com.arc4io.archud.network.ArcNet.bytes(buf))),
                player -> present(player.connection.connection));
    }

    public static boolean present(net.minecraft.network.Connection connection) {
        try {
            return connection != null && CHANNEL.isRemotePresent(connection);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void sendTo(ServerPlayer player, TempadSyncPacket packet) {
        if (!present(player.connection.connection)) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Отдельный класс, чтобы клиентский код не загружался на выделенном сервере. */
    static final class ClientHandlers {
        static void raw(RawS2C msg) {
            com.arc4io.archud.network.ArcNet.receiveOnClient(com.arc4io.archud.network.ArcNet.wrap(msg.data()));
        }

        static void tempadSync(TempadSyncPacket msg) {
            com.arc4io.archud.compat.TempadCompat.applySync(msg);
        }
    }

    public static void send(ArcPacket packet, ResourceLocation arg) {
        CHANNEL.sendToServer(new Msg(packet, arg));
    }
}
