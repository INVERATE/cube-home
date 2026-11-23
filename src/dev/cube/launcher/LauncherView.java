package dev.cube.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The home screen (hosted by MainActivity), in the style of Plume Launcher: an hourly
 * wallpaper with its description, clock and date, icon buttons, the apps row and
 * Jellyfin's "Continue watching". "All apps" lists the user's apps; hold OK there for options.
 *
 * Remote on the apps row:  OK = open · hold OK = move the app (◀ ▶, then OK).
 */
public class LauncherView extends FrameLayout implements SettingsPanel.Host {
    private static final String KEY_ORDER = "order";      // row order (packages)
    private static final String KEY_SHOWN = "shown";      // built-in apps the user added
    private static final String KEY_HIDDEN = "hidden";    // installed apps the user removed

    /** Built-in apps that are real streaming apps, shown on Home by default. */
    private static final Set<String> DEFAULT_SHOWN = new HashSet<>(Collections.singletonList(
            "com.amazon.firebat"));   // Prime Video
    /** Never listed anywhere. */
    private static final Set<String> NEVER = new HashSet<>(java.util.Arrays.asList(
            HomeService.AMAZON_HOME, "com.amazon.firehomestarter"));

    static String lastFocusTag;

    static class App {
        String pkg, label;
        Drawable banner, icon;
        Intent intent;
        boolean builtIn;
    }

    private final Handler handler = new Handler();
    private final SharedPreferences prefs;
    private final float dp;
    private final ImageView wallpaper;
    private final TextView clock, date, notice;
    private final LinearLayout row;
    private final HorizontalScrollView rowScroll;
    private final ScrollView page;          // apps row + Jellyfin rows; scrolls vertically
    private final JfRow resumeRow, nextRow, latestRow;
    private String jfKey = "";
    private SettingsPanel settings;
    private View header, headerRight;
    private final FrameLayout allPanel, optionsLayer;
    private final LinearLayout optionsCard;
    private LinearLayout bar, bottom;   // home-screen focus areas, blocked while a panel is open
    private final LinearLayout allGrid;
    private final ScrollView allScroll;

    private List<App> apps = new ArrayList<>();
    private String moving;              // package being moved, or null
    private boolean swallowNextOkUp;    // the OK release that ended a long press

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clock.setText(DateFormat.getTimeFormat(getContext()).format(now));
            String d = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now);
            date.setText(d.substring(0, 1).toUpperCase(Locale.getDefault()) + d.substring(1));
            handler.postDelayed(this, 10000);
            Wallpaper.refreshIfStale(getContext(), LauncherView.this::setWallpaper);
        }
    };

    public LauncherView(Context ctx) {
        super(ctx);
        prefs = ctx.getSharedPreferences("cubehome", Context.MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        Look.load(ctx);

        // ---- wallpaper + scrims
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2A3A, 0xFF27384A, 0xFF34303F}));
        wallpaper = new ImageView(ctx);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wallpaper, new LayoutParams(-1, -1));
        // Dark gradients behind the text; how dark depends on how bright the picture is.
        topScrim = new View(ctx);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF000000, 0x00000000}));
        addView(topScrim, new LayoutParams(-1, px(300), Gravity.TOP));
        bottomScrim = new View(ctx);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF000000, 0x00000000}));
        addView(bottomScrim, new LayoutParams(-1, px(420), Gravity.BOTTOM));
        Bitmap cached = Wallpaper.cached(ctx);
        if (cached != null) wallpaper.setImageBitmap(cached);
        fitScrims(cached, false);

        // ---- clock + date (top-left)
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.VERTICAL);
        clock = text(60, Color.WHITE, Look.font(true));
        head.addView(clock);
        date = text(17, 0xFFF3F4F6, Look.font(false));
        date.setPadding(px(2), px(10), 0, 0);
        head.addView(date);
        notice = text(13, 0xFFFDE68A, Look.font(false));
        notice.setPadding(px(2), px(10), 0, 0);
        notice.setVisibility(GONE);
        head.addView(notice);
        LayoutParams hl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.setMargins(px(48), px(30), 0, 0);
        addView(head, hl);
        header = head;

        // ---- icon buttons (top-right)
        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        refreshBtn = iconButton(R.drawable.lucide_refresh_cw, str(R.string.btn_new_wallpaper), "top:wall", this::newWallpaper);
        bar.addView(iconButton(R.drawable.lucide_layout_grid, str(R.string.btn_all_apps), "top:all", this::openAllApps));
        bar.addView(refreshBtn);
        bar.addView(iconButton(R.drawable.lucide_settings, str(R.string.btn_settings), "top:settings", this::openCubeSettings));
        bar.addView(iconButton(R.drawable.lucide_house, str(R.string.btn_amazon_home), "top:amazon", this::openAmazonHome));
        // Top-right: the icons, and under them what the wallpaper shows.
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(bar, new LinearLayout.LayoutParams(-2, -2));   // keep the icons in the corner
        caption = text(13, Color.WHITE, Look.font(true));
        caption.setGravity(Gravity.END);
        caption.setMaxWidth(px(400));
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setShadowLayer(px(5), 0, px(1), 0xCC000000);
        caption.setPadding(0, px(14), px(8), 0);
        caption.setAlpha(0.9f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.gravity = Gravity.END;   // own width (up to 400 dp), right-aligned
        right.addView(caption, cl);
        showCaption();
        LayoutParams bl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        bl.setMargins(0, px(26), px(36), 0);
        addView(right, bl);
        headerRight = right;

        // ---- the apps row (lower third)
        rowScroll = new CenterScroll.H(ctx);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setFillViewport(true);
        rowScroll.setPadding(px(40), px(26), px(40), 0);   // room for the lifted tile in move mode
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        rowScroll.addView(row, new FrameLayout.LayoutParams(-2, -2));
        // Below the clock: the apps row, then the Jellyfin rows; the page scrolls when they
        // don't all fit, keeping the selected row near the middle.
        bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setPadding(0, px(165), 0, px(40));
        bottom.addView(rowScroll, new LinearLayout.LayoutParams(-1, -2));
        resumeRow = new JfRow(str(R.string.row_resume));
        nextRow = new JfRow(str(R.string.row_nextup));
        latestRow = new JfRow(str(R.string.row_latest));
        page = new CenterScroll.V(ctx);
        page.setVerticalScrollBarEnabled(false);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.addView(bottom, new ScrollView.LayoutParams(-1, -2));
        addView(page, new LayoutParams(-1, -1));
        // Scrolling down to the Jellyfin rows fades the clock and icons out, so nothing overlaps.
        page.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float a = Math.max(0f, 1f - y / (float) px(110));
            header.setAlpha(a);
            headerRight.setAlpha(a);
        });
        showJellyfin(Jellyfin.cached());

        // ---- "All apps" panel (hidden until opened)
        allPanel = new FrameLayout(ctx);
        allPanel.setBackgroundColor(0xFA0E1116);
        allPanel.setVisibility(GONE);
        allPanel.setClickable(true);
        LinearLayout allCol = new LinearLayout(ctx);
        allCol.setOrientation(LinearLayout.VERTICAL);
        allCol.setPadding(px(48), px(30), px(48), 0);
        TextView allTitle = text(26, Color.WHITE, Look.font(false));
        allTitle.setText(R.string.btn_all_apps);
        allCol.addView(allTitle);
        TextView allHint = text(13, 0xFF9CA3AF, Look.font(false));
        allHint.setText(R.string.all_hint);
        allHint.setPadding(0, px(8), 0, px(8));
        allCol.addView(allHint);
        allScroll = new CenterScroll.V(ctx);
        allScroll.setVerticalScrollBarEnabled(false);
        allScroll.setClipToPadding(false);
        allScroll.setPadding(0, px(8), 0, px(40));
        allGrid = new LinearLayout(ctx);
        allGrid.setOrientation(LinearLayout.VERTICAL);
        allGrid.setClipChildren(false);
        allScroll.addView(allGrid, new ScrollView.LayoutParams(-1, -2));
        allCol.addView(allScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        allPanel.addView(allCol, new LayoutParams(-1, -1));
        addView(allPanel, new LayoutParams(-1, -1));

        // ---- app options (hold OK in All apps): a small card over a dimmed screen
        optionsLayer = new FrameLayout(ctx);
        optionsLayer.setBackgroundColor(0x99000000);
        optionsLayer.setVisibility(GONE);
        optionsLayer.setClickable(true);
        optionsCard = new LinearLayout(ctx);
        optionsCard.setOrientation(LinearLayout.VERTICAL);
        optionsCard.setPadding(px(10), px(14), px(10), px(10));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(px(18));
        cardBg.setColor(0xFF1C2028);
        cardBg.setStroke(px(1), 0x22FFFFFF);
        optionsCard.setBackground(cardBg);
        optionsLayer.addView(optionsCard, new LayoutParams(px(300), -2, Gravity.CENTER));
        addView(optionsLayer, new LayoutParams(-1, -1));

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(tick);
    }

    private TextView caption;
    private View topScrim, bottomScrim, refreshBtn;

    private void newWallpaper() {
        if (!Wallpaper.next(getContext(), this::setWallpaper)) return;   // one already loading
        View icon = ((ViewGroup) refreshBtn).getChildAt(0);
        icon.animate().cancel();
        icon.setRotation(0);
        icon.animate().rotationBy(360 * 20).setDuration(20000)
                .setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    /**
     * Brighter picture -> darker gradients, so white text and icons stay readable.
     * Measures the top and bottom thirds of a tiny copy of the picture.
     */
    private void fitScrims(Bitmap b, boolean animate) {
        float top = 0.55f, bottom = 0.7f;   // defaults, also for the plain background
        if (b != null) {
            Bitmap s = Bitmap.createScaledBitmap(b, 32, 18, true);
            top = scrimFor(luma(s, 0, 6));
            bottom = scrimFor(luma(s, 8, 18));
            if (s != b) s.recycle();
        }
        long d = animate ? Look.d(1200) : 0;
        topScrim.animate().alpha(top).setDuration(d).start();
        bottomScrim.animate().alpha(bottom).setDuration(d).start();
    }

    private static float luma(Bitmap s, int y0, int y1) {
        float sum = 0;
        for (int y = y0; y < y1; y++) for (int x = 0; x < s.getWidth(); x++) {
            int c = s.getPixel(x, y);
            sum += 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c);
        }
        return sum / ((y1 - y0) * s.getWidth() * 255f);
    }

    /** 0 (black) .. 1 (white) brightness -> gradient opacity 0.45 .. 0.9. */
    private static float scrimFor(float luma) { return 0.45f + 0.45f * Math.min(1f, luma / 0.7f); }

    private void showCaption() {
        String c = Wallpaper.caption(getContext());
        caption.setText(c);
        caption.setVisibility(c.isEmpty() ? GONE : VISIBLE);
    }

    private void setWallpaper(Bitmap b) {
        View icon = ((ViewGroup) refreshBtn).getChildAt(0);
        icon.animate().cancel();
        icon.animate().rotation(0).setDuration(Look.d(300)).start();
        if (b == null) return;
        showCaption();
        fitScrims(b, true);
        android.graphics.drawable.Drawable old = wallpaper.getDrawable();
        android.graphics.drawable.BitmapDrawable next =
                new android.graphics.drawable.BitmapDrawable(getResources(), b);
        if (old == null) { wallpaper.setImageDrawable(next); return; }
        if (old instanceof android.graphics.drawable.TransitionDrawable) {
            old = ((android.graphics.drawable.TransitionDrawable) old).getDrawable(1);
        }
        android.graphics.drawable.TransitionDrawable t = new android.graphics.drawable.TransitionDrawable(
                new android.graphics.drawable.Drawable[]{old, next});
        t.setCrossFadeEnabled(true);
        wallpaper.setImageDrawable(t);
        t.startTransition((int) Look.d(1200));
    }

    void setNotice(String msg) {
        notice.setText(msg == null ? "" : msg);
        notice.setVisibility(msg == null ? GONE : VISIBLE);
    }

    /** Home was pressed: close any open panel or menu, stop moving. */
    void closePanels() {
        if (settings != null) closeSettings(false);
        closeOptions();
        closeAllApps();
        endMove();
    }

    /** Opened from the launcher icon or a fresh start: back to the first app. */
    void goTop() {
        if (settings != null) closeSettings(false);
        closeOptions();
        closeAllApps();
        endMove();
        rowScroll.scrollBy(-rowScroll.getScrollX(), 0);
        page.scrollBy(0, -page.getScrollY());
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    /** Last selected item of each row, so coming back into a row returns to it. */
    private String lastInApps;

    /**
     * Moving up/down into another row goes to the item last selected there (like Android TV's
     * launcher), not to whatever lies straight below, which could be far off screen.
     */
    @Override
    public View focusSearch(View focused, int direction) {
        View next = super.focusSearch(focused, direction);
        if (next == null || focused == null
                || (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN)) return next;
        if (next.getParent() == row && focused.getParent() != row && lastInApps != null) {
            View v = row.findViewWithTag(lastInApps);
            if (v != null) return v;
        }
        for (JfRow r : new JfRow[]{resumeRow, nextRow, latestRow}) {
            if (next.getParent() == r.cards && focused.getParent() != r.cards && r.last != null) {
                View v = r.cards.findViewWithTag(r.last);
                if (v != null) return v;
            }
        }
        return next;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (optionsLayer.getVisibility() == VISIBLE) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeOptions();
                return true;
            }
            if (allPanel.getVisibility() == VISIBLE || moving != null) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (moving != null) endMove(); else closeAllApps();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ================================================================ data

    private List<App> loadAll() {
        Context ctx = getContext();
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (map.containsKey(pkg) || pkg.equals(ctx.getPackageName()) || NEVER.contains(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.banner = ri.activityInfo.loadBanner(pm);
                if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                a.icon = ri.loadIcon(pm);
                a.builtIn = (ri.activityInfo.applicationInfo.flags
                        & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                a.intent = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                map.put(pkg, a);
            }
        }
        List<App> list = new ArrayList<>(map.values());
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return list;
    }

    private boolean onHome(App a) {
        if (a.builtIn) return DEFAULT_SHOWN.contains(a.pkg) ? !set(KEY_HIDDEN).contains(a.pkg)
                : set(KEY_SHOWN).contains(a.pkg);
        return !set(KEY_HIDDEN).contains(a.pkg);
    }

    private void setOnHome(App a, boolean show) {
        Set<String> shown = set(KEY_SHOWN), hidden = set(KEY_HIDDEN);
        shown.remove(a.pkg);
        hidden.remove(a.pkg);
        boolean defaultOn = !a.builtIn || DEFAULT_SHOWN.contains(a.pkg);
        if (show && !defaultOn) shown.add(a.pkg);
        if (!show && defaultOn) hidden.add(a.pkg);
        prefs.edit().putString(KEY_SHOWN, TextUtils.join(",", shown))
                .putString(KEY_HIDDEN, TextUtils.join(",", hidden)).apply();
    }

    /** Apps on Home, in the user's order; apps never ordered go at the end, A–Z. */
    private List<App> homeApps() {
        Map<String, App> by = new LinkedHashMap<>();
        for (App a : apps) if (onHome(a)) by.put(a.pkg, a);
        List<App> out = new ArrayList<>();
        List<String> order = list(KEY_ORDER);
        if (!order.contains(Jellyfin.APP)) order.add(0, Jellyfin.APP);   // main app first by default
        for (String p : order) {
            App a = by.remove(p);
            if (a != null) out.add(a);
        }
        out.addAll(by.values());
        return out;
    }

    private void saveOrder(List<App> l) {
        List<String> p = new ArrayList<>();
        for (App a : l) p.add(a.pkg);
        prefs.edit().putString(KEY_ORDER, TextUtils.join(",", p)).apply();
    }

    private List<String> list(String key) {
        List<String> l = new ArrayList<>();
        for (String s : TextUtils.split(prefs.getString(key, ""), ",")) if (!s.isEmpty()) l.add(s);
        return l;
    }

    private Set<String> set(String key) { return new HashSet<>(list(key)); }

    // ================================================================ home row

    /** Apps were installed, removed or changed (also arrives while the user is elsewhere on Home). */
    void reload() {
        apps = loadAll();
        View cur = findFocus();
        if (cur == null || cur.getParent() == row) {   // focus was in the apps row (or nowhere): keep it there
            buildRow(cur != null && cur.getTag() instanceof String ? (String) cur.getTag() : lastFocusTag);
        } else {
            buildRow(null, false);                      // focus is in another row or panel: leave it alone
        }
        if (allPanel.getVisibility() == VISIBLE && optionsLayer.getVisibility() != VISIBLE) {
            View f = findFocus();
            buildAllGrid(f != null && f.getTag() instanceof String ? (String) f.getTag() : null);
        }
    }

    private void buildRow(String focusTag) { buildRow(focusTag, true); }

    private void buildRow(String focusTag, boolean takeFocus) {
        row.removeAllViews();
        List<App> home = homeApps();
        int tileW = px(132 * Look.scale), tileH = px(74 * Look.scale), gap = px(9);
        for (App a : home) {
            View tile = rowTile(a, tileW, tileH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, -2);
            lp.setMargins(gap, 0, gap, 0);
            row.addView(tile, lp);
        }
        if (home.isEmpty()) {
            TextView t = text(16, 0xFFE5E7EB, Look.font(false));
            t.setText(R.string.no_apps);
            row.addView(t);
        }
        if (!takeFocus) return;
        View v = focusTag != null ? row.findViewWithTag(focusTag) : null;
        if (v == null && focusTag != null && focusTag.startsWith("top:")) v = findViewWithTag(focusTag);
        if (v == null && row.getChildCount() > 0 && !home.isEmpty()) v = row.getChildAt(0);
        if (v != null) { final View t = v; t.post(t::requestFocus); }
    }

    private View rowTile(final App a, int w, int h) {
        Context ctx = getContext();
        final LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setTag("app:" + a.pkg);

        final FrameLayout card = card(a, h);
        final View ring = ring();
        card.addView(ring, new LayoutParams(-1, -1));
        cell.addView(card, new LinearLayout.LayoutParams(w, h));

        final TextView label = text(14, Color.WHITE, Look.font(true));
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, px(14), 0, 0);
        label.setAlpha(0f);
        label.setShadowLayer(px(4), 0, px(1), 0xCC000000);
        cell.addView(label, new LinearLayout.LayoutParams(w, -2));

        cell.setOnFocusChangeListener((v, has) -> {
            if (has) { lastFocusTag = (String) v.getTag(); lastInApps = lastFocusTag; }
            boolean isMoving = a.pkg.equals(moving);
            ring.setVisibility(has || isMoving ? VISIBLE : INVISIBLE);
            label.setText(isMoving ? "◀  " + a.label + "  ▶" : a.label);
            float sc = has ? 1.12f : 1f;
            card.animate().scaleX(sc).scaleY(sc).translationZ(has ? px(10) : 0).setDuration(Look.d(220))
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            label.animate().cancel();
            if (has) {   // name rises gently into place
                label.setTranslationY(-px(6));
                label.animate().withLayer().alpha(1f).translationY(0).setStartDelay(60).setDuration(Look.d(260))
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else {     // and fades away a little slower than it would blink
                label.animate().withLayer().alpha(0f).translationY(-px(4)).setStartDelay(0).setDuration(Look.d(200))
                        .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
            }
        });
        cell.setOnClickListener(v -> {
            if (moving != null) { endMove(); return; }
            launch(a);
        });
        final Runnable startMove = () -> {
            moving = a.pkg;
            swallowNextOkUp = true;
            ring.setVisibility(VISIBLE);   // same white outline: the lift and ◀ ▶ show move mode
            label.setText("◀  " + a.label + "  ▶");
            // Lift the tile while it is being moved.
            card.animate().scaleX(1.16f).scaleY(1.16f).translationY(-px(6)).translationZ(px(18))
                    .setDuration(Look.d(160)).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            dropMoved = () -> {
                ring.setVisibility(cell.hasFocus() ? VISIBLE : INVISIBLE);
                label.setText(a.label);
                float sc = cell.hasFocus() ? 1.12f : 1f;
                card.animate().scaleX(sc).scaleY(sc).translationY(0).translationZ(cell.hasFocus() ? px(10) : 0)
                        .setDuration(Look.d(180)).setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            };
        };
        cell.setOnKeyListener((v, code, ev) -> {
            boolean up = ev.getAction() == KeyEvent.ACTION_UP;
            if (moving == null && isHold(v, code, ev)) { startMove.run(); return true; }
            if (moving != null) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (!up) shift(a.pkg, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                if (isOk(code)) {
                    if (up) {
                        if (swallowNextOkUp) swallowNextOkUp = false; else endMove();
                    }
                    return true;
                }
            }
            return false;
        });
        return cell;
    }

    /** Settles the tile that was being moved (set when a move starts). */
    private Runnable dropMoved;

    /** Moves the tile one place left/right: the two tiles slide past each other. */
    private void shift(String pkg, int dir) {
        int i = -1;
        for (int k = 0; k < row.getChildCount(); k++) {
            if (("app:" + pkg).equals(row.getChildAt(k).getTag())) i = k;
        }
        int j = i + dir;
        if (i < 0 || j < 0 || j >= row.getChildCount() || !shiftOrder(pkg, dir)) return;
        final View moved = row.getChildAt(i), other = row.getChildAt(j);
        // Where they are on screen now (including any slide still in progress).
        final float movedX = moved.getLeft() + moved.getTranslationX();
        final float otherX = other.getLeft() + other.getTranslationX();
        // Swap them by moving the neighbour, so the moved tile keeps focus.
        row.removeViewAt(j);
        row.addView(other, i);
        row.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                row.getViewTreeObserver().removeOnPreDrawListener(this);
                android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.6f);
                moved.setTranslationX(movedX - moved.getLeft());
                other.setTranslationX(otherX - other.getLeft());
                moved.animate().translationX(0).setDuration(Look.d(320)).setInterpolator(ease).start();
                other.animate().translationX(0).setDuration(Look.d(380)).setInterpolator(ease).start();
                rowScroll.requestChildRectangleOnScreen(row, new android.graphics.Rect(
                        moved.getLeft(), moved.getTop(), moved.getRight(), moved.getBottom()), true);
                return true;
            }
        });
    }

    private boolean shiftOrder(String pkg, int dir) {
        List<App> home = homeApps();
        int i = -1;
        for (int k = 0; k < home.size(); k++) if (home.get(k).pkg.equals(pkg)) i = k;
        int j = i + dir;
        if (i < 0 || j < 0 || j >= home.size()) return false;
        Collections.swap(home, i, j);
        saveOrder(home);
        return true;
    }

    private void endMove() {
        if (moving == null) return;
        moving = null;
        swallowNextOkUp = false;
        if (dropMoved != null) { dropMoved.run(); dropMoved = null; }
    }

    // ================================================================ Jellyfin rows

    /** One Jellyfin row: a title and horizontally scrolling cards. */
    private final class JfRow {
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout cards;
        String last;   // card selected last in this row

        JfRow(String name) {
            Context ctx = getContext();
            title = text(17, Color.WHITE, Look.font(true));
            title.setText(name);
            title.setPadding(px(50), px(18), 0, px(12));
            bottom.addView(title);
            scroll = new CenterScroll.H(ctx);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(px(40), 0, px(40), 0);
            cards = new LinearLayout(ctx);
            cards.setOrientation(LinearLayout.HORIZONTAL);
            cards.setClipChildren(false);
            cards.setClipToPadding(false);
            scroll.addView(cards, new FrameLayout.LayoutParams(-2, -2));
            bottom.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            show(false);
        }

        void show(boolean on) {
            title.setVisibility(on ? VISIBLE : GONE);
            scroll.setVisibility(on ? VISIBLE : GONE);
        }
    }

    /** Called by MainActivity each time Home shows. */
    void refreshContinue() {
        Jellyfin.refresh(getContext(), this::showJellyfin);
    }

    private void showJellyfin(Jellyfin.Rows rows) {
        if (rows == null) return;
        StringBuilder k = new StringBuilder();
        for (String pref : new String[]{"row_resume", "row_nextup", "row_latest"}) k.append(prefs.getInt(pref, 1));
        for (List<Jellyfin.Item> l : java.util.Arrays.asList(rows.resume, rows.nextUp, rows.latest)) {
            for (Jellyfin.Item it : l) k.append(it.id).append(it.subtitle).append('|');
            k.append('#');
        }
        if (k.toString().equals(jfKey)) return;   // unchanged: don't rebuild under the user's focus
        jfKey = k.toString();
        fill(resumeRow, rows.resume, "row_resume");
        fill(nextRow, rows.nextUp, "row_nextup");
        fill(latestRow, rows.latest, "row_latest");
    }

    private void fill(JfRow r, List<Jellyfin.Item> items, String pref) {
        View f = findFocus();
        boolean hadFocus = f != null && f.getParent() == r.cards;
        String keep = hadFocus ? (String) f.getTag() : null;
        r.cards.removeAllViews();
        boolean on = prefs.getInt(pref, 1) == 1 && !items.isEmpty();
        r.show(on);
        if (!on) return;
        int w = px(204 * Look.scale), h = px(115 * Look.scale), gap = px(10);
        for (Jellyfin.Item it : items) {
            View c = jfCard(it, w, h, r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            lp.setMargins(gap, 0, gap, 0);
            r.cards.addView(c, lp);
        }
        if (hadFocus) {
            View v = r.cards.findViewWithTag(keep);
            if (v == null) v = r.cards.getChildAt(0);
            final View t = v;
            t.post(t::requestFocus);
        }
    }

    private View jfCard(final Jellyfin.Item it, int w, int h, final JfRow r) {
        Context ctx = getContext();
        final LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setTag("jf:" + it.id);

        final FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius());
        bg.setColor(0x40000000);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        final ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, new LayoutParams(-1, -1));
        Jellyfin.image(ctx, it.imageUrl, b -> {
            iv.setImageBitmap(b);
            iv.setAlpha(0f);
            iv.animate().alpha(1f).setDuration(Look.d(200)).start();
        });
        final View ring = ring();
        card.addView(ring, new LayoutParams(-1, -1));
        cell.addView(card, new LinearLayout.LayoutParams(w, h));

        if (it.progress > 0) {   // progress bar under the picture
            FrameLayout track = new FrameLayout(ctx);
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(px(2));
            tb.setColor(0x55FFFFFF);
            track.setBackground(tb);
            View fill = new View(ctx);
            GradientDrawable fb = new GradientDrawable();
            fb.setCornerRadius(px(2));
            fb.setColor(Color.WHITE);
            fill.setBackground(fb);
            track.addView(fill, new LayoutParams(Math.max(px(4), (int) (w * it.progress)), -1));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(w, px(3));
            tl.topMargin = px(8);
            cell.addView(track, tl);
        }

        TextView title = text(15, Color.WHITE, Look.font(false));
        title.setText(it.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, px(8), 0, 0);
        cell.addView(title, new LinearLayout.LayoutParams(w, -2));
        TextView sub = text(13, 0xFFF3F4F6, Look.font(false));
        sub.setText(it.subtitle);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setAlpha(0.8f);
        sub.setPadding(0, px(4), 0, 0);
        cell.addView(sub, new LinearLayout.LayoutParams(w, -2));

        cell.setOnFocusChangeListener((v, has) -> {
            if (has) { lastFocusTag = (String) v.getTag(); r.last = lastFocusTag; }
            ring.setVisibility(has ? VISIBLE : INVISIBLE);
            float sc = has ? 1.06f : 1f;
            card.animate().scaleX(sc).scaleY(sc).translationZ(has ? px(10) : 0).setDuration(Look.d(180)).start();
        });
        cell.setOnClickListener(v -> openJf(Jellyfin.play(getContext(), it)));
        cell.setOnKeyListener((v, code, ev) -> {
            if (isHold(v, code, ev)) { showItemMenu(it, cell); return true; }
            if (heldOk && isOk(code)) {   // the release of that hold: not a click
                if (ev.getAction() == KeyEvent.ACTION_UP) { heldOk = false; v.setPressed(false); }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showItemMenu(it, cell);
                return true;
            }
            return false;
        });
        return cell;
    }

    private void openJf(Intent i) {
        if (i == null) { toast(str(R.string.no_player)); return; }
        try { getContext().startActivity(i); } catch (Exception e) { toast(str(R.string.player_failed)); }
    }

    /** Hold OK on a Jellyfin card: play, details, mark watched, remove from Continue watching. */
    private void showItemMenu(final Jellyfin.Item it, View from) {
        menu(it.title);
        View first = option(R.drawable.lucide_play, str(it.positionMs > 0 ? R.string.menu_resume : R.string.menu_play), () -> {
            closeOptions();
            openJf(Jellyfin.play(getContext(), it));
        });
        optionsCard.addView(first);
        optionsCard.addView(option(R.drawable.lucide_info, str(R.string.menu_details), () -> {
            closeOptions();
            openJf(Jellyfin.details(getContext(), it));
        }));
        if (!"Series".equals(it.type)) {
            optionsCard.addView(option(R.drawable.lucide_circle_check, str(R.string.menu_watched), () -> {
                closeOptions();
                Jellyfin.markWatched(getContext(), it, ok -> afterServerChange(ok, getContext().getString(R.string.toast_watched, it.title)));
            }));
        }
        if (it.resumable) {
            optionsCard.addView(option(R.drawable.lucide_x, str(R.string.menu_remove_resume), () -> {
                closeOptions();
                Jellyfin.removeFromResume(getContext(), it,
                        ok -> afterServerChange(ok, getContext().getString(R.string.toast_removed_resume, it.title)));
            }));
        }
        showMenu(first, from);
    }

    private void afterServerChange(boolean ok, String message) {
        toast(ok ? message : str(R.string.server_unreachable));
        if (ok) Jellyfin.refresh(getContext(), this::showJellyfin, true);
    }

    /** While a panel is open, nothing behind it may take focus. */
    private void homeFocusable(boolean on) {
        int mode = on ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS;
        bar.setDescendantFocusability(mode);
        bottom.setDescendantFocusability(mode);
    }

    // ================================================================ all apps

    private void openAllApps() {
        endMove();
        homeFocusable(false);
        allPanel.setVisibility(VISIBLE);
        allPanel.setAlpha(0f);
        allPanel.animate().alpha(1f).setDuration(Look.d(150)).start();
        buildAllGrid(null);
        allScroll.scrollTo(0, 0);
    }

    private void closeAllApps() {
        if (allPanel.getVisibility() != VISIBLE) return;
        allPanel.setVisibility(GONE);
        homeFocusable(true);
        buildRow("top:all");
    }

    private void buildAllGrid(String focusTag) {
        allGrid.removeAllViews();
        int cols = Look.scale > 1.1f ? 5 : Look.scale < 0.9f ? 7 : 6, gap = px(10);
        int w = (getResources().getDisplayMetrics().widthPixels - px(96) - cols * 2 * gap) / cols;
        int h = w * 9 / 16;
        LinearLayout r = null;
        View first = null, wanted = null;
        List<App> listed = new ArrayList<>();
        for (App a : apps) if (!a.builtIn || DEFAULT_SHOWN.contains(a.pkg)) listed.add(a);
        for (int i = 0; i < listed.size(); i++) {
            if (i % cols == 0) {
                r = new LinearLayout(getContext());
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setClipChildren(false);
                allGrid.addView(r);
            }
            View t = allTile(listed.get(i), w, h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            lp.setMargins(gap, gap, gap, gap);
            r.addView(t, lp);
            if (first == null) first = t;
            if (t.getTag().equals(focusTag)) wanted = t;
        }
        final View f = wanted != null ? wanted : first;
        if (f != null) f.post(f::requestFocus);
    }

    private View allTile(final App a, int w, int h) {
        Context ctx = getContext();
        final LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setTag("all:" + a.pkg);
        final boolean shown = onHome(a);

        final FrameLayout card = card(a, h);
        final View ring = ring();
        card.addView(ring, new LayoutParams(-1, -1));
        if (shown) {   // small house badge, top-left: this app is on Home
            FrameLayout badge = new FrameLayout(getContext());
            GradientDrawable bb = new GradientDrawable();
            bb.setShape(GradientDrawable.OVAL);
            bb.setColor(0xB3000000);
            badge.setBackground(bb);
            ImageView hi = new ImageView(getContext());
            hi.setImageResource(R.drawable.lucide_house);
            badge.addView(hi, new LayoutParams(px(14), px(14), Gravity.CENTER));
            LayoutParams bl = new LayoutParams(px(26), px(26), Gravity.TOP | Gravity.START);
            bl.setMargins(px(6), px(6), 0, 0);
            card.addView(badge, bl);
        }
        cell.addView(card, new LinearLayout.LayoutParams(-1, h));

        final TextView label = text(13, 0xFFE5E7EB, Look.font(false));
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, px(7), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(-1, -2));

        cell.setOnFocusChangeListener((v, has) -> {
            ring.setVisibility(has ? VISIBLE : INVISIBLE);
            float sc = has ? 1.07f : 1f;
            card.animate().scaleX(sc).scaleY(sc).setDuration(Look.d(140)).start();
        });
        cell.setOnClickListener(v -> launch(a));
        cell.setOnKeyListener((v, code, ev) -> {
            if (isHold(v, code, ev)) { showOptions(a); return true; }
            if (heldOk && isOk(code)) {   // the release of that hold: not a click
                if (ev.getAction() == KeyEvent.ACTION_UP) { heldOk = false; v.setPressed(false); }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showOptions(a);
                return true;
            }
            return false;
        });
        return cell;
    }

    // ================================================================ app options

    /** Starts a menu card with a title; add options, then call showMenu. */
    private void menu(String name) {
        optionsCard.removeAllViews();
        TextView title = text(18, Color.WHITE, Look.font(true));
        title.setText(name);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(px(14), 0, px(14), px(10));
        optionsCard.addView(title);
    }

    /** Where focus goes back to when the menu closes. */
    private View menuReturn;

    private void showMenu(View first, View returnTo) {
        menuReturn = returnTo;
        allPanel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        homeFocusable(false);
        optionsLayer.setVisibility(VISIBLE);
        optionsLayer.setAlpha(0f);
        optionsLayer.animate().alpha(1f).setDuration(Look.d(120)).start();
        first.post(first::requestFocus);
    }

    private void showOptions(final App a) {
        final boolean shown = onHome(a);
        menu(a.label);
        View first = option(shown ? R.drawable.lucide_circle_minus : R.drawable.lucide_house_plus,
                str(shown ? R.string.menu_remove_home : R.string.menu_add_home), () -> {
                    setOnHome(a, !shown);
                    if (!shown) {   // new Home apps go to the end of the row
                        List<String> o = list(KEY_ORDER);
                        o.remove(a.pkg);
                        o.add(a.pkg);
                        prefs.edit().putString(KEY_ORDER, TextUtils.join(",", o)).apply();
                    }
                    toast(getContext().getString(shown ? R.string.toast_removed_home : R.string.toast_added_home, a.label));
                    closeOptions();
                    buildAllGrid("all:" + a.pkg);
                });
        optionsCard.addView(first);
        optionsCard.addView(option(R.drawable.lucide_info, str(R.string.menu_app_info), () -> {
            closeOptions();
            launch(intentApp(str(R.string.menu_app_info), new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }));
        showMenu(first, allGrid.findViewWithTag("all:" + a.pkg));
    }

    private void closeOptions() {
        if (optionsLayer.getVisibility() != VISIBLE) return;
        optionsLayer.setVisibility(GONE);
        allPanel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (allPanel.getVisibility() != VISIBLE) homeFocusable(true);
        if (menuReturn != null) menuReturn.requestFocus();
    }

    private View option(int icon, String label, final Runnable action) {
        Context ctx = getContext();
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(px(14), px(11), px(14), px(11));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius() * 0.8f);
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        final ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        r.addView(iv, new LinearLayout.LayoutParams(px(20), px(20)));
        final TextView t = text(16, Color.WHITE, Look.font(false));
        t.setText(label);
        t.setShadowLayer(0, 0, 0, 0);
        t.setPadding(px(14), 0, 0, 0);
        r.addView(t);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Look.accent : Color.TRANSPARENT);
            iv.setColorFilter(has ? Look.onAccent() : Color.WHITE);
            t.setTextColor(has ? Look.onAccent() : Color.WHITE);
        });
        r.setOnClickListener(v -> action.run());
        return r;
    }

    // ================================================================ pieces

    private FrameLayout card(App a, int h) {
        Context ctx = getContext();
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius());
        bg.setColor(0x33FFFFFF);
        bg.setStroke(Math.max(1, px(0.5f)), 0x33FFFFFF);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        ImageView iv = new ImageView(ctx);
        if (a.banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            card.addView(iv, new LayoutParams(-1, -1));
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.icon);
            int s = (int) (h * 0.6f);
            card.addView(iv, new LayoutParams(s, s, Gravity.CENTER));
        }
        return card;
    }

    private View ring() {
        View ring = new View(getContext());
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius());
        g.setStroke(px(2.5f), Look.accent);
        ring.setBackground(g);
        ring.setVisibility(INVISIBLE);
        return ring;
    }

    private View iconButton(int icon, String name, String tag, final Runnable action) {
        Context ctx = getContext();
        final FrameLayout b = new FrameLayout(ctx);
        b.setFocusable(true);
        b.setClickable(true);
        b.setTag(tag);
        b.setContentDescription(name);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x40000000);   // faint dark disc: icons stay visible on bright pictures
        b.setBackground(bg);
        final ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        b.addView(iv, new LayoutParams(px(24), px(24), Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            if (has) lastFocusTag = (String) v.getTag();
            bg.setColor(has ? Look.accent : 0x40000000);
            iv.setColorFilter(has ? Look.onAccent() : Color.WHITE);
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(46), px(46));
        lp.setMargins(px(6), 0, px(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void openCubeSettings() {
        endMove();
        homeFocusable(false);
        settings = new SettingsPanel(getContext(), this);
        addView(settings, new LayoutParams(-1, -1));
        settings.setAlpha(0f);
        settings.animate().alpha(1f).setDuration(Look.d(150)).start();
        settings.post(settings::focusFirst);
    }

    @Override
    public void closeSettings(boolean appearanceChanged) {
        if (appearanceChanged) {   // rebuild the whole screen with the new look
            ((android.app.Activity) getContext()).recreate();
            return;
        }
        removeView(settings);
        settings = null;
        homeFocusable(true);
        View gear = findViewWithTag("top:settings");
        if (gear != null) gear.requestFocus();
        jfKey = "";   // rows may have been switched on/off
        showJellyfin(Jellyfin.cached());
        Jellyfin.refresh(getContext(), this::showJellyfin, true);
    }

    @Override
    public void checkForUpdate() { Updater.check(getContext(), true); }

    @Override
    public void openFireTvSettings() {
        launch(intentApp(str(R.string.set_firetv), new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS")
                .setPackage(HomeService.AMAZON_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private void openAmazonHome() {
        HomeService.startBypass();
        launch(intentApp(str(R.string.btn_amazon_home), new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeService.AMAZON_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private App intentApp(String label, Intent i) {
        App a = new App();
        a.label = label;
        a.intent = i;
        return a;
    }

    private void launch(App a) {
        try {
            getContext().startActivity(a.intent);
        } catch (Exception e) {
            HomeService.bypass = false;
            toast(getContext().getString(R.string.open_failed, a.label));
        }
    }

    /** Set while OK is held after a hold action fired, so its release isn't taken as a click. */
    private boolean heldOk;

    /**
     * True once, when OK has been held (~0.5 s): the remote's first key repeat carries the
     * long-press flag. Used instead of View long-clicks, which TV remotes don't always trigger.
     */
    private boolean isHold(View v, int code, KeyEvent ev) {
        if (!isOk(code) || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (ev.getRepeatCount() == 0) { heldOk = false; return false; }
        if (heldOk) return false;   // already handled this hold
        heldOk = true;
        v.cancelLongPress();
        v.setPressed(false);
        return true;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void toast(String s) { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        if (color == Color.WHITE || color == 0xFFF3F4F6) t.setShadowLayer(px(8), 0, px(1), 0xCC000000);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }

    private float radius() { return Look.radiusDp * dp; }

    private String str(int id) { return getContext().getString(id); }
}
