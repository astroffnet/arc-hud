#version 150

// Arc HUD: «объектив» нагрудной камеры — бочкообразное искажение, хроматические аберрации,
// виньетка, лёгкое обесцвечивание, контраст и плёночный шум.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Time;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 c = texCoord - 0.5;
    float aspect = InSize.x / max(InSize.y, 1.0);
    vec2 ca2 = vec2(c.x * aspect, c.y);
    float r2 = dot(ca2, ca2) / (0.25 * (aspect * aspect + 1.0)); // 0 в центре, ~1 в углу
    float k = 0.22;
    vec2 d = c * (1.0 + k * r2) / (1.0 + k);
    vec2 uv = d + 0.5;

    float ab = 0.004 * r2;
    vec3 col;
    col.r = texture(DiffuseSampler, uv + c * ab).r;
    col.g = texture(DiffuseSampler, uv).g;
    col.b = texture(DiffuseSampler, uv - c * ab).b;

    float l = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(l), col, 0.82);
    col = (col - 0.5) * 1.1 + 0.5;
    col *= vec3(0.97, 1.0, 1.04);

    float v = smoothstep(1.15, 0.35, r2);
    col *= mix(0.42, 1.0, v);

    float n = rand(floor(texCoord * InSize) + fract(Time * 13.0) * 97.0) - 0.5;
    col += n * 0.055;
    col *= 0.985 + 0.015 * sin(Time * 50.0);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
