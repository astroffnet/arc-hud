package com.arc4io.archud.util;

/**
 * Поднятые лимиты пакетов (как PacketFixer): большие NBT предметов, книги, моды с тяжёлыми пакетами
 * больше не кикают с «Packet too big» / «tried to allocate … max allowed 2097152».
 * Лимиты большие, но конечные — чтобы сервер не мог положить клиент гигантским пакетом.
 */
public final class PacketLimits {
    public static final int MAX_FRAME = 512 * 1024 * 1024;   // пакет после распаковки
    public static final long MAX_NBT = 256L * 1024 * 1024;   // NBT в пакете
    public static final int MAX_PAYLOAD = 256 * 1024 * 1024; // пакет мода (custom payload)

    /** Поднятые лимиты только на клиенте (и во встроенном сервере в том же процессе); выделенный сервер — ванильные. */
    public static volatile boolean client;

    private PacketLimits() {}

    public static int frame(int original) {
        return client ? Math.max(original, MAX_FRAME) : original;
    }

    public static long nbt(long original) {
        return client ? Math.max(original, MAX_NBT) : original;
    }

    public static int payload(int original) {
        return client ? Math.max(original, MAX_PAYLOAD) : original;
    }

    public static int varintBytes(int original) {
        return client ? 5 : original;
    }
}
