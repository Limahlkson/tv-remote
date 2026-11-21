package com.dave.tvremote;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Offline speech-to-text inside the app (Vosk, small English model bundled in the APK).
 * Doesn't use the phone's speech service at all, so vivo / Google settings can't break it.
 * Hold to talk: start() opens the mic, stop() returns the words.
 */
public class VoiceEngine {

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);
    }

    public static final int LOADING = 0, READY = 1, FAILED = 2;

    private static final int RATE = 16000;
    private static final long MAX_MS = 20000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Model model;
    private volatile int state = LOADING;
    private volatile String failReason;
    private volatile boolean running, cancelled;
    private Thread worker;

    public int state() {
        return state;
    }

    public String failReason() {
        return failReason;
    }

    public boolean isRecording() {
        return running;
    }

    /** Unpack the bundled model (first run only) and load it, in the background. */
    public void prepare(Context ctx, int version) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                try {
                    LibVosk.setLogLevel(LogLevel.WARNINGS);
                } catch (Throwable ignored) {
                }
                File dir = unpackModel(app, version);
                model = new Model(dir.getAbsolutePath());
                state = READY;
            } catch (Throwable t) {
                failReason = t.getClass().getSimpleName() + ": " + t.getMessage();
                state = FAILED;
            }
        }, "voice-load").start();
    }

    /** Starts listening. Returns false if the engine or mic isn't available (listener told why). */
    public boolean start(Listener l) {
        if (state != READY || model == null) {
            l.onError(state == LOADING ? "Voice is still getting ready, try again in a few seconds"
                    : "Voice couldn't load: " + failReason);
            return false;
        }
        if (running) return false;
        cancelled = false;
        running = true;
        worker = new Thread(() -> record(l), "voice-rec");
        worker.start();
        return true;
    }

    /** Stop listening; the final words arrive via onFinal shortly after. */
    public void stop() {
        running = false;
    }

    /** Stop listening and throw the audio away (a quick tap, not speech). */
    public void cancel() {
        cancelled = true;
        running = false;
    }

    private void record(Listener l) {
        AudioRecord rec = null;
        Recognizer r = null;
        StringBuilder done = new StringBuilder();
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("microphone busy or blocked");
            }
            r = new Recognizer(model, (float) RATE);
            rec.startRecording();
            short[] buf = new short[RATE / 10]; // 100 ms chunks
            long start = System.currentTimeMillis();
            String lastShown = "";
            while (running && System.currentTimeMillis() - start < MAX_MS) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (r.acceptWaveForm(buf, n)) {
                    append(done, field(r.getResult(), "text"));
                } else {
                    String shown = join(done.toString(), field(r.getPartialResult(), "partial"));
                    if (!cancelled && !shown.equals(lastShown) && !shown.isEmpty()) {
                        lastShown = shown;
                        main.post(() -> l.onPartial(shown));
                    }
                }
            }
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            if (cancelled) return;
            append(done, field(r.getFinalResult(), "text"));
            String text = done.toString().trim();
            main.post(() -> l.onFinal(text));
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            if (!cancelled) main.post(() -> l.onError("Mic error: " + msg));
        } finally {
            running = false;
            if (rec != null) {
                try {
                    rec.release();
                } catch (Exception ignored) {
                }
            }
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(s.trim());
    }

    private static String join(String a, String b) {
        a = a == null ? "" : a.trim();
        b = b == null ? "" : b.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }

    private static String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ model files

    /** Copies the model folder out of the APK's assets once, then returns the folder Vosk should load. */
    private static File unpackModel(Context ctx, int version) throws Exception {
        File root = new File(ctx.getFilesDir(), "vosk");
        File marker = new File(root, ".ready-" + version);
        if (!marker.exists()) {
            deleteRecursive(root);
            root.mkdirs();
            AssetManager am = ctx.getAssets();
            String[] top = am.list("");
            boolean copied = false;
            if (top != null) {
                for (String name : top) {
                    String lower = name.toLowerCase();
                    if (lower.contains("model") || lower.contains("vosk")) {
                        String[] kids = am.list(name);
                        if (kids != null && kids.length > 0) {
                            copyAssetDir(am, name, new File(root, name));
                            copied = true;
                        }
                    }
                }
            }
            if (!copied) throw new IllegalStateException("speech model missing from the app");
            marker.createNewFile();
        }
        File dir = findModelDir(root, 0);
        if (dir == null) throw new IllegalStateException("speech model folder not found");
        return dir;
    }

    /** The model folder is the one that contains "am" or "conf". */
    private static File findModelDir(File f, int depth) {
        if (f == null || !f.isDirectory() || depth > 4) return null;
        if (new File(f, "am").isDirectory() || new File(f, "conf").isDirectory()) return f;
        File[] kids = f.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            File found = findModelDir(k, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void copyAssetDir(AssetManager am, String path, File dest) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            dest.getParentFile().mkdirs();
            InputStream in;
            try {
                in = am.open(path);
            } catch (java.io.FileNotFoundException emptyDir) {
                dest.mkdirs(); // an empty folder, not a file
                return;
            }
            try (InputStream i = in; OutputStream out = new FileOutputStream(dest)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = i.read(b)) > 0) out.write(b, 0, n);
            }
            return;
        }
        dest.mkdirs();
        for (String k : kids) copyAssetDir(am, path + "/" + k, new File(dest, k));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
