package com.arc4io.archud.forge;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.network.ArcPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ForgeNetwork {
    private static final String PROTOCOL = "2";
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
    }

    public static void send(ArcPacket packet, ResourceLocation arg) {
        CHANNEL.sendToServer(new Msg(packet, arg));
    }
}
