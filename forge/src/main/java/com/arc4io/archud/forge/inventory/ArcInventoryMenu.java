package com.arc4io.archud.forge.inventory;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Меню нового инвентаря выживания с аксессуарами (Forge + Curios). Слоты 0–45 — как у ванильного
 * InventoryMenu (результат, крафт 2×2, броня, инвентарь, хотбар, вторая рука), 46+ — все слоты Curios игрока,
 * включая те, что моды прячут из меню Curios (например, перчатки, кольца и кулоны Aether).
 */
public class ArcInventoryMenu extends AbstractContainerMenu {
    private static final ResourceLocation[] ARMOR_ICONS = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS};
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Описание слота аксессуара: тип Curios, номер, иконка пустого слота. */
    public record Acc(String type, int index, ResourceLocation icon) {}

    private final CraftingContainer craft = new TransientCraftingContainer(this, 2, 2);
    private final ResultContainer result = new ResultContainer();
    private final Player owner;
    private final int accStart;

    private ArcInventoryMenu(int id, Inventory inv, List<Acc> acc, boolean server) {
        super(ForgeInventory.TYPE.get(), id);
        this.owner = inv.player;
        addSlot(new ResultSlot(inv.player, craft, result, 0, 0, 0));
        for (int i = 0; i < 4; i++) addSlot(new Slot(craft, i, 0, 0));
        for (int k = 0; k < 4; k++) {
            EquipmentSlot eq = ARMOR[k];
            ResourceLocation icon = ARMOR_ICONS[k];
            addSlot(new Slot(inv, 39 - k, 0, 0) {
                @Override
                public void setByPlayer(ItemStack stack) {
                    owner.onEquipItem(eq, getItem(), stack);
                    super.setByPlayer(stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.canEquip(eq, owner);
                }

                @Override
                public boolean mayPickup(Player p) {
                    ItemStack s = getItem();
                    return (s.isEmpty() || p.isCreative() || !EnchantmentHelper.hasBindingCurse(s)) && super.mayPickup(p);
                }

                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return Pair.of(InventoryMenu.BLOCK_ATLAS, icon);
                }
            });
        }
        for (int i = 0; i < 27; i++) addSlot(new Slot(inv, 9 + i, 0, 0));
        for (int i = 0; i < 9; i++) addSlot(new Slot(inv, i, 0, 0));
        addSlot(new Slot(inv, 40, 0, 0) {
            @Override
            public void setByPlayer(ItemStack stack) {
                owner.onEquipItem(EquipmentSlot.OFFHAND, getItem(), stack);
                super.setByPlayer(stack);
            }

            @Override
            public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
            }
        });
        accStart = slots.size();
        if (server) CuriosSlots.addServer(this, inv.player, acc);
        else CuriosSlots.addClient(this, inv.player, acc);
    }

    /** Добавить слот (для CuriosSlots). */
    Slot add(Slot s) {
        return addSlot(s);
    }

    public static ArcInventoryMenu server(int id, Inventory inv, List<Acc> acc) {
        return new ArcInventoryMenu(id, inv, acc, true);
    }

    public static ArcInventoryMenu client(int id, Inventory inv, FriendlyByteBuf buf) {
        return new ArcInventoryMenu(id, inv, read(buf), false);
    }

    public static void write(FriendlyByteBuf buf, List<Acc> acc) {
        buf.writeVarInt(acc.size());
        for (Acc a : acc) {
            buf.writeUtf(a.type());
            buf.writeVarInt(a.index());
            buf.writeResourceLocation(a.icon());
        }
    }

    private static List<Acc> read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Acc> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Acc(buf.readUtf(), buf.readVarInt(), buf.readResourceLocation()));
        return out;
    }

    // ------------------------------------------------------------------ крафт
    @Override
    public void slotsChanged(Container container) {
        if (container == craft && owner instanceof ServerPlayer sp) {
            ItemStack out = ItemStack.EMPTY;
            Optional<CraftingRecipe> recipe = sp.server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craft, sp.level());
            if (recipe.isPresent() && result.setRecipeUsed(sp.level(), sp, recipe.get())) {
                ItemStack r = recipe.get().assemble(craft, sp.level().registryAccess());
                if (r.isItemEnabled(sp.level().enabledFeatures())) out = r;
            }
            result.setItem(0, out);
            setRemoteSlot(0, out);
            sp.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), 0, out));
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        result.clearContent();
        if (!player.level().isClientSide) clearContainer(player, craft);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != result && super.canTakeItemForPickAll(stack, slot);
    }

    // ------------------------------------------------------------------ shift-клик
    private boolean anyAccessoryAccepts(ItemStack stack) {
        for (int i = accStart; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (!s.hasItem() && s.mayPlace(stack)) return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return copy;
        ItemStack stack = slot.getItem();
        copy = stack.copy();
        EquipmentSlot eq = Mob.getEquipmentSlotForItem(copy);
        int end = 45;
        if (index == 0) {
            if (!moveItemStackTo(stack, 9, end, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(stack, copy);
        } else if (index >= accStart) {
            if (!moveItemStackTo(stack, 9, end, false)) return ItemStack.EMPTY;
        } else if (index >= 1 && index < 9) {
            if (!moveItemStackTo(stack, 9, end, false)) return ItemStack.EMPTY;
        } else if (eq.getType() == EquipmentSlot.Type.ARMOR && !slots.get(8 - eq.getIndex()).hasItem()) {
            int i = 8 - eq.getIndex();
            if (!moveItemStackTo(stack, i, i + 1, false)) return ItemStack.EMPTY;
        } else if (anyAccessoryAccepts(stack) && moveItemStackTo(stack, accStart, slots.size(), false)) {
            // надето в слот аксессуара
        } else if (eq == EquipmentSlot.OFFHAND && !slots.get(45).hasItem()) {
            if (!moveItemStackTo(stack, 45, 46, false)) return ItemStack.EMPTY;
        } else if (index >= 9 && index < 36) {
            if (!moveItemStackTo(stack, 36, 45, false)) return ItemStack.EMPTY;
        } else if (index >= 36 && index < 45) {
            if (!moveItemStackTo(stack, 9, 36, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 9, end, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        if (index == 0) player.drop(stack, false);
        return copy;
    }
}
