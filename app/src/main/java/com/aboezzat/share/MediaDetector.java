package com.aboezzat.share;

import android.net.Uri;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
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

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(https?://[^\\s\\\"'<>]+?\\.(?:mp4|m3u8|mpd|webm|m4v|mov|mp3|m4a|aac|ogg|wav)(?:\\?[^\\s\\\"'<>]*)?)");
    private static final Pattern MEDIA_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mp4|m4v|webm|mov|avi|mkv|3gp|ts|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac|mid|midi|jpe?g|png|webp|gif|bmp|avif)$");

    private MediaDetector() {}

    public static String kind(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        String p = (path == null ? url : path).toLowerCase(Locale.ROOT);
        if (p.endsWith(".m3u8")) return "HLS";
        if (p.endsWith(".mpd")) return "DASH";
        if (p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".aac")
                || p.endsWith(".ogg") || p.endsWith(".oga") || p.endsWith(".opus")
                || p.endsWith(".wav") || p.endsWith(".flac")) return "Audio";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png")
                || p.endsWith(".webp") || p.endsWith(".gif") || p.endsWith(".bmp")
                || p.endsWith(".avif")) return "Image";
        return "Video";
    }

    public static String quality(String url) {
        Matcher m = Pattern.compile("(?i)(2160|1440|1080|720|540|480|360)p").matcher(url);
        return m.find() ? m.group(1) + "p" : "جودة غير معروفة";
    }

    private static boolean isMediaCandidate(String url) {
        if (!BrowserEngine.isWebUrl(url)) return false;
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        String lowerPath = (path == null ? "" : path).toLowerCase(Locale.ROOT);
        if (MEDIA_EXTENSION.matcher(lowerPath).find()) return true;

        // Keep extensionless detection narrow: these are recognizable media endpoints,
        // not arbitrary page URLs or generic query strings.
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("mime=video") || lower.contains("mime=audio")
                || lower.contains("video_playback") || lower.contains("/videoplayback")
                || lower.contains("format=mp4") || lower.contains("type=video")
                || lower.contains("type=audio") || lower.contains("contenttype=video")
                || lower.contains("content-type=video")
                || (lower.contains("fbcdn.net") && (lower.contains("video") || lower.contains("audio")));
    }

    private static String resolveMediaUrl(HttpUrl base, String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace("&amp;", "&").replace("\\\\/", "/");
        if (value.isEmpty() || value.startsWith("blob:") || value.startsWith("data:")
                || value.startsWith("javascript:")) return null;
        HttpUrl resolved = base.resolve(value);
        return resolved == null ? null : resolved.toString();
    }

    public static void inspectPage(String pageUrl, Listener listener) {
        if (!BrowserEngine.isWebUrl(pageUrl) || listener == null) return;
        Request request = new Request.Builder().url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) return;
                    String type = r.header("Content-Type", "");
                    String finalUrl = r.request().url().toString();

                    // A direct media URL is accepted only when the server confirms its MIME type.
                    if (type.toLowerCase(Locale.ROOT).startsWith("video/")
                            || type.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                        listener.onMediaFound(finalUrl, kind(finalUrl), quality(finalUrl));
                        return;
                    }
                    if (!type.isEmpty() && !type.toLowerCase(Locale.ROOT).contains("html")) return;

                    String html;
                    try { html = r.body().string(); } catch (IOException e) { return; }
                    Set<String> found = new LinkedHashSet<>();

                    Matcher m = URL_PATTERN.matcher(html);
                    while (m.find()) {
                        String candidate = resolveMediaUrl(r.request().url(), m.group(1));
                        if (candidate != null && isMediaCandidate(candidate)) found.add(candidate);
                    }

                    String[] tags = {
                        "(?is)<meta[^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|twitter:player:stream)[\\\"'][^>]+content=[\\\"']([^\\\"']+)",
                        "(?is)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|twitter:player:stream)[\\\"']",
                        "(?is)<(?:video|source|audio)[^>]+(?:src|data-src)=[\\\"']([^\\\"']+)"
                    };
                    for (String tag : tags) {
                        Matcher tm = Pattern.compile(tag).matcher(html);
                        while (tm.find()) {
                            String candidate = resolveMediaUrl(r.request().url(), tm.group(1));
                            if (candidate != null && isMediaCandidate(candidate)) found.add(candidate);
                        }
                    }

                    for (String candidate : found) {
                        listener.onMediaFound(candidate, kind(candidate), quality(candidate));
                    }
                } catch (Exception ignored) {
                    // Page inspection is best-effort; it must not interrupt browser playback.
                }
            }
        });
    }
}
