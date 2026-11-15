#version 120
// 主菜单月亮：被光照亮的球体。surface = 1 时带月面（月海、几个大环形山、细颗粒、临边昏暗，
// 表面随天平动轻微摆动），并用小坑高度场扰动法线，交界线附近有凹凸的明暗；
// occlude = 1 时整个圆盘不透明（暗面 = 天色 + 一点地照，挡住后面的星星）。
// surface = 0、occlude = 0 时就是一个带抗锯齿的光照圆点（菜单项的小点）。
uniform vec2 center;
uniform float radius, earth, aa, alpha, surface, occlude, time;
uniform vec3 light, tint, base;

float hash3(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x), mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
        mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x), mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y),
        f.z);
}

float fbm3(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise3(p);
        p = p * 2.1 + 3.7;
        a *= 0.5;
    }
    return v;
}

// 环形山：c 为球面上的方向，s 为角半径；返回反照率增量（坑底略暗、坑沿一圈亮）
float crater(vec3 n, vec3 c, float s) {
    float d = acos(clamp(dot(n, normalize(c)), -1.0, 1.0)) / s;
    float floorDark = -0.08 * (1.0 - smoothstep(0.55, 0.95, d));
    float rim = 0.045 * exp(-pow((d - 1.0) * 4.0, 2.0));
    return floorDark + rim;
}

// 小环形山高度场：每个网格单元最多一个坑（限制在格子中心区域，相邻格子不会互相穿插），
// 坑底是抛物面凹陷、坑沿一圈隆起。p 为月面二维坐标，返回高度（大约 -1..0.3）
float craterField(vec2 p, float seed) {
    vec2 id = floor(p);
    vec2 f = fract(p);
    float h = hash3(vec3(id, seed));
    if (h > 0.55) {
        return 0.0;
    }
    vec2 c = 0.3 + 0.4 * vec2(hash3(vec3(id, seed + 3.1)), hash3(vec3(id, seed + 7.3)));
    float rr = 0.12 + 0.16 * hash3(vec3(id, seed + 11.7));
    float d = length(f - c) / rr;
    float bowl = (d * d - 1.0) * (1.0 - step(1.0, d));
    float rim = 0.3 * exp(-pow((d - 1.0) * 4.0, 2.0));
    return bowl + rim;
}

// 凹凸高度：两层小坑 + 细颗粒；w1 / w2 按月亮像素大小淡入，月亮小时不画最细的层（避免闪烁）
float relief(vec2 q, float w1, float w2) {
    return craterField(q * 7.0, 1.0) * 0.6 * w1 + craterField(q * 15.0, 9.0) * 0.35 * w2 + (fbm3(vec3(q * 30.0, 0.5)) - 0.5) * 0.25 * w1;
}

void main() {
    vec2 d = (gl_FragCoord.xy - center) / radius;
    float r = length(d);
    float disk = clamp(0.5 - (r - 1.0) * radius / aa, 0.0, 1.0);
    vec3 n = vec3(d, sqrt(max(0.0, 1.0 - r * r)));
    vec3 albedo = tint;
    vec3 shadeN = n;
    if (surface > 0.5) {
        // 天平动：表面绕两个轴各自缓慢来回摆几度
        float ax = 0.07 * sin(time * 0.11);
        float ay = 0.06 * sin(time * 0.083 + 1.3);
        vec3 m = n;
        m = vec3(m.x * cos(ax) + m.z * sin(ax), m.y, -m.x * sin(ax) + m.z * cos(ax));
        m = vec3(m.x, m.y * cos(ay) - m.z * sin(ay), m.y * sin(ay) + m.z * cos(ay));
        float maria = smoothstep(0.48, 0.66, fbm3(m * 2.2 + vec3(4.0, 1.0, 7.0)));
        float a = 1.0 - 0.24 * maria;
        a += crater(m, vec3(-0.62, -0.38, 0.69), 0.15);
        a += crater(m, vec3(-0.45, -0.70, 0.55), 0.09);
        a += crater(m, vec3(-0.80, -0.12, 0.58), 0.07);
        a += crater(m, vec3(-0.25, -0.50, 0.83), 0.06);
        a += crater(m, vec3(0.35, 0.40, 0.85), 0.13);
        a += crater(m, vec3(0.60, -0.20, 0.78), 0.08);
        a += (fbm3(m * 14.0) - 0.5) * 0.12;
        // 临边昏暗：边缘略暗，球体感更强
        a *= mix(0.78, 1.0, pow(n.z, 0.35));
        albedo = tint * clamp(a, 0.0, 1.1);

        // 凹凸：小坑高度场按有限差分求梯度（逐像素，平滑；屏幕导数是 2×2 一块，会出马赛克）
        vec2 q = m.xy / max(m.z + 1.0, 0.35);
        float w1 = smoothstep(40.0, 90.0, radius);
        float w2 = smoothstep(90.0, 200.0, radius);
        if (w1 > 0.0) {
            float e = 1.0 / radius;
            float h0 = relief(q, w1, w2);
            vec2 g = vec2(relief(q + vec2(e, 0.0), w1, w2) - h0, relief(q + vec2(0.0, e), w1, w2) - h0) / e;
            shadeN = normalize(n - vec3(g * 0.006, 0.0) * smoothstep(0.98, 0.6, r));
        }
    }

    // 交界线：用扰动后的法线，交界附近的坑一边亮一边暗；柔化宽度按像素算，月亮越大越锐利
    float w = clamp(3.5 / radius, 0.004, 0.035);
    float ndl = dot(shadeN, light);
    // 交界线主要由球面决定，凹凸只让它略微起伏，不会碎成一块块
    float lit = smoothstep(-w, w, mix(dot(n, light), ndl, 0.45));
    // 亮面上很轻的朝光明暗：靠近交界处的坑壁有立体感，正对太阳的地方保持月面"平"的观感
    albedo *= mix(1.0, clamp(0.7 + 1.6 * ndl, 0.72, 1.06), surface * (1.0 - smoothstep(0.12, 0.45, dot(n, light))));

    // 暗面：地照带着月面纹理，再加一圈很细的冷色临边（峰值收在边缘内侧，不与抗锯齿边重叠，避免锯齿）
    vec3 dark = mix(base, albedo * vec3(0.78, 0.84, 1.0), earth);
    float limb = smoothstep(0.84, 0.97, r) * (1.0 - smoothstep(0.97, 1.0, r));
    dark += vec3(0.5, 0.58, 0.85) * limb * 0.055 * surface;
    vec3 c = mix(dark, albedo, lit);
    float coverage = occlude > 0.5 ? 1.0 : mix(earth, 1.0, lit);
    // 非遮挡模式里暗面是半透明的 tint，颜色用 tint 本身
    vec3 outC = occlude > 0.5 ? c : mix(tint, albedo, lit);
    gl_FragColor = vec4(outC, alpha * disk * coverage);
}
