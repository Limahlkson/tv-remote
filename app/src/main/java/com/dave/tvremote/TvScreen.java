package com.dave.tvremote;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what's on the Fire TV screen (Android's UI dump over ADB) and steers the focus with
 * D-pad keys. Used to reach SmartTube's "Chat/Comments" player button, which has no shortcut key.
 */
public final class TvScreen {

    private TvScreen() {
    }

    static class Node {
        String desc = "", text = "";
        boolean focused, focusable;
        int x1, y1, x2, y2;

        int cx() {
            return (x1 + x2) / 2;
        }

        int cy() {
            return (y1 + y2) / 2;
        }

        int w() {
            return x2 - x1;
        }

        int h() {
            return y2 - y1;
        }

        boolean contains(int x, int y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }

        long area() {
            return (long) w() * h();
        }
    }

    private static final int KEY_UP = 19, KEY_DOWN = 20, KEY_LEFT = 21, KEY_RIGHT = 22, KEY_OK = 23,
            KEY_PAUSE = 127;

    /** Opens the comments of the SmartTube video that's playing. Returns null on success, else why it failed. */
    public static String openComments(AdbClient adb, String ip) {
        try {
            String focus = adb.exec(ip, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", 8000);
            if (!focus.contains("PlaybackActivity")) return "play a video in SmartTube first";

            boolean controlsShown = false, paused = false;
            for (int round = 0; round < 8; round++) {
                String xml = adb.exec(ip, "uiautomator dump /sdcard/tvremote_ui.xml >/dev/null 2>&1; "
                        + "cat /sdcard/tvremote_ui.xml; rm -f /sdcard/tvremote_ui.xml", 25000);
                if (!xml.contains("<hierarchy")) {
                    // a moving progress bar can stop Android reading the screen; pausing lets it settle
                    if (!paused) {
                        adb.exec(ip, "input keyevent " + KEY_PAUSE, 6000);
                        paused = true;
                        Thread.sleep(500);
                        continue;
                    }
                    return "couldn't read the TV screen";
                }
                List<Node> nodes = parse(xml);
                Node target = findComments(nodes);
                if (target == null) {
                    if (!controlsShown) {
                        adb.exec(ip, "input keyevent " + KEY_DOWN, 6000); // brings up the player controls
                        controlsShown = true;
                        Thread.sleep(700);
                        continue;
                    }
                    return "no Chat/Comments button in the player (switch it on in SmartTube's player buttons)";
                }
                Node focused = null;
                for (Node n : nodes) {
                    if (n.focused && (focused == null || n.area() < focused.area())) focused = n;
                }
                if (focused == null) {
                    adb.exec(ip, "input keyevent " + KEY_DOWN, 6000);
                    Thread.sleep(500);
                    continue;
                }
                if (target.contains(focused.cx(), focused.cy()) || focused.contains(target.cx(), target.cy())) {
                    adb.exec(ip, "input keyevent " + KEY_OK, 6000);
                    return null;
                }
                int rowTol = Math.max(target.h(), focused.h()) / 2;
                if (Math.abs(target.cy() - focused.cy()) > rowTol) {
                    adb.exec(ip, "input keyevent " + (target.cy() > focused.cy() ? KEY_DOWN : KEY_UP), 6000);
                    Thread.sleep(450);
                    continue;
                }
                // same row: count the buttons in between, then move that many steps at once
                int lo = Math.min(focused.cx(), target.cx()), hi = Math.max(focused.cx(), target.cx());
                Set<Integer> between = new HashSet<>();
                for (Node n : nodes) {
                    if (n == target || n.desc.isEmpty() || !n.focusable) continue;
                    if (Math.abs(n.cy() - target.cy()) > rowTol) continue;
                    if (n.w() > target.w() * 2) continue;
                    if (n.contains(focused.cx(), focused.cy())) continue;
                    if (n.cx() > lo && n.cx() < hi) between.add(n.cx() / 8);
                }
                int steps = between.size() + 1;
                int key = target.cx() > focused.cx() ? KEY_RIGHT : KEY_LEFT;
                StringBuilder keys = new StringBuilder("input keyevent");
                for (int i = 0; i < steps; i++) keys.append(' ').append(key);
                adb.exec(ip, keys.toString(), 8000);
                Thread.sleep(450);
            }
            return "couldn't reach the comments button";
        } catch (Exception e) {
            return "lost the connection to the Fire TV (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static Node findComments(List<Node> nodes) {
        Node best = null;
        for (Node n : nodes) {
            String label = (n.desc + " " + n.text).toLowerCase();
            if (!label.contains("comment")) continue;
            if (best == null || (n.focusable && !best.focusable) || (n.focusable == best.focusable && n.area() < best.area())) {
                best = n;
            }
        }
        return best;
    }

    private static final Pattern NODE = Pattern.compile("<node ([^>]*?)/?>");
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    static List<Node> parse(String xml) {
        List<Node> out = new ArrayList<>();
        Matcher m = NODE.matcher(xml);
        while (m.find()) {
            Node n = new Node();
            Matcher a = ATTR.matcher(m.group(1));
            while (a.find()) {
                String k = a.group(1), v = a.group(2);
                switch (k) {
                    case "content-desc":
                        n.desc = unescape(v);
                        break;
                    case "text":
                        n.text = unescape(v);
                        break;
                    case "focused":
                        n.focused = "true".equals(v);
                        break;
                    case "focusable":
                        n.focusable = "true".equals(v);
                        break;
                    case "bounds":
                        Matcher b = BOUNDS.matcher(v);
                        if (b.find()) {
                            n.x1 = Integer.parseInt(b.group(1));
                            n.y1 = Integer.parseInt(b.group(2));
                            n.x2 = Integer.parseInt(b.group(3));
                            n.y2 = Integer.parseInt(b.group(4));
                        }
                        break;
                    default:
                        break;
                }
            }
            out.add(n);
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }
}
