package com.arc4io.archud.forge.inventory;

import com.arc4io.archud.menu.AccessoryInfo;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Слоты Curios для нового инвентаря: все типы слотов игрока (в том числе скрытые из меню Curios, как у Aether).
 * На сервере слоты работают напрямую с хранилищем Curios; на клиенте — с копией, которую синхронизирует меню.
 */
final class CuriosSlots {
    private CuriosSlots() {}

    /** Список слотов аксессуаров игрока (по порядку типов Curios). */
    static List<ArcInventoryMenu.Acc> list(Player player) {
        List<ArcInventoryMenu.Acc> out = new ArrayList<>();
        Optional<ICuriosItemHandler> inv = CuriosApi.getCuriosInventory(player).resolve();
        if (inv.isEmpty()) return out;
        List<Map.Entry<String, ICurioStacksHandler>> entries = new ArrayList<>(inv.get().getCurios().entrySet());
        Map<String, ISlotType> types = CuriosApi.getPlayerSlots(player);
        entries.sort(Comparator.comparingInt((Map.Entry<String, ICurioStacksHandler> e) -> {
            ISlotType t = types.get(e.getKey());
            return t == null ? 1000 : t.getOrder();
        }).thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, ICurioStacksHandler> e : entries) {
            ISlotType t = types.get(e.getKey());
            ResourceLocation icon = t != null && t.getIcon() != null ? t.getIcon() : new ResourceLocation("curios", "slot/empty_curio_slot");
            int n = e.getValue().getStacks().getSlots();
            for (int i = 0; i < n; i++) out.add(new ArcInventoryMenu.Acc(e.getKey(), i, icon));
        }
        return out;
    }

    static boolean valid(Player player, String type, int index, ItemStack stack) {
        if (stack.isEmpty()) return true;
        SlotContext ctx = new SlotContext(type, player, index, false, true);
        try {
            if (!CuriosApi.isStackValid(ctx, stack)) return false;
            return CuriosApi.getCurio(stack).map(c -> c.canEquip(ctx)).orElse(true);
        } catch (Throwable t) {
            return false;
        }
    }

    static void addServer(ArcInventoryMenu menu, Player player, List<ArcInventoryMenu.Acc> acc) {
        Optional<ICuriosItemHandler> inv = CuriosApi.getCuriosInventory(player).resolve();
        if (inv.isEmpty()) return;
        Map<String, ICurioStacksHandler> curios = inv.get().getCurios();
        for (ArcInventoryMenu.Acc a : acc) {
            ICurioStacksHandler h = curios.get(a.type());
            if (h == null || a.index() >= h.getStacks().getSlots()) {
                menu.add(new ClientSlot(new SimpleContainer(1), 0, a, player)); // слот пропал — пустышка
                continue;
            }
            menu.add(new ServerSlot(h.getStacks(), a, player));
        }
    }

    static void addClient(ArcInventoryMenu menu, Player player, List<ArcInventoryMenu.Acc> acc) {
        SimpleContainer box = new SimpleContainer(Math.max(1, acc.size()));
        for (int i = 0; i < acc.size(); i++) menu.add(new ClientSlot(box, i, acc.get(i), player));
    }

    private static Component label(String type) {
        return Component.translatableWithFallback("curios.identifier." + type, type);
    }

    /** Слот на сервере — прямо в хранилище Curios. */
    static final class ServerSlot extends SlotItemHandler implements AccessoryInfo {
        private final ArcInventoryMenu.Acc acc;
        private final Player player;

        ServerSlot(IItemHandler handler, ArcInventoryMenu.Acc acc, Player player) {
            super(handler, acc.index(), 0, 0);
            this.acc = acc;
            this.player = player;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return valid(player, acc.type(), acc.index(), stack) && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player p) {
            ItemStack s = getItem();
            if (s.isEmpty() || p.isCreative()) return super.mayPickup(p);
            SlotContext ctx = new SlotContext(acc.type(), player, acc.index(), false, true);
            return CuriosApi.getCurio(s).map(c -> c.canUnequip(ctx)).orElse(true) && super.mayPickup(p);
        }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, acc.icon());
        }

        @Override
        public String group() {
            return acc.type();
        }

        @Override
        public Component label() {
            return CuriosSlots.label(acc.type());
        }
    }

    /** Слот на клиенте — содержимое приходит синхронизацией меню. */
    static final class ClientSlot extends Slot implements AccessoryInfo {
        private final ArcInventoryMenu.Acc acc;
        private final Player player;

        ClientSlot(SimpleContainer box, int i, ArcInventoryMenu.Acc acc, Player player) {
            super(box, i, 0, 0);
            this.acc = acc;
            this.player = player;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return valid(player, acc.type(), acc.index(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, acc.icon());
        }

        @Override
        public String group() {
            return acc.type();
        }

        @Override
        public Component label() {
            return CuriosSlots.label(acc.type());
        }
    }
}
