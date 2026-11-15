#version 120

uniform vec2 u_size;
uniform float u_radius;
uniform vec4 u_color;
uniform float u_scale;

void main(void)
{
    // 局部像素坐标：p ∈ [-size/2, +size/2]，原点在矩形中心
    vec2 half2 = u_size * 0.5;
    vec2 p = (gl_TexCoord[0].st - 0.5) * u_size;

    // 半径不超过短边一半
    float r = min(u_radius, min(half2.x, half2.y));

    // 圆角盒 SDF（q 为角部圆心相对位置）
    vec2 q = abs(p) - (half2 - r);
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;

    // 抗锯齿宽度 ≈ 1.5 实际像素换算到局部 GUI 坐标（u_scale = GUI 缩放倍数）
    float aa = 1.5 / u_scale;
    float coverage = 1.0 - smoothstep(-aa, aa, d);

    gl_FragColor = vec4(u_color.rgb, u_color.a * coverage);
}
