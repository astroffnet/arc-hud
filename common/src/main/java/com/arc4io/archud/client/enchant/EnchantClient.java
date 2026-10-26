package com.arc4io.archud.client.enchant;

import com.arc4io.archud.network.ArcNet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Данные стола зачарования от сервера (полные списки вариантов, сила полок, разрешённые режимы). */
public final class EnchantClient {
    public static int containerId = -1;
    public static int seed;
    @SuppressWarnings("unchecked")
    public static final List<EnchantmentInstance>[] PREVIEW = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
    public static int power;
    public static boolean selective, reroll;
    public static long receivedAt;

    private EnchantClient() {}

    public static void init() {
        ArcNet.onClient("ench_data", EnchantClient::onData);
    }

    /** Сервер с Arc HUD прислал данные для этого окна. */
    public static boolean has(int id) {
        return containerId == id && receivedAt > 0;
    }

    public static boolean serverSupports() {
        return ArcNet.canSendToServer() && com.arc4io.archud.client.ArcClientNet.modServer();
    }

    private static void onData(FriendlyByteBuf buf) {
        containerId = buf.readVarInt();
        seed = buf.readInt();
        for (int i = 0; i < 3; i++) {
            PREVIEW[i].clear();
            int n = buf.readVarInt();
            for (int k = 0; k < n; k++) {
                ResourceLocation id = ResourceLocation.tryParse(buf.readUtf());
                int lvl = buf.readVarInt();
                Enchantment e = id == null ? null : BuiltInRegistries.ENCHANTMENT.get(id);
                if (e != null) PREVIEW[i].add(new EnchantmentInstance(e, lvl));
            }
        }
        power = buf.readVarInt();
        selective = buf.readBoolean();
        reroll = buf.readBoolean();
        receivedAt = System.currentTimeMillis();
    }

    public static void requestPreview(int id) {
        if ("1".equals(System.getenv("ARCHUD_SHOWCASE"))) com.arc4io.archud.ArcHud.LOGGER.info("[Showcase] enchant preview request {}", id);
        FriendlyByteBuf b = ArcNet.create("ench_preview");
        b.writeVarInt(id);
        ArcNet.sendToServer(b);
    }

    public static void requestReroll(int id) {
        FriendlyByteBuf b = ArcNet.create("ench_reroll");
        b.writeVarInt(id);
        ArcNet.sendToServer(b);
    }

    public static void requestSelect(int id, Map<Enchantment, Integer> chosen) {
        FriendlyByteBuf b = ArcNet.create("ench_select");
        b.writeVarInt(id);
        List<Map.Entry<Enchantment, Integer>> list = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> e : chosen.entrySet()) if (e.getValue() > 0) list.add(e);
        b.writeVarInt(list.size());
        for (Map.Entry<Enchantment, Integer> e : list) {
            ResourceLocation key = BuiltInRegistries.ENCHANTMENT.getKey(e.getKey());
            b.writeUtf(key == null ? "" : key.toString(), 256);
            b.writeVarInt(e.getValue());
        }
        ArcNet.sendToServer(b);
    }
}
