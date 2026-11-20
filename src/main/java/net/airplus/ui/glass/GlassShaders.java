package net.airplus.ui.glass;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/** GLSL 120 programs shared by every glass surface. Compiled lazily on the render thread. */
public final class GlassShaders {
    private GlassShaders() { }

    /** A linked program, or an unusable one ({@link #ok} false) when the driver rejected it. */
    public static final class Program {
        public final int id;
        public final boolean ok;
        private final Map<String, Integer> uniforms = new HashMap<>();

        Program(String name, String vertex, String fragment) {
            int program = 0;
            boolean linked = false;
            try {
                int vs = compile(name, GL20.GL_VERTEX_SHADER, vertex);
                int fs = compile(name, GL20.GL_FRAGMENT_SHADER, fragment);
                if (vs != 0 && fs != 0) {
                    program = GL20.glCreateProgram();
                    GL20.glAttachShader(program, vs);
                    GL20.glAttachShader(program, fs);
                    GL20.glLinkProgram(program);
                    linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0;
                    if (!linked) net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().error("[Glass] {} link failed: {}", name, GL20.glGetProgramInfoLog(program, 2048));
                }
                if (vs != 0) GL20.glDeleteShader(vs);
                if (fs != 0) GL20.glDeleteShader(fs);
            } catch (RuntimeException e) {
                net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().error("[Glass] {} unavailable", name, e);
            }
            this.id = program;
            this.ok = linked;
        }

        private static int compile(String name, int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().error("[Glass] {} compile failed: {}", name, GL20.glGetShaderInfoLog(shader, 2048));
                GL20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        public int loc(String uniform) {
            Integer cached = uniforms.get(uniform);
            if (cached == null) {
                cached = GL20.glGetUniformLocation(id, uniform);
                uniforms.put(uniform, cached);
            }
            return cached;
        }
    }

    private static final String SHAPE_VERTEX =
            "#version 120\n" +
            "void main() {\n" +
            "  gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
            "  gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n" +
            "}";

    /**
     * Signed-distance rounded rectangle. gl_TexCoord[0].xy is the position relative to the
     * rectangle centre, in the same units as the vertices.
     */
    private static final String SHAPE_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 halfSize;\n" +
            "uniform vec4 radii;\n" +      // tl, tr, br, bl
            "uniform float aa;\n" +        // one pixel, in units
            "uniform float soft;\n" +      // > 0: soft shadow / glow falloff distance
            "uniform float stroke;\n" +    // > 0: outline thickness
            "uniform vec4 colA;\n" +
            "uniform vec4 colB;\n" +
            "uniform vec2 grad;\n" +       // gradient direction, (0,0) = solid colA
            "uniform vec3 spot;\n" +       // x, y, radius of a radial mask; radius <= 0 = off
            "uniform vec2 band;\n" +       // position, width of a diagonal sheen; width <= 0 = off
            "uniform float texMode;\n" +   // 1 = sample the blurred screen
            "uniform sampler2D tex;\n" +
            "uniform vec2 screen;\n" +
            "float sdf(vec2 p) {\n" +
            "  float r = p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y) : (p.y > 0.0 ? radii.w : radii.x);\n" +
            "  vec2 q = abs(p) - halfSize + r;\n" +
            "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 p = gl_TexCoord[0].xy;\n" +
            "  float d = sdf(p);\n" +
            "  float a;\n" +
            "  if (soft > 0.0) { a = 1.0 - smoothstep(-soft * 0.35, soft, d); a *= a; }\n" +
            "  else {\n" +
            "    if (stroke > 0.0) d = abs(d + stroke * 0.5) - stroke * 0.5;\n" +
            "    float px = length(vec2(dFdx(d), dFdy(d)));\n" +
            "    a = clamp(0.5 - d / max(px > 0.0 ? px : aa, 0.00001), 0.0, 1.0);\n" +
            "  }\n" +
            "  vec4 c = colA;\n" +
            "  if (grad.x != 0.0 || grad.y != 0.0) {\n" +
            "    float t = dot(p, grad) / max(dot(abs(grad), halfSize), 0.0001) * 0.5 + 0.5;\n" +
            "    c = mix(colA, colB, clamp(t, 0.0, 1.0));\n" +
            "  }\n" +
            "  if (texMode > 0.5) {\n" +
            "    vec3 s = texture2D(tex, gl_FragCoord.xy / screen).rgb;\n" +
            "    float l = dot(s, vec3(0.299, 0.587, 0.114));\n" +
            "    s = mix(vec3(l), s, 1.22);\n" +
            "    c = vec4(clamp(s, 0.0, 1.0), c.a);\n" +
            "  }\n" +
            "  if (spot.z > 0.0) { float f = clamp(1.0 - length(p - spot.xy) / spot.z, 0.0, 1.0); a *= f * f; }\n" +
            "  if (band.y > 0.0) { float f = (p.x + p.y * 0.6 - band.x) / band.y; a *= exp(-f * f); }\n" +
            "  gl_FragColor = vec4(c.rgb, c.a * a);\n" +
            "}";

    /**
     * Liquid glass. Normal mode samples the blurred screen through a lens that bends the
     * backdrop along the rounded edge, tints it and adds a directional rim light. Snapshot
     * mode (snap.z > 0) instead shows a cut-out of a full-screen texture taken earlier.
     */
    private static final String GLASS_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 halfSize;\n" +
            "uniform vec4 radii;\n" +      // tl, tr, br, bl
            "uniform float aa;\n" +        // one pixel, in units
            "uniform vec4 tintA;\n" +      // top tint rgb + coverage
            "uniform vec4 tintB;\n" +      // bottom tint
            "uniform float opacity;\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 screen;\n" +
            "uniform vec4 lens;\n" +       // x refraction (pixels), y bevel width (units), z dispersion, w saturation
            "uniform vec4 rim;\n" +        // x main light, y second light, z inner shadow, w grain (1/255)
            "uniform vec3 snap;\n" +       // z > 0: snapshot mode; xy = original centre (window pixels, y up), z = pixels per unit
            "float cornerRadius(vec2 p) {\n" +
            "  return p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y) : (p.y > 0.0 ? radii.w : radii.x);\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 p = gl_TexCoord[0].xy;\n" +
            "  float r = cornerRadius(p);\n" +
            "  vec2 q = abs(p) - halfSize + r;\n" +
            "  float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "  float px = length(vec2(dFdx(d), dFdy(d)));\n" +
            "  float a = clamp(0.5 - d / max(px > 0.0 ? px : aa, 0.00001), 0.0, 1.0);\n" +
            "  vec3 s;\n" +
            "  if (snap.z > 0.0) {\n" +
            "    s = texture2D(tex, vec2(snap.x + p.x * snap.z, snap.y - p.y * snap.z) / screen).rgb;\n" +
            "  } else {\n" +
            "    vec2 uv = gl_FragCoord.xy / screen;\n" +
            "    float depth = -d;\n" +
            "    float bevel = max(lens.y, 0.0001);\n" +
            "    float e = clamp(1.0 - depth / bevel, 0.0, 1.0);\n" +
            "    float rr = max(r, bevel);\n" +
            "    vec2 q2 = abs(p) - halfSize + rr;\n" +
            "    vec2 n = sign(p) * normalize(max(q2, vec2(0.0)) + vec2(0.0001));\n" +
            "    vec2 off = n * (e * e) * lens.x / screen;\n" +
            "    off.y = -off.y;\n" +
            "    if (lens.z > 0.0) {\n" +
            "      s.r = texture2D(tex, uv - off * (1.0 + lens.z)).r;\n" +
            "      s.g = texture2D(tex, uv - off).g;\n" +
            "      s.b = texture2D(tex, uv - off * (1.0 - lens.z)).b;\n" +
            "    } else {\n" +
            "      s = texture2D(tex, uv - off).rgb;\n" +
            "    }\n" +
            "    float l = dot(s, vec3(0.299, 0.587, 0.114));\n" +
            "    s = mix(vec3(l), s, lens.w);\n" +
            "    float v = clamp(p.y / max(halfSize.y, 0.0001) * 0.5 + 0.5, 0.0, 1.0);\n" +
            "    vec4 t = mix(tintA, tintB, v);\n" +
            "    s = mix(s, t.rgb, t.a);\n" +
            "    s = mix(s, vec3(1.0), 0.094 * (1.0 - v));\n" +
            "    float ndl = dot(n, vec2(-0.5547, -0.8321));\n" +
            "    float band = e * e;\n" +
            "    float lineW = max(aa * 1.25, 0.6);\n" +
            "    float line = clamp(1.0 - depth / lineW, 0.0, 1.0);\n" +
            "    s *= 1.0 - rim.z * band * (0.35 + 0.65 * max(-ndl, 0.0)) * (1.0 - line);\n" +
            "    float hi = band * rim.x * pow(max(ndl, 0.0), 1.5)\n" +
            "             + line * (0.10 + 0.34 * max(ndl, 0.0) + rim.y * max(-ndl, 0.0));\n" +
            "    s = mix(s, vec3(1.0), clamp(hi, 0.0, 1.0));\n" +
            "    float nz = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);\n" +
            "    s += (nz - 0.5) * rim.w / 255.0;\n" +
            "  }\n" +
            "  gl_FragColor = vec4(clamp(s, 0.0, 1.0), a * opacity);\n" +
            "}";

    /**
     * A texture region (player head, server icon) inside a rounded rectangle. The region is
     * uv.xy (top-left) .. uv.zw (bottom-right) and is clamped, so texels outside it (the rest
     * of a skin) never bleed in at the antialiased edge.
     */
    private static final String IMAGE_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 halfSize;\n" +
            "uniform vec4 radii;\n" +      // tl, tr, br, bl
            "uniform float aa;\n" +
            "uniform vec4 uv;\n" +
            "uniform vec4 tint;\n" +       // multiplied in, alpha already includes the current fade
            "uniform sampler2D tex;\n" +
            "void main() {\n" +
            "  vec2 p = gl_TexCoord[0].xy;\n" +
            "  float r = p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y) : (p.y > 0.0 ? radii.w : radii.x);\n" +
            "  vec2 q = abs(p) - halfSize + r;\n" +
            "  float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "  float px = length(vec2(dFdx(d), dFdy(d)));\n" +
            "  float a = clamp(0.5 - d / max(px > 0.0 ? px : aa, 0.00001), 0.0, 1.0);\n" +
            "  vec2 f = clamp(p / max(halfSize, vec2(0.0001)) * 0.5 + 0.5, 0.0, 1.0);\n" +
            "  vec2 lo = min(uv.xy, uv.zw), hi = max(uv.xy, uv.zw);\n" +
            "  vec2 st = clamp(mix(uv.xy, uv.zw, f), lo + 0.00001, hi - 0.00001);\n" +
            "  vec4 c = texture2D(tex, st) * tint;\n" +
            "  gl_FragColor = vec4(c.rgb, c.a * a);\n" +
            "}";

    private static final String PASS_VERTEX =
            "#version 120\n" +
            "void main() {\n" +
            "  gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
            "  gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n" +
            "}";

    private static final String DOWN_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv) * 4.0;\n" +
            "  sum += texture2D(tex, uv - o);\n" +
            "  sum += texture2D(tex, uv + o);\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y));\n" +
            "  sum += texture2D(tex, uv - vec2(o.x, -o.y));\n" +
            "  gl_FragColor = vec4(sum.rgb / 8.0, 1.0);\n" +
            "}";

    private static final String UP_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv + vec2(-o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, -o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, -o.y)) * 2.0;\n" +
            "  gl_FragColor = vec4(sum.rgb / 12.0, 1.0);\n" +
            "}";

    private static final String COPY_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "void main() { gl_FragColor = vec4(texture2D(tex, gl_TexCoord[0].st).rgb, 1.0); }";

    // The same two kernels for a texture whose alpha matters (WorldGlow's silhouette mask).
    private static final String DOWN_ALPHA_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv) * 4.0;\n" +
            "  sum += texture2D(tex, uv - o);\n" +
            "  sum += texture2D(tex, uv + o);\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y));\n" +
            "  sum += texture2D(tex, uv - vec2(o.x, -o.y));\n" +
            "  gl_FragColor = sum / 8.0;\n" +
            "}";

    private static final String UP_ALPHA_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec2 halfpixel;\n" +
            "uniform float offset;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec2 o = halfpixel * offset;\n" +
            "  vec4 sum = texture2D(tex, uv + vec2(-o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(o.x * 2.0, 0.0));\n" +
            "  sum += texture2D(tex, uv + vec2(o.x, -o.y)) * 2.0;\n" +
            "  sum += texture2D(tex, uv + vec2(0.0, -o.y * 2.0));\n" +
            "  sum += texture2D(tex, uv + vec2(-o.x, -o.y)) * 2.0;\n" +
            "  gl_FragColor = sum / 12.0;\n" +
            "}";

    /**
     * Puts WorldGlow's result on screen: the blurred mask as a halo outside the silhouettes,
     * plus a faint tint inside them. Both textures hold premultiplied colour; the output is
     * premultiplied too, with its alpha cut so the halo adds light more than it covers.
     */
    private static final String GLOW_FRAGMENT =
            "#version 120\n" +
            "uniform sampler2D blurTex;\n" +
            "uniform sampler2D maskTex;\n" +
            "uniform float strength;\n" +
            "uniform float fill;\n" +
            "void main() {\n" +
            "  vec2 uv = gl_TexCoord[0].st;\n" +
            "  vec4 g = texture2D(blurTex, uv);\n" +
            "  vec4 m = texture2D(maskTex, uv);\n" +
            "  vec3 haloColor = g.a > 0.001 ? g.rgb / g.a : vec3(0.0);\n" +
            "  vec3 innerColor = m.a > 0.001 ? m.rgb / m.a : vec3(0.0);\n" +
            "  float halo = clamp(g.a * strength, 0.0, 1.0);\n" +
            "  halo = halo * halo * (3.0 - 2.0 * halo) * (1.0 - m.a);\n" +
            "  float inner = m.a * fill;\n" +
            "  vec3 c = haloColor * halo + innerColor * inner;\n" +
            "  gl_FragColor = vec4(c, (halo + inner) * 0.55);\n" +
            "}";


    /** Hash and value noise shared by the backdrop scenes (no sin(): steady on every driver). */
    private static final String NOISE =
            "float hash(vec2 p) {\n" +
            "  vec3 q = fract(vec3(p.xyx) * 0.1031);\n" +
            "  q += dot(q, q.yzx + 33.33);\n" +
            "  return fract((q.x + q.y) * q.z);\n" +
            "}\n" +
            "float noise(vec2 p) {\n" +
            "  vec2 i = floor(p), f = fract(p);\n" +
            "  vec2 u = f * f * (3.0 - 2.0 * f);\n" +
            "  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);\n" +
            "}\n";

    /**
     * AURORA: soft lights in the palette colours drifting and melting into each other over a
     * near-black ground, warped by layered noise; a faint diagonal sheen, vignette and grain.
     * Uniforms: res (pixels), time (seconds), c1..c3 (palette), mouse (-1..1, eased).
     */
    private static final String AURORA_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 res;\n" +
            "uniform float time;\n" +
            "uniform vec3 c1, c2, c3;\n" +
            "uniform vec2 mouse;\n" +
            NOISE +
            "float fbm3(vec2 p) {\n" +
            "  float v = 0.0, a = 0.5;\n" +
            "  for (int i = 0; i < 3; i++) { v += a * noise(p); p = p * 2.02 + vec2(1.7, 9.2); a *= 0.5; }\n" +
            "  return v / 0.875;\n" +
            "}\n" +
            "float blob(vec2 p, vec2 c, float k) { vec2 d = p - c; return exp(-dot(d, d) * k); }\n" +
            "void main() {\n" +
            "  vec2 frag = gl_FragCoord.xy;\n" +
            "  float aspect = res.x / res.y;\n" +
            "  vec2 p = vec2((frag.x / res.x - 0.5) * aspect, frag.y / res.y - 0.5) + mouse * 0.012;\n" +
            "  float t = time * 0.045;\n" +
            "  vec2 q = vec2(fbm3(p * 1.3 + vec2(0.0, t)), fbm3(p * 1.3 + vec2(5.2, -t * 0.8)));\n" +
            "  vec2 r = vec2(fbm3(p * 1.6 + 2.4 * q + vec2(1.7, 9.2) + t * 0.7), fbm3(p * 1.6 + 2.4 * q + vec2(8.3, 2.8) - t * 0.6));\n" +
            "  float f = fbm3(p * 1.1 + 2.0 * r);\n" +
            "  vec2 w = p + (r - 0.5) * 0.35;\n" +
            "  float b1 = blob(w, vec2(-0.42 * aspect + 0.10 * sin(t * 2.3), 0.20 + 0.08 * cos(t * 1.9)), 4.4);\n" +
            "  float b2 = blob(w, vec2(0.38 * aspect + 0.12 * cos(t * 1.6), -0.18 + 0.10 * sin(t * 2.1)), 3.8);\n" +
            "  float b3 = blob(w, vec2(0.05 + 0.18 * sin(t * 1.2), 0.42 + 0.06 * sin(t * 2.7)), 4.2);\n" +
            "  float b4 = blob(w, vec2(-0.10 * aspect + 0.14 * cos(t * 1.4), -0.40), 5.0);\n" +
            "  vec3 col = mix(vec3(0.012, 0.014, 0.024), vec3(0.022, 0.026, 0.042), frag.y / res.y);\n" +
            "  float lift = 0.30 + 0.85 * f * f;\n" +
            "  col += c1 * b1 * lift * 0.72;\n" +
            "  col += c2 * b2 * lift * 0.66;\n" +
            "  col += c3 * b3 * lift * 0.50;\n" +
            "  col += mix(c2, c1, 0.5) * b4 * lift * 0.30;\n" +
            "  float ribbon = smoothstep(0.55, 0.95, f) * (b1 + b2 + b3);\n" +
            "  col += mix(c1, c3, clamp(r.x, 0.0, 1.0)) * ribbon * 0.18;\n" +
            "  float band = p.x * 0.55 + p.y + 0.12 * sin(t * 2.6);\n" +
            "  col += vec3(0.85, 0.9, 1.0) * exp(-band * band * 9.0) * 0.022;\n" +
            "  vec2 v = vec2(p.x / aspect, p.y) * 1.55;\n" +
            "  col *= mix(0.62, 1.0, smoothstep(1.1, 0.2, length(v)));\n" +
            "  col += (hash(frag + fract(time * 7.13) * 397.0) - 0.5) * 0.016;\n" +
            "  gl_FragColor = vec4(max(col, vec3(0.0)), 1.0);\n" +
            "}";

    /**
     * NIGHT: an original blocky night scene. The picture is built on a grid of square blocks
     * (about screen height / 110 pixels): a sky fading to a palette-coloured glow at the horizon,
     * twinkling pixel stars, a square moon, drifting block clouds, and four ridges of stepped hills
     * with fog between them, the nearer ones moving more with the pointer; a few warm lights.
     */
    private static final String NIGHT_FRAGMENT =
            "#version 120\n" +
            "uniform vec2 res;\n" +
            "uniform float time;\n" +
            "uniform vec3 c1, c2, c3;\n" +
            "uniform vec2 mouse;\n" +
            NOISE +
            "float n1(float x) { float i = floor(x), f = fract(x); return mix(hash(vec2(i, 3.1)), hash(vec2(i + 1.0, 3.1)), f * f * (3.0 - 2.0 * f)); }\n" +
            "float ridge(float x, float seed) { return n1(x + seed) * 0.62 + n1(x * 2.1 + seed * 1.7) * 0.28 + n1(x * 4.3 + seed * 2.3) * 0.10; }\n" +
            "void main() {\n" +
            "  float B = max(3.0, floor(res.y / 110.0));\n" +
            "  vec2 frag = gl_FragCoord.xy;\n" +
            "  vec2 grid = res / B;\n" +
            "  vec2 cell = floor(frag / B);\n" +
            "  float h = (cell.y + 0.5) / grid.y;\n" +
            "  vec3 horizon = mix(vec3(0.10, 0.10, 0.20), c1, 0.5) * 0.78;\n" +
            "  vec3 sky = mix(horizon, vec3(0.035, 0.050, 0.120), smoothstep(0.08, 0.48, h));\n" +
            "  sky = mix(sky, vec3(0.012, 0.018, 0.048), smoothstep(0.48, 1.0, h));\n" +
            "  vec3 col = sky;\n" +
            // stars, a little parallax
            "  vec2 sc = floor((frag + mouse * B * 1.5) / B);\n" +
            "  float s = hash(sc * 1.37 + 11.0);\n" +
            "  if (s > 0.986) {\n" +
            "    float tw = 0.55 + 0.45 * sin(time * (1.2 + (s - 0.986) * 300.0) + s * 300.0);\n" +
            "    col += mix(vec3(0.75, 0.82, 1.0), c3, 0.25) * tw * smoothstep(0.32, 0.75, h) * (s > 0.996 ? 0.95 : 0.5);\n" +
            "  }\n" +
            // the moon: a square with a softer square halo
            "  vec2 moon = floor(vec2(grid.x * 0.78, grid.y * 0.76) + mouse * 2.0);\n" +
            "  vec2 dm = abs(cell - moon);\n" +
            "  float md = max(dm.x, dm.y);\n" +
            "  col += vec3(0.55, 0.62, 0.85) * 0.10 * exp(-md * 0.22);\n" +
            "  if (md < 3.5) col = mix(vec3(0.86, 0.89, 0.95), vec3(0.70, 0.74, 0.84), step(0.5, hash(cell + 7.0)) * 0.35 * step(md, 2.5));\n" +
            // clouds: coarse noise on the block grid, drifting
            "  vec2 cc = floor((frag + vec2(time * B * 0.9, 0.0) + mouse * B * 3.0) / B);\n" +
            "  float cn = noise(cc / vec2(11.0, 4.5) + vec2(3.0, 1.0)) * 0.75 + noise(cc / vec2(5.0, 2.5) + 9.0) * 0.25;\n" +
            "  float cloud = step(0.64, cn) * smoothstep(0.52, 0.64, h) * (1.0 - smoothstep(0.86, 0.94, h));\n" +
            "  col = mix(col, mix(vec3(0.20, 0.23, 0.34), horizon * 1.2, 0.3), cloud * 0.45);\n" +
            // hills, far to near
            "  for (int i = 0; i < 4; i++) {\n" +
            "    float k = float(i);\n" +
            "    float shift = mouse.x * B * (1.5 + k * 3.5) + time * B * (0.06 + k * 0.10);\n" +
            "    float hx = floor((frag.x + shift) / B);\n" +
            "    float scale = 34.0 - k * 7.0;\n" +
            "    float base = grid.y * (0.40 - k * 0.075) + mouse.y * (k * 1.2);\n" +
            "    float amp = grid.y * (0.10 + k * 0.035);\n" +
            "    float top = floor(base + amp * (ridge(hx / scale, k * 17.0) - 0.5));\n" +
            "    if (cell.y < top) {\n" +
            "      float depth = k / 3.0;\n" +
            "      vec3 land = mix(mix(horizon, vec3(0.06, 0.08, 0.15), 0.55), vec3(0.010, 0.013, 0.024), depth);\n" +
            "      float below = top - cell.y;\n" +
            "      if (below <= 1.0) land += mix(c1 * 0.10, vec3(0.08, 0.10, 0.16), depth);\n" +
            "      land = mix(land, horizon * 0.8, (1.0 - depth) * 0.25 * (1.0 - smoothstep(0.0, 14.0, below)));\n" +
            "      if (i == 3 && below > 3.0) {\n" +
            "        float l = hash(vec2(hx, cell.y) + k * 31.0);\n" +
            "        if (l > 0.9965) land = mix(land, vec3(1.0, 0.64, 0.28), 0.75 * (0.8 + 0.2 * sin(time * 3.0 + l * 90.0)));\n" +
            "      }\n" +
            "      col = land;\n" +
            "    }\n" +
            "  }\n" +
            "  vec2 v = (frag / res - 0.5) * vec2(1.25, 1.6);\n" +
            "  col *= mix(0.6, 1.0, smoothstep(1.0, 0.25, length(v)));\n" +
            "  col += (hash(frag + fract(time * 7.13) * 397.0) - 0.5) * 0.012;\n" +
            "  gl_FragColor = vec4(max(col, vec3(0.0)), 1.0);\n" +
            "}";

    private static Program shape, glass, image, down, up, copy, downAlpha, upAlpha, glow, aurora, night;
    private static boolean loaded;

    private static void load() {
        if (loaded) return;
        loaded = true;
        shape = new Program("shape", SHAPE_VERTEX, SHAPE_FRAGMENT);
        glass = new Program("glass", SHAPE_VERTEX, GLASS_FRAGMENT);
        image = new Program("image", SHAPE_VERTEX, IMAGE_FRAGMENT);
        down = new Program("blur-down", PASS_VERTEX, DOWN_FRAGMENT);
        up = new Program("blur-up", PASS_VERTEX, UP_FRAGMENT);
        copy = new Program("blur-copy", PASS_VERTEX, COPY_FRAGMENT);
        downAlpha = new Program("glow-down", PASS_VERTEX, DOWN_ALPHA_FRAGMENT);
        upAlpha = new Program("glow-up", PASS_VERTEX, UP_ALPHA_FRAGMENT);
        glow = new Program("glow", PASS_VERTEX, GLOW_FRAGMENT);
        aurora = new Program("backdrop-aurora", PASS_VERTEX, AURORA_FRAGMENT);
        night = new Program("backdrop-night", PASS_VERTEX, NIGHT_FRAGMENT);
        int error = GL11.glGetError();
        if (error != 0) net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().warn("[Glass] GL error {} after shader setup", error);
    }

    public static Program shape() { load(); return shape; }
    public static Program glass() { load(); return glass; }
    public static Program image() { load(); return image; }
    public static Program down() { load(); return down; }
    public static Program up() { load(); return up; }
    public static Program copy() { load(); return copy; }
    public static Program downAlpha() { load(); return downAlpha; }
    public static Program upAlpha() { load(); return upAlpha; }
    public static Program glow() { load(); return glow; }
    public static Program aurora() { load(); return aurora; }
    public static Program night() { load(); return night; }
}
