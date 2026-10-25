package com.kulwant.atvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.util.TypedValue;
import android.widget.TextView;

/** Small helpers: dp, the Outfit variable font at a given weight, and shared drawables. */
final class Ui {
    private static final SparseArray<Typeface> FONTS = new SparseArray<>();

    private Ui() {}

    /**
     * Scale for one unit, in pixels. TVs report wildly different densities
     * for the same physical screen, so size everything relative to the
     * screen width instead of the reported density: 1 unit = 2 px at the
     * 1920-wide reference, which is exactly the old density-2.0 behaviour
     * on the bedroom TV, and proportionally smaller/larger elsewhere.
     */
    static float scale(Context c) {
        int w = c.getResources().getDisplayMetrics().widthPixels;
        return w > 0 ? w / 960f : c.getResources().getDisplayMetrics().density;
    }

    static int dp(Context c, float v) {
        return Math.round(v * scale(c));
    }

    static Typeface font(Context c, int weight) {
        Typeface t = FONTS.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getAssets(), "fonts/Outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight)
                        .build();
            } catch (Exception e) {
                t = Typeface.create("sans-serif-light", Typeface.NORMAL);
            }
            FONTS.put(weight, t);
        }
        return t;
    }

    static TextView text(Context c, int weight, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(font(c, weight));
        // TV text is sized in the same width-relative units as dp, not
        // in sp: a TV's font-scale setting must not shrink the 10-foot UI.
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * scale(c));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /** Liquid Glass, Lumen-style, without live blur: a translucent tint plus a
     *  rim-light stroke, never a backdrop blur. Live blur on a TV GPU halves
     *  the frame rate while focus moves; tint + rim reads as glass and costs
     *  nothing per frame. Selected = white pill with dark text, as on tvOS. */
    static GradientDrawable glass(Context c, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        if (selected) {
            d.setColor(0xFFFFFFFF);
        } else {
            d.setColor(0x55202020);
        }
        d.setCornerRadius(dp(c, 26));
        d.setStroke(dp(c, 1), selected ? 0xFFFFFFFF : 0x33FFFFFF);
        return d;
    }

    /** Circular glass tile, for tvOS-style Control Center icons: an oval,
     *  white when focused, dark glass otherwise, with a rim stroke. */
    static android.graphics.drawable.GradientDrawable glassCircle(Context c, boolean selected) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(selected ? 0xFFFFFFFF : 0xFF2A2A2A);
        d.setStroke(dp(c, 1), selected ? 0xFFFFFFFF : 0x33FFFFFF);
        return d;
    }

    /** Opaque dark panel, so home does not bleed through Control Center. */
    static android.graphics.drawable.GradientDrawable opaquePanel(Context c) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFF1E1E1E);
        d.setCornerRadius(dp(c, 28));
        d.setStroke(dp(c, 1), 0x33FFFFFF);
        return d;
    }

    /** Large left-column glyph panel for the tvOS-style settings layout. */
    static GradientDrawable glassPanel(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x55202020);
        d.setCornerRadius(dp(c, 28));
        d.setStroke(dp(c, 1), 0x33FFFFFF);
        return d;
    }

    static GradientDrawable rounded(int fill, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }
}
