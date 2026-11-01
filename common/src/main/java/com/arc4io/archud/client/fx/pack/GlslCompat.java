package com.arc4io.archud.client.fx.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Перевод шейдеров шейдерпаков (обычно GLSL 120/130 с «compatibility»-функциями: gl_FragData, texture2D, varying,
 * ftransform, gl_TexCoord…) в core-профиль GLSL 150+, в котором работает Minecraft. Дополнительно — разбор
 * DRAWBUFFERS / RENDERTARGETS (в какие буферы пишет проход).
 */
public final class GlslCompat {
    private GlslCompat() {}

    public record Result(String source, int[] drawBuffers, int outputs) {}

    private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+(\\d+)(\\s+\\w+)?\\s*$", Pattern.MULTILINE);
    private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension[^\\n]*$", Pattern.MULTILINE);
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern TEX_COORD = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern TEX_MATRIX = Pattern.compile("\\bgl_TextureMatrix\\s*\\[\\s*\\d+\\s*\\]");
    private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9A-Fa-f]+)\\s*\\*/");
    private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]+)\\*/");
    private static final Pattern LAYOUT_OUT = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\b");

    public static Result translate(String src, boolean fragment) {
        int version = 120;
        Matcher vm = VERSION.matcher(src);
        if (vm.find()) {
            version = Integer.parseInt(vm.group(1));
            src = src.substring(0, vm.start()) + "\n" + src.substring(vm.end());
        }
        List<String> exts = new ArrayList<>();
        Matcher em = EXTENSION.matcher(src);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (em.find()) {
            exts.add(em.group().trim());
            rest.append(src, last, em.start());
            last = em.end();
        }
        rest.append(src.substring(last));
        src = rest.toString();

        int[] drawBuffers = null;
        if (fragment) {
            Matcher rt = RENDERTARGETS.matcher(src);
            Matcher db = DRAWBUFFERS.matcher(src);
            if (rt.find()) {
                String[] p = rt.group(1).split(",");
                List<Integer> l = new ArrayList<>();
                for (String s : p) if (!s.isBlank()) l.add(Integer.parseInt(s.trim()));
                drawBuffers = l.stream().mapToInt(Integer::intValue).toArray();
            } else if (db.find()) {
                String s = db.group(1);
                drawBuffers = new int[s.length()];
                for (int i = 0; i < s.length(); i++) drawBuffers[i] = Character.digit(s.charAt(i), 16);
            }
        }

        StringBuilder head = new StringBuilder();
        int target = Math.max(150, version >= 330 ? version : 150);
        head.append("#version ").append(target).append(" core\n");
        for (String e : exts) head.append(e).append('\n');
        head.append("#define MC_VERSION 12001\n#define MC_GL_VERSION 320\n#define MC_GLSL_VERSION ").append(version).append('\n');
        head.append("#define MC_RENDER_QUALITY 1.0\n#define MC_SHADOW_QUALITY 1.0\n#define ARC_SHADERS 1\n");

        // общие замены (оба вида шейдеров)
        src = src.replaceAll("\\btexture2DLod\\b", "textureLod")
                .replaceAll("\\btexture2DGradARB\\b", "textureGrad")
                .replaceAll("\\btexture2DGrad\\b", "textureGrad")
                .replaceAll("\\btexture2DProj\\b", "textureProj")
                .replaceAll("\\btexture2D\\b", "texture")
                .replaceAll("\\btexture3D\\b", "texture")
                .replaceAll("\\btextureCube\\b", "texture")
                .replaceAll("\\bshadow2DLod\\s*\\(", "archud_shadow2DLod(")
                .replaceAll("\\bshadow2D\\s*\\(", "archud_shadow2D(");
        src = TEX_MATRIX.matcher(src).replaceAll("mat4(1.0)");
        // gl_Fog из старого GLSL — своя uniform-структура с теми же полями
        head.append("struct archud_FogParameters { vec4 color; float density; float start; float end; float scale; };\n");
        head.append("uniform archud_FogParameters archud_Fog;\n#define gl_Fog archud_Fog\n");
        head.append("vec4 archud_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }\n");
        head.append("vec4 archud_shadow2D(sampler2D s, vec3 c) { return vec4(step(c.z, texture(s, c.xy).r)); }\n");
        head.append("vec4 archud_shadow2DLod(sampler2DShadow s, vec3 c, float l) { return vec4(texture(s, c)); }\n");
        head.append("vec4 archud_shadow2DLod(sampler2D s, vec3 c, float l) { return vec4(step(c.z, textureLod(s, c.xy, l).r)); }\n");

        TreeSet<Integer> texCoords = new TreeSet<>();
        Matcher tc = TEX_COORD.matcher(src);
        while (tc.find()) texCoords.add(Integer.parseInt(tc.group(1)));
        src = TEX_COORD.matcher(src).replaceAll("archud_TexCoord$1");
        boolean color = src.contains("gl_Color") || src.contains("gl_FrontColor");
        int outputs = 0;

        if (fragment) {
            src = src.replaceAll("\\bvarying\\b", "in").replaceAll("\\bgl_Color\\b", "archud_Color");
            for (int n : texCoords) head.append("in vec4 archud_TexCoord").append(n).append(";\n");
            if (color) head.append("in vec4 archud_Color;\n");
            TreeSet<Integer> outs = new TreeSet<>();
            Matcher fd = FRAG_DATA.matcher(src);
            while (fd.find()) outs.add(Integer.parseInt(fd.group(1)));
            if (src.matches("(?s).*\\bgl_FragColor\\b.*")) outs.add(0);
            src = FRAG_DATA.matcher(src).replaceAll("archud_FragData$1").replaceAll("\\bgl_FragColor\\b", "archud_FragData0");
            for (int n : outs) head.append("out vec4 archud_FragData").append(n).append(";\n");
            outputs = outs.isEmpty() ? 0 : outs.last() + 1;
            Matcher lo = LAYOUT_OUT.matcher(src);
            while (lo.find()) outputs = Math.max(outputs, Integer.parseInt(lo.group(1)) + 1);
        } else {
            src = src.replaceAll("\\battribute\\b", "in").replaceAll("\\bvarying\\b", "out")
                    .replaceAll("\\bftransform\\s*\\(\\s*\\)", "(archud_MVP * vec4(archud_Position, 1.0))")
                    .replaceAll("\\bgl_Vertex\\b", "vec4(archud_Position, 1.0)")
                    .replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(archud_UV0, 0.0, 1.0)")
                    .replaceAll("\\bgl_MultiTexCoord\\d\\b", "vec4(0.0, 0.0, 0.0, 1.0)")
                    .replaceAll("\\bgl_ModelViewProjectionMatrix\\b", "archud_MVP")
                    .replaceAll("\\bgl_ProjectionMatrix\\b", "archud_MVP")
                    .replaceAll("\\bgl_ModelViewMatrixInverse\\b", "mat4(1.0)")
                    .replaceAll("\\bgl_ModelViewMatrix\\b", "mat4(1.0)")
                    .replaceAll("\\bgl_NormalMatrix\\b", "mat3(1.0)")
                    .replaceAll("\\bgl_Normal\\b", "vec3(0.0, 0.0, 1.0)")
                    .replaceAll("\\bgl_FrontColor\\b", "archud_Color")
                    .replaceAll("\\bgl_Color\\b", "vec4(1.0)");
            head.append("in vec3 archud_Position;\nin vec2 archud_UV0;\nuniform mat4 archud_MVP;\n");
            // выходы объявляются всегда — фрагментный шейдер может читать то, что вершинный не пишет
            for (int n = 0; n < 8; n++) head.append("out vec4 archud_TexCoord").append(n).append(";\n");
            head.append("out vec4 archud_Color;\n");
        }
        return new Result(head + "#line 1\n" + src, drawBuffers, outputs);
    }
}
