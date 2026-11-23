package dev.cube.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * Scroll views that keep the focused item in the middle (clamped at the ends) instead of
 * scrolling only just enough, which left the selection stuck at the screen edge, and that
 * always slide rather than jump.
 */
final class CenterScroll {
    private CenterScroll() { }

    static final class H extends HorizontalScrollView {
        private final Glide glide = new Glide(this, true);

        H(Context c) {
            super(c);
            // The platform's smooth scroll is a fixed, abrupt 250 ms. With it off, every scroll
            // (arrow keys, focus arriving from another row) goes through scrollBy, eased below.
            setSmoothScrollingEnabled(false);
        }

        @Override
        public void scrollBy(int x, int y) {
            if (!isLaidOut()) { super.scrollBy(x, y); return; }
            glide.by(x);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            if (getChildCount() == 0) return 0;
            int max = getChildAt(0).getWidth() + getPaddingLeft() + getPaddingRight() - getWidth();
            if (max <= 0) return 0;
            int target = r.centerX() - getWidth() / 2;
            target = Math.max(0, Math.min(max, target));
            return target - glide.position();
        }
    }

    static final class V extends ScrollView {
        private final Glide glide = new Glide(this, false);

        V(Context c) {
            super(c);
            setSmoothScrollingEnabled(false);
        }

        @Override
        public void scrollBy(int x, int y) {
            if (!isLaidOut()) { super.scrollBy(x, y); return; }
            glide.by(y);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            if (getChildCount() == 0) return 0;
            int max = getChildAt(0).getHeight() + getPaddingTop() + getPaddingBottom() - getHeight();
            if (max <= 0) return 0;
            int target = r.centerY() - getHeight() / 2;
            target = Math.max(0, Math.min(max, target));
            return target - glide.position();
        }
    }

    /**
     * Eased scroll animation. A new scroll while one is running continues from where the view
     * is now toward the new target, so holding a direction key stays fluid.
     */
    private static final class Glide {
        private final android.view.View view;
        private final boolean horizontal;
        private android.animation.ValueAnimator anim;
        private int target;

        Glide(android.view.View v, boolean horizontal) { this.view = v; this.horizontal = horizontal; }

        /** Where the view is heading (its scroll position once the running animation ends). */
        int position() {
            boolean running = anim != null && anim.isRunning();
            return running ? target : horizontal ? view.getScrollX() : view.getScrollY();
        }

        void by(int delta) {
            if (delta == 0) return;
            int from = horizontal ? view.getScrollX() : view.getScrollY();
            int to = position() + delta;
            if (to == position()) return;
            target = to;
            if (anim != null) anim.cancel();
            final int start = from, end = target;
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(Look.d(450));
            anim.setInterpolator(new android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f));
            anim.addUpdateListener(a -> {
                int v = Math.round(start + (end - start) * (float) a.getAnimatedValue());
                if (horizontal) view.scrollTo(v, view.getScrollY()); else view.scrollTo(view.getScrollX(), v);
            });
            anim.start();
        }
    }
}
