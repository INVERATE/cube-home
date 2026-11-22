package dev.cube.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

/** Appearance chosen in Cube Home's settings. Read once per screen build (see load). */
final class Look {
    private Look() { }

    static final String[] SIZE_NAMES = {"Small", "Medium", "Large"};
    static final float[] SIZES = {0.85f, 1f, 1.2f};

    static final String[] RADIUS_NAMES = {"Square", "Slight", "Rounded", "Round"};
    static final int[] RADII = {0, 6, 12, 20};   // dp

    static final String[] FONT_NAMES = {"Roboto", "Condensed", "Serif", "Monospace", "Casual"};
    static final String[] FONTS = {"sans-serif", "sans-serif-condensed", "serif", "monospace", "casual"};

    static final String[] COLOR_NAMES = {"White", "Amber", "Jellyfin purple", "Sky blue", "Mint", "Coral"};
    static final int[] COLORS = {Color.WHITE, 0xFFFBBF24, 0xFFAA5CC3, 0xFF38BDF8, 0xFF34D399, 0xFFFB7185};

    static final String[] SPEED_NAMES = {"Off", "Fast", "Normal", "Relaxed", "Slow"};
    static final float[] SPEEDS = {0f, 0.6f, 1f, 1.4f, 1.9f};

    static float scale = 1f, anim = 1f;
    static int radiusDp = 12, accent = Color.WHITE;
    static String family = "sans-serif";

    static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("cubehome", Context.MODE_PRIVATE);
        scale = SIZES[p.getInt("look_size", 1)];
        radiusDp = RADII[p.getInt("look_radius", 2)];
        family = FONTS[p.getInt("look_font", 0)];
        accent = COLORS[p.getInt("look_color", 0)];
        anim = SPEEDS[p.getInt("look_speed", 2)];
    }

    /** Animation length scaled by the chosen speed. */
    static long d(long ms) { return (long) (ms * anim); }

    /** Regular or emphasised weight of the chosen font. */
    static Typeface font(boolean strong) {
        if (strong && family.equals("sans-serif")) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return Typeface.create(family, strong ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** Text/icon colour that reads on top of the accent colour. */
    static int onAccent() {
        float l = 0.2126f * Color.red(accent) + 0.7152f * Color.green(accent) + 0.0722f * Color.blue(accent);
        return l > 150 ? 0xFF111827 : Color.WHITE;
    }
}
