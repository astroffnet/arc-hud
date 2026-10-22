package com.arc4io.archud.network;

import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Упаковка чанков карты (16×16 цветов ABGR) для передачи: заголовки + сжатые пиксели.
 */
public final class MapCodec {
    public record Chunk(String dim, long key, int[] data) {}

    private MapCodec() {}

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static void write(FriendlyByteBuf buf, List<Chunk> chunks) {
        buf.writeVarInt(chunks.size());
        ByteBuffer raw = ByteBuffer.allocate(chunks.size() * 1024);
        for (Chunk c : chunks) {
            buf.writeUtf(c.dim(), 128);
            buf.writeLong(c.key());
            for (int v : c.data()) raw.putInt(v);
        }
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw.array());
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!d.finished()) out.write(tmp, 0, d.deflate(tmp));
        d.end();
        buf.writeByteArray(out.toByteArray());
    }

    public static List<Chunk> read(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("too many chunks: " + n);
        String[] dims = new String[n];
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            dims[i] = buf.readUtf(128);
            keys[i] = buf.readLong();
        }
        byte[] packed = buf.readByteArray(1 << 20);
        byte[] raw = new byte[n * 1024];
        Inflater inf = new Inflater();
        inf.setInput(packed);
        try {
            int off = 0;
            while (off < raw.length && !inf.finished()) {
                int r = inf.inflate(raw, off, raw.length - off);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                off += r;
            }
            if (off != raw.length) throw new IllegalArgumentException("short map data");
        } catch (DataFormatException e) {
            throw new IllegalArgumentException(e);
        } finally {
            inf.end();
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        List<Chunk> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] px = new int[256];
            for (int j = 0; j < 256; j++) px[j] = bb.getInt();
            out.add(new Chunk(dims[i], keys[i], px));
        }
        return out;
    }
}
