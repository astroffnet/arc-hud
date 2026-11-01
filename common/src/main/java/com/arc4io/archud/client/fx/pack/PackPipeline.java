package com.arc4io.archud.client.fx.pack;

import com.arc4io.archud.ArcHud;
import com.arc4io.archud.config.ArcConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Своя альтернатива Iris / OptiFine для шейдерпаков из папки shaderpacks (когда их нет в игре): проходы
 * постобработки шейдерпака — composite, composite1…composite15 и final — выполняются над готовой картинкой мира
 * (цвет и глубина), с буферами colortex0…15, uniform-переменными в формате OptiFine/Iris и переводом старого GLSL
 * в core-профиль. Тени (shadow) и собственные материалы мира (gbuffers_*) не поддерживаются: для них нужен свой
 * рендер всего мира — эффекты, которые на них опираются, получают «пустые» данные (без теней, без отражений).
 */
public final class PackPipeline {
    private static final int MAX_BUFFERS = 16;
    private static ShaderPack pack;
    private static String loadedKey = "";
    private static final List<Pass> PASSES = new ArrayList<>();
    private static Pass finalPass;
    private static String status = "";
    private static int errors;
    private static boolean failed;

    private static final int[][] TEX = new int[MAX_BUFFERS][2];
    private static final int[] CUR = new int[MAX_BUFFERS];
    private static final boolean[] USED = new boolean[MAX_BUFFERS];
    private static int fbo, copyFbo, finalTex, vao, vbo, noiseTex, whiteTex, shadowTex, shadowCmpTex, depthTex;
    private static int w = -1, h = -1;
    /** Своя маленькая программа: картинка мира → colortex0 в линейном цвете (как после gbuffers шейдерпаков). */
    private static int linearProg, linearSrc;

    private record Uniform(String name, int location, int type) {}

    private static final class Pass {
        final String name;
        final int program;
        final int[] drawBuffers;
        final List<Uniform> uniforms = new ArrayList<>();

        Pass(String name, int program, int[] drawBuffers) {
            this.name = name;
            this.program = program;
            this.drawBuffers = drawBuffers;
        }
    }

    private PackPipeline() {}

    public static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
    }

    public static List<String> packs() {
        return ShaderPack.list(dir());
    }

    public static String status() {
        return status;
    }

    public static boolean active() {
        String sel = ArcConfig.get().shaderPack;
        return sel != null && !sel.isEmpty() && !failed && !com.arc4io.archud.client.fx.ShaderPacks.inUse()
                && !com.arc4io.archud.client.gfx.GlContext.vulkan();
    }

    /** Выбрать другой шейдерпак / перезагрузить. */
    public static void reload() {
        failed = false;
        status = "";
        loadedKey = ""; // при следующем кадре старый пак выгрузится, новый загрузится (всё — в потоке отрисовки)
    }

    private static String dimFolder() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String d = mc.level.dimension().location().toString();
        return switch (d) {
            case "minecraft:the_nether" -> "world-1";
            case "minecraft:the_end" -> "world1";
            default -> "world0";
        };
    }

    // ------------------------------------------------------------------ загрузка
    private static void ensureLoaded() {
        String key = ArcConfig.get().shaderPack + "|" + dimFolder();
        if (key.equals(loadedKey)) return;
        unload();
        loadedKey = key;
        errors = 0;
        try {
            pack = ShaderPack.open(dir(), ArcConfig.get().shaderPack);
        } catch (Exception e) {
            status = "✘ " + e.getMessage();
            failed = true;
            ArcHud.LOGGER.warn("Arc HUD: cannot open shader pack {}", ArcConfig.get().shaderPack, e);
            return;
        }
        String dim = dimFolder();
        for (int i = 0; i <= 15; i++) {
            String n = i == 0 ? "composite" : "composite" + i;
            Pass p = compile(n, dim);
            if (p != null) PASSES.add(p);
        }
        finalPass = compile("final", dim);
        int total = PASSES.size() + (finalPass != null ? 1 : 0);
        status = pack.name() + ": " + total + (errors > 0 ? " ✔, " + errors + " ✘" : " ✔");
        if (total == 0) status = pack.name() + ": composite/final ✘";
        ArcHud.LOGGER.info("Arc HUD: shader pack {} — {} passes, {} errors", pack.name(), total, errors);
    }

    private static Pass compile(String name, String dim) {
        String vsh = pack.program(name + ".vsh", dim), fsh = pack.program(name + ".fsh", dim);
        if (fsh == null) return null;
        if (vsh == null) vsh = "#version 120\nvarying vec2 texcoord;\nvoid main(){ gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.xy; gl_TexCoord[0] = gl_MultiTexCoord0; }\n";
        GlslCompat.Result v = GlslCompat.translate(vsh, false), f = GlslCompat.translate(fsh, true);
        int vs = shader(GL20C.GL_VERTEX_SHADER, v.source(), name + ".vsh");
        int fs = shader(GL20C.GL_FRAGMENT_SHADER, f.source(), name + ".fsh");
        if (vs == 0 || fs == 0) {
            errors++;
            if (vs != 0) GL20C.glDeleteShader(vs);
            if (fs != 0) GL20C.glDeleteShader(fs);
            return null;
        }
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glBindAttribLocation(prog, 0, "archud_Position");
        GL20C.glBindAttribLocation(prog, 1, "archud_UV0");
        for (int i = 0; i < 8; i++) GL30C.glBindFragDataLocation(prog, i, "archud_FragData" + i);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            ArcHud.LOGGER.warn("Arc HUD: shader pack program {} link failed: {}", name, GL20C.glGetProgramInfoLog(prog));
            GL20C.glDeleteProgram(prog);
            errors++;
            return null;
        }
        int[] db = f.drawBuffers();
        if (db != null && db.length > 8) db = java.util.Arrays.copyOf(db, 8);
        if (db == null) {
            int n = Math.max(1, f.outputs());
            db = new int[n];
            for (int i = 0; i < n; i++) db[i] = i;
        }
        Pass p = new Pass(name, prog, db);
        int count = GL20C.glGetProgrami(prog, GL20C.GL_ACTIVE_UNIFORMS);
        IntBuffer size = BufferUtils.createIntBuffer(1), type = BufferUtils.createIntBuffer(1);
        for (int i = 0; i < count; i++) {
            String un = GL20C.glGetActiveUniform(prog, i, size, type);
            if (un.endsWith("[0]")) un = un.substring(0, un.length() - 3);
            p.uniforms.add(new Uniform(un, GL20C.glGetUniformLocation(prog, un), type.get(0)));
            int buf = bufferIndex(un);
            if (buf >= 0) USED[buf] = true;
        }
        for (int b : db) if (b >= 0 && b < MAX_BUFFERS) USED[b] = true;
        return p;
    }

    private static int shader(int kind, String src, String name) {
        int s = GL20C.glCreateShader(kind);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(s);
            ArcHud.LOGGER.warn("Arc HUD: shader pack {} does not compile: {}", name, log.length() > 2000 ? log.substring(0, 2000) : log);
            GL20C.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /** Имя сэмплера → номер буфера colortex (или −1). */
    private static int bufferIndex(String n) {
        return switch (n) {
            case "gcolor", "texture", "tex", "gtexture" -> 0;
            case "gdepth" -> 1;
            case "gnormal" -> 2;
            case "composite" -> 3;
            case "gaux1" -> 4;
            case "gaux2" -> 5;
            case "gaux3" -> 6;
            case "gaux4" -> 7;
            default -> {
                if (n.startsWith("colortex")) {
                    try {
                        int i = Integer.parseInt(n.substring(8));
                        yield i >= 0 && i < MAX_BUFFERS ? i : -1;
                    } catch (NumberFormatException e) {
                        yield -1;
                    }
                }
                yield -1;
            }
        };
    }

    private static void unload() {
        for (Pass p : PASSES) GL20C.glDeleteProgram(p.program);
        PASSES.clear();
        if (finalPass != null) GL20C.glDeleteProgram(finalPass.program);
        finalPass = null;
        if (pack != null) pack.close();
        pack = null;
        for (int i = 0; i < MAX_BUFFERS; i++) {
            USED[i] = false;
            for (int k = 0; k < 2; k++) {
                if (TEX[i][k] != 0) GlStateManager._deleteTexture(TEX[i][k]);
                TEX[i][k] = 0;
            }
        }
        if (finalTex != 0) GlStateManager._deleteTexture(finalTex);
        finalTex = 0;
        w = h = -1;
    }

    // ------------------------------------------------------------------ ресурсы GL
    private static int newTexture(int internal, int format, int type, int tw, int th, ByteBuffer data) {
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL15C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL15C.GL_CLAMP_TO_EDGE);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internal, tw, th, 0, format, type, data);
        return id;
    }

    private static void ensureResources(int width, int height) {
        if (vao == 0) {
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            FloatBuffer q = BufferUtils.createFloatBuffer(20);
            q.put(new float[]{0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 1, 0, 1, 1}).flip();
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, q, GL15C.GL_STATIC_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 20, 0);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, 20, 12);
            GlStateManager._glBindVertexArray(0);
            fbo = GlStateManager.glGenFramebuffers();
            copyFbo = GlStateManager.glGenFramebuffers();
            // шум (noisetex), белая текстура и «тень без теней» (глубина 1 — всё освещено)
            ByteBuffer noise = BufferUtils.createByteBuffer(256 * 256 * 4);
            java.util.Random r = new java.util.Random(1337);
            for (int i = 0; i < 256 * 256 * 4; i++) noise.put((byte) r.nextInt(256));
            noise.flip();
            noiseTex = newTexture(GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 256, 256, noise);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            ByteBuffer white = BufferUtils.createByteBuffer(4);
            white.put(new byte[]{-1, -1, -1, -1}).flip();
            whiteTex = newTexture(GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 1, 1, white);
            shadowTex = depthTexture(false);
            shadowCmpTex = depthTexture(true);
        }
        if (width == w && height == h) return;
        w = width;
        h = height;
        USED[0] = true;
        for (int i = 0; i < MAX_BUFFERS; i++) {
            for (int k = 0; k < 2; k++) {
                if (TEX[i][k] != 0) GlStateManager._deleteTexture(TEX[i][k]);
                TEX[i][k] = USED[i] ? newTexture(GL30C.GL_RGBA16F, GL11C.GL_RGBA, GL11C.GL_FLOAT, w, h, null) : 0;
            }
            CUR[i] = 0;
        }
        if (finalTex != 0) GlStateManager._deleteTexture(finalTex);
        finalTex = newTexture(GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, w, h, null);
    }

    private static int linearProgram() {
        if (linearProg != 0) return linearProg;
        int vs = shader(GL20C.GL_VERTEX_SHADER, "#version 150 core\nin vec3 archud_Position;\nin vec2 archud_UV0;\nout vec2 uv;\n"
                + "void main() { gl_Position = vec4(archud_Position.xy * 2.0 - 1.0, 0.0, 1.0); uv = archud_UV0; }\n", "arc_linear.vsh");
        int fs = shader(GL20C.GL_FRAGMENT_SHADER, "#version 150 core\nuniform sampler2D src;\nin vec2 uv;\nout vec4 o;\n"
                + "void main() { vec4 c = texture(src, uv); o = vec4(pow(max(c.rgb, vec3(0.0)), vec3(2.2)), 1.0); }\n", "arc_linear.fsh");
        if (vs == 0 || fs == 0) return 0;
        int p = GL20C.glCreateProgram();
        GL20C.glAttachShader(p, vs);
        GL20C.glAttachShader(p, fs);
        GL20C.glBindAttribLocation(p, 0, "archud_Position");
        GL20C.glBindAttribLocation(p, 1, "archud_UV0");
        GL30C.glBindFragDataLocation(p, 0, "o");
        GL20C.glLinkProgram(p);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) return 0;
        linearProg = p;
        linearSrc = GL20C.glGetUniformLocation(p, "src");
        return p;
    }

    /** 1×1 глубина 1.0 — «теней нет» (для sampler2DShadow — с режимом сравнения). */
    private static int depthTexture(boolean compare) {
        FloatBuffer one = BufferUtils.createFloatBuffer(1);
        one.put(1F).flip();
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL14C.GL_DEPTH_COMPONENT24, 1, 1, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, one);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, compare ? GL11C.GL_LINEAR : GL11C.GL_NEAREST);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, compare ? GL11C.GL_LINEAR : GL11C.GL_NEAREST);
        if (compare) {
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_COMPARE_REF_TO_TEXTURE);
            GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_FUNC, GL11C.GL_LEQUAL);
        }
        return id;
    }

    // ------------------------------------------------------------------ кадр
    /** После отрисовки мира, до интерфейса. */
    public static void process(float partialTick) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RenderTarget main = mc.getMainRenderTarget();
        try {
            ensureLoaded();
            if (pack == null || (PASSES.isEmpty() && finalPass == null)) return;
            ensureResources(main.width, main.height);
            PackUniforms.update(partialTick, w, h);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            // colortex0 ← картинка мира; глубина читается прямо из буфера мира (в него проходы не рисуют)
            depthTex = main.getDepthTextureId();
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, copyFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, TEX[0][CUR[0]], 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            if (ArcConfig.get().shaderPackLinear && linearProgram() != 0) {
                // шейдерпаки считают в линейном цвете (gbuffers переводят текстуры из sRGB) — иначе всё пересвечено
                GlStateManager._viewport(0, 0, w, h);
                GlStateManager._glUseProgram(linearProg);
                bindUnit(0, main.getColorTextureId());
                GL20C.glUniform1i(linearSrc, 0);
                GlStateManager._glBindVertexArray(vao);
                GL11C.glDrawArrays(GL11C.GL_TRIANGLE_STRIP, 0, 4);
                bindUnit(0, 0);
            } else {
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
                GL30C.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
            }
            // остальные буферы — очистить (gdepth / colortex1 — белым, как в OptiFine)
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, copyFbo);
            for (int i = 1; i < MAX_BUFFERS; i++) {
                if (!USED[i] || TEX[i][CUR[i]] == 0) continue;
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, TEX[i][CUR[i]], 0);
                float c = i == 1 ? 1F : 0F;
                GL11C.glClearColor(c, c, c, c);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            }

            GlStateManager._glBindVertexArray(vao);
            for (Pass p : PASSES) run(p, false);
            int out;
            if (finalPass != null) {
                run(finalPass, true);
                out = finalTex;
            } else {
                out = TEX[0][CUR[0]]; // нет final — на экран идёт colortex0
            }
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, copyFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, out, 0);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
            GL30C.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        } catch (Throwable t) {
            failed = true;
            status = "✘ " + t;
            ArcHud.LOGGER.warn("Arc HUD: shader pack disabled after an error", t);
        } finally {
            restore(main);
        }
    }

    private static void run(Pass p, boolean toScreen) {
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        if (toScreen) {
            // final рисует в свою текстуру, затем она копируется на экран
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, finalTex, 0);
            for (int i = 1; i < 8; i++) GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11C.GL_TEXTURE_2D, 0, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        } else {
            IntBuffer bufs = BufferUtils.createIntBuffer(p.drawBuffers.length);
            for (int i = 0; i < 8; i++) {
                int tex = 0;
                if (i < p.drawBuffers.length) {
                    int b = p.drawBuffers[i];
                    if (b >= 0 && b < MAX_BUFFERS && TEX[b][1 - CUR[b]] != 0) tex = TEX[b][1 - CUR[b]];
                }
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11C.GL_TEXTURE_2D, tex, 0);
            }
            for (int i = 0; i < p.drawBuffers.length; i++) bufs.put(GL30C.GL_COLOR_ATTACHMENT0 + i);
            bufs.flip();
            GL20C.glDrawBuffers(bufs);
        }
        GlStateManager._viewport(0, 0, w, h);
        GlStateManager._glUseProgram(p.program);
        int unit = 0;
        for (Uniform u : p.uniforms) {
            if (u.location < 0) continue;
            if (isSampler(u.type)) {
                int tex = samplerTexture(u);
                bindUnit(unit, tex);
                GL20C.glUniform1i(u.location, unit);
                unit++;
            } else {
                setValue(u);
            }
        }
        GL11C.glDrawArrays(GL11C.GL_TRIANGLE_STRIP, 0, 4);
        if (!toScreen) {
            for (int b : p.drawBuffers) if (b >= 0 && b < MAX_BUFFERS && TEX[b][1 - CUR[b]] != 0) CUR[b] = 1 - CUR[b];
        }
        for (int i = unit - 1; i >= 0; i--) bindUnit(i, 0);
    }

    /** Minecraft помнит только 12 текстурных слотов — дальше привязка напрямую, без его кэша. */
    private static final int MC_UNITS = 12;

    private static void bindUnit(int unit, int tex) {
        if (unit < MC_UNITS) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(tex);
            return;
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
        // вернуть активный слот, который помнит Minecraft
        GL13C.glActiveTexture(GlStateManager._getActiveTexture());
    }

    private static boolean isSampler(int type) {
        return type == GL20C.GL_SAMPLER_2D || type == GL20C.GL_SAMPLER_2D_SHADOW || type == GL20C.GL_SAMPLER_3D
                || type == GL30C.GL_INT_SAMPLER_2D || type == GL30C.GL_UNSIGNED_INT_SAMPLER_2D || type == GL20C.GL_SAMPLER_CUBE;
    }

    private static int samplerTexture(Uniform u) {
        String n = u.name;
        int b = bufferIndex(n);
        if (b >= 0) return TEX[b][CUR[b]] != 0 ? TEX[b][CUR[b]] : whiteTex;
        if (n.startsWith("depthtex") || n.equals("gdepthtex")) return depthTex;
        if (n.startsWith("shadowtex") || n.equals("shadow") || n.equals("watershadow")) {
            return u.type == GL20C.GL_SAMPLER_2D_SHADOW ? shadowCmpTex : shadowTex;
        }
        if (n.equals("noisetex")) return noiseTex;
        return whiteTex;
    }

    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    private static void setValue(Uniform u) {
        Object v = PackUniforms.VALUES.get(u.name);
        if (v == null) return;
        switch (u.type) {
            case GL11C.GL_FLOAT -> GL20C.glUniform1f(u.location, v instanceof Number n ? n.floatValue() : 0F);
            case GL11C.GL_INT, GL20C.GL_BOOL -> GL20C.glUniform1i(u.location, v instanceof Number n ? n.intValue() : 0);
            case GL20C.GL_FLOAT_VEC2 -> {
                if (v instanceof float[] f && f.length >= 2) GL20C.glUniform2f(u.location, f[0], f[1]);
            }
            case GL20C.GL_FLOAT_VEC3 -> {
                if (v instanceof float[] f && f.length >= 3) GL20C.glUniform3f(u.location, f[0], f[1], f[2]);
            }
            case GL20C.GL_FLOAT_VEC4 -> {
                if (v instanceof float[] f && f.length >= 3) GL20C.glUniform4f(u.location, f[0], f[1], f[2], f.length > 3 ? f[3] : 1F);
            }
            case GL20C.GL_INT_VEC2 -> {
                if (v instanceof int[] i && i.length >= 2) GL20C.glUniform2i(u.location, i[0], i[1]);
            }
            case GL20C.GL_FLOAT_MAT4 -> {
                if (v instanceof Matrix4f m) {
                    MAT.clear();
                    m.get(MAT);
                    GL20C.glUniformMatrix4fv(u.location, false, MAT);
                }
            }
            default -> {
            }
        }
    }

    /** Вернуть состояние GL, которое помнит Minecraft (программа, VAO, буферы, текстуры). */
    private static void restore(RenderTarget main) {
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        GlStateManager._glUseProgram(0);
        com.arc4io.archud.mixin.ShaderProgramCacheAccessor.archud$setShaderLastProgram(-1);
        com.arc4io.archud.mixin.ShaderProgramCacheAccessor.archud$setShaderLastApplied(null);
        com.arc4io.archud.mixin.EffectProgramCacheAccessor.archud$setEffectLastProgram(-1);
        com.arc4io.archud.mixin.EffectProgramCacheAccessor.archud$setEffectLastApplied(null);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        main.bindWrite(true);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    public static String describe() {
        return status.isEmpty() ? (ArcConfig.get().shaderPack.isEmpty() ? "" : ArcConfig.get().shaderPack) : status;
    }

    public static String selectedLabel() {
        String s = ArcConfig.get().shaderPack;
        return s == null || s.isEmpty() ? "" : s.toLowerCase(Locale.ROOT).endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
    }
}
