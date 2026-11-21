package com.dave.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    static final int PAGE = 0xFF000000, CARD = 0xFF2B2B2B, BTN = 0xFF3A3A3A, PAD = 0xFF1B1B1B,
            BLUE = 0xFF4DA3FF, ORANGE = 0xFFFF5500, RED = 0xFFD32F2F, TEXT = 0xFFE8E8E8,
            DIM = 0xFF9A9A9A, AMBER = 0xFFFFB300, TV_PAD = 0xFF2A2210;

    static final String[] DIRS = {"dpad_up", "dpad_down", "dpad_left", "dpad_right"};
    static final int[] IR_DIRS = {IrRemote.UP, IrRemote.DOWN, IrRemote.LEFT, IrRemote.RIGHT};

    // Known UK apps: package, label, tile colour, text colour
    static final String[][] FAVOURITES = {
            {"uk.co.bbc.iplayer", "BBC iPlayer", "#FFB0127A", "#FFFFFFFF"},
            {"com.netflix.ninja", "NETFLIX", "#FFFFFFFF", "#FFE50914"},
            {"com.amazon.firetv.youtube", "YouTube", "#FFFFFFFF", "#FFFF0000"},
            {"com.channel4.ondemand", "Channel 4", "#FFA8E08C", "#FF000000"},
            {"air.ITVMobilePlayer", "ITVX", "#FF1E1E5A", "#FFDDFF55"},
            {"com.amazon.cloud9", "Prime Video", "#FF1A98FF", "#FFFFFFFF"},
            {"com.disney.disneyplus", "Disney+", "#FF0E2D78", "#FFFFFFFF"},
            {"com.mobileiq.demand5", "My5", "#FF111111", "#FFFFFFFF"},
            {"com.bskyb.nowtv.beta", "NOW", "#FF00818A", "#FFFFFFFF"},
    };

    SharedPreferences prefs;
    FireTvClient ftv;
    IrRemote ir;
    Vibrator vibrator;
    final Handler h = new Handler(Looper.getMainLooper());

    TextView title, banner;
    View statusDot;
    FrameLayout padArea;
    Widgets.TouchPad trackpad;
    Widgets.Dpad dpad;
    Widgets.CircleButton backBtn;
    LinearLayout tabContent;
    TextView[] tabViews = new TextView[3];
    int currentTab = 1;
    boolean tvMode;
    long lastToast;
    List<FireTvClient.App> apps;
    final Map<String, Bitmap> iconCache = new HashMap<>();
    final Runnable leaveTvMode = () -> setTvMode(false);

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tvremote", MODE_PRIVATE);
        vibrator = getSystemService(Vibrator.class);
        ir = new IrRemote(this);
        ir.setCodeSet(prefs.getInt("irSet", 0));
        ftv = new FireTvClient();
        ftv.configure(prefs.getString("ip", null), prefs.getString("token", null), prefs.getString("mac", null));
        ftv.setListener(this::onFireTvStatus);

        getWindow().setStatusBarColor(PAGE);
        getWindow().setNavigationBarColor(CARD);
        setContentView(buildUi());
        applyControlStyle();
        updateTitle();

        if (!ftv.isPaired()) h.postDelayed(this::showDevicePicker, 600);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ftv.warmUp();
        if (ftv.isPaired() && apps == null) loadApps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanSafety.run(); // let go of rewind / fast forward if the app is left mid-hold
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (prefs.getBoolean("volKeys", true)
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            irPress(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? IrRemote.VOL_UP : IrRemote.VOL_DOWN, false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (prefs.getBoolean("volKeys", true)
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------ UI

    int dp(float v) {
        return Math.round(Widgets.dp(this, v));
    }

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD);
        float rr = dp(28);
        cardBg.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
        card.setBackground(cardBg);
        card.setPadding(dp(14), dp(8), dp(14), dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardLp.topMargin = dp(10);
        root.addView(card, cardLp);

        // grab handle
        View handle = new View(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xFF4A4A4A);
        hb.setCornerRadius(dp(3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(56), dp(5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(6);
        card.addView(handle, hlp);

        // header: sleep | name | menu
        FrameLayout header = new FrameLayout(this);
        Widgets.CircleButton moon = new Widgets.CircleButton(this, Icons.MOON, 0, TEXT).iconScale(0.5f)
                .action(() -> ftvAction("sleep"), false);
        header.addView(moon, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER);
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(DIM);
        statusDot.setBackground(dot);
        titleRow.addView(statusDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        title = new TextView(this);
        title.setTextColor(BLUE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, 0, 0);
        titleRow.addView(title);
        titleRow.setOnClickListener(v -> showDevicePicker());
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, dp(48), Gravity.CENTER);
        tlp.leftMargin = dp(56);
        tlp.rightMargin = dp(56);
        header.addView(titleRow, tlp);

        Widgets.CircleButton more = new Widgets.CircleButton(this, Icons.MORE, 0, TEXT).iconScale(0.5f)
                .action(this::showSettings, false);
        header.addView(more, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        card.addView(header, new LinearLayout.LayoutParams(-1, dp(52)));

        // Media / Apps / Channels drawer
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable db = new GradientDrawable();
        db.setColor(0xFF353535);
        db.setCornerRadius(dp(26));
        drawer.setBackground(db);
        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"Media", "Apps", "Channels"};
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            final int idx = i;
            t.setOnClickListener(v -> selectTab(idx));
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, dp(46), 1f));
        }
        drawer.addView(tabs);
        tabContent = new LinearLayout(this);
        tabContent.setGravity(Gravity.CENTER_VERTICAL);
        tabContent.setPadding(dp(8), dp(4), dp(8), dp(10));
        drawer.addView(tabContent, new LinearLayout.LayoutParams(-1, dp(76)));
        LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(-1, -2);
        drLp.topMargin = dp(4);
        drLp.bottomMargin = dp(8);
        card.addView(drawer, drLp);
        selectTab(prefs.getInt("tab", 1));

        // TV-menu banner (shown after Input)
        banner = new TextView(this);
        banner.setText("TV menu mode: swipe and tap go to the TV.  Tap here when done.");
        banner.setTextColor(0xFF000000);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(AMBER);
        bb.setCornerRadius(dp(12));
        banner.setBackground(bb);
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> setTvMode(false));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.bottomMargin = dp(6);
        card.addView(banner, blp);

        // pad area with notched corner buttons
        padArea = new FrameLayout(this);
        trackpad = new Widgets.TouchPad(this, PAD, padListener);
        dpad = new Widgets.Dpad(this, padListener);
        padArea.addView(trackpad, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        dlp.setMargins(dp(40), dp(30), dp(40), dp(30));
        padArea.addView(dpad, dlp);

        int corner = dp(74), ring = dp(9);
        Widgets.CircleButton kb = new Widgets.CircleButton(this, Icons.KEYBOARD, BTN, TEXT).ring(CARD, ring)
                .iconScale(0.4f).action(this::showKeyboard, false);
        padArea.addView(kb, cornerLp(corner, Gravity.TOP | Gravity.START));
        Widgets.CircleButton mute = new Widgets.CircleButton(this, Icons.MUTE, BTN, TEXT).ring(CARD, ring)
                .iconScale(0.36f).action(() -> irPress(IrRemote.MUTE, false), false);
        padArea.addView(mute, cornerLp(corner, Gravity.TOP | Gravity.END));
        Widgets.CircleButton mic = new Widgets.CircleButton(this, Icons.MIC, 0xFFCC0000, 0xFFFFFFFF)
                .iconScale(0.34f).action(this::startYouTubeVoiceSearch, false)
                .longAction(this::askYouTubeTyped);
        FrameLayout.LayoutParams micLp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.END);
        micLp.topMargin = corner - dp(10) + dp(8);
        micLp.setMarginEnd((corner - dp(54)) / 2 - dp(10));
        padArea.addView(mic, micLp);
        backBtn = new Widgets.CircleButton(this, Icons.BACK, BTN, TEXT).ring(CARD, ring).iconScale(0.36f)
                .action(this::backPressed, false);
        padArea.addView(backBtn, cornerLp(corner + dp(8), Gravity.BOTTOM | Gravity.START));
        Widgets.CircleButton home = new Widgets.CircleButton(this, Icons.HOME, ORANGE, 0xFFFFFFFF).ring(CARD, ring)
                .iconScale(0.36f).action(this::homePressed, false);
        padArea.addView(home, cornerLp(corner + dp(6), Gravity.BOTTOM | Gravity.END));

        // Rewind, play/pause and fast forward in an arc around Home
        float homeSize = 74 + 6, homeCentre = homeSize / 2f - 10; // dp from the pad's bottom-right corner
        float small = 46, arc = homeSize / 2f + small / 2f + 8;
        int[] mediaIcons = {Icons.REWIND, Icons.PLAY_PAUSE, Icons.FFWD};
        Runnable[] mediaDown = {
                () -> scanPress(false, true),
                () -> ftvCall(ftv::playPause),
                () -> scanPress(true, true)
        };
        Runnable[] mediaUp = {() -> scanPress(false, false), null, () -> scanPress(true, false)};
        double[] angles = {180, 225, 270}; // left of Home, diagonal, above Home
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(angles[i]);
            float fromRight = homeCentre - (float) (arc * Math.cos(a));
            float fromBottom = homeCentre - (float) (arc * Math.sin(a));
            Widgets.CircleButton mb = new Widgets.CircleButton(this, mediaIcons[i], BTN, TEXT)
                    .iconScale(0.3f).action(mediaDown[i], false).release(mediaUp[i]);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(small), dp(small),
                    Gravity.BOTTOM | Gravity.END);
            mlp.setMarginEnd(dp(fromRight - small / 2f));
            mlp.bottomMargin = dp(fromBottom - small / 2f);
            padArea.addView(mb, mlp);
        }
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        padLp.topMargin = dp(14);
        card.addView(padArea, padLp);

        // TV volume and channel rockers (infrared)
        LinearLayout rockers = new LinearLayout(this);
        rockers.setGravity(Gravity.CENTER);
        Widgets.Rocker vol = new Widgets.Rocker(this, Icons.MINUS, Icons.PLUS, "VOL", BTN,
                right -> irPress(right ? IrRemote.VOL_UP : IrRemote.VOL_DOWN, true));
        Widgets.Rocker ch = new Widgets.Rocker(this, Icons.CHEV_DOWN, Icons.CHEV_UP, "CH", BTN,
                right -> irPress(right ? IrRemote.CH_UP : IrRemote.CH_DOWN, true));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp.setMargins(dp(4), 0, dp(4), 0);
        rockers.addView(ch, rlp);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp2.setMargins(dp(4), 0, dp(4), 0);
        rockers.addView(vol, rlp2);
        LinearLayout.LayoutParams rocLp = new LinearLayout.LayoutParams(-1, dp(58));
        rocLp.topMargin = dp(10);
        card.addView(rockers, rocLp);

        // bottom row: Fire TV menu | Fire TV settings | TV input | TV power
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER);
        top.addView(labelled(new Widgets.CircleButton(this, Icons.MENU, BTN, TEXT)
                .action(() -> ftvAction("menu"), false), "Menu"), weight());
        top.addView(labelled(new Widgets.CircleButton(this, Icons.GEAR, BTN, TEXT)
                .action(this::openFireTvSettings, false), "Settings"), weight());
        top.addView(labelled(new Widgets.CircleButton(this, Icons.INPUT, BTN, TEXT)
                .action(this::inputPressed, false), "Input / AV"), weight());
        top.addView(labelled(new Widgets.CircleButton(this, Icons.POWER, RED, 0xFFFFFFFF).iconScale(0.34f)
                .action(() -> irPress(IrRemote.POWER, false), false), "TV power"), weight());
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(-1, dp(96));
        topLp.topMargin = dp(10);
        card.addView(top, topLp);

        return root;
    }

    LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -1, 1f);
    }

    View labelled(View button, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(66), dp(66));
        col.addView(button, lp);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(-2, -2));
        return col;
    }

    FrameLayout.LayoutParams cornerLp(int size, int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, gravity);
        int off = -dp(10);
        if ((gravity & Gravity.TOP) == Gravity.TOP) lp.topMargin = off;
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) lp.bottomMargin = off;
        if ((gravity & Gravity.START) == Gravity.START) lp.setMarginStart(off);
        if ((gravity & Gravity.END) == Gravity.END) lp.setMarginEnd(off);
        return lp;
    }

    void applyControlStyle() {
        boolean useDpad = prefs.getBoolean("dpadMode", false);
        trackpad.setVisibility(useDpad ? View.GONE : View.VISIBLE);
        dpad.setVisibility(useDpad ? View.VISIBLE : View.GONE);
        padArea.setClipChildren(false);
        ((ViewGroup) padArea.getParent()).setClipChildren(false);
    }

    // ------------------------------------------------------------------ tabs

    void selectTab(int i) {
        currentTab = i;
        prefs.edit().putInt("tab", i).apply();
        for (int k = 0; k < 3; k++) {
            TextView t = tabViews[k];
            t.setTextColor(k == i ? 0xFFFFFFFF : DIM);
            t.setTypeface(null, k == i ? Typeface.BOLD : Typeface.NORMAL);
            if (k == i) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xFF4A4A4A);
                g.setCornerRadius(dp(22));
                t.setBackground(g);
            } else {
                t.setBackground(null);
            }
        }
        tabContent.removeAllViews();
        if (i == 0) {
            tabContent.addView(new PillButton(this, Icons.REWIND, () -> scanPress(false, true),
                    () -> scanPress(false, false)), pillLp());
            tabContent.addView(pill(Icons.PLAY_PAUSE, () -> ftvCall(ftv::playPause)), pillLp());
            tabContent.addView(new PillButton(this, Icons.FFWD, () -> scanPress(true, true),
                    () -> scanPress(true, false)), pillLp());
        } else if (i == 1) {
            buildAppsRow();
        } else {
            tabContent.addView(pill(Icons.TV, () -> ftvAction("epg")), pillLp());
            tabContent.addView(pill(Icons.CHEV_DOWN, () -> ftvAction("dpad_down")), pillLp());
            tabContent.addView(pill(Icons.CHEV_UP, () -> ftvAction("dpad_up")), pillLp());
        }
    }

    View pill(int icon, Runnable r) {
        return new PillButton(this, icon, r, null);
    }

    LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        return lp;
    }

    /** Rounded rectangle button used in the drawer. */
    class PillButton extends View {
        final int icon;
        final Runnable r, up;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        PillButton(Context c, int icon, Runnable r, Runnable up) {
            super(c);
            this.icon = icon;
            this.r = r;
            this.up = up;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            p.setColor(isPressed() ? 0xFF3A3A3A : 0xFF262626);
            float rad = getHeight() / 2.4f;
            c.drawRoundRect(new android.graphics.RectF(0, 0, getWidth(), getHeight()), rad, rad, p);
            Icons.draw(c, icon, getWidth() / 2f, getHeight() / 2f, getHeight() * 0.2f, 0xFFFFFFFF);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                setPressed(true);
                invalidate();
                r.run();
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                if (up != null && isPressed()) up.run();
                setPressed(false);
                invalidate();
                if (a == android.view.MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    void buildAppsRow() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(row);
        tabContent.addView(hs, new LinearLayout.LayoutParams(-1, -1));

        List<String[]> tiles = new ArrayList<>();
        Map<String, String> iconUrls = new HashMap<>();
        if (apps != null && !apps.isEmpty()) {
            Map<String, FireTvClient.App> byId = new HashMap<>();
            for (FireTvClient.App a : apps) byId.put(a.id, a);
            for (String[] f : FAVOURITES) {
                FireTvClient.App a = byId.remove(f[0]);
                if (a != null) {
                    tiles.add(f);
                    if (a.icon != null) iconUrls.put(a.id, a.icon);
                }
            }
            List<FireTvClient.App> rest = new ArrayList<>(byId.values());
            rest.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
            for (FireTvClient.App a : rest) {
                tiles.add(new String[]{a.id, a.name, "#FF444444", "#FFFFFFFF"});
                if (a.icon != null) iconUrls.put(a.id, a.icon);
            }
        } else {
            for (String[] f : FAVOURITES) tiles.add(f);
        }

        for (String[] t : tiles) {
            FrameLayout tile = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(android.graphics.Color.parseColor(t[2]));
            g.setCornerRadius(dp(18));
            tile.setBackground(g);
            tile.setClipToOutline(true);
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
                }
            });
            TextView tv = new TextView(this);
            tv.setText(t[1]);
            tv.setTextColor(android.graphics.Color.parseColor(t[3]));
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, t[1].length() > 11 ? 12 : 15);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(2);
            tv.setPadding(dp(6), 0, dp(6), 0);
            tile.addView(tv, new FrameLayout.LayoutParams(-1, -1));
            String url = iconUrls.get(t[0]);
            if (url != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                Bitmap cached = iconCache.get(url);
                if (cached != null) {
                    iv.setImageBitmap(cached);
                } else {
                    ftv.loadBitmap(url, (bmp, err) -> {
                        iconCache.put(url, bmp);
                        iv.setImageBitmap(bmp);
                    });
                }
            }
            final String pkg = t[0];
            tile.setOnClickListener(v -> {
                buzz();
                ftvCall(() -> ftv.launchApp(pkg));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(118), dp(62));
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(tile, lp);
        }
    }

    void loadApps() {
        ftv.loadApps((list, err) -> {
            if (list != null && !list.isEmpty()) {
                apps = list;
                if (currentTab == 1) selectTab(1);
            }
        });
    }

    // ------------------------------------------------------------------ actions

    final Widgets.PadListener padListener = new Widgets.PadListener() {
        @Override
        public void onDirection(int dir, boolean isRepeat) {
            if (tvMode) {
                if (isRepeat && ir.busy()) return;
                irPress(IR_DIRS[dir], false);
                bumpTvMode();
                return;
            }
            if (isRepeat && ftv.busy()) return; // skip rather than pile up
            buzz();
            ftvAction(DIRS[dir], false);
        }

        @Override
        public void onSelect() {
            if (tvMode) {
                irPress(IrRemote.OK, false);
                bumpTvMode();
                return;
            }
            buzz();
            if (checkPaired()) ftv.keyed("select");
        }
    };

    boolean scanHeld;
    boolean scanHeldForward;
    final Runnable scanSafety = () -> {
        if (scanHeld) {
            scanHeld = false;
            ftv.scanKey(scanHeldForward, false);
        }
    };

    /** Rewind / fast forward behave like the physical remote: tap to skip, hold to scrub. */
    void scanPress(boolean forward, boolean down) {
        if (down) {
            if (!checkPaired()) return;
            buzz();
            scanHeld = true;
            scanHeldForward = forward;
            ftv.scanKey(forward, true);
            h.removeCallbacks(scanSafety);
            h.postDelayed(scanSafety, 15000); // never leave the TV scrubbing
        } else if (scanHeld) {
            scanHeld = false;
            h.removeCallbacks(scanSafety);
            ftv.scanKey(forward, false);
        }
    }

    static final int REQ_SPEECH = 42;

    void startYouTubeVoiceSearch() {
        if (!checkPaired()) return;
        buzz();
        android.content.Intent i = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-GB");
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search YouTube for…");
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (Exception e) {
            askYouTubeTyped(); // no speech recogniser on the phone: type it instead
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK || data == null) return;
        java.util.ArrayList<String> res =
                data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (res == null || res.isEmpty() || res.get(0).trim().isEmpty()) return;
        runYouTubeSearch(res.get(0).trim());
    }

    void askYouTubeTyped() {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("What do you want to watch?");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Search YouTube")
                .setView(box)
                .setPositiveButton("Search", (d, w) -> {
                    String q = et.getText().toString().trim();
                    if (!q.isEmpty()) runYouTubeSearch(q);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** SmartTube package to use for voice search, or null to use the YouTube app. */
    String smartTubePackage() {
        if (!prefs.getBoolean("useSmartTube", true)) return null;
        if (apps != null) {
            for (String pkg : FireTvClient.SMARTTUBE) {
                for (FireTvClient.App a : apps) {
                    if (pkg.equals(a.id)) {
                        prefs.edit().putString("smartTubePkg", pkg).apply();
                        return pkg;
                    }
                }
            }
            for (FireTvClient.App a : apps) {
                if (a.name != null && a.name.toLowerCase().contains("smarttube")) {
                    prefs.edit().putString("smartTubePkg", a.id).apply();
                    return a.id;
                }
            }
        }
        return prefs.getString("smartTubePkg", null);
    }

    void runYouTubeSearch(String q) {
        String st = smartTubePackage();
        String appName = st != null ? "SmartTube" : "YouTube";
        Toast.makeText(this, "Searching " + appName + " for \"" + q + "\"…", Toast.LENGTH_LONG).show();
        Runnable done = () -> toast("Searched " + appName + " for \"" + q + "\"");
        if (st != null) ftv.smartTubeSearch(st, q, done);
        else ftv.youtubeSearch(q, done);
    }

    void inputPressed() {
        irPress(IrRemote.INPUT, false);
        setTvMode(true);
    }

    void backPressed() {
        if (tvMode) {
            irPress(IrRemote.BACK, false);
            bumpTvMode();
        } else {
            ftvAction("back");
        }
    }

    void homePressed() {
        if (tvMode) setTvMode(false);
        ftvAction("home");
    }

    void setTvMode(boolean on) {
        tvMode = on;
        h.removeCallbacks(leaveTvMode);
        banner.setVisibility(on ? View.VISIBLE : View.GONE);
        trackpad.setPadColor(on ? TV_PAD : PAD);
        if (on) {
            trackpad.setHint("TV menu: swipe to move,", "tap for OK");
            dpad.setColors(0xFF4A3B12, 0xFF5A4816);
            h.postDelayed(leaveTvMode, 12000);
        } else {
            trackpad.setHint("Swipe to move,", "tap to select");
            dpad.setColors(BTN, 0xFF4A4A4A);
        }
    }

    void bumpTvMode() {
        h.removeCallbacks(leaveTvMode);
        h.postDelayed(leaveTvMode, 12000);
    }

    void irPress(int key, boolean fromRepeat) {
        if (!ir.available()) {
            toast("This phone's IR blaster isn't available to apps");
            return;
        }
        buzz();
        ir.send(key);
    }

    void ftvAction(String action) {
        buzz();
        ftvAction(action, true);
    }

    void ftvAction(String action, boolean ignored) {
        if (checkPaired()) ftv.action(action);
    }

    void ftvCall(Runnable r) {
        buzz();
        if (checkPaired()) r.run();
    }

    boolean checkPaired() {
        if (ftv.isPaired()) return true;
        toast("Pair your Fire TV first: tap the name at the top");
        return false;
    }

    void openFireTvSettings() {
        // Opens the Fire TV's own Settings app
        if (!checkPaired()) return;
        buzz();
        ftv.launchApp("com.amazon.tv.settings.v2");
    }

    void buzz() {
        if (!prefs.getBoolean("vibrate", true) || vibrator == null) return;
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    void toast(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastToast < 1500) return;
        lastToast = now;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    void onFireTvStatus(int state, String msg) {
        int color;
        switch (state) {
            case FireTvClient.ST_OK:
                color = 0xFF43A047;
                break;
            case FireTvClient.ST_WAKING:
                color = AMBER;
                break;
            case FireTvClient.ST_ERROR:
            case FireTvClient.ST_UNPAIRED:
                color = RED;
                break;
            default:
                color = DIM;
        }
        ((GradientDrawable) statusDot.getBackground()).setColor(color);
        if (msg != null) toast(msg);
        if (state == FireTvClient.ST_OK && apps == null) loadApps();
    }

    void updateTitle() {
        String name = prefs.getString("name", null);
        title.setText(ftv.isPaired() ? (name != null ? name : "Fire TV") : "Tap to pair Fire TV");
    }

    // ------------------------------------------------------------------ keyboard

    void showKeyboard() {
        if (!checkPaired()) return;
        buzz();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView hint = new TextView(this);
        hint.setText("Select a search box on the TV first, then type here.");
        hint.setTextColor(DIM);
        box.addView(hint);
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(et);
        AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Type on TV")
                .setView(box)
                .setPositiveButton("Send", (di, w) -> ftv.typeText(et.getText().toString(), false))
                .setNeutralButton("Send + OK", (di, w) -> ftv.typeText(et.getText().toString(), true))
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> {
            et.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
    }

    // ------------------------------------------------------------------ settings

    void showSettings() {
        boolean useDpad = prefs.getBoolean("dpadMode", false);
        boolean vib = prefs.getBoolean("vibrate", true);
        boolean volKeys = prefs.getBoolean("volKeys", true);
        String ip = prefs.getString("ip", null);
        String[] items = {
                "Fire TV: " + (ftv.isPaired() ? prefs.getString("name", "Fire TV") + " (" + ip + ")" : "not paired"),
                "Control style: " + (useDpad ? "D-Pad" : "Trackpad") + "  (tap to switch)",
                "Vibration: " + (vib ? "On" : "Off"),
                "Phone volume buttons change TV volume: " + (volKeys ? "On" : "Off"),
                "TV IR codes: " + (ir.getCodeSet() == 0 ? "Set A" : "Set B") + "  (tap to test/change)",
                "IR blaster: " + (ir.available() ? "found" : "NOT found"),
                "Voice search app: " + (prefs.getBoolean("useSmartTube", true)
                        ? "SmartTube" + (smartTubePackage() == null ? " (not found on Fire TV yet)" : "")
                        : "YouTube"),
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Remote settings")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            showDevicePicker();
                            break;
                        case 1:
                            prefs.edit().putBoolean("dpadMode", !useDpad).apply();
                            applyControlStyle();
                            break;
                        case 2:
                            prefs.edit().putBoolean("vibrate", !vib).apply();
                            break;
                        case 3:
                            prefs.edit().putBoolean("volKeys", !volKeys).apply();
                            break;
                        case 4:
                            showIrSetPicker();
                            break;
                        case 6:
                            prefs.edit().putBoolean("useSmartTube", !prefs.getBoolean("useSmartTube", true)).apply();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    void showIrSetPicker() {
        final int[] chosen = {ir.getCodeSet()};
        AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Hisense IR codes")
                .setSingleChoiceItems(IrRemote.SET_NAMES, chosen[0], (di, w) -> chosen[0] = w)
                .setPositiveButton("Use this", (di, w) -> {
                    ir.setCodeSet(chosen[0]);
                    prefs.edit().putInt("irSet", chosen[0]).apply();
                })
                .setNeutralButton("Test Vol +", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            buzz();
            if (!ir.available()) toast("IR blaster not available");
            else ir.send(IrRemote.VOL_UP, chosen[0]);
        }));
        d.show();
    }

    // ------------------------------------------------------------------ pairing

    void showDevicePicker() {
        AlertDialog scanning = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Finding Fire TVs…")
                .setMessage("Searching your Wi-Fi. Make sure the Fire TV is switched on.")
                .setNegativeButton("Enter IP instead", (d, w) -> askForIp())
                .create();
        scanning.show();
        Discovery.scan(this, (list, err) -> {
            if (!scanning.isShowing()) return;
            scanning.dismiss();
            List<String> labels = new ArrayList<>();
            for (Discovery.Device d : list) {
                labels.add((d.amazon ? "📺 " : "") + d.name + "\n" + d.ip);
            }
            labels.add("Enter IP address manually…");
            new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle(list.isEmpty() ? "No Fire TV found" : "Choose your Fire TV")
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        if (which >= list.size()) {
                            askForIp();
                        } else {
                            Discovery.Device dev = list.get(which);
                            startPairing(dev.ip, dev.name, dev.mac);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    void askForIp() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("e.g. 192.168.1.50");
        String cur = prefs.getString("ip", "");
        et.setText(cur);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Fire TV IP address")
                .setMessage("On the Fire TV: Settings > My Fire TV > About > Network")
                .setView(box)
                .setPositiveButton("Pair", (d, w) -> {
                    String ip = et.getText().toString().trim();
                    if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                        toast("That doesn't look like an IP address");
                        return;
                    }
                    startPairing(ip, null, null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void startPairing(String ip, String name, String mac) {
        AlertDialog wait = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Connecting…")
                .setMessage("Asking the Fire TV to show a PIN on screen.")
                .setCancelable(false)
                .create();
        wait.show();
        ftv.requestPin(ip, "TV Remote (" + Build.MODEL + ")", (ok, err) -> {
            wait.dismiss();
            if (!Boolean.TRUE.equals(ok)) {
                new AlertDialog.Builder(this, R.style.DialogTheme)
                        .setTitle("Couldn't connect")
                        .setMessage(err)
                        .setPositiveButton("Try again", (d, w) -> startPairing(ip, name, mac))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            askForPin(ip, name, mac);
        });
    }

    void askForPin(String ip, String name, String mac) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("4-digit PIN");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        et.setGravity(Gravity.CENTER);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Enter the PIN shown on your TV")
                .setView(box)
                .setPositiveButton("Pair", (di, w) -> {
                    String pin = et.getText().toString().trim();
                    AlertDialog wait = new AlertDialog.Builder(this, R.style.DialogTheme)
                            .setMessage("Pairing…").setCancelable(false).create();
                    wait.show();
                    ftv.verifyPin(ip, pin, (token, err) -> {
                        wait.dismiss();
                        if (token == null) {
                            new AlertDialog.Builder(this, R.style.DialogTheme)
                                    .setTitle("Pairing failed")
                                    .setMessage(err)
                                    .setPositiveButton("Try again", (x, y) -> startPairing(ip, name, mac))
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            return;
                        }
                        String keepMac = mac;
                        if (keepMac == null && ip.equals(prefs.getString("ip", null))) {
                            keepMac = prefs.getString("mac", null);
                        }
                        prefs.edit()
                                .putString("ip", ip)
                                .putString("token", token)
                                .putString("name", name != null && !name.equals(ip) ? name : "Fire TV")
                                .putString("mac", keepMac)
                                .apply();
                        ftv.configure(ip, token, keepMac);
                        updateTitle();
                        apps = null;
                        loadApps();
                        toast("Paired with your Fire TV");
                        onFireTvStatus(FireTvClient.ST_OK, null);
                    });
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> {
            et.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
    }
}
