package com.arc4io.archud.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/** Общая карта: чанки 16×16 цветов от игроков с Arc HUD, хранение в plugins/ArcHUD/map и рассылка всем. */
final class MapStore {
    private record Chunk(String dim, long key, int[] data) {}

    private final ArcHudPlugin plugin;
    private final Map<String, Map<Long, int[]>> map = new HashMap<>();
    private final List<Chunk> relay = new ArrayList<>();
    private final List<UUID> relaySource = new ArrayList<>();
    private final Map<UUID, Deque<Object[]>> upload = new HashMap<>();
    private boolean dirty;

    MapStore(ArcHudPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private Path dir() {
        return plugin.getDataFolder().toPath().resolve("map");
    }

    void onChunks(Player p, Buf buf) {
        if (!plugin.getConfig().getBoolean("map-sync", true)) return;
        List<Chunk> list = read(buf, 64);
        String dim = ArcHudPlugin.dimId(p.getWorld());
        int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
        for (Chunk c : list) {
            if (!c.dim().equals(dim)) continue;
            int cx = (int) (c.key() >> 32), cz = (int) c.key();
            if (Math.abs(cx - pcx) > 40 || Math.abs(cz - pcz) > 40) continue;
            int[] old = map.computeIfAbsent(dim, k -> new HashMap<>()).put(c.key(), c.data());
            if (old != null && Arrays.equals(old, c.data())) continue;
            dirty = true;
            relay.add(c);
            relaySource.add(p.getUniqueId());
        }
    }

    void queueUpload(Player p) {
        Deque<Object[]> q = new ArrayDeque<>();
        String own = ArcHudPlugin.dimId(p.getWorld());
        List<String> dims = new ArrayList<>(map.keySet());
        dims.sort((a, b) -> a.equals(own) ? -1 : b.equals(own) ? 1 : a.compareTo(b));
        for (String d : dims) for (Long k : map.get(d).keySet()) q.add(new Object[]{d, k});
        if (!q.isEmpty()) upload.put(p.getUniqueId(), q);
    }

    void forget(UUID id) {
        upload.remove(id);
    }

    void tick() {
        if (!relay.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!plugin.arcPlayers.contains(p.getUniqueId())) continue;
                List<Chunk> out = new ArrayList<>();
                for (int i = 0; i < relay.size(); i++) if (!relaySource.get(i).equals(p.getUniqueId())) out.add(relay.get(i));
                sendChunks(p, out);
            }
            relay.clear();
            relaySource.clear();
        }
        if (!upload.isEmpty()) {
            upload.entrySet().removeIf(e -> {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) return true;
                List<Chunk> out = new ArrayList<>();
                while (out.size() < 32 && !e.getValue().isEmpty()) {
                    Object[] k = e.getValue().poll();
                    int[] data = map.getOrDefault((String) k[0], Map.of()).get((Long) k[1]);
                    if (data != null) out.add(new Chunk((String) k[0], (Long) k[1], data));
                }
                sendChunks(p, out);
                return e.getValue().isEmpty();
            });
        }
    }

    private void sendChunks(Player p, List<Chunk> chunks) {
        for (int i = 0; i < chunks.size(); i += 32) {
            Buf buf = ArcHudPlugin.packet("map_chunks");
            write(buf, chunks.subList(i, Math.min(chunks.size(), i + 32)));
            plugin.send(p, buf);
        }
    }

    // ------------------------------------------------------------------ формат (как MapCodec мода)
    private static void write(Buf buf, List<Chunk> chunks) {
        buf.varInt(chunks.size());
        ByteBuffer raw = ByteBuffer.allocate(chunks.size() * 1024);
        for (Chunk c : chunks) {
            buf.utf(c.dim()).i64(c.key());
            for (int v : c.data()) raw.putInt(v);
        }
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw.array());
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!d.finished()) out.write(tmp, 0, d.deflate(tmp));
        d.end();
        buf.bytes(out.toByteArray());
    }

    private static List<Chunk> read(Buf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("too many chunks");
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
        } catch (java.util.zip.DataFormatException e) {
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

    // ------------------------------------------------------------------ хранение
    private void load() {
        Path d = dir();
        if (!Files.isDirectory(d)) return;
        try (var files = Files.list(d)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".bin")) continue;
                String dim = fn.substring(0, fn.length() - 4).replace('~', ':');
                Map<Long, int[]> m = new HashMap<>();
                try (InputStream in = new GZIPInputStream(Files.newInputStream(f)); DataInputStream data = new DataInputStream(in)) {
                    int n = data.readInt();
                    for (int i = 0; i < n; i++) {
                        long key = data.readLong();
                        int[] px = new int[256];
                        for (int j = 0; j < 256; j++) px[j] = data.readInt();
                        m.put(key, px);
                    }
                }
                map.put(dim, m);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Cannot load shared map: " + e);
        }
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        Path d = dir();
        try {
            Files.createDirectories(d);
            for (Map.Entry<String, Map<Long, int[]>> e : map.entrySet()) {
                Path f = d.resolve(e.getKey().replace(':', '~') + ".bin");
                Path tmp = d.resolve(f.getFileName() + ".tmp");
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp)); DataOutputStream data = new DataOutputStream(out)) {
                    data.writeInt(e.getValue().size());
                    for (Map.Entry<Long, int[]> c : e.getValue().entrySet()) {
                        data.writeLong(c.getKey());
                        for (int v : c.getValue()) data.writeInt(v);
                    }
                }
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Cannot save shared map: " + ex);
        }
    }
}
