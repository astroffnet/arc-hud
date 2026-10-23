package com.arc4io.archud.forge.compat;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.server.TempadServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.DropRulesEvent;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tempad не теряется после смерти:
 *  1) правило Curios «всегда оставлять» для Tempad в слотах (браслет и др.);
 *  2) страховка: если Tempad всё же попал в выпадающие предметы (другой мод, настройки Curios),
 *     он забирается из дропа и после возрождения возвращается в слот браслета (или в инвентарь).
 * Загружается только при установленном Curios.
 */
public final class TempadKeeper {
    private static final Map<UUID, List<ItemStack>> KEPT = new HashMap<>();

    private TempadKeeper() {}

    @SubscribeEvent
    public static void onDropRules(DropRulesEvent e) {
        if (!ArcConfig.get().tempadKeepOnDeath) return;
        e.addOverride(TempadServer::isTempadStack, ICurio.DropRule.ALWAYS_KEEP);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrops(LivingDropsEvent e) {
        if (!ArcConfig.get().tempadKeepOnDeath || !(e.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        List<ItemStack> kept = new ArrayList<>();
        e.getDrops().removeIf((ItemEntity ie) -> {
            if (!TempadServer.isTempadStack(ie.getItem())) return false;
            kept.add(ie.getItem().copy());
            return true;
        });
        if (!kept.isEmpty()) {
            KEPT.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).addAll(kept);
            ArcHud.LOGGER.info("Arc HUD: Tempad сохранён после смерти игрока {}", player.getGameProfile().getName());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        List<ItemStack> list = KEPT.remove(player.getUUID());
        if (list == null) return;
        for (ItemStack stack : list) {
            if (insertCurio(player, stack)) continue;
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
    }

    private static boolean insertCurio(ServerPlayer player, ItemStack stack) {
        try {
            var inv = CuriosApi.getCuriosInventory(player).resolve();
            if (inv.isEmpty()) return false;
            List<String> ids = new ArrayList<>();
            try {
                ids.addAll(CuriosApi.getItemStackSlots(stack, player.level()).keySet());
            } catch (Throwable ignored) {
            }
            if (!ids.contains("bracelet")) ids.add(0, "bracelet");
            for (String id : ids) {
                Optional<ICurioStacksHandler> h = inv.get().getStacksHandler(id);
                if (h.isEmpty()) continue;
                IDynamicStackHandler stacks = h.get().getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    if (stacks.getStackInSlot(i).isEmpty()) {
                        stacks.setStackInSlot(i, stack);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            ArcHud.LOGGER.debug("Arc HUD: не удалось вернуть Tempad в слот Curios", t);
        }
        return false;
    }
}
