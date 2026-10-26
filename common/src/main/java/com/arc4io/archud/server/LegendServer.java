package com.arc4io.archud.server;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.arc4io.archud.network.ArcNet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Метки-«легенды» на карте: деревни, аванпосты, храмы, крепости и т. п. (как значки мест в играх с картой).
 * <ul>
 *   <li>Легенда появляется у игрока, только когда постройка оказалась в прогруженных чанках рядом с ним
 *       (никакого поиска по всему миру — не читерно);</li>
 *   <li>при открытии запоминается ~60 «опорных» блоков постройки (не природных); если позже их осталось
 *       меньше трети (деревню разобрали, аванпост снесли), легенда исчезает у всех;</li>
 *   <li>легенды не попадают в список меток игрока, игрок может скрыть любую у себя.</li>
 * </ul>
 * Хранится в папке мира: data/archud_legends.json.
 */
public final class LegendServer {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_SAMPLES = 60;

    /** Легенда на сервере. */
    static final class Legend {
        String id, structure, dim;
        int x, y, z;
        boolean destroyed;
        /** Опорные блоки: позиция (BlockPos.asLong) и id блока. */
        List<long[]> samplePos = new ArrayList<>();
        List<String> sampleBlock = new ArrayList<>();
        Set<String> seenBy = new HashSet<>();
    }

    private static final Map<String, Legend> LEGENDS = new HashMap<>();
    private static MinecraftServer current;
    private static boolean dirty;
    private static int tick;

    private LegendServer() {}

    private static Path file(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).resolve("data").resolve("archud_legends.json");
    }

    private static void load(MinecraftServer s) {
        LEGENDS.clear();
        Path f = file(s);
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            List<Legend> list = GSON.fromJson(r, new TypeToken<List<Legend>>() {}.getType());
            if (list != null) for (Legend l : list) if (l.id != null) LEGENDS.put(l.id, l);
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot read legends", e);
        }
    }

    public static void save(MinecraftServer s) {
        if (!dirty || s == null) return;
        dirty = false;
        Path f = file(s);
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(LEGENDS.values()), w);
            }
        } catch (Exception e) {
            ArcHud.LOGGER.warn("Arc HUD: cannot save legends", e);
        }
    }

    /** Каждый тик сервера (из ArcServer). */
    public static void tick(MinecraftServer server, Set<java.util.UUID> arcPlayers) {
        if (current != server) {
            current = server;
            load(server);
        }
        if (!ArcConfig.get().serverLegends) return;
        tick++;
        if (tick % 40 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (arcPlayers.contains(p.getUUID())) discover(p);
            }
        }
        if (tick % 200 == 100) checkIntegrity(server);
        if (tick % 1200 == 0) save(server);
    }

    private static String nick(ServerPlayer p) {
        return NicknameStorage.key(p.getGameProfile().getName());
    }

    /** Постройки в прогруженных чанках рядом с игроком (до 8 чанков). */
    private static void discover(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        String dim = level.dimension().location().toString();
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        int r = Math.min(8, p.server.getPlayerList().getViewDistance());
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        String nick = nick(p);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(pcx + dx, pcz + dz);
                if (chunk == null) continue;
                Map<Structure, StructureStart> starts = chunk.getAllStarts();
                if (starts.isEmpty()) continue;
                for (Map.Entry<Structure, StructureStart> e : starts.entrySet()) {
                    StructureStart start = e.getValue();
                    if (start == null || !start.isValid()) continue;
                    ResourceLocation key = registry.getKey(e.getKey());
                    if (key == null || !interesting(key)) continue;
                    BoundingBox bb = start.getBoundingBox();
                    String id = dim + "|" + key + "|" + start.getChunkPos().x + "," + start.getChunkPos().z;
                    Legend l = LEGENDS.get(id);
                    if (l == null) {
                        l = new Legend();
                        l.id = id;
                        l.structure = key.toString();
                        l.dim = dim;
                        BlockPos c = bb.getCenter();
                        l.x = c.getX();
                        l.y = c.getY();
                        l.z = c.getZ();
                        sample(level, start, l);
                        LEGENDS.put(id, l);
                        dirty = true;
                    }
                    if (!l.destroyed && l.seenBy.add(nick)) {
                        dirty = true;
                        send(p, l);
                    }
                }
            }
        }
    }

    /** Только «места», а не всё подряд (шахты, клады, окаменелости — нет). */
    private static boolean interesting(ResourceLocation key) {
        String p = key.getPath();
        if (!key.getNamespace().equals("minecraft")) return !p.contains("mineshaft") && !p.contains("fossil") && !p.contains("treasure");
        return p.startsWith("village") || p.equals("pillager_outpost") || p.equals("mansion") || p.equals("monument")
                || p.equals("desert_pyramid") || p.equals("jungle_pyramid") || p.equals("swamp_hut") || p.equals("igloo")
                || p.startsWith("shipwreck") || p.startsWith("ruined_portal") || p.startsWith("ocean_ruin") || p.equals("stronghold")
                || p.equals("ancient_city") || p.equals("trail_ruins") || p.equals("fortress") || p.equals("bastion_remnant")
                || p.equals("end_city");
    }

    private static boolean natural(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.SAND) || s.is(BlockTags.LEAVES) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.ICE) || s.is(Blocks.END_STONE) || s.is(BlockTags.REPLACEABLE)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(BlockTags.TERRACOTTA);
    }

    /** Запомнить опорные блоки постройки (только в прогруженных чанках). */
    private static void sample(ServerLevel level, StructureStart start, Legend l) {
        RandomSource rnd = RandomSource.create(l.id.hashCode());
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.isEmpty()) return;
        int tries = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (l.samplePos.size() < MAX_SAMPLES && tries++ < MAX_SAMPLES * 30) {
            BoundingBox bb = pieces.get(rnd.nextInt(pieces.size())).getBoundingBox();
            pos.set(bb.minX() + rnd.nextInt(bb.getXSpan()), bb.minY() + rnd.nextInt(bb.getYSpan()), bb.minZ() + rnd.nextInt(bb.getZSpan()));
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) continue;
            BlockState s = level.getBlockState(pos);
            if (natural(s)) continue;
            l.samplePos.add(new long[]{pos.asLong()});
            l.sampleBlock.add(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
        }
    }

    /** Разрушена ли постройка: осталось меньше трети опорных блоков (проверяется, когда её чанки прогружены). */
    private static void checkIntegrity(MinecraftServer server) {
        for (Legend l : LEGENDS.values()) {
            if (l.destroyed || l.samplePos.size() < 8) continue;
            ServerLevel level = null;
            for (ServerLevel lv : server.getAllLevels()) if (lv.dimension().location().toString().equals(l.dim)) level = lv;
            if (level == null) continue;
            int checked = 0, left = 0;
            for (int i = 0; i < l.samplePos.size(); i++) {
                BlockPos pos = BlockPos.of(l.samplePos.get(i)[0]);
                if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) continue;
                checked++;
                if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(l.sampleBlock.get(i))) left++;
            }
            if (checked < l.samplePos.size() * 0.7) continue; // постройка прогружена не целиком
            if (left * 3 < checked) {
                l.destroyed = true;
                dirty = true;
                ArcHud.LOGGER.info("Arc HUD: legend {} is gone ({} of {} blocks left)", l.id, left, checked);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (!ArcNet.hasMod(p)) continue;
                    FriendlyByteBuf b = ArcNet.create("legend_remove");
                    b.writeUtf(l.id, 256);
                    ArcNet.sendToPlayer(p, b);
                }
            }
        }
    }

    private static void send(ServerPlayer p, Legend l) {
        FriendlyByteBuf b = ArcNet.create("legend_add");
        b.writeUtf(l.id, 256);
        b.writeUtf(l.structure, 128);
        b.writeUtf(l.dim, 128);
        b.writeInt(l.x);
        b.writeInt(l.y);
        b.writeInt(l.z);
        ArcNet.sendToPlayer(p, b);
    }

    /** При входе: все легенды, которые игрок уже видел. */
    public static void onWelcome(ServerPlayer p) {
        if (!ArcConfig.get().serverLegends) return;
        if (current != p.server) {
            current = p.server;
            load(p.server);
        }
        String nick = nick(p);
        for (Legend l : LEGENDS.values()) if (!l.destroyed && l.seenBy.contains(nick)) send(p, l);
    }
}
