package com.dave.tvremote;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

/**
 * Minimal ADB-over-Wi-Fi client (port 5555) so the app can hand the Fire TV a link,
 * e.g. "open this channel in SmartTube". Needs "ADB debugging" on in the Fire TV's
 * Developer options, and "Always allow" ticked on the TV the first time.
 */
public class AdbClient {

    public static class NotAllowedException extends IOException {
        NotAllowedException(String m) {
            super(m);
        }
    }

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private KeyPair keys;

    public AdbClient(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * Runs a shell command on the Fire TV. onNeedApproval fires if the TV is showing
     * "Allow USB debugging?" for this phone (first time only).
     */
    public void shell(String ip, String command, Runnable onNeedApproval, FireTvClient.Callback<String> cb) {
        exec.execute(() -> {
            try {
                String out = runShell(ip, command, onNeedApproval);
                main.post(() -> cb.done(out, null));
            } catch (ConnectException e) {
                main.post(() -> cb.done(null, "The Fire TV refused the connection. ADB debugging looks switched off "
                        + "(Settings > My Fire TV > Developer options > ADB debugging)."));
            } catch (NotAllowedException e) {
                main.post(() -> cb.done(null, e.getMessage()));
            } catch (SocketTimeoutException e) {
                main.post(() -> cb.done(null, "The Fire TV didn't answer in time. If it asked to allow debugging, "
                        + "tick Always allow and press OK, then try again."));
            } catch (Exception e) {
                main.post(() -> cb.done(null, "ADB error: " + e.getMessage()));
            }
        });
    }

    private String runShell(String ip, String command, Runnable onNeedApproval) throws Exception {
        ensureKeys();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 5555), 3000);
            s.setSoTimeout(8000);
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            send(out, A_CNXN, 0x01000000, 256 * 1024, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSignature = false, sentKey = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == A_CNXN) break;
                if (m.cmd != A_AUTH || m.a0 != AUTH_TOKEN) continue;
                if (!sentSignature) {
                    send(out, A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    sentSignature = true;
                } else if (!sentKey) {
                    // TV doesn't know this phone yet: offer our key; the TV asks the user
                    send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes());
                    sentKey = true;
                    if (onNeedApproval != null) main.post(onNeedApproval);
                    s.setSoTimeout(90000);
                } else {
                    throw new NotAllowedException("The Fire TV didn't allow this phone. Try again and press OK on the TV prompt.");
                }
            }
            s.setSoTimeout(8000);

            int localId = 1;
            send(out, A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            while (true) {
                Msg m;
                try {
                    m = read(in);
                } catch (IOException eof) {
                    break;
                }
                if (m.cmd == A_WRTE) {
                    result.append(new String(m.data, StandardCharsets.UTF_8));
                    send(out, A_OKAY, localId, m.a0, new byte[0]);
                } else if (m.cmd == A_CLSE) {
                    send(out, A_CLSE, localId, m.a0, new byte[0]);
                    break;
                }
            }
            return result.toString();
        }
    }

    // ------------------------------------------------------------------ protocol

    private static class Msg {
        int cmd, a0, a1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += (b & 0xFF);
        ByteBuffer bb = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd);
        bb.put(data);
        out.write(bb.array());
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt();
        m.a0 = bb.getInt();
        m.a1 = bb.getInt();
        int len = bb.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad ADB packet");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------------ keys

    private synchronized void ensureKeys() throws Exception {
        if (keys != null) return;
        String priv = prefs.getString("adbPriv", null), pub = prefs.getString("adbPub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            try {
                keys = new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))));
                return;
            } catch (Exception ignored) {
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        prefs.edit()
                .putString("adbPriv", Base64.encodeToString(keys.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("adbPub", Base64.encodeToString(keys.getPublic().getEncoded(), Base64.NO_WRAP))
                .apply();
    }

    /** ADB signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    private byte[] sign(byte[] token) throws Exception {
        PrivateKey pk = keys.getPrivate();
        try {
            Signature sg = Signature.getInstance("NONEwithRSA");
            sg.initSign(pk);
            sg.update(SHA1_DIGEST_INFO);
            sg.update(token);
            return sg.sign();
        } catch (Exception e) {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, pk);
            c.update(SHA1_DIGEST_INFO);
            return c.doFinal(token);
        }
    }

    /** Public key in Android's adbkey.pub format: base64(struct RSAPublicKey) + " user@host\0". */
    private byte[] publicKeyBytes() {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pub.getModulus();
        BigInteger r = BigInteger.ZERO.setBit(words * 32);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv.negate().intValue());
        BigInteger nn = n;
        for (int i = 0; i < words; i++) {
            BigInteger[] qr = nn.divideAndRemainder(r32);
            nn = qr[0];
            bb.putInt(qr[1].intValue());
        }
        BigInteger rrr = rr;
        for (int i = 0; i < words; i++) {
            BigInteger[] qr = rrr.divideAndRemainder(r32);
            rrr = qr[0];
            bb.putInt(qr[1].intValue());
        }
        bb.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " tvremote@phone\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
