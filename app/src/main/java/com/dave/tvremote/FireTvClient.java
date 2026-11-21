package com.dave.tvremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Talks to the Fire TV's own local remote-control API (the one Amazon's Fire TV app uses):
 * https://IP:8080 with a self-signed certificate, PIN pairing for a client token,
 * and a DIAL wake on http://IP:8009 when the stick has gone idle.
 */
public class FireTvClient {

    public static final int ST_UNKNOWN = 0, ST_OK = 1, ST_WAKING = 2, ST_ERROR = 3, ST_UNPAIRED = 4;

    public interface Listener {
        void onStatus(int state, String message);
    }

    public interface Callback<T> {
        void done(T result, String error);
    }

    public static class App {
        public String id, name, icon;
    }

    private static final String API_KEY = "0987654321";
    private static final String UA = "okhttp/4.10.0";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService side = Executors.newCachedThreadPool();
    private final AtomicInteger pending = new AtomicInteger();

    private volatile String ip, token, mac;
    private volatile boolean waking;
    private volatile int lastState = ST_UNKNOWN;
    private Listener listener;

    private static SSLSocketFactory trustAll;
    private static final HostnameVerifier ANY_HOST = (h, s) -> true;

    static {
        try {
            TrustManager[] tm = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            trustAll = ctx.getSocketFactory();
        } catch (Exception e) {
            trustAll = null;
        }
    }

    static class AuthException extends Exception {
        AuthException(String m) {
            super(m);
        }
    }

    interface Cmd {
        void run() throws IOException, AuthException;
    }

    static class Resp {
        int code;
        String body;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void configure(String ip, String token, String mac) {
        this.ip = ip;
        this.token = token;
        this.mac = mac;
    }

    public boolean isPaired() {
        return ip != null && !ip.isEmpty() && token != null && !token.isEmpty();
    }

    public String getIp() {
        return ip;
    }

    /** True while a command is queued, running or the stick is being woken. */
    public boolean busy() {
        return pending.get() > 0 || waking;
    }

    // ---------------------------------------------------------------- commands

    /** Single press with no body: home, back, menu, sleep, epg, dpad_* (one step). */
    public void action(String action) {
        submit(() -> post("/v1/FireTV?action=" + action, null));
    }

    /** keyDown / keyUp pair, the way the official app sends Select. */
    public void keyed(String action) {
        submit(() -> {
            post("/v1/FireTV?action=" + action, "{\"keyActionType\":\"keyDown\"}");
            sleep(150);
            try {
                post("/v1/FireTV?action=" + action, "{\"keyActionType\":\"keyUp\"}");
            } catch (IOException e) {
                post("/v1/FireTV?action=" + action, "{\"keyActionType\":\"keyUp\"}");
            }
        });
    }

    public void playPause() {
        submit(() -> post("/v1/media?action=play", null));
    }

    public void scan(boolean forward) {
        String body = "{\"direction\":\"" + (forward ? "forward" : "back")
                + "\",\"durationInSeconds\":\"10\",\"speed\":\"1\"}";
        submit(() -> post("/v1/media?action=scan", body));
    }

    private volatile long scanDownAt;

    /**
     * Rewind / fast forward as a real remote button: keyDown on press, keyUp on release.
     * A tap skips (usually 10 s in Netflix, Prime, iPlayer), holding scrubs.
     */
    public void scanKey(boolean forward, boolean down) {
        String body = "{\"direction\":\"" + (forward ? "forward" : "back")
                + "\",\"keyAction\":{\"keyActionType\":\"" + (down ? "keyDown" : "keyUp") + "\"}}";
        Cmd cmd = () -> {
            if (down) {
                scanDownAt = System.currentTimeMillis();
            } else {
                long held = System.currentTimeMillis() - scanDownAt;
                if (held < 120) sleep(120 - held); // a real tap still has some length
            }
            post("/v1/media?action=scan", body);
        };
        if (down) {
            submit(cmd);
        } else {
            submitAlways(cmd); // never drop a key release
        }
    }

    public static final String YOUTUBE = "com.amazon.firetv.youtube";

    /**
     * Opens YouTube on the Fire TV, moves to its Search page and types the query.
     * There is no direct search command in the Fire TV API, so this drives the screen like a remote.
     */
    public void youtubeSearch(String query, Runnable onTyped) {
        submit(() -> {
            post("/v1/FireTV?action=home", null);  // leave any playing video first
            sleep(1200);
            post("/v1/FireTV/app/" + YOUTUBE, null);
            sleep(7000);                            // YouTube takes a few seconds to load
            post("/v1/FireTV?action=dpad_left", null); // open the side menu
            sleep(700);
            for (int i = 0; i < 6; i++) {           // Search is the top item
                post("/v1/FireTV?action=dpad_up", null);
                sleep(250);
            }
            post("/v1/FireTV?action=select", "{\"keyActionType\":\"keyDown\"}");
            sleep(150);
            post("/v1/FireTV?action=select", "{\"keyActionType\":\"keyUp\"}");
            sleep(1800);
            for (int i = 0; i < query.length(); ) {
                int cp = query.codePointAt(i);
                i += Character.charCount(cp);
                JSONObject o = new JSONObject();
                try {
                    o.put("text", new String(Character.toChars(cp)));
                } catch (Exception ignored) {
                }
                post("/v1/FireTV/text", o.toString());
            }
            if (onTyped != null) main.post(onTyped);
        });
    }

    /** Known SmartTube package names, newest first. */
    public static final String[] SMARTTUBE = {
            "org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid", "app.smarttube",
            "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv"
    };

    /**
     * SmartTube: bring it to the front (it resumes where it was, no Home press), jump to Search
     * at the top of its side menu, type the words, then press Enter to run the search.
     */
    public void smartTubeSearch(String[] packages, String query, Callback<String> done) {
        submit(() -> {
            // Ask the Fire TV which SmartTube it actually has, so we never open the wrong app
            List<String> order = new ArrayList<>();
            try {
                Resp list = request("GET", base(ip) + "/v1/FireTV/appsV2", null, true, 1500, 5000);
                JSONArray arr = new JSONArray(list.body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("appId", "");
                    String name = o.optString("name", "").toLowerCase();
                    boolean known = false;
                    for (String k : SMARTTUBE) if (k.equals(id)) known = true;
                    if (!id.isEmpty() && (known || name.contains("smarttube") || name.contains("smart tube"))) {
                        order.add(id);
                    }
                }
            } catch (Exception ignored) {
            }
            for (String p : packages) if (!order.contains(p)) order.add(p);
            String used = null;
            for (String pkg : order) {
                Resp r = request("POST", base(ip) + "/v1/FireTV/app/" + pkg, "", true, 1500, 4000);
                if (r.code == 401 || r.code == 403) throw new AuthException(r.body);
                String desc = "";
                try {
                    desc = new JSONObject(r.body).optString("description", "");
                } catch (Exception ignored) {
                }
                if (r.code >= 200 && r.code < 300 && !desc.toLowerCase().contains("not")) {
                    used = pkg;
                    break;
                }
            }
            if (used == null) {
                main.post(() -> done.done(null, "SmartTube isn't installed on this Fire TV (or uses a name I don't know)"));
                return;
            }
            sleep(3000);                               // quick if already open, allows a cold start
            post("/v1/FireTV?action=dpad_left", null); // into the side menu
            sleep(600);
            for (int i = 0; i < 10; i++) {             // Search sits at the top
                post("/v1/FireTV?action=dpad_up", null);
                sleep(220);
            }
            post("/v1/FireTV?action=select", "{\"keyActionType\":\"keyDown\"}");
            sleep(150);
            post("/v1/FireTV?action=select", "{\"keyActionType\":\"keyUp\"}");
            sleep(1500);
            typeChars(query);
            sleep(500);
            typeChars("\n");                          // Enter runs the search in SmartTube
            final String fUsed = used;
            main.post(() -> done.done(fUsed, null));
        });
    }

    private void typeChars(String text) throws IOException, AuthException {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            JSONObject o = new JSONObject();
            try {
                o.put("text", new String(Character.toChars(cp)));
            } catch (Exception ignored) {
            }
            post("/v1/FireTV/text", o.toString());
        }
    }

    public void launchApp(String pkg) {
        submit(() -> post("/v1/FireTV/app/" + pkg, null));
    }

    public void typeText(String text, boolean thenSelect) {
        submit(() -> {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                i += Character.charCount(cp);
                JSONObject o = new JSONObject();
                try {
                    o.put("text", ch);
                } catch (Exception ignored) {
                }
                post("/v1/FireTV/text", o.toString());
            }
            if (thenSelect) {
                post("/v1/FireTV?action=select", "{\"keyActionType\":\"keyDown\"}");
                sleep(150);
                post("/v1/FireTV?action=select", "{\"keyActionType\":\"keyUp\"}");
            }
        });
    }

    private void submit(Cmd cmd) {
        if (!isPaired()) {
            setState(ST_UNPAIRED, "Pair your Fire TV first: tap the name at the top");
            return;
        }
        if (waking) return; // a press during a wake would just pile up
        if (pending.get() >= 6) return;
        pending.incrementAndGet();
        exec.execute(() -> {
            try {
                runWithWake(cmd);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private void submitAlways(Cmd cmd) {
        if (!isPaired()) return;
        pending.incrementAndGet();
        exec.execute(() -> {
            try {
                cmd.run();
            } catch (Exception ignored) {
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private void runWithWake(Cmd cmd) {
        try {
            cmd.run();
            setState(ST_OK, null);
            return;
        } catch (AuthException e) {
            setState(ST_UNPAIRED, "Fire TV didn't accept this phone. Pair again from the menu.");
            return;
        } catch (IOException first) {
            // refused or silent: the stick is idle. Wake it, then try once more.
        }
        waking = true;
        setState(ST_WAKING, "Waking Fire TV…");
        boolean up;
        try {
            up = wake(25000);
        } finally {
            waking = false;
        }
        if (!up) {
            setState(ST_ERROR, "Can't reach the Fire TV. Is it on and on the same Wi-Fi?");
            return;
        }
        try {
            cmd.run();
            setState(ST_OK, null);
        } catch (AuthException e) {
            setState(ST_UNPAIRED, "Fire TV didn't accept this phone. Pair again from the menu.");
        } catch (IOException e) {
            setState(ST_ERROR, "Fire TV isn't answering");
        }
    }

    /** Fire-and-forget DIAL wake plus a status check, used when the app comes to the front. */
    public void warmUp() {
        if (!isPaired()) return;
        side.execute(() -> {
            dialWake(ip, 1500);
            try {
                Resp r = request("GET", base(ip) + "/v1/FireTV/status", null, true, 1500, 2000);
                if (r.code == 401 || r.code == 403) {
                    setState(ST_UNPAIRED, "Fire TV didn't accept this phone. Pair again from the menu.");
                } else {
                    setState(ST_OK, null);
                }
            } catch (IOException e) {
                setState(ST_UNKNOWN, null);
            }
        });
    }

    // ---------------------------------------------------------------- wake

    private boolean wake(long budgetMs) {
        long end = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < end) {
            sendMagicPacket();
            dialWake(ip, 2000);
            for (int i = 0; i < 4 && System.currentTimeMillis() < end; i++) {
                try {
                    request("GET", base(ip) + "/v1/FireTV/status", null, true, 1000, 1200);
                    return true; // any HTTP answer means the API is up
                } catch (IOException e) {
                    sleep(500);
                }
            }
        }
        return false;
    }

    private static void dialWake(String ip, int timeout) {
        try {
            request("POST", "http://" + ip + ":8009/apps/FireTVRemote", "", false, timeout, timeout, null);
        } catch (IOException ignored) {
        }
    }

    private void sendMagicPacket() {
        String m = mac;
        if (m == null || m.isEmpty()) return;
        try {
            String hex = m.replaceAll("[^0-9A-Fa-f]", "");
            if (hex.length() != 12) return;
            byte[] macBytes = new byte[6];
            for (int i = 0; i < 6; i++) {
                macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            byte[] pkt = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
            for (int i = 6; i < pkt.length; i += 6) System.arraycopy(macBytes, 0, pkt, i, 6);
            String subnetBcast = ip.substring(0, ip.lastIndexOf('.')) + ".255";
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (String target : new String[]{subnetBcast, "255.255.255.255"}) {
                    InetAddress a = InetAddress.getByName(target);
                    s.send(new DatagramPacket(pkt, pkt.length, a, 9));
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- pairing

    public void requestPin(String ip, String friendlyName, Callback<Boolean> cb) {
        side.execute(() -> {
            String err = null;
            boolean ok = false;
            for (int attempt = 0; attempt < 3 && !ok; attempt++) {
                dialWake(ip, 2500);
                try {
                    JSONObject o = new JSONObject();
                    o.put("friendlyName", friendlyName);
                    Resp r = request("POST", base(ip) + "/v1/FireTV/pin/display", o.toString(), false, 3000, 5000);
                    if (r.code >= 200 && r.code < 300) {
                        ok = true;
                    } else {
                        err = "Fire TV replied " + r.code + ": " + r.body;
                    }
                } catch (IOException e) {
                    err = "Couldn't reach " + ip + ". Make sure the Fire TV is on and on this Wi-Fi.";
                    sleep(1500);
                } catch (Exception e) {
                    err = e.toString();
                }
            }
            final boolean fOk = ok;
            final String fErr = ok ? null : err;
            main.post(() -> cb.done(fOk, fErr));
        });
    }

    public void verifyPin(String ip, String pin, Callback<String> cb) {
        side.execute(() -> {
            String token = null, err = null;
            for (int attempt = 0; attempt < 4 && token == null; attempt++) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("pin", pin);
                    Resp r = request("POST", base(ip) + "/v1/FireTV/pin/verify", o.toString(), false, 3000, 5000);
                    String desc = null;
                    try {
                        desc = new JSONObject(r.body).optString("description", null);
                    } catch (Exception ignored) {
                    }
                    if (r.code >= 200 && r.code < 300 && desc != null && !desc.isEmpty()
                            && !"OK".equalsIgnoreCase(desc)) {
                        token = desc;
                    } else if (r.code >= 200 && r.code < 300) {
                        sleep(1000); // "OK" means the token isn't ready yet
                    } else {
                        err = "Wrong PIN, or it expired. (" + r.code + ")";
                        break;
                    }
                } catch (IOException e) {
                    err = "Lost contact with the Fire TV while pairing.";
                    sleep(1000);
                } catch (Exception e) {
                    err = e.toString();
                }
            }
            final String t = token;
            final String e = token == null ? (err == null ? "Fire TV didn't return a token. Try again." : err) : null;
            main.post(() -> cb.done(t, e));
        });
    }

    // ---------------------------------------------------------------- apps

    public void loadApps(Callback<List<App>> cb) {
        if (!isPaired()) return;
        side.execute(() -> {
            List<App> list = new ArrayList<>();
            String err = null;
            try {
                Resp r = request("GET", base(ip) + "/v1/FireTV/appsV2", null, true, 2500, 6000);
                if (r.code != 200) r = request("GET", base(ip) + "/v1/FireTV/apps", null, true, 2500, 6000);
                JSONArray arr = new JSONArray(r.body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o.has("isInstalled") && !o.optBoolean("isInstalled", true)) continue;
                    App a = new App();
                    a.id = o.optString("appId", o.optString("packageName", ""));
                    a.name = o.optString("name", a.id);
                    a.icon = o.optString("tvIconArt", o.optString("iconArtSmallUri", null));
                    if (!a.id.isEmpty()) list.add(a);
                }
            } catch (Exception e) {
                err = e.toString();
            }
            final String fErr = list.isEmpty() ? (err == null ? "no apps" : err) : null;
            main.post(() -> cb.done(list, fErr));
        });
    }

    public void loadBitmap(String url, Callback<Bitmap> cb) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;
        side.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = open(url, 3000, 6000);
                if (c.getURL().getHost().equals(ip)) addAuth(c, true);
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inPreferredConfig = Bitmap.Config.RGB_565;
                    b = BitmapFactory.decodeStream(in, null, opt);
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            final Bitmap fb = b;
            if (fb != null) main.post(() -> cb.done(fb, null));
        });
    }

    // ---------------------------------------------------------------- http

    private void post(String path, String body) throws IOException, AuthException {
        Resp r = request("POST", base(ip) + path, body == null ? "" : body, true, 1500, 2500);
        if (r.code == 401 || r.code == 403) throw new AuthException(r.body);
        if (r.code >= 500) throw new IOException("HTTP " + r.code);
    }

    private static String base(String ip) {
        return "https://" + ip + ":8080";
    }

    private Resp request(String method, String url, String body, boolean auth, int connectMs, int readMs)
            throws IOException {
        return request(method, url, body, auth, connectMs, readMs, auth ? token : null);
    }

    private static HttpURLConnection open(String url, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && trustAll != null) {
            String host = c.getURL().getHost();
            if (isLocal(host)) {
                ((HttpsURLConnection) c).setSSLSocketFactory(trustAll);
                ((HttpsURLConnection) c).setHostnameVerifier(ANY_HOST);
            }
        }
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setUseCaches(false);
        return c;
    }

    private void addAuth(HttpURLConnection c, boolean withToken) {
        c.setRequestProperty("X-Api-Key", API_KEY);
        if (withToken && token != null) c.setRequestProperty("X-Client-Token", token);
    }

    private static Resp request(String method, String url, String body, boolean auth, int connectMs,
                                int readMs, String tok) throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs);
        try {
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            if (url.startsWith("https")) {
                c.setRequestProperty("X-Api-Key", API_KEY);
                if (auth && tok != null) c.setRequestProperty("X-Client-Token", tok);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            } else {
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            }
            if (body != null && !"GET".equals(method)) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : readAll(in);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static boolean isLocal(String host) {
        return host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")
                || host.startsWith("169.254.") || host.endsWith(".local");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void setState(int s, String msg) {
        if (s == lastState && msg == null) return;
        lastState = s;
        main.post(() -> {
            if (listener != null) listener.onStatus(s, msg);
        });
    }
}
