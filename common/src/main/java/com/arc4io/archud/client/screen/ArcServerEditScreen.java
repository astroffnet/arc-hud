package com.arc4io.archud.client.screen;

import com.arc4io.archud.client.hud.Draw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Добавление / изменение сервера и прямое подключение в стиле Arc.
 */
public class ArcServerEditScreen extends ArcScreen {
    private final ArcMultiplayerScreen list;
    private final ServerData editing;
    private final boolean direct;
    private EditBox name, address;
    private ServerData.ServerPackStatus pack = ServerData.ServerPackStatus.PROMPT;
    private ArcButton done, packButton;

    public ArcServerEditScreen(ArcMultiplayerScreen list, ServerData editing, boolean direct) {
        super(Component.translatable(direct ? "selectServer.direct" : editing == null ? "selectServer.add" : "selectServer.edit"), list);
        this.list = list;
        this.editing = editing;
        this.direct = direct;
        if (editing != null) pack = editing.getResourcePackStatus();
    }

    @Override
    protected void init() {
        layout(300, direct ? 120 : 170);
        int x = px + 10, w = pw - 20, y = py + 30;
        String oldName = name == null ? null : name.getValue(), oldAddr = address == null ? null : address.getValue();
        if (!direct) {
            name = addRenderableWidget(new EditBox(font, x, y + 10, w, 18, Component.translatable("addServer.enterName")));
            name.setMaxLength(64);
            name.setValue(oldName != null ? oldName : editing != null ? editing.name : I18n.get("selectServer.defaultName"));
            y += 36;
        }
        address = addRenderableWidget(new EditBox(font, x, y + 10, w, 18, Component.translatable("addServer.enterIp")));
        address.setMaxLength(128);
        address.setValue(oldAddr != null ? oldAddr : editing != null ? editing.ip : direct ? minecraft.options.lastMpIp : "");
        address.setResponder(s -> updateDone());
        y += 36;
        if (!direct) {
            packButton = addRenderableWidget(new ArcButton(x, y, w, 20, packLabel(), b -> {
                pack = ServerData.ServerPackStatus.values()[(pack.ordinal() + 1) % ServerData.ServerPackStatus.values().length];
                b.setMessage(packLabel());
            }));
        }
        done = addRenderableWidget(new ArcButton(px + pw - 108, py + ph - 24, 100, 18,
                Component.translatable(direct ? "selectServer.select" : "addServer.add"), b -> submit()));
        addBack();
        setInitialFocus(direct ? address : name);
        updateDone();
    }

    private Component packLabel() {
        return Component.translatable("addServer.resourcePack").append(": ").append(pack.getName());
    }

    private void updateDone() {
        if (done != null) done.active = ServerAddress.isValidAddress(address.getValue().trim());
    }

    private void submit() {
        String ip = address.getValue().trim();
        if (!ServerAddress.isValidAddress(ip)) return;
        if (direct) {
            minecraft.options.lastMpIp = ip;
            minecraft.options.save();
            list.connect(new ServerData(I18n.get("selectServer.defaultName"), ip, false));
            return;
        }
        if (editing != null) {
            editing.name = name.getValue();
            editing.ip = ip;
            editing.setResourcePackStatus(pack);
            list.saveServer(editing, false);
        } else {
            ServerData d = new ServerData(name.getValue(), ip, false);
            d.setResourcePackStatus(pack);
            list.saveServer(d, true);
        }
        minecraft.setScreen(list);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && done.active) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        frame(g);
        int x = px + 10, y = py + 30;
        if (!direct) {
            Draw.small(g, font, Component.translatable("addServer.enterName").getString(), x, y, Draw.TEXT_DIM, 0.75F);
            y += 36;
        }
        Draw.small(g, font, Component.translatable("addServer.enterIp").getString(), x, y, Draw.TEXT_DIM, 0.75F);
        if (direct && !minecraft.options.lastMpIp.isEmpty()) {
            Draw.small(g, font, Component.translatable("archud.mp.last_direct", minecraft.options.lastMpIp).getString(), x, y + 32, 0xFF6F7C8B, 0.65F);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
