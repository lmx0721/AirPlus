package net.airplus.ui.glass;

/** Frame-time driven animation helpers shared by every glass surface. */
public final class Anim {
    private Anim() { }

    // ---------------------------------------------------------------- global frame clock

    private static long frameLast;
    private static float frameDt, speed = 1;
    private static boolean reduced;

    /**
     * Called once per rendered frame, before anything is drawn.
     *
     * @param speedMultiplier scales every animation (1 = designed speed)
     * @param enabled         false snaps every animation straight to its target
     */
    public static void beginFrame(float speedMultiplier, boolean enabled) {
        long now = System.nanoTime();
        float raw = frameLast == 0 ? 0 : Math.min(0.05f, Math.max(0, (now - frameLast) / 1_000_000_000f));
        frameLast = now;
        speed = Math.max(0.1f, speedMultiplier);
        reduced = !enabled;
        frameDt = raw * speed;
    }

    /** Seconds since the previous frame, already scaled by the animation speed. */
    public static float dt() {
        return frameDt;
    }

    /** The global animation speed multiplier, for code that keeps its own clock. */
    public static float speed() {
        return speed;
    }

    /** True when animations are switched off: everything jumps to its target. */
    public static boolean reduced() {
        return reduced;
    }

    // ---------------------------------------------------------------- easing

    public static float clamp01(float t) {
        return t < 0 ? 0 : Math.min(t, 1);
    }

    public static float outCubic(float t) {
        t = 1 - clamp01(t);
        return 1 - t * t * t;
    }

    public static float inCubic(float t) {
        t = clamp01(t);
        return t * t * t;
    }

    public static float outQuint(float t) {
        t = 1 - clamp01(t);
        return 1 - t * t * t * t * t;
    }

    public static float inOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Overshoots slightly past 1 before settling. */
    public static float outBack(float t) {
        t = clamp01(t) - 1;
        float c = 1.70158f;
        return 1 + (c + 1) * t * t * t + c * t * t;
    }

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * A spring described the way iOS does: {@code response} is roughly how long it takes to
     * arrive (seconds), {@code dampingFraction} 1 = no overshoot, lower = bouncier.
     */
    public static Spring spring(float initial, float response, float dampingFraction) {
        float w = (float) (2 * Math.PI / Math.max(0.05f, response));
        return new Spring(initial, w * w, 2 * dampingFraction * w);
    }

    /** Seconds since the previous call, capped so a stall does not skip an animation. */
    public static final class Clock {
        private long last;

        public float tick() {
            long now = System.nanoTime();
            float dt = last == 0 ? 0 : Math.min(0.05f, Math.max(0, (now - last) / 1_000_000_000f));
            last = now;
            return dt;
        }

        public void reset() {
            last = 0;
        }
    }

    /** A value that eases towards its target at a frame-rate independent speed. */
    public static final class Smooth {
        public float value;
        private final float speed;

        public Smooth(float initial, float speed) {
            this.value = initial;
            this.speed = speed;
        }

        public float update(float target, float dt) {
            if (reduced) {
                value = target;
                return value;
            }
            value += (target - value) * (1 - (float) Math.exp(-speed * dt));
            if (Math.abs(target - value) < 0.0005f) value = target;
            return value;
        }

        public float update(boolean on, float dt) {
            return update(on ? 1f : 0f, dt);
        }

        public void snap(float to) {
            value = to;
        }
    }

    /** Damped spring: overshoots and settles, for toggles, knobs and anything that pops. */
    public static final class Spring {
        public float value, velocity;
        private final float stiffness, damping;

        public Spring(float initial, float stiffness, float damping) {
            this.value = initial;
            this.stiffness = stiffness;
            this.damping = damping;
        }

        public float update(float target, float dt) {
            if (reduced) {
                value = target;
                velocity = 0;
                return value;
            }
            float remaining = dt;
            while (remaining > 0) {
                float step = Math.min(remaining, 1 / 240f);
                velocity += ((target - value) * stiffness - velocity * damping) * step;
                value += velocity * step;
                remaining -= step;
            }
            if (Math.abs(target - value) < 0.0001f && Math.abs(velocity) < 0.001f) {
                value = target;
                velocity = 0;
            }
            return value;
        }

        /** Jump to a value and stop. */
        public void snap(float to) {
            value = to;
            velocity = 0;
        }

        /** Add an impulse: the value swings away and springs back. */
        public void kick(float impulse) {
            if (!reduced) velocity += impulse;
        }

        public boolean settled(float target) {
            return value == target && velocity == 0;
        }
    }

    /** A rounded rectangle whose position, size and corner radius are all on springs. */
    public static final class SpringRect {
        public final Spring x, y, w, h, r;
        private boolean placed;

        public SpringRect(float response, float dampingFraction) {
            x = spring(0, response, dampingFraction);
            y = spring(0, response, dampingFraction);
            w = spring(0, response, dampingFraction);
            h = spring(0, response, dampingFraction);
            // The radius must not wobble past half the height, so it is critically damped.
            r = spring(0, response, 1);
        }

        /** Move towards the target; the very first call places the rectangle without animating. */
        public void to(float tx, float ty, float tw, float th, float tr, float dt) {
            if (!placed) {
                snap(tx, ty, tw, th, tr);
                return;
            }
            x.update(tx, dt);
            y.update(ty, dt);
            w.update(tw, dt);
            h.update(th, dt);
            r.update(tr, dt);
        }

        public void snap(float tx, float ty, float tw, float th, float tr) {
            x.snap(tx);
            y.snap(ty);
            w.snap(tw);
            h.snap(th);
            r.snap(tr);
            placed = true;
        }

        public boolean placed() {
            return placed;
        }
    }
}
