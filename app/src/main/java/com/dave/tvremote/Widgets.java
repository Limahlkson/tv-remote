package com.dave.tvremote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** Custom-drawn controls for the remote. */
public final class Widgets {

    private Widgets() {
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static int lighten(int color, float amt) {
        int a = (color >>> 24), r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r = (int) (r + (255 - r) * amt);
        g = (int) (g + (255 - g) * amt);
        b = (int) (b + (255 - b) * amt);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Fires once, then keeps firing while held. */
    public static class Repeater {
        private final Handler h = new Handler(Looper.getMainLooper());
        private Runnable task;

        public void start(Runnable fire, long firstDelay, long interval) {
            stop();
            task = new Runnable() {
                @Override
                public void run() {
                    fire.run();
                    h.postDelayed(this, interval);
                }
            };
            h.postDelayed(task, firstDelay);
        }

        public void stop() {
            if (task != null) h.removeCallbacks(task);
            task = null;
        }
    }

    /** Round button with a drawn icon. Fires on touch-down, optional hold-to-repeat. */
    public static class CircleButton extends View {
        int icon, fill, iconColor, ringColor;
        float ringWidth, iconScale = 0.36f;
        String text;
        Runnable onPress, onLongPress;
        boolean repeat;
        final Repeater repeater = new Repeater();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean longFired;
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable longTask = () -> {
            if (onLongPress != null) {
                longFired = true;
                onLongPress.run();
            }
        };

        public CircleButton(Context c, int icon, int fill, int iconColor) {
            super(c);
            this.icon = icon;
            this.fill = fill;
            this.iconColor = iconColor;
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        public CircleButton ring(int color, float widthPx) {
            ringColor = color;
            ringWidth = widthPx;
            return this;
        }

        public CircleButton text(String t) {
            text = t;
            return this;
        }

        public CircleButton iconScale(float s) {
            iconScale = s;
            return this;
        }

        public CircleButton action(Runnable r, boolean repeat) {
            onPress = r;
            this.repeat = repeat;
            return this;
        }

        public CircleButton longAction(Runnable r) {
            onLongPress = r;
            return this;
        }

        public void setIcon(int i) {
            icon = i;
            invalidate();
        }

        public void setFill(int f) {
            fill = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            if (ringWidth > 0) {
                p.setColor(ringColor);
                c.drawCircle(cx, cy, r, p);
                r -= ringWidth;
            }
            if (fill != 0) {
                p.setColor(isPressed() ? lighten(fill, 0.22f) : fill);
                c.drawCircle(cx, cy, r, p);
            } else if (isPressed()) {
                p.setColor(0x33FFFFFF);
                c.drawCircle(cx, cy, r, p);
            }
            float u = r * iconScale;
            if (icon != Icons.NONE) {
                Icons.draw(c, icon, cx, text != null ? cy - r * 0.12f : cy, u, iconColor);
            }
            if (text != null) {
                p.setColor(iconColor);
                p.setTextSize(r * (icon == Icons.NONE ? 0.42f : 0.26f));
                float ty = icon == Icons.NONE ? cy + p.getTextSize() * 0.35f : cy + r * 0.62f;
                c.drawText(text, cx, ty, p);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setPressed(true);
                    invalidate();
                    longFired = false;
                    if (onLongPress != null) {
                        h.postDelayed(longTask, 600);
                    } else if (onPress != null) {
                        onPress.run();
                        if (repeat) repeater.start(onPress, 420, 170);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (onLongPress != null) {
                        h.removeCallbacks(longTask);
                        if (!longFired && onPress != null) onPress.run();
                    }
                    performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(longTask);
                    repeater.stop();
                    setPressed(false);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    /** Pill with two halves, e.g. [ - VOL + ]. Both halves hold-to-repeat. */
    public static class Rocker extends View {
        public interface Listener {
            void onHalf(boolean right);
        }

        final int leftIcon, rightIcon;
        final String label;
        int fill;
        int pressedHalf = -1;
        Listener listener;
        final Repeater repeater = new Repeater();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Rocker(Context c, int leftIcon, int rightIcon, String label, int fill, Listener l) {
            super(c);
            this.leftIcon = leftIcon;
            this.rightIcon = rightIcon;
            this.label = label;
            this.fill = fill;
            this.listener = l;
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            p.setColor(fill);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            if (pressedHalf >= 0) {
                p.setColor(lighten(fill, 0.2f));
                c.save();
                if (pressedHalf == 0) c.clipRect(0, 0, w / 2f, h);
                else c.clipRect(w / 2f, 0, w, h);
                c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
                c.restore();
            }
            float u = h * 0.17f;
            Icons.draw(c, leftIcon, r + h * 0.08f, h / 2f, u, 0xFFFFFFFF);
            Icons.draw(c, rightIcon, w - r - h * 0.08f, h / 2f, u, 0xFFFFFFFF);
            p.setColor(0xFFB8B8B8);
            p.setTextSize(h * 0.26f);
            c.drawText(label, w / 2f, h / 2f + p.getTextSize() * 0.36f, p);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    boolean right = e.getX() > getWidth() / 2f;
                    pressedHalf = right ? 1 : 0;
                    invalidate();
                    listener.onHalf(right);
                    repeater.start(() -> listener.onHalf(right), 420, 170);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    repeater.stop();
                    pressedHalf = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    public interface PadListener {
        /** 0 up, 1 down, 2 left, 3 right */
        void onDirection(int dir, boolean isRepeat);

        void onSelect();
    }

    /** Fire TV style trackpad: swipe to move, tap to select. */
    public static class TouchPad extends View {
        final PadListener listener;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
                dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        float startX, startY, lastX, lastY, touchX = -1, touchY;
        long downAt;
        boolean moved;
        int color;
        String[] hint = {"Swipe to move,", "tap to select"};
        final float step, slop;

        public TouchPad(Context c, int color, PadListener l) {
            super(c);
            this.color = color;
            listener = l;
            step = dp(c, 52);
            slop = dp(c, 12);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(0xFF8E8E8E);
            txt.setTextSize(dp(c, 22));
            dot.setColor(0x22FFFFFF);
        }

        public void setHint(String a, String b) {
            hint = new String[]{a, b};
            invalidate();
        }

        public void setPadColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            bg.setColor(color);
            float r = dp(getContext(), 46);
            c.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, bg);
            float cy = getHeight() / 2f;
            c.drawText(hint[0], getWidth() / 2f, cy - txt.getTextSize() * 0.15f, txt);
            c.drawText(hint[1], getWidth() / 2f, cy + txt.getTextSize() * 1.05f, txt);
            if (touchX >= 0) c.drawCircle(touchX, touchY, dp(getContext(), 34), dot);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lastX = x;
                    startY = lastY = y;
                    moved = false;
                    downAt = System.currentTimeMillis();
                    touchX = x;
                    touchY = y;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = x - lastX, dy = y - lastY;
                    if (Math.hypot(x - startX, y - startY) > slop) moved = true;
                    if (Math.abs(dx) >= step && Math.abs(dx) > Math.abs(dy)) {
                        listener.onDirection(dx > 0 ? 3 : 2, false);
                        lastX = x;
                        lastY = y;
                    } else if (Math.abs(dy) >= step && Math.abs(dy) >= Math.abs(dx)) {
                        listener.onDirection(dy > 0 ? 1 : 0, false);
                        lastX = x;
                        lastY = y;
                    }
                    touchX = x;
                    touchY = y;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved && System.currentTimeMillis() - downAt < 600) listener.onSelect();
                    performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    touchX = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    /** Round D-pad with OK in the middle; directions repeat while held. */
    public static class Dpad extends View {
        final PadListener listener;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Repeater repeater = new Repeater();
        int pressed = -1; // 0-3 directions, 4 = OK
        int ring = 0xFF3A3A3A, okColor = 0xFF4A4A4A;

        public Dpad(Context c, PadListener l) {
            super(c);
            listener = l;
        }

        public void setColors(int ringColor, int ok) {
            ring = ringColor;
            okColor = ok;
            invalidate();
        }

        float radius() {
            return Math.min(getWidth(), getHeight()) / 2f - dp(getContext(), 6);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
            p.setStyle(Paint.Style.FILL);
            p.setColor(ring);
            c.drawCircle(cx, cy, r, p);
            if (pressed >= 0 && pressed < 4) {
                p.setColor(lighten(ring, 0.18f));
                float start = new float[]{225, 45, 135, -45}[pressed];
                c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), start, 90, true, p);
            }
            p.setColor(pressed == 4 ? lighten(okColor, 0.2f) : okColor);
            c.drawCircle(cx, cy, r * 0.42f, p);
            p.setColor(0xFF2B2B2B);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 4));
            c.drawCircle(cx, cy, r * 0.42f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFE0E0E0);
            float a = r * 0.71f, s = r * 0.09f;
            Icons.draw(c, Icons.CHEV_UP, cx, cy - a, s * 1.6f, 0xFFE0E0E0);
            Icons.draw(c, Icons.CHEV_DOWN, cx, cy + a, s * 1.6f, 0xFFE0E0E0);
            c.save();
            c.rotate(-90, cx - a, cy);
            Icons.draw(c, Icons.CHEV_UP, cx - a, cy, s * 1.6f, 0xFFE0E0E0);
            c.restore();
            c.save();
            c.rotate(90, cx + a, cy);
            Icons.draw(c, Icons.CHEV_UP, cx + a, cy, s * 1.6f, 0xFFE0E0E0);
            c.restore();
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(r * 0.16f);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            c.drawText("OK", cx, cy + p.getTextSize() * 0.36f, p);
        }

        int hit(float x, float y) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
            float dx = x - cx, dy = y - cy;
            double d = Math.hypot(dx, dy);
            if (d > r * 1.15) return -1;
            if (d < r * 0.42f) return 4;
            if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 3 : 2;
            return dy > 0 ? 1 : 0;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int h = hit(e.getX(), e.getY());
                    if (h < 0) return false;
                    pressed = h;
                    invalidate();
                    if (h < 4) {
                        final int dir = h;
                        listener.onDirection(dir, false);
                        repeater.start(() -> listener.onDirection(dir, true), 450, 220);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (pressed == 4) listener.onSelect();
                    performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    repeater.stop();
                    pressed = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
