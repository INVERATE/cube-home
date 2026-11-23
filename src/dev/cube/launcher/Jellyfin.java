package dev.cube.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Jellyfin rows on Home (Fire OS gives launchers no Watch Next data): Continue watching,
 * Next up and Recently added. Items play in Wholphin (a Jellyfin client that can start
 * playback from an intent and reports progress to the server); without Wholphin they open
 * in the Jellyfin app. Server, API key and user are set in Cube Home's settings.
 */
final class Jellyfin {
    private static final String TAG = "CubeHome";
    static final String APP = "org.jellyfin.androidtv";
    static final String WHOLPHIN = "com.github.damontecres.wholphin";

    static final class Item {
        String id, type, title, subtitle, imageUrl;
        float progress;      // 0..1
        long positionMs;     // resume point
        boolean resumable;   // in Continue watching
    }

    /** The three rows; any may be empty. */
    static final class Rows {
        List<Item> resume = new ArrayList<>(), nextUp = new ArrayList<>(), latest = new ArrayList<>();
    }

    interface Sink { void accept(Rows rows); }
    interface ImageSink { void accept(Bitmap b); }
    interface Done { void accept(boolean ok); }

    private static final ExecutorService net = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static Rows last;
    private static long lastFetch;

    private Jellyfin() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cubehome", Context.MODE_PRIVATE);
    }

    static boolean configured(Context c) {
        return !prefs(c).getString("jf_url", "").isEmpty() && !prefs(c).getString("jf_key", "").isEmpty();
    }

    /** New server settings: forget the cached user and rows. */
    static void reset() {
        last = null;
        lastFetch = 0;
    }

    /** Last result right away (may be null), then a fresh one if older than 15 s. */
    static Rows cached() { return last; }

    static void refresh(Context ctx, Sink sink) { refresh(ctx, sink, false); }

    static void refresh(Context ctx, Sink sink, boolean now) {
        final Context c = ctx.getApplicationContext();
        if (!configured(c)) return;
        if (!now && System.currentTimeMillis() - lastFetch < 15000) return;
        lastFetch = System.currentTimeMillis();
        net.execute(() -> {
            try {
                String base = base(c), uid = userId(c);
                Context t = Lang.wrap(c);   // texts in the chosen language
                Rows r = new Rows();
                Set<String> seen = new HashSet<>();
                JSONArray a = new JSONObject(get(c, "/UserItems/Resume?userId=" + uid
                        + "&limit=12&mediaTypes=Video&enableUserData=true")).getJSONArray("Items");
                for (int k = 0; k < a.length(); k++) add(r.resume, seen, parse(t, base, a.getJSONObject(k), true));
                a = new JSONObject(get(c, "/Shows/NextUp?userId=" + uid + "&limit=12&enableUserData=true"))
                        .getJSONArray("Items");
                for (int k = 0; k < a.length(); k++) add(r.nextUp, seen, parse(t, base, a.getJSONObject(k), false));
                a = new JSONArray(get(c, "/Items/Latest?userId=" + uid
                        + "&limit=16&includeItemTypes=Movie,Episode&groupItems=true&enableUserData=true"));
                for (int k = 0; k < a.length(); k++) add(r.latest, seen, parse(t, base, a.getJSONObject(k), false));
                last = r;
                main.post(() -> sink.accept(r));
            } catch (Exception e) {
                Log.w(TAG, "jellyfin: " + e);
                lastFetch = System.currentTimeMillis() - 10000;   // retry sooner
            }
        });
    }

    /** Each item appears in one row only (the first that has it). */
    private static void add(List<Item> row, Set<String> seen, Item it) {
        if (seen.add(it.id)) row.add(it);
    }

    private static Item parse(Context t, String base, JSONObject o, boolean resume) throws Exception {
        Item it = new Item();
        it.id = o.getString("Id");
        it.type = o.optString("Type");
        it.resumable = resume;
        JSONObject ud = o.optJSONObject("UserData");
        long run = o.optLong("RunTimeTicks", 0), pos = ud == null ? 0 : ud.optLong("PlaybackPositionTicks", 0);
        it.positionMs = pos / 10_000L;
        it.progress = run > 0 ? Math.min(1f, (float) pos / run) : 0f;
        String left = run > 0 && pos > 0 ? minutes(t, (run - pos) / 600_000_000L) : "";
        boolean episode = "Episode".equals(it.type);
        if (episode) {
            it.title = o.optString("SeriesName", o.optString("Name"));
            String se = "S" + o.optInt("ParentIndexNumber", 0) + " E" + o.optInt("IndexNumber", 0);
            it.subtitle = se + (left.isEmpty() ? " · " + o.optString("Name") : " · " + left);
        } else if ("Series".equals(it.type)) {
            it.title = o.optString("Name");
            int n = o.optInt("ChildCount", 0);
            it.subtitle = n > 1 ? t.getString(R.string.jf_new_eps, n) : t.getString(R.string.jf_new);
        } else {
            it.title = o.optString("Name");
            it.subtitle = !left.isEmpty() ? left
                    : o.has("ProductionYear") ? String.valueOf(o.optInt("ProductionYear")) : "";
        }
        // Landscape art: the episode's series thumb, else the item's thumb, else a backdrop, else primary.
        JSONObject tags = o.optJSONObject("ImageTags");
        String img;
        if (episode && o.has("ParentThumbItemId")) {
            img = "/Items/" + o.getString("ParentThumbItemId") + "/Images/Thumb";
        } else if (tags != null && tags.has("Thumb")) {
            img = "/Items/" + it.id + "/Images/Thumb";
        } else if (o.optJSONArray("BackdropImageTags") != null && o.getJSONArray("BackdropImageTags").length() > 0) {
            img = "/Items/" + it.id + "/Images/Backdrop";
        } else if (episode && o.has("ParentBackdropItemId")) {
            img = "/Items/" + o.getString("ParentBackdropItemId") + "/Images/Backdrop";
        } else {
            img = "/Items/" + it.id + "/Images/Primary";
        }
        it.imageUrl = base + img + "?maxWidth=480&quality=85";
        return it;
    }

    private static String minutes(Context t, long m) {
        if (m < 1) return "";
        int mm = (int) m;
        return mm >= 60 ? t.getString(R.string.jf_left_h, mm / 60, mm % 60) : t.getString(R.string.jf_left_min, mm);
    }

    // ------------------------------------------------------------------ actions

    /**
     * Plays the item in Wholphin from its resume point (a series opens its page instead);
     * without Wholphin, opens the item's page in the Jellyfin app. Null if neither is installed.
     */
    static Intent play(Context c, Item it) {
        return wholphin(c, "Series".equals(it.type) ? "view" : "play", it, it.positionMs);
    }

    /** The item's details page. */
    static Intent details(Context c, Item it) {
        return wholphin(c, "view", it, 0);
    }

    private static Intent wholphin(Context c, String what, Item it, long positionMs) {
        boolean hasWholphin = c.getPackageManager().getLaunchIntentForPackage(WHOLPHIN) != null;
        if (hasWholphin) {
            String uri = "wholphin://" + what + "?itemId=" + it.id + (positionMs > 0 ? "&position=" + positionMs : "");
            // Wholphin reads the action first: PLAYBACK plays, VIEW opens the page.
            String action = what.equals("play") ? "com.github.damontecres.wholphin.PLAYBACK" : Intent.ACTION_VIEW;
            return new Intent(action, Uri.parse(uri)).setPackage(WHOLPHIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(APP);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(APP);
        if (i == null) return null;
        return i.putExtra("ItemId", it.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /** Marks the item as watched on the server. */
    static void markWatched(Context c, Item it, Done done) {
        send(c, "POST", "/UserPlayedItems/" + it.id + "?userId=%u", null, done);
    }

    /** Takes the item out of Continue watching by clearing its resume point (stays unwatched). */
    static void removeFromResume(Context c, Item it, Done done) {
        send(c, "POST", "/UserItems/" + it.id + "/UserData?userId=%u", "{\"PlaybackPositionTicks\":0}", done);
    }

    private static void send(Context ctx, String method, String path, String json, Done done) {
        final Context c = ctx.getApplicationContext();
        net.execute(() -> {
            boolean ok = false;
            try {
                HttpURLConnection h = open(c, base(c) + path.replace("%u", userId(c)));
                h.setRequestMethod(method);
                if (json != null) {
                    h.setDoOutput(true);
                    h.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = h.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
                }
                ok = h.getResponseCode() / 100 == 2;
                h.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "jellyfin " + method + ": " + e);
            }
            final boolean r = ok;
            main.post(() -> done.accept(r));
        });
    }

    // ------------------------------------------------------------------ images, http

    static void image(Context ctx, String url, ImageSink sink) {
        Bitmap b = images.get(url);
        if (b != null) { sink.accept(b); return; }
        final Context c = ctx.getApplicationContext();
        net.execute(() -> {
            try {
                byte[] d = bytes(open(c, url));
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length, o);
                if (bm == null) return;
                images.put(url, bm);
                main.post(() -> sink.accept(bm));
            } catch (Exception e) {
                Log.w(TAG, "jellyfin image: " + e);
            }
        });
    }

    private static String base(Context c) {
        return prefs(c).getString("jf_url", "").replaceAll("/+$", "");
    }

    /** The configured user's id (looked up by name once, then cached). */
    private static String userId(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String uid = p.getString("jf_user_id", null);
        if (uid != null) return uid;
        JSONArray users = new JSONArray(get(c, "/Users"));
        String want = p.getString("jf_user", "");
        for (int k = 0; k < users.length(); k++) {
            JSONObject u = users.getJSONObject(k);
            if (uid == null || u.getString("Name").equalsIgnoreCase(want)) uid = u.getString("Id");
            if (u.getString("Name").equalsIgnoreCase(want)) break;
        }
        p.edit().putString("jf_user_id", uid).apply();
        return uid;
    }

    private static String get(Context c, String path) throws Exception {
        return new String(bytes(open(c, base(c) + path)), "UTF-8");
    }

    private static HttpURLConnection open(Context c, String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(5000);
        h.setReadTimeout(10000);
        h.setRequestProperty("Authorization", "MediaBrowser Client=\"Cube Home\", Device=\"Fire TV Cube\", "
                + "DeviceId=\"cubehome\", Version=\"1.0\", Token=\"" + prefs(c).getString("jf_key", "") + "\"");
        return h;
    }

    private static byte[] bytes(HttpURLConnection h) throws Exception {
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode() + " " + h.getURL());
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
}
