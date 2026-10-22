package com.arc4io.archud.network;

import com.arc4io.archud.ArcHud;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сервер -> клиент: локации Tempad игрока (как в tempadtomapx), чтобы они были метками на карте,
 * компасе и в мире сразу, без открытия Tempad.
 */
public record TempadSyncPacket(List<Entry> entries) {
    public static final ResourceLocation ID = ArcHud.id("tempad_sync");

    public record Entry(UUID id, String name, BlockPos pos, String dimension) {}

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUUID(e.id());
            buf.writeUtf(e.name(), 256);
            buf.writeBlockPos(e.pos());
            buf.writeUtf(e.dimension(), 256);
        }
    }

    public static TempadSyncPacket read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 4096);
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUUID(), buf.readUtf(256), buf.readBlockPos(), buf.readUtf(256)));
        }
        return new TempadSyncPacket(list);
    }
}
