package com.aboezzat.share;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
                out.write(("GET " + target + " HTTP/1.1\\r\\nHost: " + ip + ":8081\\r\\nConnection: close\\r\\n\\r\\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                boolean ok = status != null && status.contains("200");
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
                        List<Thread> workers = new ArrayList<>();
                        for (int i = 1; i < 255; i++) {
                            String candidate = prefix + i;
                            if (candidate.equals(local)) continue;
                            final String host = candidate;
                            Thread t = new Thread(() -> {
                                try (Socket s = new Socket()) {
                                    s.connect(new java.net.InetSocketAddress(host, 8081), 180);
                                    synchronized (found) { if (!found.contains(host)) found.add(host); }
                                } catch (Exception ignored) {}
                            });
                            workers.add(t); t.start();
                        }
                        for (Thread t : workers) try { t.join(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        break;
                    }
                }
            } catch (Exception ignored) {}
            callback.onDevices(found);
        });
    }
}