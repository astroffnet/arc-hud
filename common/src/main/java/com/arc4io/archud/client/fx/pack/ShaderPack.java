package com.arc4io.archud.client.fx.pack;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Шейдерпак из папки shaderpacks (zip или папка) в формате OptiFine / Iris: файлы shaders/*.vsh|fsh,
 * подкаталоги измерений (world-1, world1), #include с путями от корня shaders.
 */
public final class ShaderPack implements AutoCloseable {
    private final String name;
    private final FileSystem zip;
    private final Path root; // папка shaders/

    private ShaderPack(String name, FileSystem zip, Path root) {
        this.name = name;
        this.zip = zip;
        this.root = root;
    }

    public String name() {
        return name;
    }

    /** Список шейдерпаков в папке (zip-файлы и папки с подпапкой shaders). */
    public static List<String> list(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p) && Files.isDirectory(p.resolve("shaders"))) out.add(n);
                else if (n.toLowerCase(Locale.ROOT).endsWith(".zip") && Files.isRegularFile(p)) out.add(n);
            }
        } catch (IOException ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static ShaderPack open(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        if (Files.isDirectory(p)) return new ShaderPack(name, null, p.resolve("shaders"));
        FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + p.toUri()), Map.of());
        Path shaders = fs.getPath("/shaders");
        if (!Files.isDirectory(shaders)) {
            // некоторые архивы содержат ещё одну папку верхнего уровня
            try (var top = Files.list(fs.getPath("/"))) {
                for (Path t : (Iterable<Path>) top::iterator) {
                    if (Files.isDirectory(t.resolve("shaders"))) {
                        shaders = t.resolve("shaders");
                        break;
                    }
                }
            }
        }
        if (!Files.isDirectory(shaders)) {
            fs.close();
            throw new IOException("no shaders folder in " + name);
        }
        return new ShaderPack(name, fs, shaders);
    }

    /** Файл программы с учётом папки измерения (world-1 — Незер, world1 — Энд). */
    public String program(String file, String dimFolder) {
        try {
            if (dimFolder != null) {
                Path d = root.resolve(dimFolder).resolve(file);
                if (Files.isRegularFile(d)) return read(d, new HashSet<>(), 0);
            }
            Path f = root.resolve(file);
            if (Files.isRegularFile(f)) return read(f, new HashSet<>(), 0);
        } catch (IOException ignored) {
        }
        return null;
    }

    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+[\"<]([^\">]+)[\">]", Pattern.MULTILINE);

    /** Текст с подставленными #include (пути от корня shaders или от текущего файла). */
    private String read(Path f, Set<String> stack, int depth) throws IOException {
        if (depth > 32 || !stack.add(f.toString())) return "";
        String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r", "");
        StringBuilder out = new StringBuilder();
        Matcher m = INCLUDE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String inc = m.group(1);
            Path target = inc.startsWith("/") ? root.resolve(inc.substring(1)) : f.getParent().resolve(inc);
            if (Files.isRegularFile(target)) out.append(read(target.normalize(), stack, depth + 1)).append('\n');
            else out.append("// archud: missing include ").append(inc).append('\n');
            last = m.end();
        }
        out.append(text.substring(last));
        stack.remove(f.toString());
        return out.toString();
    }

    @Override
    public void close() {
        try {
            if (zip != null) zip.close();
        } catch (IOException ignored) {
        }
    }
}
