package dev.cube.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Sends the Home button to Cube Home on Fire OS 7.
 *
 * Fire OS handles KEYCODE_HOME itself (FireTVKeyPolicyManager starts Amazon's
 * HomeActivity by component name) and never delivers it to apps. Two signals:
 *
 *  A. System log (primary, needs `pm grant dev.cube.launcher android.permission.READ_LOGS`):
 *     ActivityManager logs "START ... cat=[HOME] cmp=com.amazon.tv.launcher/... from
 *     uid 1000" the instant Home is pressed, before Amazon has drawn anything, and
 *     also when Amazon's home is already showing. uid 1000 = the system's Home key
 *     handler, so our own "Amazon Home" tile (our uid) is never mistaken for it.
 *  B. Accessibility (fallback): Amazon's HomeActivity window comes to the front
 *     (~150 ms later than A). Also catches Amazon re-opening its home by itself.
 *
 * Response: a plain black, non-focusable accessibility overlay (instant) hides
 * Amazon's home, and Cube Home is started through a ~20 ms AlarmManager PendingIntent,
 * which is sent by system_server and so skips the 5 s "app switch" lock after Home
 * (needs `settings put global alarm_manager_constants min_futurity=0`). Once the real
 * Cube Home is on screen the black cover fades out, so Cube Home fades in.
 *
 * Safety nets: re-check 1.5 s after each redirect; check the screen at service start
 * (boot); stop redirecting if Cube Home fails to appear 6 times in a row.
 */
public class HomeService extends AccessibilityService {
    static final String TAG = "CubeHome";
    static final String AMAZON_HOME = "com.amazon.tv.launcher";

    /** "Amazon Home" tile is active: leave Amazon's home alone until Home is pressed. */
    static volatile boolean bypass = false;
    /** Maintained by MainActivity. */
    static volatile boolean cubeVisible = false;
    /** Redirects since Cube Home last appeared; caps a crash loop if it can't start. */
    static volatile int unanswered = 0;

    private static HomeService instance;

    private static final long DUPLICATE_MS = 300;   // one Home press = log line + window event
    private static final long VERIFY_MS = 1500;
    private static final long COVER_MAX_MS = 1500;
    private static final long FADE_MS = 300;        // Cube Home fades in from black
    private static final int MAX_UNANSWERED = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PendingIntent launch;
    private WindowManager wm;
    private View cover;
    private boolean amazonHomeFront = false;
    private long lastFire = -10000;
    private volatile boolean running;
    private Process logProc;

    /** Called by the "Amazon Home" tile before it opens Amazon's home. */
    static void startBypass() { bypass = true; }

    /** Called by MainActivity once it is fully on screen. */
    static void cubeDrawn() {
        HomeService s = instance;
        if (s != null) s.handler.post(s::fadeOutCover);
    }

    static boolean canReadLogs(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final Runnable verify = () -> {
        if (amazonHomeFront && !cubeVisible && !bypass) {
            Log.i(TAG, "verify: Amazon home still in front, redirecting again");
            fire();
        }
    };

    private final Runnable coverTimeout = this::fadeOutCover;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("from_home", true);
        launch = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        running = true;
        startLogWatcher();

        // After boot, Amazon's home may already be up before we connected.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence pkg = root.getPackageName();
            root.recycle();
            if (pkg != null && AMAZON_HOME.contentEquals(pkg)) {
                amazonHomeFront = true;
                maybeFire(0, "boot");
            }
        }
    }

    // ------------------------------------------------------------ signal A: system log

    private void startLogWatcher() {
        if (!canReadLogs(this)) {
            Log.w(TAG, "READ_LOGS not granted; using the slower window-event signal only");
            return;
        }
        Thread t = new Thread(() -> {
            long backoff = 500;
            while (running) {
                try {
                    // -T 1: start at the newest line, then follow. Only ActivityManager INFO.
                    logProc = new ProcessBuilder("logcat", "-v", "brief", "-T", "1",
                            "-s", "ActivityManager:I").redirectErrorStream(true).start();
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(logProc.getInputStream()), 1024);
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        if (line.contains("cat=[android.intent.category.HOME]")
                                && line.contains("cmp=" + AMAZON_HOME + "/")
                                && line.trim().endsWith("from uid 1000")) {
                            handler.post(this::onHomeKey);
                        }
                        backoff = 500;
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "log watcher: " + ex);
                } finally {
                    if (logProc != null) logProc.destroy();
                }
                if (running) SystemClock.sleep(backoff);
                backoff = Math.min(backoff * 2, 10000);
            }
        }, "cubehome-logwatch");
        t.setDaemon(true);
        t.start();
    }

    /** The Home button was pressed (anywhere, including on Amazon's home). */
    private void onHomeKey() {
        bypass = false;   // Home always means "take me home", even after the Amazon Home tile
        maybeFire(DUPLICATE_MS, "home key");
    }

    // ------------------------------------------------------------ signal B: accessibility

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        String cls = e.getClassName() == null ? "" : e.getClassName().toString();
        if (!cls.contains("Activity")) return;   // popups / sub-windows, not a new screen

        if (AMAZON_HOME.equals(pkg)) {
            amazonHomeFront = cls.contains("HomeActivity");
            if (amazonHomeFront) maybeFire(DUPLICATE_MS, "window");
        } else {
            amazonHomeFront = false;
            if (!pkg.equals(getPackageName())) {
                hideCover();   // another app took the screen
                // Opening a real app also ends the "Amazon Home" escape.
                if (!pkg.startsWith("com.amazon.tv.") && !pkg.equals("com.amazon.vizzini")
                        && !pkg.startsWith("com.android.systemui")) {
                    bypass = false;
                }
            }
        }
    }

    // ------------------------------------------------------------ response

    private void maybeFire(long minGap, String why) {
        if (bypass) return;
        if (SystemClock.elapsedRealtime() - lastFire < minGap) return;
        if (unanswered >= MAX_UNANSWERED) {
            Log.w(TAG, "Cube Home did not appear after " + unanswered + " tries; pausing redirects");
            return;
        }
        Log.i(TAG, "redirect (" + why + ")");
        fire();
    }

    private void fire() {
        showCover();
        long now = SystemClock.elapsedRealtime();
        lastFire = now;
        unanswered++;
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        try {
            // Plain setExact: the allow-while-idle variant is rate-limited per app.
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 20, launch);
        } catch (Exception ex) {
            try { launch.send(); } catch (Exception ignored) { }
        }
        handler.removeCallbacks(verify);
        handler.postDelayed(verify, VERIFY_MS);
    }

    private void showCover() {
        if (cover != null) return;
        try {
            View v = new View(this);
            v.setBackgroundColor(Color.BLACK);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT);   // translucent so it can fade out
            lp.windowAnimations = 0;
            wm.addView(v, lp);
            cover = v;
            handler.removeCallbacks(coverTimeout);
            handler.postDelayed(coverTimeout, COVER_MAX_MS);
        } catch (Exception ex) {
            cover = null;
        }
    }

    /** Cube Home is on screen: fade the black away so it fades in. */
    private void fadeOutCover() {
        handler.removeCallbacks(coverTimeout);
        final View v = cover;
        if (v == null) return;
        cover = null;   // a new Home press during the fade gets a fresh cover
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            // Hide first and remove the window a few frames later: removing it straight
            // away can flash one black frame.
            v.setVisibility(View.INVISIBLE);
            handler.postDelayed(() -> {
                try { wm.removeView(v); } catch (Exception ignored) { }
            }, 100);
        }).start();
    }

    /** Remove at once (another app is opening, or the safety timeout). */
    private void hideCover() {
        handler.removeCallbacks(coverTimeout);
        if (cover == null) return;
        try { wm.removeView(cover); } catch (Exception ignored) { }
        cover = null;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        if (logProc != null) logProc.destroy();
        hideCover();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
