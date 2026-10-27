package com.aboezzat.share;

import android.net.Uri;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MediaDetector {
    public interface Listener { void onMediaFound(String url, String kind, String quality); }
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?://[^\\s\"'<>]+?\\.(?:mp4|m3u8|mpd|webm|m4v|mov|mp3|m4a|aac|ogg|wav)(?:\\?[^\\s\"'<>]*)?)");
    private MediaDetector() {}

    public static String kind(String url) {
        String p = Uri.parse(url).getPath();
        p = p == null ? url : p;
        p = p.toLowerCase(Locale.ROOT);
        if (p.endsWith(".m3u8")) return "HLS";
        if (p.endsWith(".mpd")) return "DASH";
        if (p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".aac") || p.endsWith(".ogg") || p.endsWith(".wav")) return "Audio";
        return "Video";
    }

    public static String quality(String url) {
        Matcher m = Pattern.compile("(?i)(2160|1440|1080|720|540|480|360)p").matcher(url);
        return m.find() ? m.group(1) + "p" : "جودة غير معروفة";
    }

    public static void inspectPage(String pageUrl, Listener listener) {
        if (!BrowserEngine.isWebUrl(pageUrl)) return;
        Request request = new Request.Builder().url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").build();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) return;
                    String type = r.header("Content-Type", "");
                    if (type.contains("video/") || type.contains("audio/")) {
                        listener.onMediaFound(r.request().url().toString(), kind(r.request().url().toString()), quality(r.request().url().toString()));
                        return;
                    }
                    if (!type.contains("html") && !type.isEmpty()) return;
                    String html = r.body().string();
                    Set<String> found = new LinkedHashSet<>();
                    Matcher m = URL_PATTERN.matcher(html);
                    while (m.find()) found.add(m.group(1).replace("\\\\/", "/").replace("&amp;", "&"));
                    String[] tags = {
                        "(?is)<meta[^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|twitter:player:stream)[\\\"'][^>]+content=[\\\"']([^\\\"']+)",
                        "(?is)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|twitter:player:stream)[\\\"']",
                        "(?is)<(?:video|source|audio)[^>]+src=[\\\"']([^\\\"']+)"
                    };
                    for (String tag : tags) {
                        Matcher tm = Pattern.compile(tag).matcher(html);
                        while (tm.find()) {
                            String u = tm.group(1).replace("&amp;", "&");
                            if (u.startsWith("//")) u = "https:" + u;
                            else if (u.startsWith("/")) u = r.request().url().resolve(u).toString();
                            if (BrowserEngine.isWebUrl(u) && (kind(u).equals("Video") || kind(u).equals("Audio") || u.toLowerCase(Locale.ROOT).contains(".m3u8") || u.toLowerCase(Locale.ROOT).contains(".mpd"))) found.add(u);
                        }
                    }
                    for (String u : found) listener.onMediaFound(u, kind(u), quality(u));
                }
            }
        });
    }
}