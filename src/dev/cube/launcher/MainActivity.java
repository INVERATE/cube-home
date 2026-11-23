package dev.cube.launcher;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.accessibility.AccessibilityManager;

public class MainActivity extends Activity {
    private LauncherView view;
    private BroadcastReceiver pkgReceiver;
    private boolean dirty = true;
    private static final long COVER_HOLD_MS = 400;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));   // texts in the language chosen in the settings
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new LauncherView(this);
        setContentView(view);

        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                dirty = true;
                if (hasWindowFocus()) { dirty = false; view.reload(); }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SelfRepair.run(this);   // only on user-visible opens; no background restarts
        Updater.check(this, false);   // at most once a day
        view.refreshContinue();
        HomeService.bypass = false;
        HomeService.cubeVisible = true;
        HomeService.unanswered = 0;
        if (!serviceEnabled()) {
            // Listed in the settings but not running: Fire OS force-stops Cube Home when the TV
            // wakes from its screensaver, and Android then leaves the service unbound.
            // Switching it off and on again binds it (needs WRITE_SECURE_SETTINGS).
            SelfRepair.rebind(this);
            view.postDelayed(() -> view.setNotice(serviceEnabled() ? null : getString(R.string.notice_service)), 1500);
        } else {
            view.setNotice(null);
        }
        // Rebuild only when apps were installed/removed, so returning home never flickers.
        if (dirty) {
            dirty = false;
            view.reload();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        HomeService.cubeVisible = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // We're on screen. Amazon's window still fades out over ours for a moment after
        // we get focus, so keep the black cover up until that has finished.
        if (hasFocus) view.postDelayed(HomeService::cubeDrawn, COVER_HOLD_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!intent.getBooleanExtra("from_home", false)) view.goTop(); else view.closePanels();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
    }

    @Override
    public void onBackPressed() { /* this is the home screen; nothing behind it */ }

    private boolean serviceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo i :
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (i.getId() != null && i.getId().startsWith(getPackageName() + "/")) return true;
        }
        return false;
    }
}
