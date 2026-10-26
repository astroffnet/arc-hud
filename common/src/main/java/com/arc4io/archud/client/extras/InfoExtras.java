package com.arc4io.archud.client.extras;

import com.arc4io.archud.client.hud.Icons;
import com.arc4io.archud.config.ArcConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Дополнительные строки информационной панели и подробности о блоке / существе под прицелом.
 */
public final class InfoExtras {
    /** Строка панели: иконка, текст, цвет. */
    public record Line(int icon, Component text, int color) {}

    private static final String[] NOTES = {"F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#", "E", "F"};

    private InfoExtras() {}

    public static List<Line> infoLines(Minecraft mc, LocalPlayer p) {
        ArcConfig cfg = ArcConfig.get();
        List<Line> out = new ArrayList<>();
        Level level = mc.level;
        boolean overworld = level.dimension() == Level.OVERWORLD;
        boolean nether = level.dimension() == Level.NETHER;
        // координаты в другом измерении
        if (cfg.infoNetherCoords && (overworld || nether)) {
            int x = p.getBlockX(), z = p.getBlockZ();
            Component c = overworld ? Component.translatable("archud.info.in_nether", Math.floorDiv(x, 8), Math.floorDiv(z, 8))
                    : Component.translatable("archud.info.in_overworld", x * 8, z * 8);
            out.add(new Line(Icons.PIN, c, overworld ? 0xFFFF8A65 : 0xFFA5D6A7));
        }
        boolean sky = !level.dimensionType().hasFixedTime();
        // фаза луны
        if (cfg.infoMoonPhase && sky) {
            out.add(new Line(Icons.MOON, Component.translatable("archud.moon." + level.getMoonPhase()), 0xFFB0BEC5));
        }
        // до ночи / до утра
        if (cfg.infoDayNightTimer && sky) {
            long tod = level.getDayTime() % 24000L;
            boolean day = tod < 12542L || tod >= 23460L;
            long ticks = day ? (tod < 12542L ? 12542L - tod : 24000L - tod + 12542L) : 23460L - tod;
            out.add(new Line(day ? Icons.MOON : Icons.SUN, Component.translatable(day ? "archud.info.to_night" : "archud.info.to_day", mmss(ticks / 20)), 0xFFE0E6EE));
        }
        var server = mc.getSingleplayerServer();
        // слизневый чанк (только одиночная игра — нужен сид)
        if (cfg.infoSlimeChunk && overworld && server != null) {
            long seed = server.overworld().getSeed();
            boolean slime = WorldgenRandom.seedSlimeChunk(p.chunkPosition().x, p.chunkPosition().z, seed, 987234911L).nextInt(10) == 0;
            if (slime) out.add(new Line(Icons.LEAF, Component.translatable("archud.info.slime_chunk"), 0xFF9CFF5A));
        }
        // прогноз погоды (одиночная игра)
        if (cfg.infoWeatherForecast && overworld && server != null) {
            ServerLevel sl = server.overworld();
            if (sl.getLevelData() instanceof ServerLevelData d) {
                Component c;
                if (d.getClearWeatherTime() > 0) c = Component.translatable("archud.info.clear_for", mmss(d.getClearWeatherTime() / 20));
                else if (d.isRaining()) c = Component.translatable("archud.info.rain_ends", mmss(d.getRainTime() / 20));
                else c = Component.translatable("archud.info.rain_in", mmss(d.getRainTime() / 20));
                out.add(new Line(d.isRaining() ? Icons.RAIN : Icons.SUN, c, 0xFF90CAF9));
            }
        }
        // время в мире
        if (cfg.infoSessionTime) {
            long sec = Extras.worldTime() / 1000;
            out.add(new Line(Icons.CALENDAR, Component.translatable("archud.info.session", hhmmss(sec)), 0xFFB0BEC5));
        }
        return out;
    }

    /** Подробности о блоке: рост, сигнал, нота, уровни. Пусто — нечего показать. */
    public static String blockDetail(BlockState s) {
        if (!ArcConfig.get().targetBlockDetails) return "";
        for (Property<?> prop : s.getProperties()) {
            if (prop.getName().equals("age") && prop instanceof IntegerProperty ip) {
                int v = s.getValue(ip), max = ip.getPossibleValues().stream().max(Integer::compare).orElse(1);
                if (max <= 0) continue;
                return v >= max ? Component.translatable("archud.target.ripe").getString()
                        : Component.translatable("archud.target.growth", v * 100 / max).getString();
            }
        }
        if (s.hasProperty(BlockStateProperties.NOTE)) {
            int n = s.getValue(BlockStateProperties.NOTE);
            return Component.translatable("archud.target.note", NOTES[n % 12], n).getString();
        }
        if (s.hasProperty(BlockStateProperties.POWER)) return Component.translatable("archud.target.power", s.getValue(BlockStateProperties.POWER)).getString();
        if (s.hasProperty(BlockStateProperties.LEVEL_HONEY)) return Component.translatable("archud.target.honey", s.getValue(BlockStateProperties.LEVEL_HONEY), 5).getString();
        if (s.hasProperty(BlockStateProperties.LEVEL_COMPOSTER)) return Component.translatable("archud.target.compost", s.getValue(BlockStateProperties.LEVEL_COMPOSTER), 8).getString();
        if (s.hasProperty(BlockStateProperties.RESPAWN_ANCHOR_CHARGES)) return Component.translatable("archud.target.charges", s.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES), 4).getString();
        if (s.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) return Component.translatable("archud.target.level", s.getValue(BlockStateProperties.LEVEL_CAULDRON), 3).getString();
        if (s.hasProperty(BlockStateProperties.DELAY)) return Component.translatable("archud.target.delay", s.getValue(BlockStateProperties.DELAY)).getString();
        if (s.hasProperty(BlockStateProperties.BITES)) return Component.translatable("archud.target.bites", 7 - s.getValue(BlockStateProperties.BITES)).getString();
        return "";
    }

    /** Характеристики лошадей, ослов, лам: скорость и высота прыжка. */
    public static String mobDetail(LivingEntity e) {
        if (!ArcConfig.get().targetMobStats || !(e instanceof AbstractHorse h)) return "";
        double speed = h.getAttributeValue(Attributes.MOVEMENT_SPEED) * 42.16;
        double j = h.getAttribute(Attributes.JUMP_STRENGTH) != null ? h.getAttributeValue(Attributes.JUMP_STRENGTH) : 0;
        double jump = -0.1817584952 * j * j * j + 3.689713992 * j * j + 2.128599134 * j - 0.343930367;
        return j > 0 ? Component.translatable("archud.target.horse", String.format(Locale.ROOT, "%.1f", speed), String.format(Locale.ROOT, "%.1f", jump)).getString()
                : Component.translatable("archud.target.horse_speed", String.format(Locale.ROOT, "%.1f", speed)).getString();
    }

    private static String mmss(long sec) {
        if (sec >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60);
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    private static String hhmmss(long sec) {
        return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60);
    }
}
