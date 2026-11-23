package dev.cube.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Home wallpaper, changed at the interval chosen in the settings, taking turns between the
 * sources ticked there:
 *  - Amazon: a still frame from one of Fire TV's own aerial screensaver videos
 *    (list in res/raw/amazon_aerials.txt, from the Aerial Views project);
 *  - NASA: an Earth photo taken from the ISS (NASA Image Library, no key needed).
 * If a source fails, the next one is tried (Bing when none is ticked). The last picture
 * is cached in the app's files dir, so Home always has one, even offline.
 */
final class Wallpaper {
    private static final String TAG = "CubeHome";
    private static final String FILE = "wallpaper.jpg";
    private static volatile boolean busy;
    private static final Random rnd = new Random();

    interface Sink { void accept(Bitmap b); }

    private Wallpaper() { }

    static Bitmap cached(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory, no visible loss here
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static final long[] INTERVALS = {30, 60, 180, 1440};   // minutes

    /** Sources ticked in the settings, in rotation order. */
    private static List<String> sources(SharedPreferences p) {
        List<String> l = new ArrayList<>();
        if (p.getInt("wall_src_amazon", 1) == 1) l.add("amazon");
        if (p.getInt("wall_src_nasa", 1) == 1) l.add("nasa");
        if (p.getInt("wall_src_bing", 0) == 1) l.add("bing");
        return l;
    }

    /** Called on every clock tick; works once per chosen interval (or after a failure, every 10 min). */
    static void refreshIfStale(Context ctx, Sink sink) {
        final SharedPreferences p = ctx.getSharedPreferences("cubehome", Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        long every = INTERVALS[p.getInt("wall_interval", 1)] * 60_000L;
        boolean have = new File(ctx.getFilesDir(), FILE).exists();
        if ((have && p.getLong("wall_slot", -1) == now / every)
                || now - p.getLong("wall_try", 0) < 10 * 60 * 1000L) return;
        fetch(ctx, sink);
    }

    /** The refresh button: a new picture now, from the next source. */
    static boolean next(Context ctx, Sink sink) {
        if (busy) return false;
        fetch(ctx, sink);
        return true;
    }

    private static void fetch(Context ctx, Sink sink) {
        final Context c = ctx.getApplicationContext();
        final SharedPreferences p = c.getSharedPreferences("cubehome", Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        final long slot = now / (INTERVALS[p.getInt("wall_interval", 1)] * 60_000L);
        if (busy) return;
        busy = true;
        p.edit().putLong("wall_try", now).apply();
        new Thread(() -> {
            try {
                List<String> src = sources(p);
                if (src.isEmpty()) src.add("bing");
                // Start after the source used last time, so they take turns.
                int start = (src.indexOf(p.getString("wall_src_last", "")) + 1) % src.size();
                String cap = null, used = null;
                for (int k = 0; k < src.size() && cap == null; k++) {
                    used = src.get((start + k) % src.size());
                    cap = used.equals("amazon") ? amazon(c) : used.equals("nasa") ? nasa(c) : bing(c);
                }
                if (cap == null) return;
                p.edit().putLong("wall_slot", slot).putLong("wall_try", 1)
                        .putString("wall_caption", cap).putString("wall_src_last", used).apply();
                Log.i(TAG, "wallpaper: " + cap);
                final Bitmap b = cached(c);
                new Handler(Looper.getMainLooper()).post(() -> sink.accept(b));
            } finally {
                busy = false;
                if (p.getLong("wall_try", 0) == now) {   // nothing found: tell the UI
                    new Handler(Looper.getMainLooper()).post(() -> sink.accept(null));
                }
            }
        }, "cubehome-wallpaper").start();
    }

    // ------------------------------------------------------------------ sources

    /** Amazon aerial scenes: {video url, name, description} (also played by the screensaver). */
    static List<String[]> scenes(Context c) throws Exception {
        List<String[]> scenes = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                c.getResources().openRawResource(R.raw.amazon_aerials), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length == 3) scenes.add(f);
            }
        }
        return scenes;
    }

    /** A frame from a random Amazon aerial (the MP4 is read with HTTP range requests, not downloaded). */
    private static String amazon(Context c) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            List<String[]> scenes = scenes(c);
            String[] s = scenes.get(rnd.nextInt(scenes.size()));
            r.setDataSource(s[0], new HashMap<>());
            long durUs = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
            // Somewhere in the middle third: past the fade-in, clear of the end.
            long at = durUs / 3 + (long) (rnd.nextDouble() * durUs / 3);
            Bitmap frame = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new Exception("no frame");
            save(c, frame);
            frame.recycle();
            return s[2];
        } catch (Exception e) {
            Log.w(TAG, "wallpaper amazon: " + e);
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** A random landscape Earth photo taken from the ISS. */
    private static String nasa(Context c) {
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                int page = 1 + rnd.nextInt(28);   // ~2,800 results, 100 per page
                JSONArray items = new JSONObject(text("https://images-api.nasa.gov/search?media_type=image"
                        + "&page_size=100&page=" + page
                        + "&q=earth%20observation%20taken%20by%20the%20expedition"))
                        .getJSONObject("collection").getJSONArray("items");
                if (items.length() == 0) continue;
                JSONObject meta = items.getJSONObject(rnd.nextInt(items.length()))
                        .getJSONArray("data").getJSONObject(0);
                String id = meta.getString("nasa_id");
                byte[] jpg = bytes("https://images-assets.nasa.gov/image/" + id + "/" + id + "~large.jpg");
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                // Wallpaper-shaped only: wide and large enough for a 1080p screen.
                if (o.outWidth < 1200 || o.outWidth < o.outHeight * 1.3f) continue;
                writeFile(c, jpg);
                return nasaCaption(Lang.wrap(c), meta);
            }
        } catch (Exception e) {
            Log.w(TAG, "wallpaper nasa: " + e);
        }
        return null;
    }

    private static String bing(Context c) {
        try {
            String mkt = Locale.getDefault().getLanguage() + "-" + Locale.getDefault().getCountry();
            JSONObject img = new JSONObject(text(
                    "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=" + mkt))
                    .getJSONArray("images").getJSONObject(0);
            writeFile(c, bytes("https://www.bing.com" + img.getString("urlbase") + "_1920x1080.jpg"));
            // "Pousse de fougère (© Reza Saputra/Getty Images)": keep the description only
            String cr = img.optString("copyright");
            int k = cr.lastIndexOf(" (");
            return k > 0 ? cr.substring(0, k).trim() : cr;
        } catch (Exception e) {
            Log.w(TAG, "wallpaper bing: " + e);
            return null;
        }
    }

    /** "Earth from the ISS · Expedition 34" (NASA's ISS photos have no other description). */
    private static String nasaCaption(Context t, JSONObject meta) {
        String text = meta.optString("description") + " " + meta.optString("title");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Expedition\\s+(\\d+)").matcher(text);
        String s = t.getString(R.string.nasa_caption);
        return m.find() ? s + " · " + t.getString(R.string.nasa_expedition, m.group(1)) : s;
    }

    /** Description of the current picture, or "". */
    static String caption(Context c) {
        return c.getSharedPreferences("cubehome", Context.MODE_PRIVATE).getString("wall_caption", "");
    }

    // ------------------------------------------------------------------ io

    private static void save(Context c, Bitmap b) throws Exception {
        Bitmap s = b.getWidth() > 1920 ? Bitmap.createScaledBitmap(b, 1920,
                Math.round(b.getHeight() * 1920f / b.getWidth()), true) : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 90, out);
        if (s != b) s.recycle();
        writeFile(c, out.toByteArray());
    }

    private static void writeFile(Context c, byte[] data) throws Exception {
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
        if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) throw new Exception("rename failed");
    }

    private static String text(String url) throws Exception { return new String(bytes(url), "UTF-8"); }

    private static byte[] bytes(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(20000);
        if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode() + " " + url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            h.disconnect();
        }
    }
}
