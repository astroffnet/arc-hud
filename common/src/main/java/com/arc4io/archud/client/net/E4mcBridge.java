package com.arc4io.archud.client.net;

import com.arc4io.archud.ArcHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * e4mc (встроен в мод): при «Открыть для сети» мир становится доступен из интернета по адресу вида
 * xxx.e4mc.link. Адрес ловится из сообщения e4mc в чате, сразу копируется в буфер обмена
 * и показывается в панели хоста.
 */
public final class E4mcBridge {
    private static final Pattern DOMAIN = Pattern.compile("([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.e4mc\\.link)", Pattern.CASE_INSENSITIVE);
    private static String domain;

    private E4mcBridge() {}

    public static boolean present() {
        return ArcHud.platform().isModLoaded("e4mc_minecraft") || ArcHud.platform().isModLoaded("e4mc");
    }

    public static String domain() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null || !mc.getSingleplayerServer().isPublished()) domain = null;
        return domain;
    }

    public static void onChat(String text) {
        Matcher m = DOMAIN.matcher(text);
        if (!m.find()) return;
        String d = m.group(1).toLowerCase(java.util.Locale.ROOT);
        if (d.equals(domain)) return;
        domain = d;
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.setClipboard(d);
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.translatable("archud.e4mc.title"), Component.translatable("archud.e4mc.copied", d));
    }
}
