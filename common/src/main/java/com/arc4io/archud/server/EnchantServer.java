package com.arc4io.archud.server;

import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.enchant.EnchantLogic;
import com.arc4io.archud.mixin.EnchantmentMenuAccessor;
import com.arc4io.archud.network.ArcNet;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Серверная часть нового стола зачарования:
 *  - ench_preview: полный список зачарований каждого из трёх вариантов (ванилла показывает только одно «…?»),
 *    сила полок и разрешён ли выборочный режим;
 *  - ench_select: выборочное зачарование (игрок сам выбирает зачарования и уровни, цена по EnchantLogic);
 *  - ench_reroll: перебросить варианты за 1 лазурит.
 * Всё проверяется на сервере, клиент только показывает.
 */
public final class EnchantServer {
    private EnchantServer() {}

    public static void init() {
        ArcNet.onServer("ench_preview", (p, buf) -> sendPreview(p, buf.readVarInt()));
        ArcNet.onServer("ench_select", EnchantServer::onSelect);
        ArcNet.onServer("ench_reroll", EnchantServer::onReroll);
    }

    private static EnchantmentMenu menu(ServerPlayer p, int containerId) {
        if (p.containerMenu instanceof EnchantmentMenu m && m.containerId == containerId && m.stillValid(p)) return m;
        return null;
    }

    private static int power(EnchantmentMenu m) {
        return ((EnchantmentMenuAccessor) m).archud$access().evaluate(EnchantLogic::power, 0);
    }

    public static void sendPreview(ServerPlayer p, int containerId) {
        EnchantmentMenu m = menu(p, containerId);
        if (m == null) {
            com.arc4io.archud.ArcHud.LOGGER.debug("Arc HUD: enchant preview for closed menu {} (open: {})", containerId, p.containerMenu.containerId);
            return;
        }
        EnchantmentMenuAccessor acc = (EnchantmentMenuAccessor) m;
        ItemStack stack = acc.archud$slots().getItem(0);
        FriendlyByteBuf out = ArcNet.create("ench_data");
        out.writeVarInt(containerId);
        out.writeInt(m.getEnchantmentSeed());
        for (int i = 0; i < 3; i++) {
            List<EnchantmentInstance> list = stack.isEmpty() || m.costs[i] <= 0 ? List.of() : acc.archud$list(stack, i, m.costs[i]);
            out.writeVarInt(list.size());
            for (EnchantmentInstance e : list) {
                ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(e.enchantment);
                out.writeUtf(id == null ? "" : id.toString());
                out.writeVarInt(e.level);
            }
        }
        out.writeVarInt(power(m));
        ArcConfig cfg = ArcConfig.get();
        out.writeBoolean(cfg.enchantSelective);
        out.writeBoolean(cfg.enchantReroll);
        if ("1".equals(System.getenv("ARCHUD_SHOWCASE"))) com.arc4io.archud.ArcHud.LOGGER.info("[Showcase] enchant preview sent {} hasMod={}", containerId, ArcNet.hasMod(p));
        ArcNet.sendToPlayer(p, out);
    }

    private static void onSelect(ServerPlayer p, FriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int n = Math.min(64, buf.readVarInt());
        Map<Enchantment, Integer> chosen = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            ResourceLocation id = ResourceLocation.tryParse(buf.readUtf(256));
            int lvl = buf.readVarInt();
            Enchantment e = id == null ? null : BuiltInRegistries.ENCHANTMENT.get(id);
            if (e != null && lvl > 0) chosen.put(e, Math.min(lvl, 255));
        }
        if (!ArcConfig.get().enchantSelective) return;
        EnchantmentMenu m = menu(p, containerId);
        if (m == null) return;
        EnchantmentMenuAccessor acc = (EnchantmentMenuAccessor) m;
        Container slots = acc.archud$slots();
        ItemStack stack = slots.getItem(0);
        ItemStack lapis = slots.getItem(1);
        int power = power(m);
        if (EnchantLogic.validate(stack, power, chosen) != null) {
            sendPreview(p, containerId);
            return;
        }
        EnchantLogic.Cost cost = EnchantLogic.cost(chosen);
        boolean creative = p.getAbilities().instabuild;
        if (!creative && (p.experienceLevel < cost.required() || lapis.getCount() < cost.lapis() || !lapis.is(Items.LAPIS_LAZULI))) {
            sendPreview(p, containerId);
            return;
        }
        ItemStack result = stack;
        if (stack.is(Items.BOOK)) {
            result = new ItemStack(Items.ENCHANTED_BOOK);
            CompoundTag tag = stack.getTag();
            if (tag != null) result.setTag(tag.copy());
            for (Map.Entry<Enchantment, Integer> e : chosen.entrySet()) {
                EnchantedBookItem.addEnchantment(result, new EnchantmentInstance(e.getKey(), e.getValue()));
            }
            slots.setItem(0, result);
        } else {
            for (Map.Entry<Enchantment, Integer> e : chosen.entrySet()) stack.enchant(e.getKey(), e.getValue());
        }
        p.onEnchantmentPerformed(result, cost.consumed());
        if (!creative) {
            lapis.shrink(cost.lapis());
            if (lapis.isEmpty()) slots.setItem(1, ItemStack.EMPTY);
        }
        p.awardStat(Stats.ENCHANT_ITEM);
        CriteriaTriggers.ENCHANTED_ITEM.trigger(p, result, cost.consumed());
        slots.setChanged();
        acc.archud$seed().set(p.getEnchantmentSeed());
        m.slotsChanged(slots);
        acc.archud$access().execute((Level level, BlockPos pos) -> level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.BLOCKS, 1.0F, level.random.nextFloat() * 0.1F + 0.9F));
        m.broadcastChanges();
        sendPreview(p, containerId);
    }

    private static void onReroll(ServerPlayer p, FriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        if (!ArcConfig.get().enchantReroll) return;
        EnchantmentMenu m = menu(p, containerId);
        if (m == null) return;
        EnchantmentMenuAccessor acc = (EnchantmentMenuAccessor) m;
        Container slots = acc.archud$slots();
        ItemStack lapis = slots.getItem(1);
        boolean creative = p.getAbilities().instabuild;
        if (slots.getItem(0).isEmpty()) return;
        if (!creative) {
            if (!lapis.is(Items.LAPIS_LAZULI) || lapis.isEmpty()) return;
            lapis.shrink(1);
            if (lapis.isEmpty()) slots.setItem(1, ItemStack.EMPTY);
        }
        p.onEnchantmentPerformed(ItemStack.EMPTY, 0); // только новый «сид», уровни не тратятся
        acc.archud$seed().set(p.getEnchantmentSeed());
        slots.setChanged();
        m.slotsChanged(slots);
        acc.archud$access().execute((Level level, BlockPos pos) -> level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN,
                SoundSource.BLOCKS, 1.0F, 1.0F));
        m.broadcastChanges();
        sendPreview(p, containerId);
    }
}
