package com.stevi2k.atvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Home-screen themes, picked in Settings > Themes.
 *
 * A theme is deliberately small: a background colour, a dock-band colour,
 * and a decoration kind. No new network call, no polling, no background
 * service. The decoration animation (DecorView) only runs while Home is
 * in the foreground; HomeActivity stops it in onPause, so it costs
 * nothing while another app is in front.
 *
 * The stored value is an index into NAMES, kept in the "home" prefs
 * alongside the other home settings, so a fresh install starts on
 * Default and an update never resets a picked theme.
 */
final class Themes {
    static final int DEFAULT = 0;
    static final int FALL = 1;
    static final int CHRISTMAS = 2;
    static final int WINTER = 3;
    static final int SPRING = 4;

    static final String[] NAMES = {"Default", "Fall", "Christmas", "Winter", "Spring"};

    static final String[] DESCRIPTIONS = {
            "Your photos, no decorations.",
            "Leaves and a fall wallpaper.",
            "Lights, snow, a Christmas scene.",
            "Snow and a winter wallpaper.",
            "Petals and a spring wallpaper.",
    };

    /** Decoration kinds, matched by DecorView. */
    static final int DECOR_NONE = 0;
    static final int DECOR_LEAVES = 1;
    static final int DECOR_LIGHTS_SNOW = 2;
    static final int DECOR_SNOW = 3;
    static final int DECOR_PETALS = 4;

    private Themes() {}

    static int current(Context c) {
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        int t = p.getInt("theme", DEFAULT);
        return t >= 0 && t < NAMES.length ? t : DEFAULT;
    }

    static void set(Context c, int theme) {
        if (theme < 0 || theme >= NAMES.length) theme = DEFAULT;
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putInt("theme", theme).apply();
    }

    static String name(Context c) {
        return NAMES[current(c)];
    }

    /** Opaque background colour behind the home screen (visible when no wallpaper photo is set). */
    static int background(int theme) {
        switch (theme) {
            case FALL: return 0xFF1A0F08;
            case CHRISTMAS: return 0xFF07130B;
            case WINTER: return 0xFF081426;
            case SPRING: return 0xFF0E1A12;
            default: return 0xFF000000;
        }
    }

    /** Dock-band colour, as an RGB value; HomeActivity adds the alpha (solid, or translucent over a photo). */
    static int bandRgb(int theme) {
        switch (theme) {
            case FALL: return 0x3A1F0D;
            case CHRISTMAS: return 0x1B3A26;
            case WINTER: return 0xC9D2D8; // grey/whitish, Nov 2, 2026 at Steve's request
            case SPRING: return 0xE8A0B4; // pinkish, Nov 2, 2026 at Steve's request
            default: return 0x141416;
        }
    }

    static int decor(int theme) {
        switch (theme) {
            case FALL: return DECOR_LEAVES;
            case CHRISTMAS: return DECOR_LIGHTS_SNOW;
            case WINTER: return DECOR_SNOW;
            case SPRING: return DECOR_PETALS;
            default: return DECOR_NONE;
        }
    }

    /** Bundled wallpaper for a theme, or 0 for Default (which keeps the user's photo rotation). */
    private static final int[] CACHED_WP = new int[]{-1, -1, -1, -1, -1};

    static int wallpaperRes(Context c, int theme) {
        if (theme < 0 || theme >= NAMES.length) return 0;
        if (CACHED_WP[theme] >= 0) return CACHED_WP[theme];
        String name = null;
        switch (theme) {
            case FALL: name = "theme_fall"; break;
            case CHRISTMAS: name = "theme_christmas"; break;
            case WINTER: name = "theme_winter"; break;
            case SPRING: name = "theme_spring"; break;
            default: return 0;
        }
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        CACHED_WP[theme] = id;
        return id;
    }

    /** Whole days from today until Christmas Day, counting this year's if it has not passed yet. */
    static int daysToChristmas() {
        java.util.Calendar today = java.util.Calendar.getInstance();
        today.set(java.util.Calendar.HOUR_OF_DAY, 0);
        today.set(java.util.Calendar.MINUTE, 0);
        today.set(java.util.Calendar.SECOND, 0);
        today.set(java.util.Calendar.MILLISECOND, 0);
        java.util.Calendar xmas = java.util.Calendar.getInstance();
        xmas.set(java.util.Calendar.HOUR_OF_DAY, 0);
        xmas.set(java.util.Calendar.MINUTE, 0);
        xmas.set(java.util.Calendar.SECOND, 0);
        xmas.set(java.util.Calendar.MILLISECOND, 0);
        xmas.set(java.util.Calendar.MONTH, java.util.Calendar.DECEMBER);
        xmas.set(java.util.Calendar.DAY_OF_MONTH, 25);
        if (xmas.before(today)) xmas.add(java.util.Calendar.YEAR, 1);
        long diff = xmas.getTimeInMillis() - today.getTimeInMillis();
        return (int) (diff / (24L * 60 * 60 * 1000));
    }

    static String christmasCountdownText() {
        return christmasCountdownText(java.util.Calendar.getInstance());
    }

    /** Countdown text for a given moment, so the Christmas Eve / hour cases are testable. */
    static String christmasCountdownText(java.util.Calendar now) {
        java.util.Calendar today = (java.util.Calendar) now.clone();
        today.set(java.util.Calendar.HOUR_OF_DAY, 0);
        today.set(java.util.Calendar.MINUTE, 0);
        today.set(java.util.Calendar.SECOND, 0);
        today.set(java.util.Calendar.MILLISECOND, 0);
        java.util.Calendar xmas = (java.util.Calendar) now.clone();
        xmas.set(java.util.Calendar.HOUR_OF_DAY, 0);
        xmas.set(java.util.Calendar.MINUTE, 0);
        xmas.set(java.util.Calendar.SECOND, 0);
        xmas.set(java.util.Calendar.MILLISECOND, 0);
        xmas.set(java.util.Calendar.MONTH, java.util.Calendar.DECEMBER);
        xmas.set(java.util.Calendar.DAY_OF_MONTH, 25);
        if (xmas.before(today)) xmas.add(java.util.Calendar.YEAR, 1);
        long diffDays = xmas.getTimeInMillis() - today.getTimeInMillis();
        int days = (int) (diffDays / (24L * 60 * 60 * 1000));
        if (days == 0) return "Merry Christmas!";
        if (days == 1) {
            // Christmas Eve: a live hours:minutes:seconds countdown to midnight, not days.
            long totalSeconds = (xmas.getTimeInMillis() - now.getTimeInMillis()) / 1000L;
            if (totalSeconds <= 0) return "Merry Christmas!";
            long h = totalSeconds / 3600, m = (totalSeconds % 3600) / 60, s = totalSeconds % 60;
            return "Christmas Eve! " + String.format("%02d:%02d:%02d", h, m, s) + " until Christmas";
        }
        return days + " days until Christmas";
    }
}
