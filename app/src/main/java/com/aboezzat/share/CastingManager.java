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

public final class CastingManager {
    public interface Callback { void onResult(boolean success, String message); }
    public interface DiscoveryCallback { void onDevices(List<String> addresses); }
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private CastingManager() {}

    public static void send(String ip, String url, Callback callback) {
        IO.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(ip, 8081), 3500);
                socket.setSoTimeout(4000);
                String target = "/?url=" + URLEncoder.encode(url, "UTF-8");
                OutputStream out = socket.getOutputStream();
                out.write(("GET " + target + " HTTP/1.1\r\nHost: " + ip + ":8081\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                boolean ok = false;
                if (status != null) {
                    String[] parts = status.trim().split("\\s+", 3);
                    ok = parts.length >= 2 && "200".equals(parts[1]);
                }
                callback.onResult(ok, ok ? "تم استلام طلب الإرسال" : "جهاز الاستقبال لم يؤكد الطلب");
            } catch (Exception e) { callback.onResult(false, "تعذّر الاتصال: " + e.getMessage()); }
        });
    }

    public static void discover(Context context, DiscoveryCallback callback) {
        IO.execute(() -> {
            List<String> found = new ArrayList<>();
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
                            if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                                synchronized (found) {
                                    if (!found.contains(ip)) found.add(ip);
                                }
                                // Keep collecting multicast candidates; report once below so
                                // the caller cannot accept a later fallback-scan result over this one.
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
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(candidate, 8081), 700);
                        verifiedMulticast.add(candidate);
                    } catch (Exception ignored) { }
                }
            }
            if (!verifiedMulticast.isEmpty()) {
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
                            try (Socket socket = new Socket()) {
                                socket.connect(new java.net.InetSocketAddress(host, 8081), 350);
                                synchronized (found) {
                                    if (!found.contains(host)) found.add(host);
                                }
                            } catch (Exception ignored) {
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
