#version 150

// Arc HUD — встроенный лёгкий шейдер (один проход, ~25 выборок на пиксель):
// FXAA (сглаживание краёв), резкость в стиле AMD RCAS, мягкое свечение ярких мест,
// FakeHDR (подъём теней + локальный контраст + плёночная тональная кривая), насыщенность, контраст, температура.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Hdr;
uniform float Bloom;
uniform float Vibrance;
uniform float Contrast;
uniform float Temperature;
uniform float Sharpen;
uniform float Fxaa;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

vec3 tex(vec2 uv) {
    return texture(DiffuseSampler, uv).rgb;
}

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// FXAA (упрощённый, по мотивам FXAA 3.11 «console»)
vec3 fxaa(vec2 uv) {
    vec3 rgbNW = tex(uv + vec2(-1.0, -1.0) * oneTexel);
    vec3 rgbNE = tex(uv + vec2(1.0, -1.0) * oneTexel);
    vec3 rgbSW = tex(uv + vec2(-1.0, 1.0) * oneTexel);
    vec3 rgbSE = tex(uv + vec2(1.0, 1.0) * oneTexel);
    vec3 rgbM = tex(uv);
    float lNW = luma(rgbNW), lNE = luma(rgbNE), lSW = luma(rgbSW), lSE = luma(rgbSE), lM = luma(rgbM);
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    if (lMax - lMin < max(0.0312, lMax * 0.125)) return rgbM;
    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), ((lNW + lSW) - (lNE + lSE)));
    float reduce = max((lNW + lNE + lSW + lSE) * 0.03125, 1.0 / 128.0);
    float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcp, vec2(-8.0), vec2(8.0)) * oneTexel;
    vec3 a = 0.5 * (tex(uv + dir * (1.0 / 3.0 - 0.5)) + tex(uv + dir * (2.0 / 3.0 - 0.5)));
    vec3 b = a * 0.5 + 0.25 * (tex(uv + dir * -0.5) + tex(uv + dir * 0.5));
    float lB = luma(b);
    return (lB < lMin || lB > lMax) ? a : b;
}

// плёночная тональная кривая (ACES, приближение Нарковича)
vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = texCoord;
    vec3 c = Fxaa > 0.5 ? fxaa(uv) : tex(uv);

    // соседи для резкости и FakeHDR
    vec3 n = tex(uv + vec2(0.0, -1.0) * oneTexel);
    vec3 s = tex(uv + vec2(0.0, 1.0) * oneTexel);
    vec3 e = tex(uv + vec2(1.0, 0.0) * oneTexel);
    vec3 w = tex(uv + vec2(-1.0, 0.0) * oneTexel);

    // резкость в стиле RCAS: усиление с ограничением по локальному min/max (без ореолов)
    if (Sharpen > 0.0) {
        vec3 mn = min(min(n, s), min(min(e, w), c));
        vec3 mx = max(max(n, s), max(max(e, w), c));
        vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0);
        vec3 wgt = -sqrt(amp) * mix(0.125, 0.2, Sharpen);
        c = clamp((c + (n + s + e + w) * wgt) / (1.0 + 4.0 * wgt), 0.0, 1.0);
    }

    // мягкое свечение: яркие места вокруг (8 выборок на радиусе ~3 и ~6 пикселей)
    if (Bloom > 0.0) {
        vec3 glow = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.785398;
            vec2 d = vec2(cos(a), sin(a)) * oneTexel;
            vec3 t1 = tex(uv + d * 3.0), t2 = tex(uv + d * 6.0);
            glow += max(t1 - 0.65, 0.0) + max(t2 - 0.65, 0.0) * 0.6;
        }
        c += glow * (0.18 * Bloom);
    }

    // FakeHDR: подъём теней, локальный контраст и тональная кривая с экспозицией
    if (Hdr > 0.0) {
        vec3 blur = (n + s + e + w) * 0.25;
        c = c + (c - blur) * (0.6 * Hdr);
        c = mix(c, pow(max(c, 0.0), vec3(1.0 - 0.25 * Hdr)), 0.7);
        c = mix(c, aces(c * (1.0 + 0.7 * Hdr)), Hdr);
    }

    // насыщенность (вибранс: сильнее для блёклых цветов), контраст, температура
    float l = luma(c);
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    c = mix(vec3(l), c, 1.0 + Vibrance * (1.0 - sat));
    c = (c - 0.5) * (1.0 + Contrast) + 0.5;
    c += vec3(Temperature, Temperature * 0.2, -Temperature) * 0.08;

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
