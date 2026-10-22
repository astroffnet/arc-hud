package com.arc4io.archud.network;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.server.BackpackManager;
import com.arc4io.archud.server.InventorySorter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Пакеты клиент -> сервер. Без данных, только тип.
 */
public enum ArcPacket {
    OPEN_BACKPACK("open_backpack"),
    SORT_INVENTORY("sort_inventory");

    private final ResourceLocation id;

    ArcPacket(String path) {
        this.id = ArcHud.id(path);
    }

    public ResourceLocation id() {
        return id;
    }

    /** Выполняется на серверном потоке. */
    public void handle(ServerPlayer player) {
        if (player == null) return;
        switch (this) {
            case OPEN_BACKPACK -> BackpackManager.openEquippedOrFirst(player);
            case SORT_INVENTORY -> InventorySorter.sort(player);
        }
    }
}
