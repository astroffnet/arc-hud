package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.server.MinecraftServer;

/**
 * Сохранение мира (одиночная игра и «Открыть для сети»): кнопка в меню паузы, клавиша, автосохранение по реальному
 * времени (по умолчанию каждые 3 минуты — при низком TPS ванильное «каждые 6000 тиков» растягивается на десятки минут).
 * <p>
 * Почему сохраняется даже при сильных лагах: запрос не ставится в очередь задач сервера (при отставании сервер
 * откладывает задачи на несколько тиков), а выполняется прямо в начале ближайшего тика на потоке сервера —
 * то есть сразу, как только закончится текущий тик. Сторож на клиенте сам запрашивает сохранение, если клиент
 * завис (кадры не рисуются больше 10 секунд), а сервер ещё жив.
 */
public final class WorldSaver {
    public enum State {IDLE, WAITING, SAVING, DONE, FAILED}

    private static volatile boolean requested, requestedFlush, requestedManual;
    private static volatile State state = State.IDLE;
    private static volatile long requestedAt, lastSaveAt, lastDurationMs, lastTickAt, worldStartAt;
    private static volatile boolean lastManual;
    /** Счётчик завершённых сохранений — клиент по нему показывает «Мир сохранён». */
    private static volatile int saveCount;
    private static volatile MinecraftServer server;

    private WorldSaver() {}

    /** Запросить сохранение (с любого потока). manual — по кнопке/клавише: с записью на диск до конца (flush). */
    public static void request(boolean manual) {
        if (manual) {
            requestedFlush = true;
            requestedManual = true;
        }
        if (!requested) requestedAt = System.currentTimeMillis();
        requested = true;
        state = State.WAITING;
    }

    /** Из начала тика сервера (поток сервера). Вызывается и в паузе одиночной игры. */
    public static void onTick(MinecraftServer s) {
        long now = System.currentTimeMillis();
        if (server != s) {
            server = s;
            worldStartAt = now;
            lastSaveAt = 0;
            requested = false;
            state = State.IDLE;
        }
        lastTickAt = now;
        if (!s.isDedicatedServer()) {
            ArcConfig cfg = ArcConfig.get();
            long since = lastSaveAt != 0 ? lastSaveAt : worldStartAt;
            if (cfg.autosave && cfg.autosaveMinutes > 0 && !requested && now - since >= cfg.autosaveMinutes * 60_000L) {
                request(false);
            }
        }
        if (!requested) return;
        boolean flush = requestedFlush, manual = requestedManual;
        requested = false;
        requestedFlush = false;
        requestedManual = false;
        save(s, flush, manual);
    }

    private static void save(MinecraftServer s, boolean flush, boolean manual) {
        state = State.SAVING;
        long t0 = System.nanoTime();
        try {
            boolean ok = s.saveEverything(!manual, flush, false);
            lastDurationMs = (System.nanoTime() - t0) / 1_000_000L;
            lastSaveAt = System.currentTimeMillis();
            lastManual = manual;
            state = ok ? State.DONE : State.FAILED;
            saveCount++;
            ArcHud.LOGGER.info("Arc HUD: world saved ({}, {} ms)", manual ? "manual" : "auto", lastDurationMs);
        } catch (Throwable t) {
            state = State.FAILED;
            saveCount++;
            ArcHud.LOGGER.error("Arc HUD: world save failed", t);
        }
    }

    public static void serverStopped() {
        server = null;
        requested = false;
        state = State.IDLE;
    }

    public static State state() {
        return state;
    }

    public static int saveCount() {
        return saveCount;
    }

    public static boolean lastManual() {
        return lastManual;
    }

    public static long lastDurationMs() {
        return lastDurationMs;
    }

    /** Сколько секунд назад было последнее сохранение (-1 — ещё не было). */
    public static long secondsSinceSave() {
        return lastSaveAt == 0 ? -1 : (System.currentTimeMillis() - lastSaveAt) / 1000L;
    }

    /** Через сколько секунд автосохранение (-1 — выключено). */
    public static long secondsToAutosave() {
        ArcConfig cfg = ArcConfig.get();
        if (!cfg.autosave || cfg.autosaveMinutes <= 0 || server == null) return -1;
        long since = lastSaveAt != 0 ? lastSaveAt : worldStartAt;
        return Math.max(0, (since + cfg.autosaveMinutes * 60_000L - System.currentTimeMillis()) / 1000L);
    }

    /** Сколько секунд ждём ответа сервера по текущему запросу. */
    public static long waitingSeconds() {
        return state == State.WAITING ? (System.currentTimeMillis() - requestedAt) / 1000L : 0;
    }

    /** Сколько секунд сервер не начинал новый тик (завис в длинном тике). */
    public static long serverStallSeconds() {
        return lastTickAt == 0 || server == null ? 0 : (System.currentTimeMillis() - lastTickAt) / 1000L;
    }
}
