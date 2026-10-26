#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

// Ванильный шейдер слоя cutout + шевеление растений Arc HUD (ArcWave: x — трава и цветы, y — листва и лианы,
// z — скорость, w — время в секундах; x = y = 0 — выключено, геометрия как в ванилле).

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;
uniform vec4 ArcWave;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec4 normal;

vec3 arcWave(vec3 local) {
    if (ArcWave.x <= 0.0 && ArcWave.y <= 0.0) return vec3(0.0);
    float sat = max(Color.r, max(Color.g, Color.b)) - min(Color.r, min(Color.g, Color.b));
    bool tinted = sat > 0.05 && Color.r < Color.g * 1.4;          // оттенок биома (трава, листва), не редстоун
    bool diagonal = abs(Normal.x) > 0.3 && abs(Normal.z) > 0.3;   // крестовые модели: трава, цветы, саженцы
    if (!tinted && !diagonal) return vec3(0.0);
    float t = ArcWave.w * (0.5 + ArcWave.z * 1.5);
    float ph = local.x * 0.7 + local.z * 0.9 + local.y * 0.35;
    vec3 d = vec3(sin(t * 1.7 + ph) + 0.5 * sin(t * 2.9 + ph * 1.3), 0.0,
                  cos(t * 1.3 + ph * 1.1) + 0.5 * sin(t * 2.3 + ph * 0.7));
    if (diagonal && abs(Normal.y) < 0.5) {
        int corner = gl_VertexID % 4;
        if (corner != 0 && corner != 3) return vec3(0.0);           // качаются только верхушки
        return d * (0.06 * ArcWave.x);
    }
    if (!tinted || ArcWave.y <= 0.0) return vec3(0.0);
    // листва и лианы: лёгкое движение в плоскости грани (грани не отрываются от блоков)
    vec3 dd = (d + vec3(0.0, sin(t * 2.1 + ph) * 0.6, 0.0)) * (0.025 * ArcWave.y);
    return dd - Normal * dot(dd, Normal);
}

void main() {
    vec3 pos = Position + ChunkOffset + arcWave(Position);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(ModelViewMat, pos, FogShape);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
}
