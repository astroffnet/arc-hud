package com.arc4io.archud.network;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.server.BackpackManager;
import com.arc4io.archud.server.InventorySorter;
import com.arc4io.archud.server.RecipeSelector;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Пакеты клиент -> сервер: тип + необязательный ResourceLocation (например id рецепта).
 */
public enum ArcPacket {
    OPEN_BACKPACK("open_backpack"),
    SORT_INVENTORY("sort_inventory"),
    SELECT_RECIPE("select_recipe"),
    TEMPAD_OPEN("tempad_open");

    private final ResourceLocation id;

    ArcPacket(String path) {
        this.id = ArcHud.id(path);
    }

    public ResourceLocation id() {
        return id;
    }

    public static void writeArg(FriendlyByteBuf buf, ResourceLocation arg) {
        buf.writeBoolean(arg != null);
        if (arg != null) buf.writeResourceLocation(arg);
    }

    public static ResourceLocation readArg(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    /** Выполняется на серверном потоке. */
    public void handle(ServerPlayer player, ResourceLocation arg) {
        if (player == null) return;
        switch (this) {
            case OPEN_BACKPACK -> BackpackManager.openEquippedOrFirst(player);
            case SORT_INVENTORY -> InventorySorter.sort(player);
            case SELECT_RECIPE -> {
                if (arg != null) RecipeSelector.select(player, arg);
            }
            case TEMPAD_OPEN -> com.arc4io.archud.server.TempadServer.openDoor(player, arg);
        }
    }
}
