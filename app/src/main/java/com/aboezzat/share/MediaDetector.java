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

    private static final Pattern VIDEO_URL_PATTERN = Pattern.compile(
            "(?i)(https?://[^\\s\\\"'<>]+?\\.(?:mp4|m4v|webm|mov|mkv|3gp|flv|m3u8|mpd)(?:\\?[^\\s\\\"'<>]*)?)");

    private static final Pattern VIDEO_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mp4|m4v|webm|mov|mkv|3gp|flv|m3u8|mpd)$");

    private static final Pattern[] JSON_VIDEO_PATTERNS = new Pattern[]{
            // تيك توك لايف: التقاط بث HLS المباشر الصريح المدعوم للشاشات
            Pattern.compile("\"hls_pull_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"live_hls_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"hls_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"pull_data\"\\s*:\\s*\\{[^}]*?\"stream_data\"\\s*:\\s*\"([^\"]+)\""),

            // تيك توك العادي
            Pattern.compile("\"download_addr\"\\s*:\\s*\\{[^}]*?\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\""),
            Pattern.compile("\"play_addr\"\\s*:\\s*\\{[^}]*?\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\""),

            // تويتر / X
            Pattern.compile("\"playbackUrl\"\\s*:\\s*\"([^\"]+?\\.m3u8[^\"]*)\""),
            Pattern.compile("\"contentUrl\"\\s*:\\s*\"([^\"]+?\\.m3u8[^\"]*)\""),
            Pattern.compile("\"playbackUrl\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"contentUrl\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\""),

            // فيسبوك وإنستجرام وثريدز
            Pattern.compile("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playback_url\"\\s*:\\s*\"([^\"]+)\"")
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
                .replace("\\u003d", "=")
                .replace("&quot;", "")
                .replace("&#39;", "");

        if (clean.contains("&bytestart=")) {
            clean = clean.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
        }
        return clean.replaceAll("[),.?!;،؛؟\\]}]+$", "");
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
        if (url == null) return "720p HD";
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("live") || lower.contains("pull-hls")) return "بث مباشر (HD)";

        Matcher m = Pattern.compile("(?i)(2160|1440|1080|720|540|480|360)p").matcher(url);
        if (m.find()) return m.group(1) + "p";

        if (lower.contains("hd_url") || lower.contains("_hd") || lower.contains("720") || lower.contains("1080")) {
            return "720p HD";
        }
        return "720p";
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

            if (lowerPath.matches(".*\\.(jpe?g|png|webp|gif|bmp|avif)$")
                    || lower.contains(".jpg?") || lower.contains(".jpeg?")
                    || lower.contains(".png?") || lower.contains(".webp?")) {
                return false;
            }

            for (String ad : AD_DOMAINS) {
                if (lower.contains(ad)) return false;
            }

            if (lower.contains(".ts") || lower.contains("seg-") || lower.contains("fragment")
                    || lower.contains(".js") || lower.contains(".css")
                    || lower.contains("preview") || lower.contains("rsrc.php")
                    || lower.contains("google_ads") || lower.contains("ad-interrupting")) {
                return false;
            }

            // تويتر / X
            if (host.equals("video.twimg.com") || lower.contains("twimg.com")) {
                if (lower.contains("/audio/") || lower.contains("audio_") || lower.contains(".m4a") || lower.contains(".aac")) {
                    return false;
                }
                if (lower.contains(".m3u8")) {
                    return true;
                }
                if (lower.contains("/amplify_video/") || lower.contains("/ext_tw_video/")) {
                    return lower.contains(".mp4");
                }
                return lowerPath.endsWith(".mp4");
            }

            // صيغ البث المباشر الشائعة
            if (lowerPath.matches(".*\\.(mp4|m4v|webm|mov|mkv|3gp|flv|m3u8|mpd)$")) return true;

            // تيك توك: العادي + اللايف (مع التركيز على روابط HLS و FLV)
            if (host.contains("tiktokcdn") || host.contains("tiktokv") || host.contains("byteoversea")
                    || host.contains("ibytedtos") || host.contains("musical.ly") || host.contains("ttlivecdn")) {
                if (lowerPath.contains("/video/") || lowerPath.contains("/obj/") || lowerPath.contains("/tos")
                        || lower.contains("pull-hls") || lower.contains(".m3u8") || lower.contains("pull-flv")) {
                    return true;
                }
            }

            // إنستجرام وفيسبوك وثريدز
            if (host.contains("cdninstagram.com") || host.contains("fbcdn.net")) {
                if ((lowerPath.contains("/v/") || lowerPath.contains("/t50.") || lowerPath.contains("/t42.")
                        || lower.contains("video") || lower.contains("_n.mp4"))
                        && !lower.contains(".jpg") && !lower.contains(".png") && !lower.contains(".webp")) {
                    return true;
                }
            }

            if (lower.contains("mime=video") || lower.contains("type=video")
                    || lower.contains("contenttype=video") || lower.contains("format=mp4")
                    || lower.contains("video_playback") || lower.contains("/videoplayback")
                    || lower.contains("live.m3u8") || lower.contains("pull-hls")) {
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

        if (lower.contains(".ts") || lower.contains("seg-") || lower.contains("fragment")
                || lower.contains(".js") || lower.contains(".css")
                || lower.contains("preview") || lower.contains("rsrc.php")
                || lower.contains("google_ads") || lower.contains("ad-interrupting")) {
            return false;
        }

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String lowerPath = (path == null ? "" : path).toLowerCase(Locale.ROOT);
            if (VIDEO_EXTENSION.matcher(lowerPath).find()) return isDirectVideoUrl(url);
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

        String cleanUrl = pageUrl.trim();

        if (cleanUrl.contains("x.com/") || cleanUrl.contains("twitter.com/")) {
            extractTwitterSyndication(cleanUrl, listener);
            return;
        }

        if (cleanUrl.contains("instagram.com/")) {
            extractInstagram(cleanUrl, listener);
            return;
        }

        if (cleanUrl.contains("threads.net/") || cleanUrl.contains("threads.com/")) {
            extractThreads(cleanUrl, listener);
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
                tryEngineExtraction(apiBase, token, cleanUrl, listener, () -> fallbackInspectHtml(cleanUrl, listener));
            }
        }

        if (!engineAttempted) {
            fallbackInspectHtml(cleanUrl, listener);
        }
    }

    public static void inspectPage(String pageUrl, Listener listener) {
        inspectPage(null, pageUrl, listener);
    }

    private static void extractTwitterSyndication(String tweetUrl, Listener listener) {
        Matcher matcher = Pattern.compile("status/(\\d+)").matcher(tweetUrl);
        if (!matcher.find()) {
            fallbackInspectHtml(tweetUrl, listener);
            return;
        }

        String tweetId = matcher.group(1);
        String apiUrl = "https://cdn.syndication.twimg.com/tweet-result?id=" + tweetId + "&token=x";

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fallbackInspectHtml(tweetUrl, listener);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        fallbackInspectHtml(tweetUrl, listener);
                        return;
                    }

                    String json = r.body().string();
                    JSONObject root = new JSONObject(json);

                    if (root.has("video")) {
                        JSONObject video = root.getJSONObject("video");
                        JSONArray variants = video.optJSONArray("variants");
                        if (variants != null && variants.length() > 0) {
                            String bestUrl = "";
                            int bestBitrate = -1;

                            for (int i = 0; i < variants.length(); i++) {
                                JSONObject v = variants.getJSONObject(i);
                                String src = v.optString("src");
                                int bitrate = v.optInt("bitrate", 0);

                                if (src.contains(".m3u8")) {
                                    String clean = cleanMediaUrl(src);
                                    dispatchMediaFound(listener, clean, "HLS", "أفضل جودة (HLS)");
                                    return;
                                }

                                if (src.contains(".mp4") && bitrate > bestBitrate) {
                                    bestBitrate = bitrate;
                                    bestUrl = src;
                                }
                            }

                            if (!bestUrl.isEmpty()) {
                                String clean = cleanMediaUrl(bestUrl);
                                dispatchMediaFound(listener, clean, "Video", quality(clean));
                                return;
                            }
                        }
                    }

                    fallbackInspectHtml(tweetUrl, listener);
                } catch (Exception e) {
                    fallbackInspectHtml(tweetUrl, listener);
                }
            }
        });
    }

    private static void extractInstagram(String instaUrl, Listener listener) {
        Matcher matcher = Pattern.compile("(?:p|reel|tv)/([A-Za-z0-9_-]+)").matcher(instaUrl);
        if (!matcher.find()) {
            fallbackInspectHtml(instaUrl, listener);
            return;
        }

        String shortcode = matcher.group(1);
        String embedUrl = "https://www.instagram.com/p/" + shortcode + "/embed/captioned/";

        Request request = new Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fallbackInspectHtml(instaUrl, listener);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        fallbackInspectHtml(instaUrl, listener);
                        return;
                    }

                    String html = r.body().string();
                    Matcher videoMatcher = Pattern.compile("video_url[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)").matcher(html);
                    if (videoMatcher.find()) {
                        String clean = cleanMediaUrl(videoMatcher.group(1));
                        if (isMediaCandidate(clean)) {
                            dispatchMediaFound(listener, clean, "Video", quality(clean));
                            return;
                        }
                    }

                    Matcher videoSrcMatcher = Pattern.compile("<video[^>]+src=[\\\"']([^\\\"']+)").matcher(html);
                    if (videoSrcMatcher.find()) {
                        String clean = cleanMediaUrl(videoSrcMatcher.group(1));
                        if (isMediaCandidate(clean)) {
                            dispatchMediaFound(listener, clean, "Video", quality(clean));
                            return;
                        }
                    }

                    fallbackInspectHtml(instaUrl, listener);
                } catch (Exception e) {
                    fallbackInspectHtml(instaUrl, listener);
                }
            }
        });
    }

    private static void extractThreads(String threadsUrl, Listener listener) {
        // Threads public post pages expose video_versions in server-rendered JSON
        // to link-preview crawlers. Parse the matching post only (not suggested
        // posts/replies) and collect every progressive MP4 rendition.
        Matcher idMatcher = Pattern.compile("(?:/post/|/t/|/share/)([A-Za-z0-9_-]+)").matcher(threadsUrl);
        final String targetCode = idMatcher.find() ? idMatcher.group(1) : null;

        Request request = new Request.Builder()
                .url(threadsUrl)
                .header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                extractThreadsEmbedFallback(threadsUrl, listener);
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        extractThreadsEmbedFallback(threadsUrl, listener);
                        return;
                    }
                    String html = r.body().string();
                    Set<String> found = new LinkedHashSet<>();
                    Matcher scripts = Pattern.compile("(?is)<script[^>]*type=[\\\"']application/json[\\\"'][^>]*>(.*?)</script>").matcher(html);
                    while (scripts.find()) {
                        try {
                            Object root = new JSONObject(scripts.group(1));
                            collectThreadsPostMedia(root, targetCode, found, false);
                        } catch (Exception ignored) { }
                    }

                    if (!found.isEmpty()) {
                        for (String candidate : found) {
                            dispatchMediaFound(listener, candidate, "Video", threadsQuality(candidate));
                        }
                    } else {
                        extractThreadsEmbedFallback(threadsUrl, listener);
                    }
                } catch (Exception e) {
                    extractThreadsEmbedFallback(threadsUrl, listener);
                }
            }
        });
    }

    private static void collectThreadsPostMedia(Object node, String targetCode,
                                               Set<String> output, boolean matchingPost) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            boolean isTarget = matchingPost;
            String code = obj.optString("code", "");
            if (targetCode != null && targetCode.equals(code)) isTarget = true;

            if (isTarget) {
                JSONArray versions = obj.optJSONArray("video_versions");
                collectThreadsVersions(versions, output);
                JSONArray carousel = obj.optJSONArray("carousel_media");
                if (carousel != null) {
                    for (int i = 0; i < carousel.length(); i++) {
                        JSONObject media = carousel.optJSONObject(i);
                        if (media != null) collectThreadsVersions(media.optJSONArray("video_versions"), output);
                    }
                }
            }

            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = obj.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectThreadsPostMedia(child, targetCode, output, isTarget);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object child = arr.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    collectThreadsPostMedia(child, targetCode, output, matchingPost);
                }
            }
        }
    }

    private static void collectThreadsVersions(JSONArray versions, Set<String> output) {
        if (versions == null) return;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject item = versions.optJSONObject(i);
            if (item == null) continue;
            String url = cleanMediaUrl(item.optString("url", ""));
            if (url != null && isMediaCandidate(url) && url.toLowerCase(Locale.ROOT).contains(".mp4")) {
                output.add(url);
            }
        }
    }

    private static String threadsQuality(String url) {
        Matcher size = Pattern.compile("(?i)(?:\\\\/|/)(\\\\d{3,4})x(\\\\d{3,4})(?:\\\\/|/|\\\\.)").matcher(url);
        if (size.find()) return size.group(2) + "p";
        Matcher height = Pattern.compile("(?i)(?:height|resolution|quality)[=:_-](\\\\d{3,4})").matcher(url);
        if (height.find()) return height.group(1) + "p";
        return quality(url);
    }

    private static void extractThreadsEmbedFallback(String threadsUrl, Listener listener) {
        String cleanUrl = threadsUrl.split("\\?")[0].replaceAll("/+$", "");
        String embedUrl = cleanUrl + "/embed";
        Request request = new Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                fallbackInspectHtml(threadsUrl, listener);
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        fallbackInspectHtml(threadsUrl, listener);
                        return;
                    }
                    String html = r.body().string();
                    Matcher m = Pattern.compile("video_url[\\\"']\\\\s*:\\\\s*[\\\"']([^\\\"']+)").matcher(html);
                    boolean sent = false;
                    while (m.find()) {
                        String clean = cleanMediaUrl(m.group(1));
                        if (isMediaCandidate(clean)) {
                            dispatchMediaFound(listener, clean, "Video", quality(clean));
                            sent = true;
                        }
                    }
                    Matcher videoTag = Pattern.compile("<video[^>]+src=[\\\"']([^\\\"']+)").matcher(html);
                    while (videoTag.find()) {
                        String clean = cleanMediaUrl(videoTag.group(1));
                        if (isMediaCandidate(clean)) {
                            dispatchMediaFound(listener, clean, "Video", quality(clean));
                            sent = true;
                        }
                    }
                    if (!sent) fallbackInspectHtml(threadsUrl, listener);
                } catch (Exception e) {
                    fallbackInspectHtml(threadsUrl, listener);
                }
            }
        });
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

                        if (directUrl == null && root.has("formats")) {
                            JSONArray formats = root.optJSONArray("formats");
                            if (formats != null && formats.length() > 0) {
                                int bestHeight = -1;
                                for (int i = 0; i < formats.length(); i++) {
                                    JSONObject fmt = formats.optJSONObject(i);
                                    if (fmt != null && fmt.has("url")) {
                                        String vcodec = fmt.optString("vcodec", "");
                                        String acodec = fmt.optString("acodec", "");
                                        if (!"none".equalsIgnoreCase(vcodec) && !"none".equalsIgnoreCase(acodec) && !acodec.isEmpty()) {
                                            int h = fmt.optInt("height", 0);
                                            if (h >= bestHeight) {
                                                bestHeight = h;
                                                directUrl = fmt.optString("url");
                                            }
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

                    // إعطاء أولوية مطلقة لروابط HLS (m3u8) للبثوث المباشرة
                    boolean hasHls = false;
                    for (String c : found) {
                        if (c.contains(".m3u8")) {
                            hasHls = true;
                            break;
                        }
                    }

                    for (String candidate : found) {
                        if (hasHls && !candidate.contains(".m3u8") && (candidate.contains("pull-flv") || candidate.contains("twimg.com"))) {
                            continue;
                        }
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
