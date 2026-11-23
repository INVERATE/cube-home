package dev.cube.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * When the user opens Cube Home, put back the system settings it depends on if they
 * are missing: e.g. after a force-stop (Android then drops the app from the enabled
 * accessibility services) or a Fire OS update resetting settings.
 *
 * Deliberately only called from MainActivity, i.e. only when the user opens the app.
 * There is no boot receiver or background trigger, so a force-stop really stops Cube
 * Home until the user opens it again.
 *
 * Needs WRITE_SECURE_SETTINGS, granted once over ADB by install.sh.
 */
final class SelfRepair {
    private static final String ALARM_KEY = "alarm_manager_constants";
    private static final String ALARM_VALUE = "min_futurity=0";

    private SelfRepair() { }

    /**
     * After an update, or after Fire OS force-stops Cube Home when waking from the screensaver,
     * the Home button service stays unbound: switch it off and on again (what install.sh does).
     */
    static void rebind(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        Log.i(HomeService.TAG, "Home button service not running: rebinding it");
        try {
            String svc = c.getPackageName() + "/" + HomeService.class.getName();
            String list = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder others = new StringBuilder();
            if (list != null) {
                for (String s : TextUtils.split(list, ":")) {
                    if (!s.isEmpty() && !s.equalsIgnoreCase(svc) && !"null".equals(s)) {
                        others.append(others.length() > 0 ? ":" : "").append(s);
                    }
                }
            }
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    others.length() > 0 ? others.toString() : null);
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    others.length() > 0 ? others + ":" + svc : svc);
        } catch (Exception e) {
            Log.w(HomeService.TAG, "rebind after update failed: " + e);
        }
        run(c);
    }

    static void run(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            String svc = c.getPackageName() + "/" + HomeService.class.getName();
            String list = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean present = false;
            if (list != null) {
                for (String s : TextUtils.split(list, ":")) if (s.equalsIgnoreCase(svc)) present = true;
            }
            if (!present) {
                // Keep any other accessibility services the user has enabled.
                boolean empty = list == null || list.isEmpty() || "null".equals(list);
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, empty ? svc : list + ":" + svc);
                Log.i(HomeService.TAG, "opened by user: Home button service re-enabled");
            }
            if (Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            String alarm = Settings.Global.getString(c.getContentResolver(), ALARM_KEY);
            if (alarm == null || !alarm.contains(ALARM_VALUE)) {
                boolean empty = alarm == null || alarm.isEmpty() || "null".equals(alarm);
                Settings.Global.putString(c.getContentResolver(), ALARM_KEY,
                        empty ? ALARM_VALUE : alarm + "," + ALARM_VALUE);
                Log.i(HomeService.TAG, "opened by user: alarm min_futurity restored");
            }
        } catch (Exception e) {
            Log.w(HomeService.TAG, "settings repair failed: " + e);
        }
    }
}
