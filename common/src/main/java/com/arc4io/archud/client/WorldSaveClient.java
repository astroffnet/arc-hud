package com.arc4io.archud.client;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.server.WorldSaver;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Клиентская часть сохранения мира: кнопка в меню паузы, клавиша (F7), сообщения и сторож зависаний.
 * Сторож — отдельный поток: если клиент не обновлялся больше 10 секунд (игра «встала»), а встроенный сервер
 * ещё работает, он сам запрашивает сохранение — сервер выполнит его в начале ближайшего тика.
 */
public final class WorldSaveClient {
    private static volatile long heartbeat = System.currentTimeMillis();
    private static volatile boolean freezeSaveRequested;
    private static int seenSaves;
    private static Thread watchdog;

    private WorldSaveClient() {}

    /** Можно ли сохранить: свой мир или оператор с правом /save-all на сервере. */
    public static boolean canSave(Minecraft mc) {
        if (mc.hasSingleplayerServer()) return true;
        return mc.player != null && mc.player.hasPermissions(4) && mc.getConnection() != null;
    }

    public static void saveNow(Minecraft mc) {
        if (mc.hasSingleplayerServer()) {
            WorldSaver.request(true);
        } else if (canSave(mc)) {
            mc.player.connection.sendCommand("save-all flush");
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("archud.save.sent").withStyle(ChatFormatting.AQUA), true);
        }
    }

    /**
     * «Мир сохранён (0.4 с)» без подстановки %s в перевод: число добавляется отдельным текстом, поэтому
     * подпись не ломается, даже если перевод (ресурспак) не содержит места для числа или содержит лишнее.
     */
    public static net.minecraft.network.chat.MutableComponent savedLabel(String key, long ms) {
        String num = ms < 1000 ? String.valueOf(ms) : String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0);
        return Component.translatable(key).append(" (" + num + " ")
                .append(Component.translatable(ms < 1000 ? "archud.save.unit_ms" : "archud.save.unit_s")).append(")");
    }

    /** Подпись кнопки в меню паузы. */
    public static Component buttonLabel(Minecraft mc) {
        if (!mc.hasSingleplayerServer()) return Component.translatable("archud.save.button");
        switch (WorldSaver.state()) {
            case WAITING -> {
                long w = WorldSaver.waitingSeconds();
                return w >= 2 ? Component.translatable("archud.save.waiting", w) : Component.translatable("archud.save.saving");
            }
            case SAVING -> {
                return Component.translatable("archud.save.saving");
            }
            case DONE -> {
                long s = WorldSaver.secondsSinceSave();
                // «Мир сохранён (N с)» — раньше перевод получал пустой аргумент и показывал «%s»
                if (s >= 0 && s < 4 && WorldSaver.lastManual()) {
                    return savedLabel("archud.save.saved", WorldSaver.lastDurationMs());
                }
            }
            case FAILED -> {
                return Component.translatable("archud.save.failed");
            }
            default -> {
            }
        }
        return Component.translatable("archud.save.button");
    }

    /** Подсказка: когда было последнее сохранение и когда будет автосохранение. */
    public static Component tooltip(Minecraft mc) {
        if (!mc.hasSingleplayerServer()) {
            return Component.translatable(canSave(mc) ? "archud.save.tooltip.server" : "archud.save.tooltip.noperm");
        }
        long last = WorldSaver.secondsSinceSave(), next = WorldSaver.secondsToAutosave();
        Component lastC = last < 0 ? Component.translatable("archud.save.never") : Component.literal(time(last));
        Component nextC = next < 0 ? Component.translatable("options.off") : Component.literal(time(next));
        Component t = Component.translatable("archud.save.tooltip", lastC, nextC);
        long stall = WorldSaver.serverStallSeconds();
        if (stall >= 5 && !mc.isPaused()) t = t.copy().append("\n").append(Component.translatable("archud.save.stall", stall));
        return t;
    }

    private static String time(long sec) {
        return sec / 60 + ":" + String.format("%02d", sec % 60);
    }

    /** Каждый тик клиента. */
    public static void tick(Minecraft mc) {
        heartbeat = System.currentTimeMillis();
        freezeSaveRequested = false;
        startWatchdog();
        while (ArcKeys.SAVE_WORLD.consumeClick()) {
            if (canSave(mc)) saveNow(mc);
        }
        int n = WorldSaver.saveCount();
        if (n != seenSaves) {
            seenSaves = n;
            if (mc.player != null && mc.hasSingleplayerServer() && (WorldSaver.lastManual() || ArcConfig.get().autosaveNotify)) {
                Component msg = WorldSaver.state() == WorldSaver.State.FAILED
                        ? Component.translatable("archud.save.failed").withStyle(ChatFormatting.RED)
                        : savedLabel(WorldSaver.lastManual() ? "archud.save.saved" : "archud.save.auto", WorldSaver.lastDurationMs())
                        .withStyle(ChatFormatting.GREEN);
                mc.player.displayClientMessage(msg, true);
            }
        }
    }

    private static synchronized void startWatchdog() {
        if (watchdog != null) return;
        watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null || !mc.hasSingleplayerServer()) continue;
                    long frozen = System.currentTimeMillis() - heartbeat;
                    long since = WorldSaver.secondsSinceSave();
                    if (frozen > 10_000 && !freezeSaveRequested && (since < 0 || since > 30)) {
                        freezeSaveRequested = true;
                        ArcHud.LOGGER.warn("Arc HUD: client frozen for {} s — requesting world save", frozen / 1000);
                        WorldSaver.request(true);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "Arc HUD save watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
