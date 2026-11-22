package dev.cube.launcher;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Cube Home's screensaver (an Android "dream", selected in place of Amazon's by install.sh):
 * Fire TV's own aerial videos, one after another in random order, fading through black
 * between scenes, with the clock and the scene's description. No ads, no sound.
 */
public class Screensaver extends DreamService {
    private static final String TAG = "CubeHome";
    private final Handler handler = new Handler();
    private VideoView video;
    private View black;
    private TextView clock, caption;
    private List<String[]> scenes;
    private int next;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeFormat(Screensaver.this).format(new Date()));
            handler.postDelayed(this, 10000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV
        setFullscreen(true);
        Look.load(this);
        float dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        View scrim = new View(this);   // keeps the text readable on bright scenes
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, (int) (220 * dp), Gravity.BOTTOM));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.END);
        clock = label(44, true);
        caption = label(15, false);
        caption.setMaxWidth((int) (520 * dp));
        caption.setGravity(Gravity.END);
        caption.setPadding(0, (int) (6 * dp), 0, 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(-2, -2);
        right.gravity = Gravity.END;
        text.addView(clock, right);
        text.addView(caption, new LinearLayout.LayoutParams(right));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        tl.setMargins(0, 0, (int) (56 * dp), (int) (44 * dp));
        root.addView(text, tl);

        black = new View(this);   // fades in and out between scenes
        black.setBackgroundColor(Color.BLACK);
        root.addView(black, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            mp.setVolume(0f, 0f);
            video.start();
            black.animate().alpha(0f).setDuration(1500).start();
        });
        video.setOnCompletionListener(mp -> playNext());
        video.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "screensaver: video error " + what + "/" + extra);
            handler.postDelayed(this::playNext, 2000);
            return true;
        });
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        try {
            scenes = Wallpaper.scenes(this);
            Collections.shuffle(scenes);
        } catch (Exception e) {
            Log.w(TAG, "screensaver: " + e);
            finish();
            return;
        }
        tick.run();
        play();
    }

    /** Fade to black, then the next scene. */
    private void playNext() {
        black.animate().alpha(1f).setDuration(1000).withEndAction(this::play).start();
    }

    private void play() {
        if (scenes == null || scenes.isEmpty()) return;
        String[] s = scenes.get(next++ % scenes.size());
        caption.setText(s[2]);
        video.setVideoURI(Uri.parse(s[0]));
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        if (video != null) video.stopPlayback();
        super.onDreamingStopped();
    }

    private TextView label(float sp, boolean strong) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Look.font(strong));
        t.setShadowLayer(8 * getResources().getDisplayMetrics().density, 0, 2, 0xCC000000);
        t.setIncludeFontPadding(false);
        return t;
    }
}
