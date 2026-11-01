package com.arc4io.archud.client.mods;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Отдельный маленький процесс (без Minecraft): ждёт, пока игра закроется, переименовывает jar-файлы модов
 * (на Windows открытый jar переименовать нельзя) и, если нужно, запускает игру заново.
 * Аргументы: pid игры, файл со списком «откуда\tкуда», файл с командой перезапуска (по аргументу в строке) или «-».
 */
public final class ModToggleHelper {
    private ModToggleHelper() {}

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        ProcessHandle.of(pid).ifPresent(h -> {
            try {
                h.onExit().get();
            } catch (Exception ignored) {
            }
        });
        Thread.sleep(500);
        for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length != 2) continue;
            for (int attempt = 0; attempt < 40; attempt++) {
                try {
                    Files.move(Path.of(p[0]), Path.of(p[1]), StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (Exception e) {
                    Thread.sleep(250);
                }
            }
        }
        if (args.length > 2 && !args[2].equals("-")) {
            List<String> cmd = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8);
            String dir = cmd.remove(0);
            new ProcessBuilder(cmd).directory(Path.of(dir).toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        }
    }
}
