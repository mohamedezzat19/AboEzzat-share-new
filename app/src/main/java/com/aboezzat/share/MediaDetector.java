package com.aboezzat.share;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class MediaDetector {

    public interface Listener {
        void onMediaFound(String url, String kind, String quality);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .build();

    // روابط الفيديو الصريحة مع دعم البث المباشر وملفات m3u8 و flv
    private static final Pattern VIDEO_URL_PATTERN = Pattern.compile(
            "(?i)(https?://[^\\s\\\"'<>]+?\\.(?:mp4|m4v|webm|mov|mkv|3gp|flv|m3u8|mpd)(?:\\?[^\\s\\\"'<>]*)?)");

    private static final Pattern VIDEO_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mp4|m4v|webm|mov|mkv|3gp|flv|m3u8|mpd)$");

    // أنماط JSON المستهدفة للفيديوهات الكاملة والبثوث الحية
    private static final Pattern[] JSON_VIDEO_PATTERNS = new Pattern[]{
            // تيك توك: البثوث الحية (Live Streams)
            Pattern.compile("\"hls_pull_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"live_hls_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"pull_data\"\\s*:\\s*\\{[^}]*?\"stream_data\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"download_addr\"\\s*:\\s*\\{[^}]*?\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\""),
            Pattern.compile("\"play_addr\"\\s*:\\s*\\{[^}]*?\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\""),

            // إنستجرام وفيسبوك وثريدز (الصوت المدمج بالصورة)
            Pattern.compile("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playback_url\"\\s*:\\s*\"([^\"]+)\""),

            // تويتر / X
            Pattern.compile("\"contentUrl\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playbackUrl\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"")
    };

    private static final List<String> AD_DOMAINS = Arrays.asList(
            "doubleclick.net", "googleads", "pagead2", "adservice.google",
            "googletagmanager", "adcolony", "criteo", "adnxs", "outbrain",
            "taboola", "popads", "popcash", "propellerads", "adsterra",
            "exoclick", "smartadserver", "scorecardresearch",
            "zedo", "adroll", "pubmatic", "rubiconproject"
    );

    private MediaDetector() {}

    public static String cleanMediaUrl(String url) {
        if (url == null) return "";
        String clean = url.trim()
                .replace("&amp;", "&")
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("\\u003D", "=")
                .replace("\\u003d", "=");

        if (clean.contains("&bytestart=")) {
            clean = clean.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
        }
        return clean;
    }

    public static String kind(String url) {
        if (url == null) return "Video";
        String lower = url.toLowerCase(Locale.ROOT);

        if (lower.contains(".m3u8") || lower.contains("/live/") || lower.contains("pull-hls")) return "HLS";
        if (lower.contains(".mpd")) return "DASH";
        if (lower.contains(".flv") || lower.contains("pull-flv")) return "Live-FLV";
        if (lower.contains("audio") || lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac")) return "Audio";

        return "Video";
    }

    public static String quality(String url) {
        if (url == null) return "جودة قياسية";
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("live")) return "أفضل بث متاح";

        Matcher m = Pattern.compile("(?i)(2160|1440|1080|720|540|480|360)p").matcher(url);
        return m.find() ? m.group(1) + "p" : "جودة قياسية";
    }

    public static boolean isDirectVideoUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            String lower = url.toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

            // استبعاد ملفات الصور والمعاينات نهائياً
            if (lowerPath.matches(".*\\.(jpe?g|png|webp|gif|bmp|avif)$")
                    || lower.contains(".jpg?") || lower.contains(".jpeg?")
                    || lower.contains(".png?") || lower.contains(".webp?")) {
                return false;
            }

            for (String ad : AD_DOMAINS) {
                if (lower.contains(ad)) return false;
            }

            if (lower.contains(".ts") || lower.contains("seg-")
                    || lower.contains(".js") || lower.contains(".css")
                    || lower.contains("preview") || lower.contains("rsrc.php")
                    || lower.contains("google_ads") || lower.contains("ad-interrupting")) {
                return false;
            }

            // صيغ البث المباشر وملفات التشغيل
            if (lowerPath.matches(".*\\.(mp4|m4v|webm|mov|mkv|3gp|flv|m3u8|mpd)$")) return true;

            // روابط تيك توك: الفيديوهات المباشرة + بث مباشر HLS/FLV
            if (host.contains("tiktokcdn") || host.contains("tiktokv") || host.contains("byteoversea")
                    || host.contains("ibytedtos") || host.contains("musical.ly") || host.contains("ttlivecdn")) {
                if (lowerPath.contains("/video/") || lowerPath.contains("/obj/") || lowerPath.contains("/tos")
                        || lower.contains("pull-") || lower.contains(".m3u8") || lower.contains(".flv")) {
                    return true;
                }
            }

            // تويتر / X
            if (host.equals("video.twimg.com") || lower.contains("/ext_tw_video/")
                    || lower.contains("/amplify_video/") || lower.contains("/tweet_video/")) {
                return true;
            }

            // إنستجرام وفيسبوك وثريدز
            if (host.contains("cdninstagram.com") || host.contains("fbcdn.net")) {
                if ((lowerPath.contains("/v/") || lowerPath.contains("/t50.") || lowerPath.contains("/t42.")
                        || lower.contains("video") || lower.contains("_n.mp4"))
                        && !lower.contains(".jpg") && !lower.contains(".png") && !lower.contains(".webp")) {
                    return true;
                }
            }

            // محددات وسائط البث والتخزين
            if (lower.contains("mime=video") || lower.contains("type=video")
                    || lower.contains("contenttype=video") || lower.contains("format=mp4")
                    || lower.contains("video_playback") || lower.contains("/videoplayback")
                    || lower.contains("live.m3u8")) {
                return true;
            }

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isMediaCandidate(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return false;

        String lower = url.toLowerCase(Locale.ROOT);

        if (lower.matches(".*\\.(jpe?g|png|webp|gif|bmp|avif)(?:\\?.*)?$")
                || lower.contains(".jpg?") || lower.contains(".png?") || lower.contains(".webp?")) {
            return false;
        }

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
            if (VIDEO_EXTENSION.matcher(lowerPath).find()) return true;
        } catch (Exception ignored) {}

        return isDirectVideoUrl(url);
    }

    private static String resolveMediaUrl(HttpUrl base, String raw) {
        if (raw == null) return null;
        String cleaned = cleanMediaUrl(raw);
        if (cleaned.isEmpty() || cleaned.startsWith("blob:") || cleaned.startsWith("data:")
                || cleaned.startsWith("javascript:")) return null;

        if (base != null) {
            HttpUrl resolved = base.resolve(cleaned);
            if (resolved != null) return cleanMediaUrl(resolved.toString());
        }
        return cleaned;
    }

    public static void inspectPage(Context context, String pageUrl, Listener listener) {
        if (pageUrl == null || (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) || listener == null) return;

        if (isDirectVideoUrl(pageUrl)) {
            dispatchMediaFound(listener, pageUrl, kind(pageUrl), quality(pageUrl));
            return;
        }

        boolean engineAttempted = false;
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences("aboezzat_browser", Context.MODE_PRIVATE);
            String apiBase = prefs.getString("extractor_api_base", "").trim();
            String token = prefs.getString("social_engine_token", "").trim();
            boolean enabled = prefs.getBoolean("engine_enabled_socialengine", true);

            if (enabled && !apiBase.isEmpty() && token.length() >= 32) {
                engineAttempted = true;
                tryEngineExtraction(apiBase, token, pageUrl, listener, () -> fallbackInspectHtml(pageUrl, listener));
            }
        }

        if (!engineAttempted) {
            fallbackInspectHtml(pageUrl, listener);
        }
    }

    public static void inspectPage(String pageUrl, Listener listener) {
        inspectPage(null, pageUrl, listener);
    }

    private static void tryEngineExtraction(String apiBase, String token, String targetUrl, Listener listener, Runnable onFallback) {
        try {
            HttpUrl endpoint = HttpUrl.parse(apiBase.replaceAll("/+$", "") + "/extract");
            if (endpoint == null) {
                onFallback.run();
                return;
            }

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("url", targetUrl);

            Request request = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer " + token)
                    .post(RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8")))
                    .build();

            CLIENT.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { onFallback.run(); }

                @Override public void onResponse(Call call, Response response) {
                    try (Response r = response) {
                        if (!r.isSuccessful() || r.body() == null) {
                            onFallback.run();
                            return;
                        }

                        String respStr = r.body().string();
                        JSONObject root = new JSONObject(respStr);
                        String directUrl = null;

                        // 1. الأولوية لروابط البث المباشر HLS إن وجدت
                        if (root.has("formats")) {
                            JSONArray formats = root.optJSONArray("formats");
                            if (formats != null && formats.length() > 0) {
                                for (int i = formats.length() - 1; i >= 0; i--) {
                                    JSONObject fmt = formats.optJSONObject(i);
                                    if (fmt != null && fmt.has("url")) {
                                        String fUrl = fmt.optString("url");
                                        if (fUrl.contains(".m3u8")) {
                                            directUrl = fUrl;
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        // 2. إذا لم يكن بثاً، نختار صيغة مدمجة بالصوت والصورة
                        if (directUrl == null && root.has("formats")) {
                            JSONArray formats = root.optJSONArray("formats");
                            if (formats != null && formats.length() > 0) {
                                for (int i = formats.length() - 1; i >= 0; i--) {
                                    JSONObject fmt = formats.optJSONObject(i);
                                    if (fmt != null && fmt.has("url")) {
                                        String vcodec = fmt.optString("vcodec", "");
                                        String acodec = fmt.optString("acodec", "");
                                        if (!"none".equalsIgnoreCase(vcodec) && !"none".equalsIgnoreCase(acodec) && !acodec.isEmpty()) {
                                            directUrl = fmt.optString("url");
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        if (directUrl == null && root.has("url")) {
                            directUrl = root.optString("url");
                        }

                        if (directUrl != null && !directUrl.trim().isEmpty() && isDirectVideoUrl(directUrl)) {
                            String finalCleanUrl = cleanMediaUrl(directUrl);
                            dispatchMediaFound(listener, finalCleanUrl, kind(finalCleanUrl), quality(finalCleanUrl));
                        } else {
                            onFallback.run();
                        }
                    } catch (Exception e) {
                        onFallback.run();
                    }
                }
            });
        } catch (Exception e) {
            onFallback.run();
        }
    }

    private static void fallbackInspectHtml(String pageUrl, Listener listener) {
        Request request = new Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) return;

                    String type = r.header("Content-Type", "");
                    String finalUrl = cleanMediaUrl(r.request().url().toString());

                    if (type != null && (type.toLowerCase(Locale.ROOT).startsWith("video/")
                            || type.toLowerCase(Locale.ROOT).contains("mpegurl"))) {
                        dispatchMediaFound(listener, finalUrl, kind(finalUrl), quality(finalUrl));
                        return;
                    }

                    String html;
                    try { html = r.body().string(); } catch (IOException e) { return; }

                    Set<String> found = new LinkedHashSet<>();
                    HttpUrl baseHttpUrl = r.request().url();

                    for (Pattern p : JSON_VIDEO_PATTERNS) {
                        Matcher jm = p.matcher(html);
                        while (jm.find()) {
                            String raw = jm.group(1);
                            String candidate = resolveMediaUrl(baseHttpUrl, raw);
                            if (candidate != null && isMediaCandidate(candidate)) {
                                found.add(candidate);
                            }
                        }
                    }

                    String[] tags = {
                            "(?is)<meta[^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|og:video:secure_url|twitter:player:stream)[\\\"'][^>]+content=[\\\"']([^\\\"']+)",
                            "(?is)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|og:video:secure_url|twitter:player:stream)[\\\"']",
                            "(?is)<(?:video|source)[^>]+(?:src|data-src)=[\\\"']([^\\\"']+)"
                    };
                    for (String tag : tags) {
                        Matcher tm = Pattern.compile(tag).matcher(html);
                        while (tm.find()) {
                            String candidate = resolveMediaUrl(baseHttpUrl, tm.group(1));
                            if (candidate != null && isMediaCandidate(candidate)) {
                                found.add(candidate);
                            }
                        }
                    }

                    Matcher m = VIDEO_URL_PATTERN.matcher(html);
                    while (m.find()) {
                        String candidate = resolveMediaUrl(baseHttpUrl, m.group(1));
                        if (candidate != null && isMediaCandidate(candidate)) {
                            found.add(candidate);
                        }
                    }

                    for (String candidate : found) {
                        dispatchMediaFound(listener, candidate, kind(candidate), quality(candidate));
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private static void dispatchMediaFound(Listener listener, String url, String kind, String quality) {
        MAIN_HANDLER.post(() -> listener.onMediaFound(url, kind, quality));
    }
}
