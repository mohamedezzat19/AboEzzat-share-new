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

    public static void setReceiverPort(int port) { 
        if (port >= 1024 && port <= 65535) receiverPort = port; 
    }
    public static int getReceiverPort() { return receiverPort; }
    
    private static int getPortFor(String ip) {
        Integer port = ip == null ? null : DEVICE_PORTS.get(ip);
        return port == null ? receiverPort : port;
    }
    
    private CastingManager() {}

    // Verify the AboEzzat Receiver HTTP response
    public static boolean isReceiver(String ip) {
        return isReceiver(ip, getPortFor(ip));
    }

    public static boolean isReceiver(String ip, int port) {
        if (ip == null || !ip.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")
                || port < 1024 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 1200);
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /?command=ping HTTP/1.1\r\nHost: " + ip + ":" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            // قبول أي رد HTTP صالح من السيرفر (200 أو حتى ردود خادم الـ NanoHTTPD)
            if (status != null && (status.contains("200") || status.contains("204") || status.startsWith("HTTP/1."))) {
                DEVICE_PORTS.put(ip, port);
                return true;
            }
            return false;
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
                out.write(("GET " + target + " HTTP/1.1\r\nHost: " + ip + ":" + getPortFor(ip) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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

    public static void sendWithHeaders(String ip, String url, Map<String, String> headers, Callback callback) {
        sendWithHeaders(ip, new CapturedMedia(url, "", "", "", "", headers), callback);
    }

    public static void sendWithHeaders(String ip, CapturedMedia media, Callback callback) {
        final CapturedMedia captured = media == null
                ? new CapturedMedia("", "", "", "", "", null) : media;
        final String targetUrl = captured.url == null ? "" : captured.url.trim();
        final Map<String, String> headers = captured.headers;
        final Map<String, String> safeHeaders = new java.util.LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
                String value = entry.getValue();
                if (("user-agent".equals(key) || "referer".equals(key) || "origin".equals(key)
                        || "accept".equals(key) || "accept-language".equals(key) || "cookie".equals(key))
                        && value != null && value.length() <= ("cookie".equals(key) ? 8192 : 2048)
                        && !value.contains("\r") && !value.contains("\n")) {
                    safeHeaders.put(key, value);
                }
            }
        }
        IO.execute(() -> {
            if (!isHttpUrl(targetUrl)) {
                callback.onResult(false, "رابط الوسائط غير صالح");
                return;
            }
            try {
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("url", targetUrl);
                payload.put("headers", new org.json.JSONObject(safeHeaders));
                if (captured.title != null && !captured.title.trim().isEmpty())
                    payload.put("title", captured.title.trim());
                if (captured.posterUrl != null && !captured.posterUrl.trim().isEmpty())
                    payload.put("poster", captured.posterUrl.trim());
                if (captured.mimeType != null && !captured.mimeType.trim().isEmpty())
                    payload.put("mimeType", captured.mimeType.trim());
                if (captured.sourcePage != null && !captured.sourcePage.trim().isEmpty())
                    payload.put("sourcePage", captured.sourcePage.trim());
                payload.put("capturedAt", captured.capturedAt);
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, getPortFor(ip)), 3500);
                    socket.setSoTimeout(5000);
                    OutputStream out = socket.getOutputStream();
                    String request = "POST /play HTTP/1.1\r\nHost: " + ip + ":" + getPortFor(ip)
                            + "\r\nContent-Type: application/json; charset=utf-8"
                            + "\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n";
                    out.write(request.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String status = reader.readLine();
                    boolean ok = status != null && status.matches("HTTP/1\\.[01] 200(?: .*)?");
                    callback.onResult(ok, ok ? "تم إرسال الفيديو والـHeaders الآمنة إلى Receiver"
                            : "Receiver لم يؤكد طلب تشغيل الفيديو");
                }
            } catch (Exception e) {
                callback.onResult(false, "تعذّر إرسال الفيديو: " + e.getClass().getSimpleName());
            }
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
                socket.connect(new InetSocketAddress(ip, getPortFor(ip)), 3500);
                socket.setSoTimeout(4000);
                String target = "/?video=" + URLEncoder.encode(video, "UTF-8")
                        + "&audio=" + URLEncoder.encode(audio, "UTF-8");
                OutputStream out = socket.getOutputStream();
                out.write(("GET " + target + " HTTP/1.1\r\nHost: " + ip
                        + ":" + getPortFor(ip) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
                    socket.connect(new InetSocketAddress(ip, getPortFor(ip)), 2500);
                    socket.setSoTimeout(3000);
                    String target = "/?command=" + URLEncoder.encode(command, "UTF-8");
                    OutputStream out = socket.getOutputStream();
                    out.write(("GET " + target + " HTTP/1.1\r\nHost: " + ip + ":" + getPortFor(ip) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
            List<String> prefixes = new ArrayList<>();
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    int ipAddress = wifi.getConnectionInfo().getIpAddress();
                    String phoneIp = android.text.format.Formatter.formatIpAddress(ipAddress);
                    if (phoneIp != null && phoneIp.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                            && !"0.0.0.0".equals(phoneIp)) {
                        String prefix = phoneIp.substring(0, phoneIp.lastIndexOf('.') + 1);
                        prefixes.add(prefix);
                    }
                }
            } catch (Exception ignored) { }

            if (prefixes.isEmpty()) {
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        NetworkInterface ni = interfaces.nextElement();
                        if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                        String name = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                        if (name.contains("tun") || name.contains("tap") || name.contains("vpn")
                                || name.contains("ppp") || name.contains("wg")) continue;
                        Enumeration<InetAddress> addresses = ni.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            InetAddress address = addresses.nextElement();
                            if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                                    || address.isLinkLocalAddress() || !address.isSiteLocalAddress()) continue;
                            String localIp = address.getHostAddress();
                            if (localIp == null) continue;
                            String prefix = localIp.substring(0, localIp.lastIndexOf('.') + 1);
                            if (!prefixes.contains(prefix)) prefixes.add(prefix);
                        }
                    }
                } catch (Exception ignored) { }
            }

            List<String> found = java.util.Collections.synchronizedList(new ArrayList<>());
            if (!prefixes.isEmpty()) {
                List<Integer> ports = new ArrayList<>();
                int configured = getReceiverPort();
                ports.add(configured);
                for (int candidatePort : new int[]{8081, 8080, 8082, 8090}) {
                    if (!ports.contains(candidatePort)) ports.add(candidatePort);
                }
                ExecutorService workers = Executors.newFixedThreadPool(64);
                CountDownLatch done = new CountDownLatch(prefixes.size() * 254);
                for (String prefix : prefixes) {
                    for (int host = 1; host <= 254; host++) {
                        final String candidate = prefix + host;
                        workers.execute(() -> {
                            try {
                                for (int port : ports) {
                                    try (Socket socket = new Socket()) {
                                        socket.connect(new InetSocketAddress(candidate, port), 450);
                                    } catch (Exception unavailable) {
                                        continue;
                                    }
                                    if (isReceiver(candidate, port)) {
                                        DEVICE_PORTS.put(candidate, port);
                                        if (!found.contains(candidate)) found.add(candidate);
                                        break;
                                    }
                                }
                            } finally {
                                done.countDown();
                            }
                        });
                    }
                }
                try {
                    done.await(9, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    workers.shutdownNow();
                }
            }
            callback.onDevices(new ArrayList<>(found));
        });
    }
}
