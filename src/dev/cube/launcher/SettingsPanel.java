package dev.cube.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Cube Home's own settings, opened from the gear icon. A list of rows: OK or ◀ ▶ change a
 * choice, OK on a text row opens the keyboard. Changes are saved at once; appearance
 * changes are applied when the panel closes (the screen is rebuilt).
 */
final class SettingsPanel extends FrameLayout {
    interface Host {
        void closeSettings(boolean appearanceChanged);
        void openFireTvSettings();
        void checkForUpdate();
    }

    private final SharedPreferences prefs;
    private final Host host;
    private final float dp;
    private final LinearLayout list;
    private final ScrollView scroll;
    private boolean appearanceChanged;
    private View first;

    SettingsPanel(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        prefs = ctx.getSharedPreferences("cubehome", Context.MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFA0E1116);
        setClickable(true);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(48), px(30), px(48), 0);
        TextView title = text(26, Color.WHITE, true);
        title.setText("Cube Home settings");
        col.addView(title);
        TextView hint = text(13, 0xFF9CA3AF, false);
        hint.setText("OK or ◀ ▶ to change     Back to close");
        hint.setPadding(0, px(8), 0, px(8));
        col.addView(hint);
        scroll = new CenterScroll.V(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(6), 0, px(60));
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        addView(col, new LayoutParams(-1, -1));
        build();
    }

    private void build() {
        section("Jellyfin");
        textRow("Server address", "jf_url", "http://192.168.1.10:8096", false);
        textRow("API key", "jf_key", "", true);
        textRow("User", "jf_user", "", false);
        toggle("Continue watching row", "row_resume", true, false);
        toggle("Next up row", "row_nextup", true, false);
        toggle("Recently added row", "row_latest", true, false);

        section("Wallpaper");
        toggle("Amazon aerials", "wall_src_amazon", true, false);
        toggle("NASA Earth photos", "wall_src_nasa", true, false);
        toggle("Bing photo of the day", "wall_src_bing", false, false);
        choice("Change picture", "wall_interval", Wallpaper.INTERVAL_NAMES, 1, false);

        section("Appearance");
        choice("Size", "look_size", Look.SIZE_NAMES, 1, true);
        choice("Corners", "look_radius", Look.RADIUS_NAMES, 2, true);
        choice("Font", "look_font", Look.FONT_NAMES, 0, true);
        choice("Selection colour", "look_color", Look.COLOR_NAMES, 0, true);
        choice("Animation speed", "look_speed", Look.SPEED_NAMES, 2, true);

        section("System");
        action("Fire TV settings", host::openFireTvSettings);
        action("Check for updates  ·  version " + version(), host::checkForUpdate);
    }

    void focusFirst() {
        scroll.scrollTo(0, 0);
        if (first != null) first.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) host.closeSettings(appearanceChanged);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ row kinds

    private void section(String name) {
        TextView t = text(14, 0xFF9CA3AF, true);
        t.setText(name.toUpperCase());
        t.setLetterSpacing(0.08f);
        t.setPadding(px(16), px(22), 0, px(6));
        list.addView(t);
    }

    private void choice(String label, String key, String[] names, int def, boolean appearance) {
        final TextView value = text(17, Color.WHITE, false);
        Runnable show = () -> value.setText("‹  " + names[prefs.getInt(key, def)] + "  ›");
        show.run();
        LinearLayout r = row(label, value);
        r.setOnClickListener(v -> step(key, names.length, def, 1, show, appearance));
        r.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                step(key, names.length, def, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, show, appearance);
            }
            return true;
        });
    }

    private void step(String key, int n, int def, int dir, Runnable show, boolean appearance) {
        prefs.edit().putInt(key, (prefs.getInt(key, def) + dir + n) % n).apply();
        if (appearance) appearanceChanged = true;
        show.run();
    }

    private void toggle(String label, String key, boolean def, boolean appearance) {
        choice(label, key, new String[]{"Off", "On"}, def ? 1 : 0, appearance);
    }

    private void textRow(String label, String key, String hint, boolean secret) {
        final TextView value = text(17, Color.WHITE, false);
        Runnable show = () -> {
            String v = prefs.getString(key, "");
            value.setText(v.isEmpty() ? "Not set" : secret ? "••••••" + v.substring(Math.max(0, v.length() - 4)) : v);
        };
        show.run();
        LinearLayout r = row(label, value);
        r.setOnClickListener(v -> {
            EditText in = new EditText(getContext());
            in.setSingleLine(true);
            in.setText(prefs.getString(key, ""));
            in.setHint(hint);
            in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            new AlertDialog.Builder(getContext())
                    .setTitle(label)
                    .setView(in)
                    .setPositiveButton("Save", (d, w) -> {
                        prefs.edit().putString(key, in.getText().toString().trim()).remove("jf_user_id").apply();
                        Jellyfin.reset();
                        show.run();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void action(String label, Runnable run) {
        TextView arrow = text(17, 0xFF9CA3AF, false);
        arrow.setText("›");
        row(label, arrow).setOnClickListener(v -> run.run());
    }

    private LinearLayout row(String label, TextView value) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(px(16), px(12), px(16), px(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(Look.radiusDp * 0.8f));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        TextView l = text(17, Color.WHITE, false);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(value);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Look.accent : Color.TRANSPARENT);
            int fg = has ? Look.onAccent() : Color.WHITE;
            l.setTextColor(fg);
            value.setTextColor(fg);
        });
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        if (first == null) first = r;
        return r;
    }

    private String version() {
        try {
            return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private TextView text(float sp, int color, boolean strong) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Look.font(strong));
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }
}
