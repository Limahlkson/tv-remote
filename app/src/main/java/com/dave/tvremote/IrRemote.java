package com.dave.tvremote;

import android.content.Context;
import android.hardware.ConsumerIrManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends Hisense TV codes through the phone's IR blaster (NEC protocol, 38 kHz).
 * Codes come from the Flipper IRDB Hisense captures; two code sets cover nearly all Hisense TVs.
 */
public class IrRemote {

    public static final int POWER = 0, INPUT = 1, MUTE = 2, VOL_UP = 3, VOL_DOWN = 4,
            CH_UP = 5, CH_DOWN = 6, UP = 7, DOWN = 8, LEFT = 9, RIGHT = 10, OK = 11,
            BACK = 12, EXIT = 13;

    public static final String[] SET_NAMES = {
            "Hisense set A (most Hisense / VIDAA TVs)",
            "Hisense set B (Hisense Android / Google TV)"
    };

    // First two bytes of each NEC frame (address, address-high / inverted address)
    private static final int[][] ADDR = {
            {0x00, 0xBF},
            {0x04, 0xFB}
    };

    // Command byte per key, in the key order above
    private static final int[][] CMD = {
            // POWER INPUT MUTE VOL+  VOL-  CH+   CH-   UP    DOWN  LEFT  RIGHT OK    BACK  EXIT
            {0x0D, 0x12, 0x0E, 0x44, 0x43, 0x4A, 0x4B, 0x16, 0x17, 0x19, 0x18, 0x15, 0x48, 0x5C},
            {0x08, 0x0B, 0x09, 0x02, 0x03, 0x00, 0x01, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x04, 0x4D}
    };

    private static final int CARRIER = 38000;

    private final ConsumerIrManager ir;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile int codeSet = 0;

    public IrRemote(Context ctx) {
        ConsumerIrManager m = null;
        try {
            m = (ConsumerIrManager) ctx.getSystemService(Context.CONSUMER_IR_SERVICE);
        } catch (Exception ignored) {
        }
        ir = m;
    }

    public boolean available() {
        try {
            return ir != null && ir.hasIrEmitter();
        } catch (Exception e) {
            return false;
        }
    }

    public void setCodeSet(int set) {
        codeSet = (set >= 0 && set < CMD.length) ? set : 0;
    }

    public int getCodeSet() {
        return codeSet;
    }

    /** True while a frame is queued or transmitting (used to skip hold-repeats instead of queueing). */
    public boolean busy() {
        return pending.get() > 0;
    }

    public boolean send(int key) {
        return send(key, codeSet);
    }

    public boolean send(int key, int set) {
        if (!available()) return false;
        if (pending.get() > 3) return true; // drop runaway repeats
        final int[] addr = ADDR[set];
        final int cmd = CMD[set][key];
        final int[] pattern = nec(addr[0], addr[1], cmd, (~cmd) & 0xFF);
        pending.incrementAndGet();
        exec.execute(() -> {
            try {
                ir.transmit(CARRIER, pattern);
                Thread.sleep(45); // gap between frames so the TV sees separate presses
            } catch (Exception ignored) {
            } finally {
                pending.decrementAndGet();
            }
        });
        return true;
    }

    /** NEC frame in microseconds: 9ms lead, 4.5ms space, 32 bits LSB first, stop pulse. */
    static int[] nec(int b0, int b1, int b2, int b3) {
        int[] p = new int[2 + 64 + 1];
        int i = 0;
        p[i++] = 9000;
        p[i++] = 4500;
        int[] bytes = {b0, b1, b2, b3};
        for (int b : bytes) {
            for (int bit = 0; bit < 8; bit++) {
                p[i++] = 562;
                p[i++] = ((b >> bit) & 1) == 1 ? 1687 : 562;
            }
        }
        p[i] = 562;
        return p;
    }
}
