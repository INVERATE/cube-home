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
 * Cube Home's own settings, opened from the gear icon: sections on the left, their settings on
 * the right. Up/Down picks a section (its settings show at once), ▶ or OK enters it, Back
 * returns to the sections and then closes. In a section, OK or ◀ ▶ change a choice and OK on
 * a text row opens the keyboard. Changes are saved at once; appearance changes are applied
 * when the panel closes (the screen is rebuilt).
 */
final class SettingsPanel extends FrameLayout {
    interface Host {
        void closeSettings(boolean appearanceChanged);
        void openFireTvSettings();
        void checkForUpdate();
    }

    private static final String[] SECTIONS = {"Jellyfin", "Home", "Wallpaper", "Appearance", "System"};

    private final SharedPreferences prefs;
    private final Host host;
    private final float dp;
    private final LinearLayout sections, list;
    private final ScrollView scroll;
    private final TextView hint;
    private boolean appearanceChanged;
    private int current = -1;
    private View first;   // first setting of the shown section

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
        hint = text(13, 0xFF9CA3AF, false);
        hint.setPadding(0, px(8), 0, px(18));
        col.addView(hint);

        LinearLayout panes = new LinearLayout(ctx);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        sections = new LinearLayout(ctx);
        sections.setOrientation(LinearLayout.VERTICAL);
        for (int k = 0; k < SECTIONS.length; k++) sections.addView(sectionItem(k));
        panes.addView(sections, new LinearLayout.LayoutParams(px(230), -2));
        View divider = new View(ctx);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(1), -1);
        dl.setMargins(px(24), 0, px(24), px(40));
        panes.addView(divider, dl);
        scroll = new CenterScroll.V(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, px(60));
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panes.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1f));
        col.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1f));
        addView(col, new LayoutParams(-1, -1));
        show(0);
    }

    private void build(int section) {
        switch (section) {
            case 0:
                textRow("Server address", "jf_url", "http://192.168.1.10:8096", false);
                textRow("API key", "jf_key", "", true);
                textRow("User", "jf_user", "", false);
                break;
            case 1:
                toggle("Continue watching row", "row_resume", true, false);
                toggle("Next up row", "row_nextup", true, false);
                toggle("Recently added row", "row_latest", true, false);
                break;
            case 2:
                toggle("Amazon aerials", "wall_src_amazon", true, false);
                toggle("NASA Earth photos", "wall_src_nasa", true, false);
                toggle("Bing photo of the day", "wall_src_bing", false, false);
                choice("Change picture", "wall_interval", Wallpaper.INTERVAL_NAMES, 1, false);
                break;
            case 3:
                choice("Size", "look_size", Look.SIZE_NAMES, 1, true);
                choice("Corners", "look_radius", Look.RADIUS_NAMES, 2, true);
                choice("Font", "look_font", Look.FONT_NAMES, 0, true);
                choice("Selection colour", "look_color", Look.COLOR_NAMES, 0, true);
                choice("Animation speed", "look_speed", Look.SPEED_NAMES, 2, true);
                break;
            default:
                action("Fire TV settings", host::openFireTvSettings);
                action("Check for updates  ·  version " + version(), host::checkForUpdate);
        }
    }

    /** Shows a section's settings on the right (as soon as it is selected on the left). */
    private void show(int section) {
        if (section == current) return;
        current = section;
        list.removeAllViews();
        first = null;
        build(section);
        scroll.scrollTo(0, 0);
        for (int k = 0; k < sections.getChildCount(); k++) ((Runnable) sections.getChildAt(k).getTag()).run();
    }

    void focusFirst() {
        sections.getChildAt(Math.max(current, 0)).requestFocus();
    }

    private boolean inSettings() {
        View f = findFocus();
        return f != null && f.getParent() == list;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (inSettings()) sections.getChildAt(current).requestFocus();
                else host.closeSettings(appearanceChanged);
            }
            return true;
        }
        View f = findFocus();
        boolean onSection = f != null && f.getParent() == sections;
        if (onSection && (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER)) {
            if (down && first != null) first.requestFocus();
            return true;
        }
        // Inside a section, Up/Down stay in it (never jump to the section list).
        if (inSettings() && down && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int i = list.indexOfChild(f) + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            if (i >= 0 && i < list.getChildCount()) list.getChildAt(i).requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** A section name on the left: selecting it shows its settings. */
    private View sectionItem(final int k) {
        final TextView t = text(18, Color.WHITE, false);
        t.setText(SECTIONS[k]);
        t.setFocusable(true);
        t.setPadding(px(16), px(12), px(16), px(12));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(Look.radiusDp * 0.8f));
        t.setBackground(bg);
        Runnable paint = () -> {
            boolean focused = t.hasFocus(), open = k == current;
            bg.setColor(focused ? Look.accent : open ? 0x26FFFFFF : Color.TRANSPARENT);
            t.setTextColor(focused ? Look.onAccent() : open ? Color.WHITE : 0xFF9CA3AF);
            t.setTypeface(Look.font(open || focused));
        };
        t.setOnFocusChangeListener((v, has) -> {
            if (has) {
                show(k);
                hint.setText("▲ ▼ choose a section     ▶ or OK open it     Back close");
            } else {
                hint.setText("OK or ◀ ▶ change     Back to sections");
            }
            for (int i = 0; i < sections.getChildCount(); i++) ((Runnable) sections.getChildAt(i).getTag()).run();
        });
        t.setTag(paint);
        paint.run();
        return t;
    }

    // ------------------------------------------------------------------ row kinds

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
