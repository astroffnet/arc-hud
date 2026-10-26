package com.arc4io.archud.client.extras;

import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.mixin.ChatComponentAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Чат: время сообщений [ЧЧ:ММ], схлопывание повторов («сообщение (×3)»), подсветка и звук, когда
 * в сообщении упоминают ваш ник.
 */
public final class ChatExtras {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static String lastText = "";
    private static long lastTime;
    private static int count = 1;
    private static Component lastDecorated;

    private ChatExtras() {}

    public static Component process(ChatComponent chat, Component msg) {
        com.arc4io.archud.client.net.E4mcBridge.onChat(msg.getString());
        ArcConfig cfg = ArcConfig.get();
        if (!cfg.chatTimestamps && !cfg.chatCollapse && !cfg.chatMentions) return msg;
        String text = msg.getString();
        long now = Util.getMillis();
        // повтор того же сообщения — убираем прошлое и добавляем счётчик
        if (cfg.chatCollapse && text.equals(lastText) && now - lastTime < 60_000L && lastDecorated != null) {
            List<GuiMessage> all = ((ChatComponentAccessor) chat).archud$getAllMessages();
            if (!all.isEmpty() && all.get(0).content() == lastDecorated) {
                all.remove(0);
                chat.rescaleChat();
                count++;
            } else count = 1;
        } else count = 1;
        lastText = text;
        lastTime = now;

        MutableComponent out = Component.empty();
        if (cfg.chatTimestamps) out.append(Component.literal("[" + LocalTime.now().format(TIME) + "] ").withStyle(ChatFormatting.DARK_GRAY));
        Minecraft mc = Minecraft.getInstance();
        boolean mention = false;
        if (cfg.chatMentions && mc.player != null) {
            String name = mc.player.getGameProfile().getName();
            String lower = text.toLowerCase(Locale.ROOT);
            mention = name.length() >= 3 && lower.contains(name.toLowerCase(Locale.ROOT))
                    && !lower.startsWith("<" + name.toLowerCase(Locale.ROOT) + ">");
        }
        if (mention) out.append(Component.literal("» ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        out.append(msg);
        if (count > 1) out.append(Component.literal(" (×" + count + ")").withStyle(ChatFormatting.GRAY));
        if (mention && count == 1) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4F, 0.6F));
        }
        lastDecorated = out;
        return out;
    }
}
