#version 120
// 菜单云层：画在月亮之后、界面之前的一层半透明薄云，两层噪声以不同速度缓慢飘动，形状也在慢慢变化。
// 靠近月亮的云被月光照成银白色（越靠近被照亮的一侧越亮），远处的云是暗淡的蓝灰色。
// 云量上薄下厚，标题附近基本是晴空。坐标均为帧缓冲像素。
uniform vec2 res;
uniform float time, scale;
uniform vec2 pan;
uniform vec2 moonC;
uniform float moonR, moonLit;
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
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.07 + vec2(13.7, 5.1);
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 uv = frag / res;
    // 云比星星近，视差更大；GUI 像素空间，缩放无关
    vec2 p = (frag + pan * 1.4) / scale;
    vec2 a = p * vec2(0.0045, 0.009) + vec2(time * 0.006, time * 0.0015);
    vec2 b = p * vec2(0.008, 0.016) + vec2(time * 0.011, -time * 0.002);
    // 轻微的域扭曲让云边更像被风拉开的
    vec2 warp = vec2(fbm(a * 1.7 + 3.0), fbm(a * 1.7 + 8.0)) - 0.5;
    float n = fbm(a + warp * 0.6) * 0.65 + fbm(b + warp * 0.3) * 0.35;
    float cover = mix(0.52, 0.38, smoothstep(0.85, 0.2, uv.y));
    float dens = smoothstep(cover, cover + 0.2, n);
    if (dens <= 0.001) {
        discard;
    }

    // 月光照在云上：离月亮越近越亮，偏向月亮被照亮的一侧
    vec2 rel = frag - moonC;
    float md = max(0.0, length(rel) - moonR * 0.6) / max(moonR, 1.0);
    vec2 dir = rel / max(length(rel), 0.001);
    float side = clamp(dot(dir, normalize(moonLight.xy + vec2(1e-4))) * length(moonLight.xy) + max(moonLight.z, 0.0), 0.0, 1.0);
    float lit = exp(-md * 0.9) * (0.3 + 0.7 * moonLit) * (0.35 + 0.65 * side);
    vec3 col = mix(vec3(0.22, 0.25, 0.38), vec3(0.88, 0.9, 0.97), clamp(lit * 1.3, 0.0, 1.0));
    // 云团内部略暗、边缘略亮（被月光勾出来的银边）
    float edge = 1.0 - smoothstep(0.0, 0.6, dens);
    col += vec3(0.25, 0.27, 0.32) * edge * lit;
    gl_FragColor = vec4(col, dens * (0.34 + 0.3 * lit));
}
