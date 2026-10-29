package com.aboezzat.share;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.MulticastSocket;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class CastingManager {
    public interface Callback { void onResult(boolean success, String message); }
    public interface DiscoveryCallback { void onDevices(List<String> addresses); }
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static volatile int receiverPort = 8081;
    private static final Map<String, Integer> DEVICE_PORTS = new ConcurrentHashMap<>();
    public static void setReceiverPort(int port) { if (port >= 1024 && port <= 65535) receiverPort = port; }
    public static int getReceiverPort() { return receiverPort; }
    private static int getPortFor(String ip) {
        Integer port = ip == null ? null : DEVICE_PORTS.get(ip);
        return port == null ? receiverPort : port;
    }
    private CastingManager() {}

    // Verify the AboEzzat Receiver HTTP response, using the port announced by this device.
    public static boolean isReceiver(String ip) {
        return isReceiver(ip, getPortFor(ip));
    }

    private static boolean isReceiver(String ip, int port) {
        if (ip == null || !ip.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")
                || port < 1024 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 900);
            socket.setSoTimeout(1200);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /?command=ping HTTP/1.1\\r\\nHost: " + ip + ":" + port
                    + "\\r\\nConnection: close\\r\\n\\r\\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            if (status == null || !status.matches("HTTP/1\\.[01] 200(?: .*)?")) return false;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) { }
            return "OK".equals(reader.readLine());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void send(String ip, String url, Callback callback) {
        final String targetUrl = url == null ? "" : url.trim();
        IO.execute(() -> {
            try {
                if (targetUrl.isEmpty()) {
                    callback.onResult(false, "الرابط فارغ");
                    return;
                }
                android.net.Uri parsed = android.net.Uri.parse(targetUrl);
                String scheme = parsed.getScheme();
                if (parsed.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                    callback.onResult(false, "لا يمكن إرسال هذا النوع من الروابط؛ استخدم رابط HTTP أو HTTPS صالحًا");
                    return;
                }
            } catch (Exception invalidUrl) {
                callback.onResult(false, "الرابط غير صالح");
                return;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(ip, getPortFor(ip)), 3500);
                socket.setSoTimeout(4000);
                String target = "/?url=" + URLEncoder.encode(targetUrl, "UTF-8");
                OutputStream out = socket.getOutputStream();
                out.write(("GET " + target + " HTTP/1.1\r\nHost: " + ip + ":" + getReceiverPort() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                boolean ok = false;
                if (status != null) {
                    String[] parts = status.trim().split("\\s+", 3);
                    ok = parts.length >= 2 && "200".equals(parts[1]);
                }
                callback.onResult(ok, ok ? "تم إرسال الرابط إلى الشاشة، جارٍ استخراج الفيديو وتشغيله" : "جهاز الاستقبال لم يؤكد الطلب");
            } catch (Exception e) { callback.onResult(false, "تعذّر الاتصال: " + e.getMessage()); }
        });
    }

    public static void sendTracks(String ip, String videoUrl, String audioUrl, Callback callback) {
        final String video = videoUrl == null ? "" : videoUrl.trim();
        final String audio = audioUrl == null ? "" : audioUrl.trim();
        IO.execute(() -> {
            if (ip == null || !ip.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")) {
                callback.onResult(false, "عنوان الريسيفر غير صالح");
                return;
            }
            if (!isHttpUrl(video) || !isHttpUrl(audio)) {
                callback.onResult(false, "لازم تختار رابط فيديو ورابط صوت مباشرين صالحين");
                return;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, getReceiverPort()), 3500);
                socket.setSoTimeout(4000);
                String target = "/?video=" + URLEncoder.encode(video, "UTF-8")
                        + "&audio=" + URLEncoder.encode(audio, "UTF-8");
                OutputStream out = socket.getOutputStream();
                out.write(("GET " + target + " HTTP/1.1\r\nHost: " + ip
                        + ":" + getReceiverPort() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                boolean ok = status != null && status.matches("HTTP/1\\.[01] 200(?: .*)?");
                callback.onResult(ok, ok ? "تم إرسال مساري الفيديو والصوت للريسيفر" : "الريسيفر لم يؤكد إرسال المسارين");
            } catch (Exception e) {
                callback.onResult(false, "تعذّر إرسال المسارين: " + e.getMessage());
            }
        });
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void sendCommand(String ip, String command, Callback callback) {
        IO.execute(() -> {
            try {
                if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    callback.onResult(false, "عنوان الريسيفر غير صالح");
                    return;
                }
                if (!("play_pause".equals(command) || "rw".equals(command) || "ff".equals(command) || "stop".equals(command) || "volume_up".equals(command) || "volume_down".equals(command) || "mute".equals(command))) {
                    callback.onResult(false, "أمر الريموت غير مدعوم");
                    return;
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, getReceiverPort()), 2500);
                    socket.setSoTimeout(3000);
                    String target = "/?command=" + URLEncoder.encode(command, "UTF-8");
                    OutputStream out = socket.getOutputStream();
                    out.write(("GET " + target + " HTTP/1.1\r\nHost: " + ip + ":" + getReceiverPort() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String status = reader.readLine();
                    boolean ok = status != null && status.matches("HTTP/1\\.[01] 200(?: .*)?");
                    callback.onResult(ok, ok ? "تم إرسال أمر التحكم إلى الريسيفر" : "الريسيفر لم يقبل أمر التحكم");
                }
            } catch (Exception e) {
                callback.onResult(false, "تعذّر إرسال أمر الريموت: " + e.getMessage());
            }
        });
    }

    public static void discover(Context context, DiscoveryCallback callback) {
        IO.execute(() -> {
            List<String> found = new ArrayList<>();
            Map<String, Integer> announcedPorts = new ConcurrentHashMap<>();
            // Android Wi-Fi filters multicast unless a MulticastLock is held.
            WifiManager.MulticastLock multicastLock = null;
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) { multicastLock = wifi.createMulticastLock("AboEzzatDiscovery"); multicastLock.setReferenceCounted(false); multicastLock.acquire(); }
            } catch (Exception ignored) { }
            // Listen briefly for receiver heartbeats before the slower TCP fallback scan.
            try (MulticastSocket listener = new MulticastSocket(null)) {
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(37020));
                listener.setSoTimeout(3000);
                InetAddress group = InetAddress.getByName("239.255.42.99");
                listener.joinGroup(group);
                long until = System.currentTimeMillis() + 3000L;
                while (System.currentTimeMillis() < until) {
                    byte[] buffer = new byte[512];
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                    try {
                        listener.receive(packet);
                        String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                        String[] fields = message.split("\\|");
                        if (fields.length == 3 && "ABOEZZAT_RECEIVER".equals(fields[0])) {
                            String ip = fields[1].trim();
                            int announcedPort;
                            try { announcedPort = Integer.parseInt(fields[2].trim()); }
                            catch (NumberFormatException invalidPort) { continue; }
                            if (announcedPort < 1024 || announcedPort > 65535
                                    || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) continue;
                            // Keep each receiver's port with its IP; different devices may use different ports.
                            announcedPorts.put(ip, announcedPort);
                            synchronized (found) {
                                if (!found.contains(ip)) found.add(ip);
                            }
                        }
                    } catch (java.net.SocketTimeoutException timeout) {
                        break;
                    }
                }
                listener.leaveGroup(group);
            } catch (Exception ignored) {
                // Multicast may be filtered; keep the existing TCP scan as a fallback.
            } finally {
                if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            }
            // Trust a multicast announcement only after its receiver port responds.
            // If a stale or unreachable address was announced, continue with the TCP fallback scan.
            List<String> verifiedMulticast = new ArrayList<>();
            synchronized (found) {
                for (String candidate : found) {
                    int port = announcedPorts.getOrDefault(candidate, getReceiverPort());
                    if (isReceiver(candidate, port)) {
                        DEVICE_PORTS.put(candidate, port);
                        setReceiverPort(port);
                        verifiedMulticast.add(candidate);
                    }
                }
            }
            if (!verifiedMulticast.isEmpty()) {
                context.getApplicationContext()
                        .getSharedPreferences("aboezzat_browser", Context.MODE_PRIVATE)
                        .edit().putInt("receiver_port", getReceiverPort()).apply();
                callback.onDevices(verifiedMulticast);
                return; // A verified receiver was found; avoid scanning the subnet.
            }
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                List<String> prefixes = new ArrayList<>();
                List<String> fallbackPrefixes = new ArrayList<>();
                while (interfaces != null && interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                    String name = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                    boolean likelyWifi = name.contains("wlan") || name.contains("wifi") || name.contains("swlan");
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress a = addresses.nextElement();
                        if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress() || !a.isSiteLocalAddress()) continue;
                        String local = a.getHostAddress();
                        if (local == null || !local.matches("\\d{1,3}(\\.\\d{1,3}){3}")) continue;
                        int dot = local.lastIndexOf('.');
                        if (dot < 0) continue;
                        String prefix = local.substring(0, dot + 1);
                        List<String> target = likelyWifi ? prefixes : fallbackPrefixes;
                        if (!target.contains(prefix)) target.add(prefix);
                    }
                }
                // Prefer Wi-Fi subnets, but keep private-interface fallbacks because
                // Android devices do not all name their Wi-Fi interface wlan0.
                List<String> scanPrefixes = prefixes.isEmpty() ? fallbackPrefixes : prefixes;
                ExecutorService workers = Executors.newFixedThreadPool(32);
                List<Runnable> tasks = new ArrayList<>();
                for (String prefix : scanPrefixes) {
                    for (int i = 1; i < 255; i++) {
                        String host = prefix + i;
                        tasks.add(() -> {
                            if (isReceiver(host)) {
                                synchronized (found) {
                                    if (!found.contains(host)) found.add(host);
                                }
                            }
                        });
                    }
                }
                // Submit bounded scans and wait for all probes before returning results.
                CountDownLatch done = new CountDownLatch(tasks.size());
                for (Runnable task : tasks) {
                    workers.execute(() -> {
                        try { task.run(); }
                        finally { done.countDown(); }
                    });
                }
                try {
                    done.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    workers.shutdownNow();
                }
            } catch (Exception ignored) {
            }
            synchronized (found) {
                callback.onDevices(new ArrayList<>(found));
            }
        });
    }
}
