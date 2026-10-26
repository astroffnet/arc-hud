package com.arc4io.archud.client.extras;

import com.arc4io.archud.client.hud.Draw;
import com.arc4io.archud.client.hud.Theme;
import com.arc4io.archud.client.screen.ArcButton;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.ContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Сундуки, бочки, шалкеры, воронки, раздатчики: поиск по содержимому (совпадения подсвечиваются,
 * остальное затемняется) и кнопки «Забрать всё» / «Сложить всё» (быстрым переносом — работает на любом сервере).
 */
public final class ContainerExtras {
    private static EditBox box;
    private static Screen owner;

    private ContainerExtras() {}

    private static boolean supported(Screen s) {
        if (!(s instanceof AbstractContainerScreen<?> cs)) return false;
        AbstractContainerMenu m = cs.getMenu();
        return m instanceof ChestMenu || m instanceof ShulkerBoxMenu || m instanceof HopperMenu || m instanceof DispenserMenu;
    }

    public static void onInit(Screen screen, Consumer<AbstractWidget> add) {
        box = null;
        owner = null;
        ArcConfig cfg = ArcConfig.get();
        if (!supported(screen)) return;
        AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) screen;
        ContainerScreenAccessor acc = (ContainerScreenAccessor) cs;
        int left = acc.archud$left(), top = acc.archud$top(), w = acc.archud$width();
        owner = screen;
        if (cfg.containerSearch) {
            box = new EditBox(Minecraft.getInstance().font, left + 1, top - 15, 96, 12, Component.translatable("archud.chest.search"));
            box.setHint(Component.translatable("archud.chest.search").withStyle(s -> s.withColor(0x6A7380)));
            box.setMaxLength(40);
            add.accept(box);
        }
        if (cfg.containerButtons) {
            ArcButton take = new ArcButton(left + w - 40, top - 16, 18, 14, Component.literal("▼"), b -> moveAll(cs, true));
            take.setTooltip(Tooltip.create(Component.translatable("archud.chest.take_all")));
            ArcButton put = new ArcButton(left + w - 20, top - 16, 18, 14, Component.literal("▲"), b -> moveAll(cs, false));
            put.setTooltip(Tooltip.create(Component.translatable("archud.chest.put_all")));
            add.accept(take);
            add.accept(put);
        }
    }

    private static void moveAll(AbstractContainerScreen<?> cs, boolean take) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        AbstractContainerMenu m = cs.getMenu();
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < m.slots.size(); i++) {
            Slot s = m.slots.get(i);
            if (!s.hasItem()) continue;
            boolean playerSlot = s.container == inv;
            if (take && !playerSlot) mc.gameMode.handleInventoryMouseClick(m.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
            // «Сложить всё» — из основного инвентаря, хотбар не трогаем
            if (!take && playerSlot && s.getContainerSlot() >= 9 && s.getContainerSlot() < 36) {
                mc.gameMode.handleInventoryMouseClick(m.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
            }
        }
    }

    public static void onRender(Screen screen, GuiGraphics g) {
        if (screen != owner || box == null) return;
        String q = box.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) screen;
        ContainerScreenAccessor acc = (ContainerScreenAccessor) cs;
        int left = acc.archud$left(), top = acc.archud$top();
        int accent = Theme.accent();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        for (Slot s : cs.getMenu().slots) {
            int x = left + s.x, y = top + s.y;
            if (s.hasItem() && matches(s.getItem(), q)) Draw.outline(g, x - 1, y - 1, 18, 18, accent);
            else g.fill(x, y, x + 16, y + 16, 0xB0101419);
        }
        g.pose().popPose();
    }

    private static boolean matches(ItemStack s, String q) {
        if (s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains(q);
    }

    /** true — клавишу съел поиск. */
    public static boolean consumeKey(int key, int scan, int mods) {
        if (box == null || !box.isFocused() || Minecraft.getInstance().screen != owner) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            box.setFocused(false);
            return true;
        }
        box.keyPressed(key, scan, mods);
        return true;
    }
}
