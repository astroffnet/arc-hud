#version 150

// Arc TSR — временной апскейл. Мир нарисован в уменьшенном разрешении со сдвигом камеры на долю пикселя (Jitter);
// кадры накапливаются в истории полного разрешения. Прошлый кадр совмещается с текущим по глубине и матрицам камеры,
// устаревшие цвета ограничиваются диапазоном соседей текущего кадра (против шлейфов).

uniform sampler2D DiffuseSampler; // текущий кадр (уменьшенный)
uniform sampler2D DepthSampler;   // его глубина
uniform sampler2D HistorySampler; // накопленная история (полное разрешение)

uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 Jitter;
uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
uniform vec3 CamDelta;
uniform float HistoryValid;
uniform float Scale;

in vec2 texCoord;
out vec4 fragColor;

vec3 toYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 fromYCoCg(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

vec3 catmull(sampler2D tex, vec2 uv, vec2 size) {
    vec2 samplePos = uv * size;
    vec2 t1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - t1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 t0 = (t1 - 1.0) / size;
    vec2 t3 = (t1 + 2.0) / size;
    vec2 t12 = (t1 + w2 / w12) / size;
    vec3 r = texture(tex, vec2(t12.x, t0.y)).rgb * w12.x * w0.y
           + texture(tex, vec2(t0.x, t12.y)).rgb * w0.x * w12.y
           + texture(tex, vec2(t12.x, t12.y)).rgb * w12.x * w12.y
           + texture(tex, vec2(t3.x, t12.y)).rgb * w3.x * w12.y
           + texture(tex, vec2(t12.x, t3.y)).rgb * w12.x * w3.y;
    float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(r / wsum, 0.0);
}

void main() {
    // где эта точка экрана лежит в сдвинутом уменьшенном кадре
    vec2 uvIn = texCoord - Jitter / InSize;
    vec2 pIn = uvIn * InSize;
    ivec2 base = ivec2(floor(pIn));
    ivec2 maxI = ivec2(InSize) - 1;

    // соседи 3×3: диапазон цветов (YCoCg, среднее ± отклонение) и ближайшая глубина
    vec3 m1 = vec3(0.0), m2 = vec3(0.0);
    float depth = 1.0;
    vec3 nearest = vec3(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 p = clamp(base + ivec2(x, y), ivec2(0), maxI);
            vec3 c = toYCoCg(texelFetch(DiffuseSampler, p, 0).rgb);
            m1 += c;
            m2 += c * c;
            depth = min(depth, texelFetch(DepthSampler, p, 0).r);
            if (x == 0 && y == 0) nearest = c;
        }
    }
    m1 /= 9.0;
    vec3 sigma = sqrt(max(m2 / 9.0 - m1 * m1, 0.0));
    vec3 lo = m1 - 1.25 * sigma, hi = m1 + 1.25 * sigma;
    lo = min(lo, nearest);
    hi = max(hi, nearest);

    vec3 smoothCur = toYCoCg(catmull(DiffuseSampler, uvIn, InSize));

    // репроекция: точка мира → где она была в прошлом кадре
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 rel = InvViewProj * ndc;
    rel /= rel.w;
    vec4 prev = PrevViewProj * vec4(rel.xyz + CamDelta, 1.0);
    vec2 prevUV = prev.xy / prev.w * 0.5 + 0.5;
    bool offscreen = prev.w <= 0.0 || any(lessThan(prevUV, vec2(0.0))) || any(greaterThan(prevUV, vec2(1.0)));
    float motionPx = length((prevUV - texCoord) * OutSize);

    // вес текущего образца: насколько центр пикселя уменьшенного кадра близок к этому пикселю экрана
    vec2 dOut = (pIn - (vec2(base) + 0.5)) / Scale;
    float w = exp(-2.5 * dot(dOut, dOut));
    vec3 cur = mix(smoothCur, nearest, clamp(w * 1.5, 0.0, 1.0));

    if (HistoryValid < 0.5 || offscreen) {
        fragColor = vec4(clamp(fromYCoCg(smoothCur), 0.0, 1.0), 1.0);
        return;
    }

    vec3 hist = toYCoCg(catmull(HistorySampler, prevUV, OutSize));
    // клиппинг к диапазону соседей (вдоль луча к центру — меньше цветовых сдвигов, чем покомпонентный clamp)
    vec3 center = 0.5 * (hi + lo), ext = 0.5 * (hi - lo) + 1e-4;
    vec3 v = hist - center;
    vec3 a = abs(v / ext);
    float ma = max(a.x, max(a.y, a.z));
    if (ma > 1.0) hist = center + v / ma;

    // в покое новый кадр вносит вклад почти только там, где его образец попал близко к пикселю экрана —
    // так из сдвинутых кадров собираются детали полного разрешения; в движении история обновляется быстрее
    float alpha = 0.012 + 0.22 * w + clamp(motionPx * 0.02, 0.0, 0.35);
    vec3 outc = mix(hist, cur, clamp(alpha, 0.012, 0.6));
    fragColor = vec4(clamp(fromYCoCg(outc), 0.0, 1.0), 1.0);
}
