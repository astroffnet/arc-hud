package com.arc4io.archud.forge.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.server.TempadServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.DropRulesEvent;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tempad «привязан» к слоту браслета (и любому слоту Curios): после смерти он остаётся на месте.
 * Tempad в обычном инвентаре выпадает как обычно.
 *
 * Как: в самом начале смерти (раньше модов-могил и Curios) Tempad снимается со слота и запоминается в
 * сохраняемых данных игрока («PlayerPersisted» — переживает смерть и выход из игры), а после возрождения
 * возвращается в тот же слот. Если смерть отменил другой мод (тотем и т.п.) — возвращается сразу.
 * Загружается только при установленном Curios.
 */
public final class TempadKeeper {
    private static final String PERSISTED = "PlayerPersisted";
    private static final String KEY = "archud_tempad_slots";

    private TempadKeeper() {}

    /** Подстраховка через правила Curios (если снятие не сработало). */
    @SubscribeEvent
    public static void onDropRules(DropRulesEvent e) {
        if (!ArcConfig.get().tempadKeepOnDeath) return;
        e.addOverride(TempadServer::isTempadStack, ICurio.DropRule.ALWAYS_KEEP);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent e) {
        if (!ArcConfig.get().tempadKeepOnDeath || !(e.getEntity() instanceof ServerPlayer player)) return;
        ListTag saved = new ListTag();
        Optional<ICuriosItemHandler> inv = CuriosApi.getCuriosInventory(player).resolve();
        if (inv.isEmpty()) return;
        for (Map.Entry<String, ICurioStacksHandler> entry : inv.get().getCurios().entrySet()) {
            IDynamicStackHandler stacks = entry.getValue().getStacks();
            for (int i = 0; i < stacks.getSlots(); i++) {
                ItemStack s = stacks.getStackInSlot(i);
                if (!TempadServer.isTempadStack(s)) continue;
                CompoundTag t = new CompoundTag();
                t.putString("slot", entry.getKey());
                t.putInt("index", i);
                t.put("item", s.save(new CompoundTag()));
                saved.add(t);
                stacks.setStackInSlot(i, ItemStack.EMPTY); // ни Curios, ни моды-могилы его уже не заберут
            }
        }
        if (saved.isEmpty()) return;
        CompoundTag persisted = player.getPersistentData().getCompound(PERSISTED);
        ListTag old = persisted.getList(KEY, Tag.TAG_COMPOUND);
        old.addAll(saved);
        persisted.put(KEY, old);
        player.getPersistentData().put(PERSISTED, persisted);
        ArcHud.LOGGER.info("Arc HUD: Tempad в браслете сохранён после смерти игрока {}", player.getGameProfile().getName());
    }

    /** Смерть отменена (тотем, моды на воскрешение) — вернуть сразу. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDeathCanceled(LivingDeathEvent e) {
        if (e.isCanceled() && e.getEntity() instanceof ServerPlayer player) restore(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer player) restore(player);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        // вышли из игры мёртвым и зашли уже живым (например, после перезапуска сервера)
        if (e.getEntity() instanceof ServerPlayer player && player.isAlive()) restore(player);
    }

    private static void restore(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(PERSISTED);
        if (!persisted.contains(KEY)) return;
        ListTag list = persisted.getList(KEY, Tag.TAG_COMPOUND);
        persisted.remove(KEY);
        player.getPersistentData().put(PERSISTED, persisted);
        List<ItemStack> leftovers = new ArrayList<>();
        Optional<ICuriosItemHandler> inv = CuriosApi.getCuriosInventory(player).resolve();
        for (int k = 0; k < list.size(); k++) {
            CompoundTag t = list.getCompound(k);
            ItemStack stack = ItemStack.of(t.getCompound("item"));
            if (stack.isEmpty()) continue;
            boolean placed = false;
            if (inv.isPresent()) {
                placed = put(inv.get(), t.getString("slot"), t.getInt("index"), stack)
                        || put(inv.get(), "bracelet", -1, stack);
            }
            if (!placed) leftovers.add(stack);
        }
        for (ItemStack s : leftovers) {
            if (!player.getInventory().add(s)) player.drop(s, false);
        }
    }

    /** В слот id (index — конкретная ячейка или -1 — любая свободная). */
    private static boolean put(ICuriosItemHandler inv, String id, int index, ItemStack stack) {
        Optional<ICurioStacksHandler> h = inv.getStacksHandler(id);
        if (h.isEmpty()) return false;
        IDynamicStackHandler stacks = h.get().getStacks();
        if (index >= 0 && index < stacks.getSlots() && stacks.getStackInSlot(index).isEmpty()) {
            stacks.setStackInSlot(index, stack);
            return true;
        }
        for (int i = 0; i < stacks.getSlots(); i++) {
            if (stacks.getStackInSlot(i).isEmpty()) {
                stacks.setStackInSlot(i, stack);
                return true;
            }
        }
        return false;
    }
}
