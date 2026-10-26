package com.arc4io.archud.bukkit;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Минимальная копия FriendlyByteBuf Minecraft (varint, строки UTF-8, UUID, BlockPos) — формат пакетов Arc HUD. */
final class Buf {
    private final ByteBuffer in;
    private final ByteArrayOutputStream out;

    private Buf(ByteBuffer in, ByteArrayOutputStream out) {
        this.in = in;
        this.out = out;
    }

    static Buf read(byte[] data, int offset) {
        ByteBuffer b = ByteBuffer.wrap(data);
        b.position(offset);
        return new Buf(b, null);
    }

    static Buf write() {
        return new Buf(null, new ByteArrayOutputStream());
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    boolean readable() {
        return in.hasRemaining();
    }

    // ---------------------------------------------------------------- чтение
    int readVarInt() {
        int value = 0, pos = 0;
        while (true) {
            byte b = in.get();
            value |= (b & 0x7F) << pos;
            if ((b & 0x80) == 0) return value;
            pos += 7;
            if (pos >= 35) throw new IllegalArgumentException("VarInt too big");
        }
    }

    String readUtf(int maxChars) {
        int len = readVarInt();
        if (len < 0 || len > maxChars * 3) throw new IllegalArgumentException("string too long");
        byte[] b = new byte[len];
        in.get(b);
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.length() > maxChars) throw new IllegalArgumentException("string too long");
        return s;
    }

    byte[] readByteArray(int max) {
        int len = readVarInt();
        if (len < 0 || len > max) throw new IllegalArgumentException("array too long");
        byte[] b = new byte[len];
        in.get(b);
        return b;
    }

    boolean readBoolean() {
        return in.get() != 0;
    }

    int readInt() {
        return in.getInt();
    }

    long readLong() {
        return in.getLong();
    }

    // ---------------------------------------------------------------- запись
    Buf varInt(int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        return this;
    }

    Buf utf(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varInt(b.length);
        out.writeBytes(b);
        return this;
    }

    Buf bytes(byte[] b) {
        varInt(b.length);
        out.writeBytes(b);
        return this;
    }

    Buf bool(boolean v) {
        out.write(v ? 1 : 0);
        return this;
    }

    Buf i32(int v) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(v).array());
        return this;
    }

    Buf i64(long v) {
        out.writeBytes(ByteBuffer.allocate(8).putLong(v).array());
        return this;
    }

    Buf f32(float v) {
        out.writeBytes(ByteBuffer.allocate(4).putFloat(v).array());
        return this;
    }

    Buf f64(double v) {
        out.writeBytes(ByteBuffer.allocate(8).putDouble(v).array());
        return this;
    }

    Buf uuid(UUID id) {
        return i64(id.getMostSignificantBits()).i64(id.getLeastSignificantBits());
    }

    /** BlockPos.asLong (как в Minecraft 1.20). */
    Buf blockPos(int x, int y, int z) {
        long v = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        return i64(v);
    }
}
