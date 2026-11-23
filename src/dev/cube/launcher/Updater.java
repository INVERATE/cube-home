package dev.cube.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from the GitHub repository's latest release. A release is tagged "v<versionCode>"
 * and carries cubehome.apk (signed with the same key). Checked once a day when Home shows, or
 * from Settings > Check for updates. Android asks the user to confirm the install on the TV.
 */
final class Updater {
    static final String REPO = "INVERATE/cube-home";
    private static final String TAG = "CubeHome";
    private static final long DAY = 24 * 3600 * 1000L;
    private static volatile boolean busy;

    private Updater() { }

    static final String SCREENSAVER = "dev.cube.screensaver";

    /** manual: from the settings (always checks, reports "up to date"); else at most once a day. */
    static void check(Context ctx, boolean manual) { check(ctx, manual, manual); }

    /**
     * A release carries cubehome.apk and screensaver.apk. One app is updated per run, the
     * screensaver first: its install finishing starts the next run (see Receiver), and the
     * Cube Home install, which restarts this app, comes last.
     */
    private static void check(Context ctx, boolean manual, boolean now) {
        final Context c = ctx.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences("cubehome", Context.MODE_PRIVATE);
        if (REPO.isEmpty() || busy) return;
        if (!now && System.currentTimeMillis() - p.getLong("update_check", 0) < DAY) return;
        busy = true;
        p.edit().putLong("update_check", System.currentTimeMillis()).apply();
        if (manual) toast(c, R.string.update_checking);
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(new String(get(
                        "https://api.github.com/repos/" + REPO + "/releases/latest"), "UTF-8"));
                int latest = Integer.parseInt(rel.getString("tag_name").replaceAll("\\D", ""));
                String want = installed(c, SCREENSAVER) < latest ? "screensaver.apk"
                        : installed(c, c.getPackageName()) < latest ? "cubehome.apk" : null;
                String apk = null;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").equals(want)) apk = a.getString("browser_download_url");
                }
                if (apk == null && "screensaver.apk".equals(want)
                        && installed(c, c.getPackageName()) < latest) {   // older release without it
                    want = "cubehome.apk";
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.getString("name").equals(want)) apk = a.getString("browser_download_url");
                    }
                }
                if (apk == null) {
                    if (manual) toast(c, R.string.update_uptodate);
                    return;
                }
                toast(c, R.string.update_downloading);
                install(c, get(apk), manual);
            } catch (Exception e) {
                Log.w(TAG, "update: " + e);
                if (manual) toast(c, R.string.update_check_failed);
            } finally {
                busy = false;
            }
        }, "cubehome-update").start();
    }

    /** Hands the APK to Android's installer; Receiver shows the confirmation. */
    /** Installed versionCode of a package, or -1. */
    private static int installed(Context c, String pkg) {
        try {
            return c.getPackageManager().getPackageInfo(pkg, 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void install(Context c, byte[] apk, boolean manual) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int id = pi.createSession(new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("cubehome.apk", 0, apk.length)) {
                out.write(apk);
                s.fsync(out);
            }
            PendingIntent done = PendingIntent.getBroadcast(c, id,
                    new Intent(c, Receiver.class).putExtra("manual", manual), PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(done.getIntentSender());
        }
    }

    /** Install status (shows Android's confirmation), and "Cube Home was updated". */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
                // Updated: rebind the Home button service and come back to Home.
                SelfRepair.rebind(c);
                c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                SelfRepair.run(c);                                  // points Fire OS at the new screensaver
                check(c, i.getBooleanExtra("manual", false), true); // then the next app, if any
            } else {
                Log.w(TAG, "update: install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                Toast.makeText(c, Lang.wrap(c).getString(R.string.update_not_installed), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(30000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode() + " " + url);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toByteArray();
            }
        } finally {
            h.disconnect();
        }
    }

    private static void toast(Context c, int text) {
        String s = Lang.wrap(c).getString(text);
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, s, Toast.LENGTH_SHORT).show());
    }
}
