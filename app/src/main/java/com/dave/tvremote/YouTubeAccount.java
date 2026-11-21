package com.dave.tvremote;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the signed-in user's YouTube subscriptions with Google's official OAuth "device" sign-in
 * (enter a code at google.com/device) and the YouTube Data API v3. Needs a client ID of type
 * "TVs and Limited Input devices" from the user's own Google Cloud project.
 */
public class YouTubeAccount {

    public static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";

    public static class DeviceCode {
        public String deviceCode, userCode, url;
        public int interval = 5, expiresIn = 1800;
    }

    public static class Sub {
        public String id, title, thumb;
    }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public YouTubeAccount(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public boolean hasClient() {
        return !prefs.getString("ytClientId", "").isEmpty() && !prefs.getString("ytClientSecret", "").isEmpty();
    }

    public void setClient(String id, String secret) {
        prefs.edit().putString("ytClientId", id.trim()).putString("ytClientSecret", secret.trim()).apply();
    }

    public boolean signedIn() {
        return !prefs.getString("ytRefresh", "").isEmpty();
    }

    public void signOut() {
        prefs.edit().remove("ytRefresh").remove("ytAccess").remove("ytAccessExp").apply();
    }

    public void forgetClient() {
        signOut();
        prefs.edit().remove("ytClientId").remove("ytClientSecret").apply();
    }

    /** Step 1: ask Google for a code the user types in at google.com/device. */
    public void startSignIn(FireTvClient.Callback<DeviceCode> cb) {
        exec.execute(() -> {
            try {
                JSONObject o = postForm("https://oauth2.googleapis.com/device/code",
                        "client_id=" + enc(prefs.getString("ytClientId", "")) + "&scope=" + enc(SCOPE));
                if (o.has("error")) {
                    String err = describe(o);
                    main.post(() -> cb.done(null, err));
                    return;
                }
                DeviceCode dc = new DeviceCode();
                dc.deviceCode = o.getString("device_code");
                dc.userCode = o.getString("user_code");
                dc.url = o.optString("verification_url", o.optString("verification_uri", "https://www.google.com/device"));
                dc.interval = Math.max(2, o.optInt("interval", 5));
                dc.expiresIn = o.optInt("expires_in", 1800);
                main.post(() -> cb.done(dc, null));
            } catch (Exception e) {
                main.post(() -> cb.done(null, "Couldn't reach Google: " + e.getMessage()));
            }
        });
    }

    /** Step 2: wait while the user approves on the Google page. */
    public void waitForApproval(DeviceCode dc, AtomicBoolean cancelled, FireTvClient.Callback<Boolean> cb) {
        new Thread(() -> {
            long end = System.currentTimeMillis() + dc.expiresIn * 1000L;
            int interval = dc.interval;
            while (!cancelled.get() && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(interval * 1000L);
                } catch (InterruptedException ignored) {
                }
                if (cancelled.get()) return;
                try {
                    JSONObject o = postForm("https://oauth2.googleapis.com/token",
                            "client_id=" + enc(prefs.getString("ytClientId", ""))
                                    + "&client_secret=" + enc(prefs.getString("ytClientSecret", ""))
                                    + "&device_code=" + enc(dc.deviceCode)
                                    + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
                    String error = o.optString("error", "");
                    if (error.isEmpty() && o.has("access_token")) {
                        saveTokens(o);
                        main.post(() -> cb.done(true, null));
                        return;
                    }
                    if ("authorization_pending".equals(error)) continue;
                    if ("slow_down".equals(error)) {
                        interval += 5;
                        continue;
                    }
                    String err = describe(o);
                    main.post(() -> cb.done(false, err));
                    return;
                } catch (Exception e) {
                    // network blip: keep waiting
                }
            }
            if (!cancelled.get()) main.post(() -> cb.done(false, "The sign-in code expired. Try again."));
        }, "yt-signin").start();
    }

    /** All subscriptions of the signed-in account. */
    public void fetchSubscriptions(FireTvClient.Callback<List<Sub>> cb) {
        exec.execute(() -> {
            List<Sub> out = new ArrayList<>();
            try {
                String token = accessToken();
                String page = "";
                for (int guard = 0; guard < 40; guard++) {
                    String url = "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true"
                            + "&maxResults=50&order=alphabetical" + (page.isEmpty() ? "" : "&pageToken=" + enc(page));
                    JSONObject o = get(url, token);
                    if (o.has("error")) {
                        String err = describe(o);
                        main.post(() -> cb.done(null, err));
                        return;
                    }
                    JSONArray items = o.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject sn = items.getJSONObject(i).optJSONObject("snippet");
                            if (sn == null) continue;
                            Sub s = new Sub();
                            s.title = sn.optString("title", "");
                            JSONObject rid = sn.optJSONObject("resourceId");
                            s.id = rid == null ? "" : rid.optString("channelId", "");
                            JSONObject th = sn.optJSONObject("thumbnails");
                            if (th != null) {
                                JSONObject pick = th.optJSONObject("medium");
                                if (pick == null) pick = th.optJSONObject("default");
                                if (pick != null) s.thumb = pick.optString("url", null);
                            }
                            if (!s.title.isEmpty()) out.add(s);
                        }
                    }
                    page = o.optString("nextPageToken", "");
                    if (page.isEmpty()) break;
                }
                main.post(() -> cb.done(out, null));
            } catch (Exception e) {
                String m = e.getMessage();
                main.post(() -> cb.done(null, m == null ? e.toString() : m));
            }
        });
    }

    // ------------------------------------------------------------------ tokens

    private void saveTokens(JSONObject o) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString("ytAccess", o.optString("access_token", ""));
        ed.putLong("ytAccessExp", System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000L);
        String refresh = o.optString("refresh_token", "");
        if (!refresh.isEmpty()) ed.putString("ytRefresh", refresh);
        ed.apply();
    }

    private String accessToken() throws Exception {
        String access = prefs.getString("ytAccess", "");
        if (!access.isEmpty() && System.currentTimeMillis() < prefs.getLong("ytAccessExp", 0)) return access;
        String refresh = prefs.getString("ytRefresh", "");
        if (refresh.isEmpty()) throw new IllegalStateException("Not signed in to YouTube");
        JSONObject o = postForm("https://oauth2.googleapis.com/token",
                "client_id=" + enc(prefs.getString("ytClientId", ""))
                        + "&client_secret=" + enc(prefs.getString("ytClientSecret", ""))
                        + "&refresh_token=" + enc(refresh)
                        + "&grant_type=refresh_token");
        if (o.has("error")) {
            if ("invalid_grant".equals(o.optString("error"))) signOut();
            throw new IllegalStateException("YouTube sign-in has expired. Sign in again. (" + describe(o) + ")");
        }
        saveTokens(o);
        return prefs.getString("ytAccess", "");
    }

    // ------------------------------------------------------------------ http

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String describe(JSONObject o) {
        Object err = o.opt("error");
        if (err instanceof JSONObject) {
            JSONObject e = (JSONObject) err;
            return e.optString("message", e.toString());
        }
        String d = o.optString("error_description", "");
        return d.isEmpty() ? String.valueOf(err) : err + ": " + d;
    }

    private static JSONObject postForm(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] b = form.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(b);
        }
        return readJson(c);
    }

    private static JSONObject get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        return readJson(c);
    }

    private static JSONObject readJson(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            body = bo.toString("UTF-8");
        }
        c.disconnect();
        if (body.trim().startsWith("{")) return new JSONObject(body);
        JSONObject o = new JSONObject();
        o.put("error", "HTTP " + code);
        return o;
    }
}
