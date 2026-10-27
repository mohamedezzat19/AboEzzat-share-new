package com.aboezzat.share;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
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

    public static void discover(DiscoveryCallback callback) {
        IO.execute(() -> {
            List<String> found = new ArrayList<>();
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
}
