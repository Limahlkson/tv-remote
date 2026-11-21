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
        yt = new YouTubeAccount(prefs);
        voice = new VoiceEngine();
        voice.prepare(this, 2); // bump when the bundled model changes

        getWindow().setStatusBarColor(PAGE);
        getWindow().setNavigationBarColor(CARD);
        setContentView(buildUi());
        applyControlStyle();
        updateTitle();

        if (!ftv.isPaired()) h.postDelayed(this::showDevicePicker, 600);
        handleShare(getIntent());
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
        tabViews[2].setOnClickListener(null);
        tabViews[2].setOnTouchListener(channelsHold);
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

        voiceBanner = new TextView(this);
        voiceBanner.setTextColor(0xFFFFFFFF);
        voiceBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        voiceBanner.setGravity(Gravity.CENTER);
        voiceBanner.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable vb = new GradientDrawable();
        vb.setColor(0xFF2E7D32);
        vb.setCornerRadius(dp(12));
        voiceBanner.setBackground(vb);
        voiceBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.bottomMargin = dp(6);
        card.addView(voiceBanner, vlp);

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
                .iconScale(0.34f).action(this::micDown, false).release(this::micUp);
        micBtn = mic;
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
            buildChannelsRow();
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
            for (FireTvClient.App a : apps) {
                if (FireTvClient.isSmartTube(a)) {
                    byId.remove(a.id);
                    tiles.add(new String[]{a.id, "SmartTube", "#FFCC0000", "#FFFFFFFF"});
                    if (a.icon != null && !a.icon.isEmpty()) iconUrls.put(a.id, a.icon);
                }
            }
            for (String[] f : FAVOURITES) {
                FireTvClient.App a = byId.remove(f[0]);
                if (a != null) {
                    tiles.add(f);
                    if (a.icon != null && !a.icon.isEmpty()) iconUrls.put(a.id, a.icon);
                }
            }
            List<FireTvClient.App> rest = new ArrayList<>(byId.values());
            rest.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
            for (FireTvClient.App a : rest) {
                tiles.add(new String[]{a.id, a.name, "#FF444444", "#FFFFFFFF"});
                if (a.icon != null && !a.icon.isEmpty()) iconUrls.put(a.id, a.icon);
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

    // ---- hold-to-talk voice search (offline, inside the app) --------------------------------

    static final int REQ_MIC = 43, REQ_IMPORT = 44;
    VoiceEngine voice;
    Widgets.CircleButton micBtn;
    TextView voiceBanner;
    boolean micHeld;
    String lastHeard;

    int appVersion() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 1;
        }
    }

    static final long TAP_MS = 450;           // shorter than this = tap (open search), longer = speech
    long micDownAt;
    boolean recording;
    final Runnable showListening = () -> {
        if (micHeld && recording) {
            buzz();
            showVoiceBanner("Listening…   let go to type it on the TV");
            micBtn.setFill(0xFF2E7D32);
        }
    };

    /** Mic down: start recording at once so the first word isn't lost; a quick tap throws it away. */
    void micDown() {
        buzz();
        micDownAt = System.currentTimeMillis();
        micHeld = true;
        lastHeard = null;
        recording = false;
        if (!ftv.isPaired()) return;
        boolean haveMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (haveMic && voice.state() == VoiceEngine.READY && !voice.isRecording()) {
            recording = voice.start(new VoiceEngine.Listener() {
                @Override
                public void onPartial(String text) {
                    lastHeard = text;
                    if (System.currentTimeMillis() - micDownAt >= TAP_MS) {
                        showVoiceBanner("“" + text + "”" + (micHeld ? "   let go to type it" : "   typing…"));
                    }
                }

                @Override
                public void onFinal(String text) {
                    recording = false;
                    endVoiceUi();
                    String q = text != null && !text.isEmpty() ? text : (lastHeard == null ? "" : lastHeard);
                    if (q.isEmpty()) {
                        toast("Didn't catch that. Hold the mic, speak, then let go.");
                    } else {
                        sendSpokenSearch(q);
                    }
                }

                @Override
                public void onError(String message) {
                    recording = false;
                    endVoiceUi();
                    toast(message);
                }
            });
        }
        h.postDelayed(showListening, TAP_MS);
    }

    /** Mic up: a tap opens the search box; after a hold, the words are typed into it. */
    void micUp() {
        micHeld = false;
        h.removeCallbacks(showListening);
        long held = System.currentTimeMillis() - micDownAt;
        if (held < TAP_MS) {
            if (recording) voice.cancel();
            recording = false;
            endVoiceUi();
            tapOpenSearch();
            return;
        }
        if (!checkPaired()) return;
        if (recording) {
            showVoiceBanner(lastHeard != null ? "“" + lastHeard + "”   typing…" : "Working it out…");
            voice.stop();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            toast("Voice: " + voiceStatus());
        }
    }

    boolean searchUsesSmartTube() {
        return prefs.getBoolean("useSmartTube", true);
    }

    /** Tap: put the TV on the search box, ready for speech. */
    void tapOpenSearch() {
        if (!checkPaired()) return;
        String app = searchUsesSmartTube() ? "SmartTube" : "YouTube";
        toast("Opening " + app + " search…");
        ftv.openSearch(searchUsesSmartTube(), smartTubePackage(), (ok, err) -> {
            if (err != null) {
                searchError(err);
            } else {
                toast("Search box ready. Hold the mic and speak.");
            }
        });
    }

    /** Spoken words: type them into the search box that's already open, then Select to search. */
    void sendSpokenSearch(String q) {
        showVoiceBanner("Typing on TV:  “" + q + "”");
        ftv.typeSearch(q, false, searchUsesSmartTube(), smartTubePackage(), (ok, err) -> {
            h.postDelayed(this::endVoiceUi, 2500);
            if (err != null) searchError(err);
        });
    }

    void searchError(String err) {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(searchUsesSmartTube() ? "SmartTube search" : "YouTube search")
                .setMessage(err)
                .setPositiveButton("OK", null)
                .setNeutralButton("Pick SmartTube", (d, w) -> pickSmartTubeApp())
                .show();
    }

    void showVoiceBanner(String text) {
        voiceBanner.setText(text);
        voiceBanner.setVisibility(View.VISIBLE);
    }

    void endVoiceUi() {
        voiceBanner.setVisibility(View.GONE);
        micBtn.setFill(0xFFCC0000);
    }

    String voiceStatus() {
        switch (voice.state()) {
            case VoiceEngine.READY:
                return "ready (offline, built in)";
            case VoiceEngine.LOADING:
                return "getting ready…";
            default:
                return "failed: " + voice.failReason();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Mic ready. Hold the mic button, speak, then let go.");
            } else {
                toast("Voice search needs the microphone");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (voice != null) voice.stop();
        super.onDestroy();
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

    // ---- SmartTube ---------------------------------------------------------------------

    /** SmartTube as the Fire TV's app list reports it, if it's there. */
    FireTvClient.App detectedSmartTube() {
        if (apps == null) return null;
        for (FireTvClient.App a : apps) if (FireTvClient.isSmartTube(a)) return a;
        return null;
    }

    /** Package to use: the one picked in settings, else the detected one, else let the client guess. */
    String smartTubePackage() {
        String chosen = prefs.getString("smartTubeChosen", null);
        if (chosen != null) return chosen;
        FireTvClient.App a = detectedSmartTube();
        return a == null ? null : a.id;
    }

    String smartTubeLabel() {
        String chosen = prefs.getString("smartTubeChosen", null);
        if (chosen != null) return prefs.getString("smartTubeChosenName", chosen) + " (picked)";
        FireTvClient.App a = detectedSmartTube();
        if (a != null) return a.name + " (found)";
        return apps == null ? "checking…" : "not found, tap to pick";
    }

    void pickSmartTubeApp() {
        if (!checkPaired()) return;
        if (apps == null || apps.isEmpty()) {
            toast("Getting the Fire TV's app list…");
            ftv.loadApps((list, err) -> {
                if (list != null && !list.isEmpty()) {
                    apps = list;
                    pickSmartTubeApp();
                } else {
                    toast("Couldn't get the app list from the Fire TV");
                }
            });
            return;
        }
        List<FireTvClient.App> sorted = new ArrayList<>(apps);
        sorted.sort((x, y) -> {
            boolean a = FireTvClient.isSmartTube(x), b = FireTvClient.isSmartTube(y);
            if (a != b) return a ? -1 : 1;
            return x.name.compareToIgnoreCase(y.name);
        });
        String[] names = new String[sorted.size()];
        for (int i = 0; i < names.length; i++) {
            FireTvClient.App a = sorted.get(i);
            names[i] = (FireTvClient.isSmartTube(a) ? "▶ " : "") + a.name + "\n" + a.id;
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Which app is SmartTube?")
                .setItems(names, (d, w) -> {
                    FireTvClient.App a = sorted.get(w);
                    prefs.edit().putString("smartTubeChosen", a.id).putString("smartTubeChosenName", a.name).apply();
                    toast("Voice search will use " + a.name);
                })
                .setNeutralButton("Auto-detect", (d, w) -> {
                    prefs.edit().remove("smartTubeChosen").remove("smartTubeChosenName").apply();
                    toast("SmartTube: " + smartTubeLabel());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Typed search or a channel tile: open the search box, type, search. */
    void runYouTubeSearch(String q) {
        if (!checkPaired()) return;
        toast("Searching " + (searchUsesSmartTube() ? "SmartTube" : "YouTube") + " for \"" + q + "\"…");
        ftv.typeSearch(q, true, searchUsesSmartTube(), smartTubePackage(), (ok, err) -> {
            if (err != null) searchError(err);
        });
    }

    // ---- YouTube channel favourites (Channels tab) --------------------------------------

    YouTubeAccount yt;

    static class Channel {
        String id = "", title = "", handle = "";

        String key() {
            if (!id.isEmpty()) return id;
            if (!handle.isEmpty()) return handle.toLowerCase();
            return "name:" + title.toLowerCase();
        }

        String avatarKey() {
            return !id.isEmpty() ? id : handle;
        }

        String pageUrl() {
            if (!id.isEmpty()) return "https://www.youtube.com/channel/" + id;
            if (!handle.isEmpty()) return "https://www.youtube.com/" + handle;
            return null;
        }
    }

    static final long CHANNEL_HOLD_MS = 5000;
    boolean channelsHoldFired;
    long channelsHoldStart;
    final Runnable channelsCountdown = new Runnable() {
        @Override
        public void run() {
            long held = System.currentTimeMillis() - channelsHoldStart;
            if (held >= CHANNEL_HOLD_MS) {
                channelsHoldFired = true;
                tabViews[2].setText("Channels");
                buzz();
                showChannelManager();
                return;
            }
            long left = (CHANNEL_HOLD_MS - held + 999) / 1000;
            tabViews[2].setText("Hold " + left + "…");
            h.postDelayed(this, 250);
        }
    };

    /** Tap Channels = show favourites. Hold 5 s = choose favourites. */
    final View.OnTouchListener channelsHold = (v, e) -> {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                channelsHoldFired = false;
                channelsHoldStart = System.currentTimeMillis();
                h.postDelayed(channelsCountdown, 600);
                return true;
            case android.view.MotionEvent.ACTION_UP:
                h.removeCallbacks(channelsCountdown);
                tabViews[2].setText("Channels");
                if (!channelsHoldFired) selectTab(2);
                v.performClick();
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(channelsCountdown);
                tabViews[2].setText("Channels");
                return true;
            default:
                return true;
        }
    };

    List<Channel> loadChannels() {
        List<Channel> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("subs", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Channel c = new Channel();
                c.id = o.optString("id", "");
                c.title = o.optString("title", "");
                c.handle = o.optString("handle", "");
                if (!c.title.isEmpty()) out.add(c);
            }
        } catch (Exception ignored) {
        }
        out.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    void saveChannels(List<Channel> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Channel c : list) {
            try {
                arr.put(new org.json.JSONObject().put("id", c.id).put("title", c.title).put("handle", c.handle));
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString("subs", arr.toString()).apply();
    }

    java.util.Set<String> loadFavKeys() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("favChannels", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    void saveFavKeys(java.util.Set<String> keys) {
        prefs.edit().putString("favChannels", new org.json.JSONArray(keys).toString()).apply();
    }

    List<Channel> favouriteChannels() {
        java.util.Set<String> fav = loadFavKeys();
        List<Channel> out = new ArrayList<>();
        for (Channel c : loadChannels()) if (fav.contains(c.key())) out.add(c);
        return out;
    }

    void buildChannelsRow() {
        List<Channel> favs = favouriteChannels();
        if (favs.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Hold the Channels tab for 5 seconds to pick your favourite YouTube channels");
            hint.setTextColor(DIM);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(12), 0, dp(12), 0);
            hint.setOnClickListener(v -> showChannelManager());
            tabContent.addView(hint, new LinearLayout.LayoutParams(-1, -1));
            return;
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(row);
        tabContent.addView(hs, new LinearLayout.LayoutParams(-1, -1));
        int[] palette = {0xFF8E24AA, 0xFF1E88E5, 0xFF00897B, 0xFFF4511E, 0xFF6D4C41, 0xFF3949AB, 0xFFC0CA33, 0xFFD81B60};
        for (Channel c : favs) {
            FrameLayout tile = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(palette[Math.abs(c.title.hashCode()) % palette.length]);
            g.setCornerRadius(dp(18));
            tile.setBackground(g);
            tile.setClipToOutline(true);
            tile.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
                }
            });
            LinearLayout inner = new LinearLayout(this);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            inner.setPadding(dp(6), 0, dp(8), 0);
            ImageView avatar = new ImageView(this);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setClipToOutline(true);
            avatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            avatar.setVisibility(View.GONE);
            inner.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView tv = new TextView(this);
            tv.setText(c.title);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.title.length() > 14 ? 12 : 14);
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(6), 0, 0, 0);
            inner.addView(tv, new LinearLayout.LayoutParams(-1, -2));
            tile.addView(inner, new FrameLayout.LayoutParams(-1, -1));
            loadAvatar(c, avatar);
            tile.setOnClickListener(v -> {
                buzz();
                openChannel(c);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(62));
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(tile, lp);
        }
    }

    void openChannel(Channel c) {
        if (!checkPaired()) return;
        runYouTubeSearch(c.title);
    }

    final java.util.concurrent.ExecutorService avatarExec = java.util.concurrent.Executors.newFixedThreadPool(2);

    /** Best effort: channel picture from the public channel page, cached. */
    void loadAvatar(Channel c, ImageView into) {
        String akey = c.avatarKey();
        String page = c.pageUrl();
        if (akey.isEmpty() || page == null) return;
        String cachedUrl = prefs.getString("avatar_" + akey, null);
        if (cachedUrl != null && !cachedUrl.isEmpty()) {
            showAvatar(cachedUrl, into);
            return;
        }
        if (cachedUrl != null) return; // looked before, none found
        avatarExec.execute(() -> {
            String found = "";
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(page).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
                conn.setRequestProperty("Accept-Language", "en-GB,en;q=0.9");
                conn.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1");
                try (java.io.InputStream in = conn.getInputStream()) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    int n;
                    java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                            "<meta property=\"og:image\" content=\"([^\"]+)\"");
                    while ((n = in.read(b)) > 0 && bo.size() < 800000) {
                        bo.write(b, 0, n);
                        java.util.regex.Matcher m = pat.matcher(bo.toString("UTF-8"));
                        if (m.find()) {
                            found = m.group(1).replace("&amp;", "&");
                            break;
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {
            }
            final String url = found;
            h.post(() -> {
                prefs.edit().putString("avatar_" + akey, url).apply();
                if (!url.isEmpty()) showAvatar(url, into);
            });
        });
    }

    void showAvatar(String url, ImageView into) {
        Bitmap cached = iconCache.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            into.setVisibility(View.VISIBLE);
            return;
        }
        ftv.loadBitmap(url, (bmp, err) -> {
            iconCache.put(url, bmp);
            into.setImageBitmap(bmp);
            into.setVisibility(View.VISIBLE);
        });
    }

    /** Tick list of subscriptions; ticked ones show in the Channels tab. */
    void showChannelManager() {
        List<Channel> all = loadChannels();
        if (all.isEmpty()) {
            channelSourcesMenu("Get your YouTube channels");
            return;
        }
        java.util.Set<String> fav = loadFavKeys();
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).title;
            checked[i] = fav.contains(all.get(i).key());
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Tick your favourite channels")
                .setMultiChoiceItems(names, checked, (d, w, isChecked) -> checked[w] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    java.util.Set<String> keys = new java.util.HashSet<>();
                    for (int i = 0; i < all.size(); i++) if (checked[i]) keys.add(all.get(i).key());
                    saveFavKeys(keys);
                    selectTab(2);
                    toast(keys.size() + " favourite channel" + (keys.size() == 1 ? "" : "s"));
                })
                .setNeutralButton("More…", (d, w) -> channelSourcesMenu("Channels"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Where channels come from: YouTube sign-in, a Takeout file, or typed names. */
    void channelSourcesMenu(String title) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Add from the YouTube app (Share > TV Remote, no setup)");
        actions.add(this::showShareHelp);
        labels.add("Add a channel by name");
        actions.add(this::askAddChannel);
        if (yt.signedIn()) {
            labels.add("Whole list: refresh from my YouTube account");
            actions.add(this::importFromYouTube);
        } else {
            labels.add("Whole list: sign in with YouTube (needs a one-off Google setup)");
            actions.add(this::signInYouTube);
        }
        labels.add("Whole list: import a Google Takeout file");
        actions.add(this::startImport);
        labels.add("How to get the Takeout file");
        actions.add(this::showTakeoutHelp);
        if (yt.signedIn()) {
            labels.add("Sign out of YouTube");
            actions.add(() -> {
                yt.signOut();
                toast("Signed out of YouTube");
            });
        }
        if (yt.hasClient()) {
            labels.add("Change Google client ID");
            actions.add(this::showYouTubeClientSetup);
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .setNegativeButton("Close", null)
                .show();
    }

    void signInYouTube() {
        if (!yt.hasClient()) {
            showYouTubeClientSetup();
        } else {
            doDeviceSignIn();
        }
    }

    /** One-off: the user's own Google client ID (YouTube only allows sign-in through one). */
    void showYouTubeClientSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView steps = new TextView(this);
        steps.setTextColor(TEXT);
        steps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        steps.setText("YouTube only lets apps read your subscriptions through Google's official sign-in, "
                + "which needs a free client ID from your own Google Cloud account. One-off, about 5 minutes:\n\n"
                + "1. Tap Open Google Cloud, sign in, create a project (any name, e.g. TV Remote).\n"
                + "2. APIs & Services > Library: search \"YouTube Data API v3\" and Enable it.\n"
                + "3. OAuth consent screen / Google Auth Platform: Get started, app name TV Remote, your email, "
                + "audience External. Under Audience > Test users, add your own Gmail.\n"
                + "4. Credentials (Clients) > Create OAuth client ID > type \"TVs and Limited Input devices\" > Create.\n"
                + "5. Copy the Client ID and Client secret into the boxes below.");
        box.addView(steps);
        EditText id = new EditText(this);
        id.setHint("Client ID (ends .apps.googleusercontent.com)");
        id.setSingleLine(true);
        id.setText(prefs.getString("ytClientId", ""));
        box.addView(id);
        EditText secret = new EditText(this);
        secret.setHint("Client secret");
        secret.setSingleLine(true);
        secret.setText(prefs.getString("ytClientSecret", ""));
        box.addView(secret);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Sign in with YouTube")
                .setView(sv)
                .setPositiveButton("Save & sign in", null)
                .setNeutralButton("Open Google Cloud", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> openUrl(
                    "https://console.cloud.google.com/apis/credentials"));
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String cid = id.getText().toString().trim();
                String sec = secret.getText().toString().trim();
                if (!cid.contains(".apps.googleusercontent.com") || sec.isEmpty()) {
                    toast("Paste both the Client ID and the Client secret");
                    return;
                }
                yt.setClient(cid, sec);
                d.dismiss();
                doDeviceSignIn();
            });
        });
        d.show();
    }

    void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("No browser found");
        }
    }

    /** Google shows a code; the user enters it at google.com/device; we wait for the approval. */
    void doDeviceSignIn() {
        toast("Asking Google for a sign-in code…");
        yt.startSignIn((dc, err) -> {
            if (dc == null) {
                new AlertDialog.Builder(this, R.style.DialogTheme)
                        .setTitle("Couldn't start sign-in")
                        .setMessage(err + "\n\nCheck the client ID is type \"TVs and Limited Input devices\" "
                                + "and the YouTube Data API v3 is enabled.")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Change client ID", (x, y) -> showYouTubeClientSetup())
                        .show();
                return;
            }
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle("Sign in with YouTube")
                    .setMessage("1. Go to  " + dc.url.replace("https://", "") + "\n"
                            + "2. Enter this code:\n\n        " + dc.userCode + "\n\n"
                            + "3. Pick your YouTube account and tap Allow.\n\n"
                            + "This screen carries on by itself once you've allowed it.")
                    .setPositiveButton("Open page", null)
                    .setNeutralButton("Copy code", null)
                    .setNegativeButton("Cancel", (x, y) -> cancelled.set(true))
                    .setCancelable(false)
                    .create();
            d.setOnShowListener(x -> {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    copyText(dc.userCode);
                    openUrl(dc.url);
                });
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> copyText(dc.userCode));
            });
            d.show();
            yt.waitForApproval(dc, cancelled, (ok, err2) -> {
                if (cancelled.get()) return;
                try {
                    d.dismiss();
                } catch (Exception ignored) {
                }
                if (Boolean.TRUE.equals(ok)) {
                    toast("Signed in to YouTube");
                    importFromYouTube();
                } else {
                    new AlertDialog.Builder(this, R.style.DialogTheme)
                            .setTitle("Sign-in didn't finish")
                            .setMessage(err2 + "\n\nIf it says access was blocked, add your Gmail as a test user "
                                    + "on the OAuth consent screen.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        });
    }

    void copyText(String t) {
        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("code", t));
        toast("Code copied: " + t);
    }

    /** Pull the subscription list from the signed-in account and merge it in. */
    void importFromYouTube() {
        toast("Fetching your YouTube subscriptions…");
        yt.fetchSubscriptions((subs, err) -> {
            if (subs == null) {
                new AlertDialog.Builder(this, R.style.DialogTheme)
                        .setTitle("Couldn't get your subscriptions")
                        .setMessage(err)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Sign in again", (x, y) -> signInYouTube())
                        .show();
                return;
            }
            List<Channel> all = loadChannels();
            java.util.Map<String, Channel> byKey = new java.util.LinkedHashMap<>();
            for (Channel c : all) byKey.put(c.key(), c);
            SharedPreferences.Editor ed = prefs.edit();
            int added = 0;
            for (YouTubeAccount.Sub sub : subs) {
                Channel c = new Channel();
                c.id = sub.id == null ? "" : sub.id;
                c.title = sub.title;
                if (!byKey.containsKey(c.key())) added++;
                byKey.put(c.key(), c);
                if (!c.id.isEmpty() && sub.thumb != null) ed.putString("avatar_" + c.id, sub.thumb);
            }
            ed.apply();
            saveChannels(new ArrayList<>(byKey.values()));
            toast(subs.size() + " subscriptions (" + added + " new). Tick your favourites.");
            showChannelManager();
        });
    }

    void showShareHelp() {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Add from the YouTube app")
                .setMessage("In the YouTube app on this phone, open a channel (or any video from it), tap Share, "
                        + "then pick TV Remote.\n\nThe channel goes straight into your Channels tab as a favourite. "
                        + "Nothing to sign in to or set up.")
                .setPositiveButton("Open YouTube", (d, w) -> {
                    android.content.Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.youtube");
                    if (i != null) startActivity(i);
                    else openUrl("https://www.youtube.com/");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    /** A link shared from the YouTube app (channel or video) adds that channel to favourites. */
    void handleShare(android.content.Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
        intent.setAction(null); // don't add it again on rotation
        if (text == null || !(text.contains("youtube.com") || text.contains("youtu.be"))) {
            toast("Share a YouTube channel or video to add it");
            return;
        }
        addSharedChannel(text);
    }

    void addSharedChannel(String text) {
        toast("Adding channel…");
        avatarExec.execute(() -> {
            String err = null;
            Channel c = new Channel();
            String image = null;
            try {
                java.util.regex.Matcher um = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
                if (!um.find()) throw new IllegalStateException("No YouTube link in that share");
                String url = um.group();
                String name = null, pageUrl = null;
                java.util.regex.Pattern pHandle = java.util.regex.Pattern.compile("youtube\\.com/(@[^/?#\\s]+)");
                java.util.regex.Pattern pId = java.util.regex.Pattern.compile("youtube\\.com/channel/(UC[\\w-]+)");
                java.util.regex.Pattern pLegacy = java.util.regex.Pattern.compile("youtube\\.com/((?:c|user)/[^/?#\\s]+)");
                java.util.regex.Matcher m;
                if ((m = pHandle.matcher(url)).find()) {
                    c.handle = java.net.URLDecoder.decode(m.group(1), "UTF-8");
                } else if ((m = pId.matcher(url)).find()) {
                    c.id = m.group(1);
                } else if ((m = pLegacy.matcher(url)).find()) {
                    pageUrl = "https://www.youtube.com/" + m.group(1);
                } else {
                    // a video or short: YouTube's public oEmbed says whose it is
                    java.util.regex.Matcher sm = java.util.regex.Pattern.compile("/shorts/([\\w-]+)").matcher(url);
                    if (sm.find()) url = "https://www.youtube.com/watch?v=" + sm.group(1);
                    org.json.JSONObject o = new org.json.JSONObject(webGet(
                            "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8"), 100000));
                    name = o.optString("author_name", null);
                    String au = o.optString("author_url", "");
                    if ((m = pHandle.matcher(au)).find()) c.handle = java.net.URLDecoder.decode(m.group(1), "UTF-8");
                    else if ((m = pId.matcher(au)).find()) c.id = m.group(1);
                    else if (!au.isEmpty()) pageUrl = au;
                }
                if (pageUrl == null) pageUrl = c.pageUrl();
                String page = "";
                try {
                    if (pageUrl != null) page = webGet(pageUrl, 700000);
                } catch (Exception ignored) {
                }
                String ogTitle = metaContent(page, "og:title");
                image = metaContent(page, "og:image");
                if (c.id.isEmpty()) {
                    java.util.regex.Matcher im = java.util.regex.Pattern.compile("\"externalId\":\"(UC[\\w-]{10,})\"").matcher(page);
                    if (im.find()) c.id = im.group(1);
                }
                if (ogTitle != null && !ogTitle.isEmpty()) c.title = ogTitle;
                else if (name != null && !name.isEmpty()) c.title = name;
                else if (!c.handle.isEmpty()) c.title = c.handle;
                else c.title = c.id;
                if (c.title.isEmpty()) throw new IllegalStateException("Couldn't work out which channel that is");
            } catch (Exception e) {
                err = "Couldn't add that channel: " + e.getMessage();
            }
            final String fErr = err;
            final String fImage = image;
            h.post(() -> {
                if (fErr != null) {
                    toast(fErr);
                    return;
                }
                if (fImage != null && !fImage.isEmpty()) prefs.edit().putString("avatar_" + c.avatarKey(), fImage).apply();
                List<Channel> all = loadChannels();
                boolean found = false;
                for (Channel x : all) {
                    if (x.key().equals(c.key()) || (!c.handle.isEmpty() && c.handle.equalsIgnoreCase(x.handle))) {
                        x.title = c.title;
                        if (x.id.isEmpty()) x.id = c.id;
                        if (x.handle.isEmpty()) x.handle = c.handle;
                        found = true;
                    }
                }
                if (!found) all.add(c);
                saveChannels(all);
                java.util.Set<String> fav = loadFavKeys();
                fav.add(c.key());
                saveFavKeys(fav);
                selectTab(2);
                toast("Added " + c.title + " to Channels");
            });
        });
    }

    /** Public YouTube page, with the cookie that skips the EU consent screen. */
    static String webGet(String url, int maxBytes) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "en-GB,en;q=0.9");
        conn.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1");
        try (java.io.InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && bo.size() < maxBytes) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    static String metaContent(String html, String property) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<meta property=\"" + java.util.regex.Pattern.quote(property) + "\" content=\"([^\"]*)\"").matcher(html);
        if (!m.find()) return null;
        return m.group(1).replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    void showTakeoutHelp() {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Get your subscriptions file")
                .setMessage("1. Open takeout.google.com (button below) signed in with your YouTube account.\n"
                        + "2. Tap Deselect all, then tick \"YouTube and YouTube Music\".\n"
                        + "3. Tap \"All YouTube data included\", untick everything except \"subscriptions\", OK.\n"
                        + "4. Next step > Create export. Google emails you a link, usually within minutes.\n"
                        + "5. Download the zip to this phone, come back here and choose Import. "
                        + "Pick the zip itself, no need to unzip it.")
                .setPositiveButton("Open Takeout", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://takeout.google.com/")));
                    } catch (Exception e) {
                        toast("No browser found");
                    }
                })
                .setNeutralButton("Import now", (d, w) -> startImport())
                .setNegativeButton("Close", null)
                .show();
    }

    void startImport() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("No file picker available");
        }
    }

    void askAddChannel() {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("Channel name or @handle");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Add a channel")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String t = et.getText().toString().trim();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("youtube\\.com/(@[^/?#]+)").matcher(t);
                    if (m.find()) t = m.group(1);
                    if (t.isEmpty()) return;
                    List<Channel> all = loadChannels();
                    Channel c = new Channel();
                    c.title = t;
                    for (Channel x : all) if (x.key().equals(c.key())) return;
                    all.add(c);
                    saveChannels(all);
                    java.util.Set<String> fav = loadFavKeys();
                    fav.add(c.key());
                    saveFavKeys(fav);
                    selectTab(2);
                    toast("Added " + t + " to favourites");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        new Thread(() -> {
            String err = null;
            List<Channel> imported = new ArrayList<>();
            try {
                byte[] bytes;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0 && bo.size() < 200_000_000) bo.write(b, 0, n);
                    bytes = bo.toByteArray();
                }
                List<String> csvs = new ArrayList<>();
                if (bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K') {
                    try (java.util.zip.ZipInputStream zin =
                                 new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                        java.util.zip.ZipEntry ze;
                        while ((ze = zin.getNextEntry()) != null) {
                            String name = ze.getName().toLowerCase();
                            if (!ze.isDirectory() && name.endsWith(".csv")) {
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                byte[] b = new byte[65536];
                                int n;
                                while ((n = zin.read(b)) > 0) bo.write(b, 0, n);
                                String text = bo.toString("UTF-8");
                                if (name.contains("subscri")) csvs.add(0, text);
                                else csvs.add(text);
                            }
                        }
                    }
                } else {
                    csvs.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
                for (String csv : csvs) {
                    imported = parseSubscriptionsCsv(csv);
                    if (!imported.isEmpty()) break;
                }
                if (imported.isEmpty()) err = "Couldn't find any channels in that file. Pick the Takeout zip or subscriptions.csv.";
            } catch (Exception e) {
                err = "Couldn't read that file: " + e.getMessage();
            }
            final String fErr = err;
            final List<Channel> fImported = imported;
            h.post(() -> {
                if (fErr != null) {
                    toast(fErr);
                    return;
                }
                List<Channel> all = loadChannels();
                java.util.Set<String> have = new java.util.HashSet<>();
                for (Channel c : all) have.add(c.key());
                int added = 0;
                for (Channel c : fImported) {
                    if (have.add(c.key())) {
                        all.add(c);
                        added++;
                    }
                }
                saveChannels(all);
                toast("Imported " + added + " channel" + (added == 1 ? "" : "s") + ". Now tick your favourites.");
                showChannelManager();
            });
        }, "import-subs").start();
    }

    /** Takeout subscriptions.csv: "Channel Id,Channel Url,Channel Title" (quoted fields allowed). */
    static List<Channel> parseSubscriptionsCsv(String csv) {
        List<Channel> out = new ArrayList<>();
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        if (rows.isEmpty()) return out;
        int idCol = -1, titleCol = -1, urlCol = -1;
        List<String> header = rows.get(0);
        for (int c = 0; c < header.size(); c++) {
            String hname = header.get(c).trim().toLowerCase();
            if (hname.contains("id") && idCol < 0) idCol = c;
            else if (hname.contains("url") && urlCol < 0) urlCol = c;
            else if ((hname.contains("title") || hname.contains("name")) && titleCol < 0) titleCol = c;
        }
        if (titleCol < 0) return out;
        for (int r = 1; r < rows.size(); r++) {
            List<String> cols = rows.get(r);
            if (cols.size() <= titleCol) continue;
            Channel ch = new Channel();
            ch.title = cols.get(titleCol).trim();
            if (idCol >= 0 && cols.size() > idCol) ch.id = cols.get(idCol).trim();
            if (ch.id.isEmpty() && urlCol >= 0 && cols.size() > urlCol) {
                String u = cols.get(urlCol);
                int k = u.indexOf("/channel/");
                if (k >= 0) ch.id = u.substring(k + 9).replaceAll("[/?#].*", "");
            }
            if (!ch.title.isEmpty()) out.add(ch);
        }
        return out;
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
        boolean useSt = prefs.getBoolean("useSmartTube", true);
        String ip = prefs.getString("ip", null);
        String[] items = {
                "Fire TV: " + (ftv.isPaired() ? prefs.getString("name", "Fire TV") + " (" + ip + ")" : "not paired"),
                "Control style: " + (useDpad ? "D-Pad" : "Trackpad") + "  (tap to switch)",
                "Vibration: " + (vib ? "On" : "Off"),
                "Phone volume buttons change TV volume: " + (volKeys ? "On" : "Off"),
                "TV IR codes: " + (ir.getCodeSet() == 0 ? "Set A" : "Set B") + "  (tap to test/change)",
                "IR blaster: " + (ir.available() ? "found" : "NOT found"),
                "Voice search opens: " + (useSt ? "SmartTube" : "YouTube app") + "  (tap to switch)",
                "SmartTube app: " + smartTubeLabel(),
                "Voice: " + voiceStatus(),
                "Type a search instead of speaking…",
                "YouTube channels (same as holding Channels)…",
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
                            prefs.edit().putBoolean("useSmartTube", !useSt).apply();
                            break;
                        case 7:
                            pickSmartTubeApp();
                            break;
                        case 9:
                            if (checkPaired()) askYouTubeTyped();
                            break;
                        case 10:
                            showChannelManager();
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
