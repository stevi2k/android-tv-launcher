package com.stevi2k.atvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seasonal decorations drawn over the home screen: falling snow, leaves or
 * petals, and (Christmas only) a string of lights across the top.
 *
 * Kept deliberately cheap: at most 80 small particles, one 20 fps tick,
 * no bitmaps, no layout passes. It never intercepts input, and it only
 * ticks between start() and stop(), which HomeActivity ties to its own
 * foreground life (onResume / onPause), so it is dead while another app
 * is in front or the TV is off.
 */
final class DecorView extends View {
    private static final int[] LIGHT_COLORS = {
            0xFFE53935, 0xFF43A047, 0xFFFFD60A, 0xFF1E88E5, 0xFFFF7043, 0xFFAB47BC,
    };
    private static final int[] LEAF_COLORS = {
            0xFFD97A2B, 0xFFB4531F, 0xFFE8A33D, 0xFF8A5A2B, 0xFFC0392B,
    };
    // Petals are pink/white only, no green: a green oval read as a leaf in Spring.
    private static final int[] PETAL_COLORS = {
            0xFFF8BBD0, 0xFFF48FB1, 0xFFFFFFFF, 0xFFFCE4EC, 0xFFE88AA8,
    };

    private static final class Particle {
        float x, y, size, speed, drift, phase;
        int color;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wirePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wirePath = new Path();
    private final Path bulbPath = new Path();
    private final Path pilePath = new Path();
    // Focused tile, in this view's (screen) coordinates, set by HomeActivity
    // from its focus highlight; and the dock band's screen top, for its snow cap.
    private final android.graphics.RectF focusRect = new android.graphics.RectF();
    private float dockBandTop = -1;
    private final Random random = new Random();
    private final List<Particle> particles = new ArrayList<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            float dt = (now - lastTick) / 1000f;
            lastTick = now;
            for (Particle p : particles) {
                p.y += p.speed * dt;
                p.x += (float) Math.sin(now / 900.0 + p.phase) * p.drift * dt;
                if (p.y > getHeight() + p.size) {
                    p.y = -p.size;
                    p.x = random.nextFloat() * Math.max(1, getWidth());
                }
                if (p.x < -p.size) p.x = getWidth() + p.size;
                if (p.x > getWidth() + p.size) p.x = -p.size;
            }
            invalidate();
            postDelayed(this, 50);
        }
    };

    private int kind = Themes.DECOR_NONE;
    private boolean running;
    private long lastTick;

    DecorView(Context c) {
        super(c);
        setFocusable(false);
        setClickable(false);
        wirePaint.setStyle(Paint.Style.STROKE);
        wirePaint.setStrokeWidth(Ui.dp(c, 2f));
        wirePaint.setColor(0xFF222222);
    }

    void setKind(int kind) {
        this.kind = kind;
        buildParticles();
        if (kind == Themes.DECOR_NONE) stop();
        else if (running) invalidate();
    }

    int getKind() {
        return kind;
    }

    void start() {
        if (kind == Themes.DECOR_NONE || running) return;
        running = true;
        lastTick = System.currentTimeMillis();
        post(tick);
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        buildParticles();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void buildParticles() {
        particles.clear();
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || kind == Themes.DECOR_NONE) return;
        int count = Math.min(80, Math.max(30, w / 24));
        int[] colors;
        float speedBase, sizeBase;
        switch (kind) {
            case Themes.DECOR_LEAVES:
                colors = LEAF_COLORS; speedBase = 55; sizeBase = 9; break;
            case Themes.DECOR_PETALS:
                colors = PETAL_COLORS; speedBase = 42; sizeBase = 7; break;
            default: // snow, with or without lights
                colors = null; speedBase = 62; sizeBase = 5; break;
        }
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            p.x = random.nextFloat() * w;
            p.y = random.nextFloat() * h;
            p.size = sizeBase + random.nextFloat() * sizeBase;
            p.speed = speedBase + random.nextFloat() * speedBase;
            p.drift = 12 + random.nextFloat() * 22;
            p.phase = random.nextFloat() * (float) (Math.PI * 2);
            p.color = colors == null ? 0xFFFFFFFF : colors[random.nextInt(colors.length)];
            particles.add(p);
        }
    }

    /** Focused tile rect in screen coordinates, or empty to draw no focus lights. */
    void setFocusRect(android.graphics.RectF r) {
        if (r == null || r.isEmpty()) focusRect.setEmpty();
        else focusRect.set(r);
        invalidate();
    }

    /** Dock band's screen top, or -1 for no dock snow cap. */
    void setDockBandTop(float top) {
        dockBandTop = top;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (kind == Themes.DECOR_NONE) return;
        long now = System.currentTimeMillis();
        if (kind == Themes.DECOR_LIGHTS_SNOW) drawLights(c, now);
        for (Particle p : particles) {
            paint.setColor(p.color);
            if (kind == Themes.DECOR_LEAVES) {
                paint.setAlpha(220);
                c.save();
                c.translate(p.x, p.y);
                c.rotate((float) Math.sin(now / 700.0 + p.phase) * 40);
                drawLeafShape(c, p.size);
                c.restore();
            } else if (kind == Themes.DECOR_PETALS) {
                // Blossom petals, not leaves: pink/white, notched top, no vein, no stem.
                paint.setAlpha(220);
                c.save();
                c.translate(p.x, p.y);
                c.rotate((float) Math.sin(now / 900.0 + p.phase) * 20);
                drawPetalShape(c, p.size);
                c.restore();
            } else {
                // A real snowflake: three crossed arms, not a dot.
                float r = p.size * 0.9f;
                int a = 150 + (int) (80 * Math.sin(now / 500.0 + p.phase));
                paint.setAlpha(a);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, Ui.dp(getContext(), 1.2f)));
                for (int k = 0; k < 3; k++) {
                    double ang = Math.PI * k / 3.0;
                    float dx = (float) Math.cos(ang) * r, dy = (float) Math.sin(ang) * r;
                    c.drawLine(p.x - dx, p.y - dy, p.x + dx, p.y + dy, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(p.x, p.y, Math.max(1.5f, r * 0.18f), paint);
            }
        }
        if (kind == Themes.DECOR_LIGHTS_SNOW || kind == Themes.DECOR_SNOW) drawSnowPiles(c);
        if (kind == Themes.DECOR_LEAVES) drawLeafPile(c);
        if (kind == Themes.DECOR_LIGHTS_SNOW && !focusRect.isEmpty()) drawFocusLights(c, now);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A leaf, drawn at the origin pointing up: pointed tip, wide middle, vein and stem. */
    private void drawLeafShape(Canvas c, float size) {
        paint.setStyle(Paint.Style.FILL);
        bulbPath.reset();
        bulbPath.moveTo(0, -size);
        bulbPath.cubicTo(size * 0.85f, -size * 0.4f, size * 0.85f, size * 0.6f, 0, size);
        bulbPath.cubicTo(-size * 0.85f, size * 0.6f, -size * 0.85f, -size * 0.4f, 0, -size);
        bulbPath.close();
        c.drawPath(bulbPath, paint);
        // Vein down the middle, and a short stem at the bottom.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 0.8f)));
        paint.setColor(0x66000000);
        c.drawLine(0, -size * 0.8f, 0, size * 0.9f, paint);
        c.drawLine(0, size, 0, size * 1.35f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    /** A blossom petal at the origin: round lobes with a notch at the top, no vein, no stem. */
    private void drawPetalShape(Canvas c, float size) {
        paint.setStyle(Paint.Style.FILL);
        bulbPath.reset();
        bulbPath.moveTo(0, -size * 0.55f);
        bulbPath.cubicTo(size * 0.45f, -size, size * 0.95f, -size * 0.3f, size * 0.55f, size * 0.5f);
        bulbPath.cubicTo(size * 0.3f, size, -size * 0.3f, size, -size * 0.55f, size * 0.5f);
        bulbPath.cubicTo(-size * 0.95f, -size * 0.3f, -size * 0.45f, -size, 0, -size * 0.55f);
        bulbPath.close();
        c.drawPath(bulbPath, paint);
    }

    /** A pile of leaves along the bottom edge: a brown mound plus scattered leaves on top. */
    private void drawLeafPile(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Mound base.
        paint.setColor(0xFF6B3A1F);
        paint.setAlpha(255);
        pilePath.reset();
        pilePath.moveTo(0, h);
        pilePath.lineTo(0, h - Ui.dp(getContext(), 14f));
        int seg = Ui.dp(getContext(), 120f);
        for (int x = 0; x < w; x += seg) {
            float peak = Ui.dp(getContext(), 10f + ((x / seg * 29) % 10));
            float x1 = Math.min(w, x + seg);
            pilePath.quadTo(x + seg / 2f, h - peak - Ui.dp(getContext(), 6f), x1, h - Ui.dp(getContext(), 12f));
        }
        pilePath.lineTo(w, h);
        pilePath.close();
        c.drawPath(pilePath, paint);
        // Scattered leaves, deterministic so the pile does not shimmer each frame.
        for (int i = 0; i < 70; i++) {
            float x = (i * 53) % Math.max(1, w);
            float y = h - Ui.dp(getContext(), 4f + ((i * 37) % 26));
            float size = Ui.dp(getContext(), 6f + ((i * 17) % 6));
            paint.setColor(LEAF_COLORS[i % LEAF_COLORS.length]);
            paint.setAlpha(235);
            c.save();
            c.translate(x, y);
            c.rotate((i * 47) % 360);
            drawLeafShape(c, size);
            c.restore();
        }
        paint.setAlpha(255);
    }

    /** Snow drift along the bottom edge, and a snow cap on top of the dock band. */
    private void drawSnowPiles(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        paint.setColor(0xFFF2F7FF);
        paint.setAlpha(255);
        // Bottom drift: a wavy top edge, thicker at the ends than in the middle.
        pilePath.reset();
        pilePath.moveTo(0, h);
        pilePath.lineTo(0, h - Ui.dp(getContext(), 22f));
        int seg = Ui.dp(getContext(), 120f);
        for (int x = 0; x < w; x += seg) {
            float peak = Ui.dp(getContext(), 16f + ((x / seg * 37) % 16));
            float x1 = Math.min(w, x + seg);
            pilePath.quadTo(x + seg / 2f, h - peak - Ui.dp(getContext(), 10f), x1, h - Ui.dp(getContext(), 20f));
        }
        pilePath.lineTo(w, h);
        pilePath.close();
        c.drawPath(pilePath, paint);
        // No snow cap on the dock band: removed Nov 2, 2026 at Steve's request.
        // dockBandTop is kept as a no-op setter so HomeActivity needs no change.
    }

    /**
     * Focus lights, top edge only (Nov 2, 2026, at Steve's request: no bottom
     * or side bulbs). Drawn strictly inside the rect, hanging down from the
     * top edge, so they never spill onto a neighbouring tile, get cut off,
     * or sit on the label under a grid tile. Tiles at 0.7 scale, the small
     * top pills at 0.5 scale, so they never crowd a pill's icon or text.
     */
    private void drawFocusLights(Canvas c, long now) {
        float gap = Ui.dp(getContext(), 30f);
        float inset = Ui.dp(getContext(), 2f);
        float left = focusRect.left + inset, top = focusRect.top + inset;
        float right = focusRect.right - inset;
        boolean isTile = focusRect.height() > Ui.dp(getContext(), 80f);
        float scale = isTile ? 0.7f : 0.5f;
        int i = 0;
        for (float x = left + gap / 2; x < right; x += gap, i++) {
            float tw = 0.75f + 0.25f * (float) Math.sin(now / 450.0 + i * 1.3f);
            drawBulbDirected(c, x, top, 0, LIGHT_COLORS[i % LIGHT_COLORS.length], tw, scale);
        }
    }

    /** A bulb drawn from an anchor point, pointing in the given direction (0 = down). */
    private void drawBulbDirected(Canvas c, float x, float y, float degrees, int color, float twinkle, float scale) {
        c.save();
        c.translate(x, y);
        c.rotate(degrees);
        drawBulb(c, 0, 0, color, twinkle, scale);
        c.restore();
    }

    /**
     * One bulb, drawn like a real string-light bulb: a small dark socket on
     * the wire, then a teardrop body, narrow at the neck and round at the
     * bottom, with a bright core and a highlight fleck.
     */
    private void drawBulb(Canvas c, float x, float wireY, int color, float twinkle, float scale) {
        float dp = Ui.dp(getContext(), 1f) * scale;
        float socketW = 5 * dp, socketH = 7 * dp;
        float bulbW = 13 * dp, bulbH = 18 * dp;
        float bulbTop = wireY + socketH;
        paint.setColor(0xFF222222);
        paint.setAlpha(255);
        c.drawRect(x - socketW / 2, wireY, x + socketW / 2, wireY + socketH, paint);
        // Soft glow behind the bulb.
        paint.setColor(color);
        paint.setAlpha(70);
        c.drawCircle(x, bulbTop + bulbH * 0.55f, bulbW * 0.85f, paint);
        // Teardrop body.
        bulbPath.reset();
        bulbPath.moveTo(x - bulbW * 0.22f, bulbTop);
        bulbPath.cubicTo(x - bulbW * 0.5f, bulbTop + bulbH * 0.35f,
                x - bulbW * 0.5f, bulbTop + bulbH * 0.75f, x, bulbTop + bulbH);
        bulbPath.cubicTo(x + bulbW * 0.5f, bulbTop + bulbH * 0.75f,
                x + bulbW * 0.5f, bulbTop + bulbH * 0.35f, x + bulbW * 0.22f, bulbTop);
        bulbPath.close();
        paint.setAlpha((int) (255 * twinkle));
        c.drawPath(bulbPath, paint);
        // Bright core and highlight fleck.
        paint.setAlpha((int) (200 * twinkle));
        c.drawCircle(x, bulbTop + bulbH * 0.55f, bulbW * 0.16f, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (160 * twinkle));
        c.drawOval(x - bulbW * 0.28f, bulbTop + bulbH * 0.25f,
                x - bulbW * 0.05f, bulbTop + bulbH * 0.5f, paint);
        paint.setAlpha(255);
    }

    /** A sagging wire with coloured bulbs, tucked into the top edge so it never covers the clock or pills. */
    private void drawLights(Canvas c, long now) {
        int w = getWidth();
        if (w <= 0) return;
        float top = Ui.dp(getContext(), 2f);
        float sag = Ui.dp(getContext(), 16f);
        int segments = Math.max(4, w / Ui.dp(getContext(), 160f));
        wirePath.reset();
        wirePath.moveTo(0, top);
        for (int i = 0; i < segments; i++) {
            float x0 = (float) i * w / segments;
            float x1 = (float) (i + 1) * w / segments;
            float midY = top + sag;
            wirePath.quadTo((x0 + x1) / 2f, midY * 2f - top, x1, top);
        }
        c.drawPath(wirePath, wirePaint);
        int gap = Ui.dp(getContext(), 64f);
        for (int i = 0, x = gap / 2; x < w; i++, x += gap) {
            // Bulb hangs from the sagging wire: approximate the wire height at this x.
            float t = (float) (x % (w / segments)) / (w / segments);
            float wireY = top + sag * 4 * t * (1 - t);
            float twinkle = 0.75f + 0.25f * (float) Math.sin(now / 450.0 + i * 1.3f);
            drawBulb(c, x, wireY, LIGHT_COLORS[i % LIGHT_COLORS.length], twinkle, 1.0f);
        }
        paint.setAlpha(255);
    }
}
