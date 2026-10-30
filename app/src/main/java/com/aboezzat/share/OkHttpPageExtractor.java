package com.aboezzat.share;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Proxy;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Independent generic page parser using OkHttp. This is a fallback strategy,
 * separate from FastMediaExtractor's platform-specific JSON extraction.
 */
final class OkHttpPageExtractor {
    interface Callback {
        void onSuccess(String mediaUrl);
        void onError(String reason);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Pattern META_TAG =
            Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VIDEO_TAG =
            Pattern.compile("<video\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SOURCE_TAG =
            Pattern.compile("<source\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern URL_ATTR =
            Pattern.compile("(?:content|src)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_ATTR =
            Pattern.compile("(?:property|name)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern STREAM_URL =
            Pattern.compile("https?:\\\\?/\\\\?/[^\\\"'\\s<>]+?\\.(?:mp4|m3u8|mpd|webm)(?:\\?[^\\\"'\\s<>]*)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern STRUCTURED_MEDIA =
            Pattern.compile("['\\\"](contentUrl|content_url|videoUrl|video_url|playbackUrl|playback_url|hlsUrl|manifestUrl)['\\\"]\\\\s*:\\\\s*['\\\"]((?:\\\\\\\\.|[^'\\\"])*)['\\\"]",
                    Pattern.CASE_INSENSITIVE);

    private OkHttpPageExtractor() {}

    static void extract(String pageUrl, Callback callback) {
        if (callback == null) return;
        try {
            Request request = new Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .get().build();
            CLIENT.newCall(request).enqueue(new CallbackAdapter(callback));
        } catch (Exception e) {
            MAIN.post(() -> callback.onError("رابط الصفحة غير صالح"));
        }
    }

    private static final class CallbackAdapter implements okhttp3.Callback {
        private final Callback callback;
        CallbackAdapter(Callback callback) { this.callback = callback; }

        @Override public void onFailure(Call call, IOException e) {
            MAIN.post(() -> callback.onError("تعذر جلب الصفحة"));
        }

        @Override public void onResponse(Call call, Response response) {
            try (Response res = response) {
                if (!res.isSuccessful() || res.body() == null) {
                    MAIN.post(() -> callback.onError("الصفحة غير متاحة بدون تسجيل دخول"));
                    return;
                }
                String html = res.body().string();
                String found = findOpenGraph(html);
                if (found == null) found = findVideoOrSource(html);
                if (found == null) found = findStructuredMedia(html);
                if (found == null) found = findMp4(html);
                found = clean(found);
                if (isHttp(found)) {
                    final String result = found;
                    MAIN.post(() -> callback.onSuccess(result));
                } else {
                    MAIN.post(() -> callback.onError("لم يظهر رابط وسائط مباشر في HTML"));
                }
            } catch (Exception e) {
                MAIN.post(() -> callback.onError("تعذر تحليل HTML"));
            }
        }
    }

    private static String findOpenGraph(String html) {
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Matcher keyMatcher = KEY_ATTR.matcher(tag);
            Matcher valueMatcher = URL_ATTR.matcher(tag);
            if (!keyMatcher.find() || !valueMatcher.find()) continue;
            String key = keyMatcher.group(1).toLowerCase(Locale.ROOT);
            if (key.equals("og:video") || key.equals("og:video:url")
                    || key.equals("og:video:secure_url") || key.equals("twitter:player:stream")) {
                String value = clean(valueMatcher.group(1));
                if (isHttp(value)) return value;
            }
        }
        return null;
    }

    private static String findVideoOrSource(String html) {
        Matcher videos = VIDEO_TAG.matcher(html);
        while (videos.find()) {
            String value = attribute(videos.group(), "src");
            if (isHttp(value)) return value;
        }
        Matcher sources = SOURCE_TAG.matcher(html);
        while (sources.find()) {
            String value = attribute(sources.group(), "src");
            if (isHttp(value)) return value;
        }
        return null;
    }

    private static String attribute(String tag, String name) {
        Pattern p = Pattern.compile(name + "\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(tag);
        return m.find() ? clean(m.group(1)) : null;
    }

    private static String findMp4(String html) {
        Matcher m = MP4.matcher(html.replace("\\/", "/"));
        return m.find() ? clean(m.group()) : null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        return value.trim().replace("\\/", "/")
                .replace("&amp;", "&").replace("\\u0026", "&")
                .replace("&#38;", "&").replace("&quot;", "\"");
    }

    private static boolean isHttp(String value) {
        if (value == null || !(value.startsWith("http://") || value.startsWith("https://"))) return false;
        try {
            String host = Uri.parse(value).getHost();
            return host != null && !host.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }
}
