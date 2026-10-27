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
                while (interfaces != null && interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress a = addresses.nextElement();
                        if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                        String local = a.getHostAddress();
                        int dot = local.lastIndexOf('.');
                        if (dot < 0) continue;
                        String prefix = local.substring(0, dot + 1);
                        ExecutorService workers = Executors.newFixedThreadPool(32);
                        CountDownLatch done = new CountDownLatch(254);
                        for (int i = 1; i < 255; i++) {
                            String candidate = prefix + i;
                            if (candidate.equals(local)) {
                                done.countDown();
                                continue;
                            }
                            final String host = candidate;
                            workers.execute(() -> {
                                try (Socket socket = new Socket()) {
                                    socket.connect(new java.net.InetSocketAddress(host, 8081), 250);
                                    synchronized (found) {
                                        if (!found.contains(host)) found.add(host);
                                    }
                                } catch (Exception ignored) {
                                } finally {
                                    done.countDown();
                                }
                            });
                        }
                        try {
                            done.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            workers.shutdownNow();
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            synchronized (found) {
                callback.onDevices(new ArrayList<>(found));
            }
        });
    }
}
