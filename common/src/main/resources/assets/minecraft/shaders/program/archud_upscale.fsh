#version 150

// Arc HUD — масштаб рендера: мир нарисован в уменьшенном разрешении и увеличивается до размера окна.
// Mode 0 — билинейно (быстрее всего); Mode 1 — в стиле FSR 1: бикубический Catmull-Rom (9 выборок)
// + резкость RCAS по исходным пикселям (контуры остаются чёткими без ореолов).

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Mode;
uniform float Sharpness;

in vec2 texCoord;

out vec4 fragColor;

vec3 catmullRom(vec2 uv) {
    vec2 samplePos = uv * InSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 texPos0 = (texPos1 - 1.0) / InSize;
    vec2 texPos3 = (texPos1 + 2.0) / InSize;
    vec2 texPos12 = (texPos1 + offset12) / InSize;
    vec3 r = vec3(0.0);
    r += texture(DiffuseSampler, vec2(texPos0.x, texPos0.y)).rgb * w0.x * w0.y;
    r += texture(DiffuseSampler, vec2(texPos12.x, texPos0.y)).rgb * w12.x * w0.y;
    r += texture(DiffuseSampler, vec2(texPos3.x, texPos0.y)).rgb * w3.x * w0.y;
    r += texture(DiffuseSampler, vec2(texPos0.x, texPos12.y)).rgb * w0.x * w12.y;
    r += texture(DiffuseSampler, vec2(texPos12.x, texPos12.y)).rgb * w12.x * w12.y;
    r += texture(DiffuseSampler, vec2(texPos3.x, texPos12.y)).rgb * w3.x * w12.y;
    r += texture(DiffuseSampler, vec2(texPos0.x, texPos3.y)).rgb * w0.x * w3.y;
    r += texture(DiffuseSampler, vec2(texPos12.x, texPos3.y)).rgb * w12.x * w3.y;
    r += texture(DiffuseSampler, vec2(texPos3.x, texPos3.y)).rgb * w3.x * w3.y;
    return max(r, 0.0);
}

void main() {
    if (Mode < 0.5) {
        fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, 1.0);
        return;
    }
    vec3 c = catmullRom(texCoord);
    // RCAS по соседним исходным пикселям
    vec2 px = 1.0 / InSize;
    vec3 n = texture(DiffuseSampler, texCoord + vec2(0.0, -px.y)).rgb;
    vec3 s = texture(DiffuseSampler, texCoord + vec2(0.0, px.y)).rgb;
    vec3 e = texture(DiffuseSampler, texCoord + vec2(px.x, 0.0)).rgb;
    vec3 w = texture(DiffuseSampler, texCoord + vec2(-px.x, 0.0)).rgb;
    vec3 mn = min(min(n, s), min(min(e, w), c));
    vec3 mx = max(max(n, s), max(max(e, w), c));
    vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0);
    vec3 wgt = -sqrt(amp) * mix(0.1, 0.2, Sharpness);
    c = (c + (n + s + e + w) * wgt) / (1.0 + 4.0 * wgt);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
