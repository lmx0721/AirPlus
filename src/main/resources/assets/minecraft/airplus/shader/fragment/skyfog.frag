#version 120

// 地平线雾化带：把菜单 Shader 天空在地平线附近淡入地形雾色，消除硬接缝。
// 像素世界仰角 = atan((2*fragY/res.y - 1) * tanHalfFovY) - pitch
//（gl_FragCoord.y 原点在屏幕底部；pitch 向下为正）。
// fade：仰角 <= -0.05 rad 全雾色（地平线以下被地形覆盖，兜底 void 区域），
//       >= +0.15 rad 全透（天空预设完全可见）。
uniform vec2 u_resolution;
uniform float u_pitch;
uniform float u_tanHalf;
uniform vec3 u_fogColor;
uniform float u_strength;

void main() {
    float elevation = atan((2.0 * gl_FragCoord.y / u_resolution.y - 1.0) * u_tanHalf) - u_pitch;
    float fade = (1.0 - smoothstep(-0.05, 0.15, elevation)) * u_strength;
    gl_FragColor = vec4(u_fogColor, fade);
}
