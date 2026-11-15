#version 120
// 主菜单夜空：深蓝渐变 + 一条很淡的银河 + 三层星星（大多数暗、少数亮，色温有冷有暖，缓慢闪烁）。
// 月亮附近有一圈随亮面大小变化的月晕和偏向亮面的泛光，星星在月光里变暗。坐标均为帧缓冲像素。
uniform vec2 res;
uniform float time, scale;
uniform vec2 drift;
// 镜头平移（帧缓冲像素）：远处的银河和暗星移动得少，近处的亮星移动得多，形成视差
uniform vec2 pan;
uniform vec2 moonC;
uniform float moonR, moonLit;
// 太阳方向（视空间，y 向上）：泛光偏向月亮被照亮的一侧
uniform vec3 moonLight;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.03 + vec2(17.1, 9.2);
        a *= 0.5;
    }
    return v;
}

// 一层星：每个网格最多一颗，位置/亮度/色温/闪烁相位都由格子哈希决定
vec3 stars(vec2 p, float cell, float density, float size, float bright, float seed) {
    vec2 g = p / cell;
    vec2 id = floor(g);
    vec3 col = vec3(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = id + vec2(float(x), float(y));
            float h = hash(c + seed);
            if (h < density) {
                vec2 pos = c + vec2(hash(c + seed + 3.1), hash(c + seed + 7.7));
                float d = length(g - pos) * cell;
                float mag = pow(hash(c + seed + 11.3), 3.0);
                float core = exp(-d * d / (size * size * (0.35 + mag)));
                float tw = 0.75 + 0.25 * sin(time * (0.6 + 1.8 * hash(c + seed + 5.5)) + h * 40.0);
                float t = hash(c + seed + 13.9);
                vec3 tint = t < 0.25 ? vec3(0.72, 0.82, 1.0) : (t > 0.85 ? vec3(1.0, 0.86, 0.7) : vec3(1.0));
                col += tint * core * (0.25 + 0.75 * mag) * tw * bright;
            }
        }
    }
    return col;
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 uv = frag / res;
    // 天空：顶部深靛蓝，往下略亮略偏紫，像地平线方向还有一点余光
    vec3 sky = mix(vec3(0.075, 0.098, 0.20), vec3(0.039, 0.047, 0.11), uv.y);
    sky += vec3(0.05, 0.035, 0.08) * pow(1.0 - uv.y, 3.0);

    vec2 p = (frag + drift + pan * 0.35) / scale;
    // 银河：沿对角线的一条宽带，用低频噪声扰动边缘，里面是更密的暗星和暗尘
    vec2 q = (frag + pan * 0.2) / res.y;
    float axis = dot(q - vec2(0.9, 0.5), normalize(vec2(0.82, -0.57)));
    float band = exp(-axis * axis * 9.0) * (0.55 + 0.9 * fbm(p * 0.012));
    float dust = smoothstep(0.45, 0.75, fbm(p * 0.03 + 4.0));
    sky += vec3(0.11, 0.12, 0.2) * band * (1.0 - 0.6 * dust) * 0.55;

    vec3 col = stars(p, 7.0, 0.35, 0.55, 0.45, 1.0) * (0.35 + 1.4 * band);
    col += stars((frag + drift + pan * 0.6) / scale, 26.0, 0.55, 0.75, 0.75, 17.0);
    col += stars((frag + drift + pan) / scale, 90.0, 0.5, 1.05, 1.25, 31.0);

    // 月光：亮面越大，月晕越亮、周围的星越淡
    float md = max(0.0, length(frag - moonC) - moonR) / moonR;
    float glow = exp(-md * 1.6) * (0.25 + 0.75 * moonLit);
    col *= 1.0 - 0.75 * glow;
    sky += vec3(0.62, 0.68, 0.9) * glow * 0.075;
    // 泛光：贴着月亮边缘的一圈紧致光晕 + 一层更宽的柔光，都偏向被照亮的那一侧（暗面一侧几乎没有）
    vec2 dir = (frag - moonC) / max(length(frag - moonC), 0.001);
    float side = clamp(dot(dir, normalize(moonLight.xy + vec2(1e-4))) * length(moonLight.xy) + max(moonLight.z, 0.0), 0.0, 1.0);
    float rim = exp(-md * 9.0) * 0.55 + exp(-md * 3.2) * 0.22;
    float bloom = rim * (0.12 + 0.88 * side) * (0.3 + 0.7 * moonLit);
    sky += vec3(0.86, 0.9, 1.0) * bloom * 0.16;
    col *= 1.0 - 0.6 * bloom;

    gl_FragColor = vec4(sky + col, 1.0);
}
