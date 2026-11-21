package com.dave.tvremote;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds Fire TVs on the Wi-Fi the same way Amazon's app does: an SSDP search for the DIAL service. */
public class Discovery {

    public static class Device {
        public String ip, name, mac, location, maker;
        public boolean amazon;
    }

    public static void scan(Context ctx, FireTvClient.Callback<List<Device>> cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Map<String, Device> byKey = new LinkedHashMap<>();
            WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = null;
            try {
                if (wifi != null) {
                    lock = wifi.createMulticastLock("tvremote-ssdp");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
                String msg = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: 239.255.255.250:1900\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 1\r\n"
                        + "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n";
                byte[] out = msg.getBytes(StandardCharsets.US_ASCII);
                try (DatagramSocket s = new DatagramSocket()) {
                    s.setSoTimeout(400);
                    InetAddress group = InetAddress.getByName("239.255.255.250");
                    long end = System.currentTimeMillis() + 3500;
                    int sent = 0;
                    byte[] buf = new byte[2048];
                    while (System.currentTimeMillis() < end) {
                        if (sent < 4) {
                            s.send(new DatagramPacket(out, out.length, group, 1900));
                            sent++;
                        }
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            s.receive(p);
                            String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                            String ip = p.getAddress().getHostAddress();
                            String usn = header(reply, "USN");
                            String key = usn != null && usn.contains("::") ? usn.substring(0, usn.indexOf("::")) : ip;
                            if (byKey.containsKey(key)) continue;
                            Device d = new Device();
                            d.ip = ip;
                            d.location = header(reply, "LOCATION");
                            String wake = header(reply, "WAKEUP");
                            if (wake != null) {
                                Matcher m = Pattern.compile("MAC=([0-9A-Fa-f:\\-]{12,17})").matcher(wake);
                                if (m.find()) d.mac = m.group(1);
                            }
                            byKey.put(key, d);
                        } catch (SocketTimeoutException ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (Exception ignored) {
                    }
                }
            }

            List<Device> list = new ArrayList<>();
            for (Device d : byKey.values()) {
                readDescription(d);
                if (d.name == null) d.name = d.ip;
                list.add(d);
            }
            // Amazon devices first
            List<Device> sorted = new ArrayList<>();
            for (Device d : list) if (d.amazon) sorted.add(d);
            for (Device d : list) if (!d.amazon) sorted.add(d);
            main.post(() -> cb.done(sorted, null));
        }, "ssdp-scan").start();
    }

    private static void readDescription(Device d) {
        if (d.location == null) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(d.location).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0 && bo.size() < 65536) bo.write(b, 0, n);
                String xml = bo.toString("UTF-8");
                d.name = unescape(tag(xml, "friendlyName"));
                d.maker = unescape(tag(xml, "manufacturer"));
                String model = tag(xml, "modelName");
                d.amazon = (d.maker != null && d.maker.toLowerCase().contains("amazon"))
                        || (model != null && model.startsWith("AFT"));
            }
            c.disconnect();
        } catch (Exception ignored) {
        }
    }

    private static String header(String reply, String name) {
        for (String line : reply.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) {
                return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    private static String tag(String xml, String t) {
        Matcher m = Pattern.compile("<" + t + ">(.*?)</" + t + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
