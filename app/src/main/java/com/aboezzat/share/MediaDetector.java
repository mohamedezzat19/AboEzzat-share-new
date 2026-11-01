package com.aboezzat.share;

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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    // روابط السوشيال إنجن الافتراضية
    private static String engineBaseUrl = "http://127.0.0.1:8765";
    private static String engineBearerToken = "";

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(https?://[^\\s\\\"'<>]+?\\.(?:mp4|m4v|webm|mov|avi|mkv|3gp|flv|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac|jpe?g|png|webp|gif|bmp|avif)(?:\\?[^\\s\\\"'<>]*)?)");

    private static final Pattern MEDIA_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mp4|m4v|webm|mov|avi|mkv|3gp|flv|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac|jpe?g|png|webp|gif|bmp|avif)$");

    // أنماط JSON الخاصة بإنستجرام، فيسبوك، وثردز
    private static final Pattern[] JSON_MEDIA_PATTERNS = new Pattern[]{
            Pattern.compile("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"download_url\"\\s*:\\s*\"([^\"]+)\"")
    };

    private static final List<String> AD_DOMAINS = Arrays.asList(
            "doubleclick.net", "googleads", "pagead2", "adservice.google",
            "googletagmanager", "adcolony", "criteo", "adnxs", "outbrain",
            "taboola", "popads", "popcash", "propellerads", "adsterra",
            "exoclick", "smartadserver", "scorecardresearch",
            "zedo", "adroll", "pubmatic", "rubiconproject"
    );

    private MediaDetector() {}

    public static void setEngineConfig(String baseUrl, String token) {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            engineBaseUrl = baseUrl.trim();
        }
        if (token != null) {
            engineBearerToken = token.trim();
        }
    }

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

            for (String ad : AD_DOMAINS) {
                if (lower.contains(ad)) return false;
            }

            if (lower.contains(".ts") || lower.contains("seg-")
                    || lower.contains(".js") || lower.contains(".css")
                    || lower.contains("preview") || lower.contains("rsrc.php")
                    || lower.contains("google_ads") || lower.contains("ad-interrupting")) {
                return false;
            }

            if (lowerPath.matches(".*\\.(mp4|m4v|webm|mov|avi|mkv|3gp|flv|m3u8|mpd)$")) return true;
            if (lower.contains("pull-") || lower.contains(".flv") || lower.contains(".m3u8")) return true;

            if (lower.contains("mime=video") || lower.contains("type=video")
                    || lower.contains("contenttype=video") || lower.contains("content-type=video")
                    || lower.contains("format=mp4") || lower.contains("video_playback")
                    || lower.contains("/videoplayback") || lower.contains("/ext_tw_video/")
                    || lower.contains("/amplify_video/") || lower.contains("/tweet_video/")) return true;

            if (host.equals("video.twimg.com")) return true;

            if ((host.contains("tiktokcdn") || host.contains("tiktokv") || host.contains("byteoversea")
                    || host.contains("ibytedtos"))
                    && (lowerPath.contains("/video/") || lowerPath.contains("/obj/") || lowerPath.contains("/tos"))) return true;

            if (host.endsWith("fbcdn.net") && (host.startsWith("video.") || lowerPath.contains("/t42.") || lower.contains("video"))) return true;
            if (host.endsWith("cdninstagram.com") && (lowerPath.contains("/t50.") || lowerPath.contains("/v/"))) return true;

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isMediaCandidate(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return false;

        String lower = url.toLowerCase(Locale.ROOT);

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
        String cleaned = cleanMediaUrl(raw);
        if (cleaned.isEmpty() || cleaned.startsWith("blob:") || cleaned.startsWith("data:")
                || cleaned.startsWith("javascript:")) return null;

        if (base != null) {
            HttpUrl resolved = base.resolve(cleaned);
            if (resolved != null) return cleanMediaUrl(resolved.toString());
        }
        return cleaned;
    }

    /**
     * الفحص المتكامل: يبدأ بالمحرك المحلي ثم يعود للفحص اليدوي كخطة بديلة
     */
    public static void inspectPage(String pageUrl, Listener listener) {
        if (pageUrl == null || (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) || listener == null) return;

        // الحل 1: إذا كان الرابط مباشراً بالفعل نرسله فوراً
        if (isDirectVideoUrl(pageUrl)) {
            dispatchMediaFound(listener, pageUrl, kind(pageUrl), quality(pageUrl));
            return;
        }

        // الحل 2: تجربة محرك AboEzzat Social Engine أولاً
        tryEngineExtraction(pageUrl, listener, new Runnable() {
            @Override
            public void run() {
                // الحل 3: في حال فشل أو توقف المحرك، الانتقال للفحص العميق لصفحة الـ HTML
                fallbackInspectHtml(pageUrl, listener);
            }
        });
    }

    /**
     * استخراج الرابط المدمج من السوشيال إنجن (yt-dlp)
     */
    private static void tryEngineExtraction(String targetUrl, Listener listener, Runnable onFallback) {
        try {
            HttpUrl endpoint = HttpUrl.parse(engineBaseUrl + "/extract");
            if (endpoint == null) {
                onFallback.run();
                return;
            }

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("url", targetUrl);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(endpoint)
                    .post(RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8")));

            if (engineBearerToken != null && !engineBearerToken.isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + engineBearerToken);
            }

            CLIENT.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    onFallback.run();
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response r = response) {
                        if (!r.isSuccessful() || r.body() == null) {
                            onFallback.run();
                            return;
                        }

                        String respStr = r.body().string();
                        JSONObject root = new JSONObject(respStr);
                        String directUrl = null;

                        if (root.has("url")) {
                            directUrl = root.optString("url");
                        } else if (root.has("formats")) {
                            JSONArray formats = root.optJSONArray("formats");
                            if (formats != null && formats.length() > 0) {
                                // اختيار أفضل صيغة فيديو
                                for (int i = formats.length() - 1; i >= 0; i--) {
                                    JSONObject fmt = formats.optJSONObject(i);
                                    if (fmt != null && fmt.has("url")) {
                                        directUrl = fmt.optString("url");
                                        break;
                                    }
                                }
                            }
                        }

                        if (directUrl != null && !directUrl.trim().isEmpty()) {
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

    /**
     * خطة الدعم: فحص HTML وتقصي الـ JSON الداخلي ووسوم الميتا مع محاكاة متصفح كامل
     */
    private static void fallbackInspectHtml(String pageUrl, Listener listener) {
        Request request = new Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
                .header("Sec-Fetch-Mode", "navigate")
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) return;

                    String type = r.header("Content-Type", "");
                    String finalUrl = cleanMediaUrl(r.request().url().toString());

                    if (type != null && (type.toLowerCase(Locale.ROOT).startsWith("video/")
                            || type.toLowerCase(Locale.ROOT).startsWith("audio/"))) {
                        dispatchMediaFound(listener, finalUrl, kind(finalUrl), quality(finalUrl));
                        return;
                    }

                    String html;
                    try {
                        html = r.body().string();
                    } catch (IOException e) {
                        return;
                    }

                    Set<String> found = new LinkedHashSet<>();
                    HttpUrl baseHttpUrl = r.request().url();

                    // 1. فحص أنماط JSON المشفرة (Instagram / Facebook / Threads)
                    for (Pattern p : JSON_MEDIA_PATTERNS) {
                        Matcher jm = p.matcher(html);
                        while (jm.find()) {
                            String raw = jm.group(1);
                            String candidate = resolveMediaUrl(baseHttpUrl, raw);
                            if (candidate != null && isMediaCandidate(candidate)) {
                                found.add(candidate);
                            }
                        }
                    }

                    // 2. فحص وسوم الفيديو والميتا
                    String[] tags = {
                            "(?is)<meta[^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|og:video:secure_url|twitter:player:stream)[\\\"'][^>]+content=[\\\"']([^\\\"']+)",
                            "(?is)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"'](?:og:video|og:video:url|og:video:secure_url|twitter:player:stream)[\\\"']",
                            "(?is)<(?:video|source|audio)[^>]+(?:src|data-src)=[\\\"']([^\\\"']+)"
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

                    // 3. مسح عام لأي امتدادات وسائط صريحة
                    Matcher m = URL_PATTERN.matcher(html);
                    while (m.find()) {
                        String candidate = resolveMediaUrl(baseHttpUrl, m.group(1));
                        if (candidate != null && isMediaCandidate(candidate)) {
                            found.add(candidate);
                        }
                    }

                    // إرسال الروابط المستخرجة
                    for (String candidate : found) {
                        dispatchMediaFound(listener, candidate, kind(candidate), quality(candidate));
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private static void dispatchMediaFound(Listener listener, String url, String kind, String quality) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                listener.onMediaFound(url, kind, quality);
            }
        });
    }
}
