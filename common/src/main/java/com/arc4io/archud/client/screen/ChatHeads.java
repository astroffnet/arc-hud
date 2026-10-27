package com.arc4io.archud.client.screen;

import com.arc4io.archud.config.ArcConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Головы игроков у сообщений чата.
 * Отправитель ищется так (работает и на пиратских / offline-mode серверах):
 * <ol>
 *   <li>подсказка «сущность» у ника (её ставит ванилла и большинство плагинов чата) — даёт UUID;</li>
 *   <li>иначе — текст: «&lt;Ник&gt;», «[Префикс] Ник: …», «Ник » …», «Ник → …» и сверка со списком игроков (TAB);</li>
 * </ol>
 * Скин берётся из списка игроков (там же скины SkinsRestorer на пиратских серверах), иначе — стандартный
 * Стив/Алекс по offline-UUID ника.
 */
public final class ChatHeads {
    /** Отступ текста под голову (в пикселях чата). */
    public static final int GUTTER = 11;
    private static final Pattern PLAIN = Pattern.compile(
            "^\\s*(?:\\[\\d{1,2}:\\d{2}(?::\\d{2})?]\\s*)?(?:»\\s*)?(?:[\\[(][^\\])]{0,24}[\\])]\\s*){0,3}<?([A-Za-z0-9_]{3,16})>?\\s*(?:[:»>→|›]|-»|->)");
    private static final Pattern ANGLE = Pattern.compile("^\\s*(?:\\[\\d{1,2}:\\d{2}(?::\\d{2})?]\\s*)?(?:»\\s*)?(?:\\[[^\\]]{0,24}]\\s*){0,3}<([A-Za-z0-9_]{3,16})>");
    private static final Sender NONE = new Sender(null, null);
    private static final Map<FormattedCharSequence, Sender> CACHE = new WeakHashMap<>();

    public record Sender(UUID id, String name) {
        boolean present() {
            return id != null || name != null;
        }
    }

    private ChatHeads() {}

    public static boolean enabled() {
        return ArcConfig.get().chatHeads && ArcConfig.get().chatMessagesStyle;
    }

    public static int gutter() {
        return enabled() ? GUTTER : 0;
    }

    /** Отправитель первой строки сообщения (null — системное сообщение). */
    public static Sender sender(FormattedCharSequence line) {
        Sender s = CACHE.get(line);
        if (s == null) {
            s = detect(line);
            CACHE.put(line, s);
        }
        return s.present() ? s : null;
    }

    private static Sender detect(FormattedCharSequence line) {
        StringBuilder sb = new StringBuilder();
        UUID[] id = {null};
        String[] name = {null};
        String[] insertion = {null};
        line.accept((index, style, cp) -> {
            if (sb.length() < 80) sb.appendCodePoint(cp);
            if (id[0] == null && sb.length() < 64) {
                HoverEvent hover = style.getHoverEvent();
                if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_ENTITY) {
                    HoverEvent.EntityTooltipInfo info = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
                    if (info != null && info.type == EntityType.PLAYER) {
                        id[0] = info.id;
                        if (info.name != null) name[0] = info.name.getString();
                    }
                }
                if (insertion[0] == null && style.getInsertion() != null && style.getInsertion().matches("[A-Za-z0-9_]{3,16}")) {
                    insertion[0] = style.getInsertion();
                }
            }
            return true;
        });
        if (id[0] != null) return new Sender(id[0], name[0]);
        String text = sb.toString();
        Matcher m = ANGLE.matcher(text);
        if (m.find()) return new Sender(null, m.group(1));
        if (insertion[0] != null && known(insertion[0])) return new Sender(null, insertion[0]);
        m = PLAIN.matcher(text);
        if (m.find() && known(m.group(1))) return new Sender(null, m.group(1));
        return NONE;
    }

    /** Ник есть в списке игроков сервера (или это мы сами). */
    private static boolean known(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getGameProfile().getName().equalsIgnoreCase(name)) return true;
        ClientPacketListener c = mc.getConnection();
        if (c == null) return false;
        for (PlayerInfo pi : c.getOnlinePlayers()) {
            if (pi.getProfile().getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static final Map<Sender, Object[]> SKINS = new java.util.HashMap<>();

    /** Скин отправителя, кэш на 2 секунды (поиск по списку игроков не каждый кадр). */
    public static ResourceLocation skin(Sender s) {
        long now = net.minecraft.Util.getMillis();
        Object[] c = SKINS.get(s);
        if (c != null && now - (Long) c[1] < 2000L) return (ResourceLocation) c[0];
        if (SKINS.size() > 256) SKINS.clear();
        ResourceLocation r = lookupSkin(s);
        SKINS.put(s, new Object[]{r, now});
        return r;
    }

    private static ResourceLocation lookupSkin(Sender s) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener c = mc.getConnection();
        PlayerInfo pi = null;
        if (c != null) {
            if (s.id() != null) pi = c.getPlayerInfo(s.id());
            if (pi == null && s.name() != null) {
                for (PlayerInfo p : c.getOnlinePlayers()) {
                    if (p.getProfile().getName().equalsIgnoreCase(s.name())) {
                        pi = p;
                        break;
                    }
                }
            }
        }
        if (pi != null) return pi.getSkinLocation();
        UUID u = s.id() != null ? s.id() : UUIDUtil.createOfflinePlayerUUID(s.name());
        return DefaultPlayerSkin.getDefaultSkin(u);
    }

    /** Голова 8×8 со слоем шапки. */
    public static void draw(GuiGraphics g, Sender s, int x, int y, int alpha) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1F, 1F, 1F, alpha / 255F);
        PlayerFaceRenderer.draw(g, skin(s), x, y, 8);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
    }

    /** Цвет ника: стабильный по нику, приятной яркости. */
    public static int nameColor(String name) {
        int h = name.toLowerCase(Locale.ROOT).hashCode();
        float hue = ((h & 0xFFFF) / 65535F);
        return net.minecraft.util.Mth.hsvToRgb(hue, 0.45F, 1F) & 0xFFFFFF;
    }

    /**
     * Ванильное «&lt;Ник&gt; текст» → «Ник › текст» (ник своим цветом, с прежними подсказкой и вставкой).
     * Остальные форматы серверов не трогаем.
     */
    public static Component restyle(Component msg) {
        if (!enabled()) return msg;
        ComponentContents contents = msg.getContents();
        if (!(contents instanceof TranslatableContents tr) || !"chat.type.text".equals(tr.getKey())) return msg;
        Object[] args = tr.getArgs();
        if (args.length != 2 || !(args[0] instanceof Component nameC)) return msg;
        Style ns = nameC.getStyle();
        if (ns.getColor() == null) ns = ns.withColor(nameColor(nameC.getString()));
        MutableComponent out = Component.empty().withStyle(msg.getStyle());
        out.append(nameC.copy().withStyle(ns.withBold(true)));
        out.append(Component.literal(" › ").withStyle(ChatFormatting.DARK_GRAY));
        if (args[1] instanceof Component body) out.append(body);
        else out.append(String.valueOf(args[1]));
        return out;
    }
}
