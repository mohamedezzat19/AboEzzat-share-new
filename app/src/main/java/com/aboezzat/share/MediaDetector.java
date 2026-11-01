package com.aboezzat.share;

import android.net.Uri;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MediaDetector {

    public interface Listener {
        void onMediaFound(String url, String kind, String quality);
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(https?://[^\\s\\\"'<>]+?\\.(?:mp4|m4v|webm|mov|avi|mkv|3gp|flv|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac|jpe?g|png|webp|gif|bmp|avif)(?:\\?[^\\s\\\"'<>]*)?)");

    private static final Pattern MEDIA_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mp4|m4v|webm|mov|avi|mkv|3gp|flv|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac|jpe?g|png|webp|gif|bmp|avif)$");

    private static final List<String> AD_DOMAINS = Arrays.asList(
            "doubleclick.net", "googleads", "pagead2", "adservice.google",
            "googletagmanager", "adcolony", "criteo", "adnxs", "outbrain",
            "taboola", "popads", "popcash", "propellerads", "adsterra",
            "exoclick", "smartadserver", "scorecardresearch",
            "zedo", "adroll", "pubmatic", "rubiconproject"
    );

    private MediaDetector() {}

    /**
     * تنظيف الرابط من معاملات التجزئة ليعمل في المشغل بسلاسة
     */
    public static String cleanMediaUrl(String url) {
        if (url == null) return "";
        String clean = url;
        if (clean.contains("&bytestart=")) {
            clean = clean.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
        }
        return clean;
    }

    /**
     * فحص نوع الميديا وتحديد الصيغة بدقة مثل كود BrowserActivity القديم
     */
    public static String kind(String url) {
        if (url == null) return "Video";
        String lower = url.toLowerCase(Locale.ROOT);

        if (lower.contains("audio") || lower.contains("dash_ln_heaac") || (lower.contains("bytestart=0") && lower.contains("mime=audio"))) {
            return "Audio";
        }
        if (lower.contains(".flv") || lower.contains("pull-")) {
            return "Live-FLV";
        }
        if (lower.contains(".m3u8")) {
            return "HLS";
        }
        if (lower.contains(".mpd")) {
            return "DASH";
        }
        if (lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac")
                || lower.contains(".ogg") || lower.contains(".oga") || lower.contains(".opus")
                || lower.contains(".wav") || lower.contains(".flac")) {
            return "Audio";
        }
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                || lower.contains(".webp") || lower.contains(".gif") || lower.contains(".bmp")
                || lower.contains(".avif")) {
            return "Image";
        }
        return "Video";
    }

    public static String quality(String url) {
        Matcher m = Pattern.compile("(?i)(2160|1440|1080|720|540|480|360)p").matcher(url);
        return m.find() ? m.group(1) + "p" : "جودة غير معروفة";
    }

    /**
     * التحقق الصارم من روابط الفيديو المباشرة ومسارات السوشيال ميديا والبث الحي
     */
    public static boolean isDirectVideoUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            String lower = url.toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

            // استبعاد نطاقات الإعلانات
            for (String ad : AD_DOMAINS) {
                if (lower.contains(ad)) return false;
            }

            // استبعاد أجزاء البث الفرعية والملفات الثابتة ومقاطع الإعلانات
            if (lower.contains(".ts") || lower.contains("seg-")
                    || lower.contains(".js") || lower.contains(".css")
                    || lower.contains("preview") || lower.contains("rsrc.php")
                    || lower.contains("google_ads") || lower.contains("ad-interrupting")) {
                return false;
            }

            // امتدادات الفيديو والبث المباشر
            if (lowerPath.matches(".*\\.(mp4|m4v|webm|mov|avi|mkv|3gp|flv|m3u8|mpd)$")) return true;

            // روابط البث الحي وتيارات السيرفرات المباشرة
            if (lower.contains("pull-") || lower.contains(".flv") || lower.contains(".m3u8")) return true;

            // بارامترات وسوم الفيديو والـ MIME
            if (lower.contains("mime=video") || lower.contains("type=video")
                    || lower.contains("contenttype=video") || lower.contains("content-type=video")
                    || lower.contains("format=mp4") || lower.contains("video_playback")
                    || lower.contains("/videoplayback") || lower.contains("/ext_tw_video/")
                    || lower.contains("/amplify_video/") || lower.contains("/tweet_video/")) return true;

            // تويتر / X
            if (host.equals("video.twimg.com")) return true;

            // تيك توك وسيرفرات ByteDance
            if ((host.contains("tiktokcdn") || host.contains("tiktokv") || host.contains("byteoversea")
                    || host.contains("ibytedtos"))
                    && (lowerPath.contains("/video/") || lowerPath.contains("/obj/")
                    || lowerPath.contains("/tos"))) return true;

            // فيسبوك وإنستجرام
            if (host.endsWith("fbcdn.net") && (host.startsWith("video.") || lowerPath.contains("/t42.") || lower.contains("video"))) return true;
            if (host.endsWith("cdninstagram.com") && lowerPath.contains("/t50.")) return true;

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isMediaCandidate(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return false;

        String lower = url.toLowerCase(Locale.ROOT);

        // استبعاد الإعلانات
        for (String ad : AD_DOMAINS) {
            if (lower.contains(ad)) return false;
        }

        if (lower.contains(".ts") || lower.contains("seg-")
                || lower.contains(".js") || lower.contains(".css")
                || lower.contains("preview") || lower.contains("rsrc.php")
                || lower.contains("google_ads") || lower.contains("ad-interrupting")) {
            return false;
        }

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String lowerPath = (path == null ? "" : path).toLowerCase(Locale.ROOT);
            if (MEDIA_EXTENSION.matcher(lowerPath).find()) return true;
        } catch (Exception ignored) {}

        if (isDirectVideoUrl(url)) return true;

        return lower.contains("mime=video") || lower.contains("mime=audio")
                || lower.contains("dash_ln_heaac") || lower.contains("pull-")
                || lower.contains("video_playback") || lower.contains("/videoplayback")
                || lower.contains("format=mp4") || lower.contains("type=video")
                || lower.contains("type=audio") || lower.contains("contenttype=video")
                || lower.contains("content-type=video")
                || (lower.contains("fbcdn.net") && (lower.contains("video") || lower.contains("audio")));
    }

    private static String resolveMediaUrl(HttpUrl base, String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace("&amp;", "&").replace("\\/", "/")
                .replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\u0026", "&").replace("\\u003D", "=");
        if (value.isEmpty() || value.startsWith("blob:") || value.startsWith("data:")
                || value.startsWith("javascript:")) return null;
        HttpUrl resolved = base.resolve(value);
        return resolved == null ? null : cleanMediaUrl(resolved.toString());
    }

    public static void inspectPage(String pageUrl, Listener listener) {
        if (pageUrl == null || (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) || listener == null) return;

        Request request = new Request.Builder().url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) return;
                    String type = r.header("Content-Type", "");
                    String finalUrl = cleanMediaUrl(r.request().url().toString());

                    if (type != null && (type.toLowerCase(Locale.ROOT).startsWith("video/")
                            || type.toLowerCase(Locale.ROOT).startsWith("audio/"))) {
                        listener.onMediaFound(finalUrl, kind(finalUrl), quality(finalUrl));
                        return;
                    }
                    if (type != null && !type.isEmpty() && !type.toLowerCase(Locale.ROOT).contains("html")) return;

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
                } catch (Exception ignored) {}
            }
        });
    }
}
