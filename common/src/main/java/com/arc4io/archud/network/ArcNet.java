package com.arc4io.archud.network;

import com.arc4io.archud.ArcHud;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Общий канал «тип + данные» в обе стороны (обмен картой, позиции игроков, метки и т.п.).
 * Платформа (Forge / Fabric) только переносит байты, обработчики живут в общем коде.
 * Обработчики вызываются на основном потоке (серверном или клиентском).
 */
public final class ArcNet {
    public static final ResourceLocation C2S = ArcHud.id("c2s");
    public static final ResourceLocation S2C = ArcHud.id("s2c");
    /** Максимальный размер пакета клиент → сервер (лимит Minecraft ~32 КБ). */
    public static final int C2S_LIMIT = 30000;

    public interface ServerHandler {
        void handle(ServerPlayer player, FriendlyByteBuf buf);
    }

    private static final Map<String, ServerHandler> SERVER = new HashMap<>();
    private static final Map<String, Consumer<FriendlyByteBuf>> CLIENT = new HashMap<>();

    // заполняются платформой
    private static BiConsumer<ServerPlayer, FriendlyByteBuf> toPlayer;
    private static Predicate<ServerPlayer> playerHasMod = p -> true;
    private static Consumer<FriendlyByteBuf> toServer;
    private static java.util.function.BooleanSupplier serverHasMod = () -> false;

    /** Первые ошибки разбора пакетов пишутся в лог (дальше молча, чтобы не засорять). */
    private static int errors;

    private ArcNet() {}

    public static void setServerSide(BiConsumer<ServerPlayer, FriendlyByteBuf> sender, Predicate<ServerPlayer> hasMod) {
        toPlayer = sender;
        playerHasMod = hasMod;
    }

    public static void setClientSide(Consumer<FriendlyByteBuf> sender, java.util.function.BooleanSupplier hasMod) {
        toServer = sender;
        serverHasMod = hasMod;
    }

    public static void onServer(String type, ServerHandler handler) {
        SERVER.put(type, handler);
    }

    public static void onClient(String type, Consumer<FriendlyByteBuf> handler) {
        CLIENT.put(type, handler);
    }

    public static FriendlyByteBuf create(String type) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(type, 64);
        return buf;
    }

    public static boolean canSendToServer() {
        return toServer != null && serverHasMod.getAsBoolean();
    }

    public static void sendToServer(FriendlyByteBuf buf) {
        if (!canSendToServer() || buf.readableBytes() > C2S_LIMIT) return;
        toServer.accept(buf);
    }

    public static boolean hasMod(ServerPlayer player) {
        return toPlayer != null && playerHasMod.test(player);
    }

    public static void sendToPlayer(ServerPlayer player, FriendlyByteBuf buf) {
        if (!hasMod(player)) return;
        toPlayer.accept(player, buf);
    }

    /** Платформа получила пакет от клиента (уже на серверном потоке). */
    public static void receiveOnServer(ServerPlayer player, FriendlyByteBuf buf) {
        try {
            String type = buf.readUtf(64);
            ServerHandler h = SERVER.get(type);
            if (h != null && player != null) h.handle(player, buf);
        } catch (Throwable t) {
            if (errors++ < 10) ArcHud.LOGGER.warn("Arc HUD: bad packet from {}", player == null ? "?" : player.getGameProfile().getName(), t);
        }
    }

    /** Платформа получила пакет от сервера (уже на клиентском потоке). */
    public static void receiveOnClient(FriendlyByteBuf buf) {
        try {
            String type = buf.readUtf(64);
            Consumer<FriendlyByteBuf> h = CLIENT.get(type);
            if (h != null) h.accept(buf);
        } catch (Throwable t) {
            if (errors++ < 10) ArcHud.LOGGER.warn("Arc HUD: bad server packet", t);
        }
    }

    /** Копия буфера в байты (для Forge-сообщений). */
    public static byte[] bytes(FriendlyByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), b);
        return b;
    }

    public static FriendlyByteBuf wrap(byte[] b) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(b));
    }
}
