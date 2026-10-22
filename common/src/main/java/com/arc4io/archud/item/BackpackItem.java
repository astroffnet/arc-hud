package com.arc4io.archud.item;

import com.arc4io.archud.server.BackpackManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Рюкзак. Можно открыть ПКМ в руке, клавишей (по умолчанию B) или надеть в слот спины
 * (Curios на Forge, Trinkets на Fabric) и открывать клавишей.
 */
public class BackpackItem extends Item {
    private final BackpackTier tier;

    public BackpackItem(BackpackTier tier, Properties properties) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public BackpackTier tier() {
        return tier;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            int slot = hand == InteractionHand.MAIN_HAND ? sp.getInventory().selected : BackpackManager.OFFHAND_SLOT;
            BackpackManager.openFromInventory(sp, slot);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        int used = 0;
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("Items", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
            used = list.size();
        }
        tooltip.add(Component.translatable("archud.backpack.slots", used, tier.size()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("archud.backpack.hint").withStyle(ChatFormatting.DARK_AQUA));
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }
}
