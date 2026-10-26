package com.arc4io.archud.forge.inventory;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.network.ArcNet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/** Регистрация меню нового инвентаря и открытие его по запросу клиента (Forge). */
public final class ForgeInventory {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ArcHud.MODID);
    public static final RegistryObject<MenuType<ArcInventoryMenu>> TYPE = MENUS.register("inventory",
            () -> IForgeMenuType.create(ArcInventoryMenu::client));

    private ForgeInventory() {}

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
        ArcNet.onServer("open_inv", (player, buf) -> open(player));
    }

    public static boolean curios() {
        return ModList.get().isLoaded("curios");
    }

    /** Открыть новый инвентарь с аксессуарами (на сервере). */
    public static void open(ServerPlayer player) {
        if (!curios()) return;
        if (player.containerMenu != player.inventoryMenu) {
            // у клиента экрана нет (он прислал запрос из игры) — значит, на сервере висит забытое меню
            ArcHud.LOGGER.info("Arc HUD: closing stale menu {} before opening inventory", player.containerMenu.getClass().getName());
            player.closeContainer();
        }
        List<ArcInventoryMenu.Acc> acc;
        try {
            acc = CuriosSlots.list(player);
        } catch (Throwable t) {
            ArcHud.LOGGER.warn("Arc HUD: cannot read Curios slots", t);
            return;
        }
        ArcHud.LOGGER.debug("Arc HUD: opening inventory with {} accessory slots", acc.size());
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inv, p) -> ArcInventoryMenu.server(id, inv, acc),
                Component.translatable("archud.inv.title")), buf -> ArcInventoryMenu.write(buf, acc));
    }
}
