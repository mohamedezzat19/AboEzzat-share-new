package com.aboezzat.share;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

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

/** استخراج مباشر وسريع من الأجهزة بدون استهلاك موارد أو سيرفرات خارجية */
public final class FastMediaExtractor {
    public interface ExtractorCallback {
        void onSuccess(String videoUrl, String platform);
        void onError(String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    // وسوم الروبوت للشبكات الاجتماعية التي تجبر السيرفرات على إرجاع كود الفيديو المدمج
    private static final String BOT_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php) Twitterbot/1.0";

    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PROP = Pattern.compile("(?:property|name)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT = Pattern.compile("content\\s*=\\s*['\\\"]([^'\\\"]*)['\\\"]", Pattern.CASE_INSENSITIVE);

    // مفاتيح الروابط المدمجة صوتاً وصورة في إنستجرام وفيسبوك وثريدز
    private static final Pattern[] PROGRESSIVE_PATTERNS = new Pattern[]{
            Pattern.compile("['\\\"](browser_native_hd_url|browser_native_sd_url|playable_url_quality_hd|playable_url)['\\\"]\\s*:\\s*['\\\"]((?:\\\\.|[^'\\\"])*)['\\\"]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"video_dash_manifest\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
    };

    private static final Pattern MP4 = Pattern.compile("https?:\\\\?/\\\\?/[^\\\"'\\s<>]+?\\.(?:mp4|m3u8|mpd|webm)(?:[^\\\"'\\s<>]*)?", Pattern.CASE_INSENSITIVE);

    private FastMediaExtractor() {}

    public static void extractDirectMedia(String inputUrl, ExtractorCallback callback) {
        extractDirectMedia(inputUrl, null, callback);
    }

    public static void extractDirectMedia(String inputUrl, String cookies, ExtractorCallback callback) {
        if (callback == null) return;
        final String url = inputUrl == null ? "" : inputUrl.trim();
        final String platform = platformFor(url);

        if (platform == null || !isHttp(url)) {
            error(callback, "الرابط غير صالح أو المنصة غير مدعومة بالاستخراج السريع");
            return;
        }

        // معالجة تويتر عبر واجهة الـ Embed المفتوحة بدون حظر الدخول
        if ("X".equals(platform)) {
            extractTwitterFast(url, callback);
            return;
        }

        // تجهيز الـ User-Agent الأنسب لكل منصة لسحب الفيديو المدمج
        String ua = DESKTOP_UA;
        if ("TikTok".equals(platform)) {
            ua = MOBILE_UA;
        } else if ("Threads".equals(platform) || "Instagram".equals(platform)) {
            ua = BOT_UA; // يجبر Meta على إرجاع وسوم الفيديو المدمج الصريح
        }

        Request request;
        try {
            Request.Builder requestBuilder = new Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("Referer", url);
            if (cookies != null && !cookies.trim().isEmpty()) {
                requestBuilder.header("Cookie", cookies);
            }
            request = requestBuilder
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .get().build();
        } catch (IllegalArgumentException ex) {
            error(callback, "الرابط غير صالح");
            return;
        }

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                error(callback, "فشل الاتصال: " + (e.getMessage() == null ? "خطأ شبكة" : e.getMessage()));
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) {
                        error(callback, "الرابط غير متاح أو قد يتطلب تسجيل الدخول");
                        return;
                    }
                    String html = closeable.body().string();

                    // 1. البحث في وسوم الميتا
                    String media = findMeta(html);

                    // 2. البحث عن الروابط المدمجة الصوت والصورة أولاً
                    if (media == null) {
                        media = findProgressiveMedia(html);
                    }

                    // 3. البحث في الـ JSON العام
                    if (media == null) {
                        media = findMp4(html);
                    }

                    media = clean(media);
                    if (!isHttp(media)) {
                        error(callback, "لم يتم العثور على رابط فيديو مباشر صالح");
                        return;
                    }

                    final String result = media;
                    MAIN.post(() -> callback.onSuccess(result, platform));
                } catch (Exception e) {
                    error(callback, "تعذر تحليل بيانات الفيديو");
                }
            }
        });
    }

    /**
     * استخراج فوري لفيديوهات X / Twitter بدون تسجيل دخول أو سيرفر
     */
    private static void extractTwitterFast(String originalUrl, ExtractorCallback callback) {
        String tweetId = extractTwitterTweetId(originalUrl);
        if (tweetId == null) {
            error(callback, "تعذر استخراج معرف التغريدة");
            return;
        }

        // واجهة API التضمين المفتوحة لتويتر
        String apiUrl = "https://api.vxtwitter.com/Twitter/status/" + tweetId;

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .get()
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                error(callback, "فشل استخراج فيديو تويتر");
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        error(callback, "فشل الرد من تويتر");
                        return;
                    }
                    String jsonStr = r.body().string();
                    JSONObject json = new JSONObject(jsonStr);

                    String videoUrl = null;

                    // VXTwitter may include mixed video + image attachments.
                    // Prefer media_extended entries explicitly marked as video so
                    // a tweet's thumbnail/photo is never selected as the cast URL.
                    JSONArray extended = json.optJSONArray("media_extended");
                    if (extended != null) {
                        for (int i = 0; i < extended.length(); i++) {
                            JSONObject item = extended.optJSONObject(i);
                            if (item == null || !"video".equalsIgnoreCase(item.optString("type", ""))) continue;
                            String u = item.optString("url", "");
                            if (u.startsWith("https://") && (u.contains(".mp4") || u.contains(".m3u8"))) {
                                videoUrl = u;
                                break;
                            }
                        }
                    }

                    // Backward-compatible fallback for API responses without media_extended.
                    if (videoUrl == null) {
                        JSONArray array = json.optJSONArray("mediaURLs");
                        if (array != null) {
                            for (int i = 0; i < array.length(); i++) {
                                String u = array.optString(i, "");
                                String lower = u.toLowerCase(Locale.ROOT);
                                if (lower.contains(".mp4") || lower.contains(".m3u8")) {
                                    videoUrl = u;
                                    break;
                                }
                            }
                        }
                    }
                    if (videoUrl == null) {
                        String hd = json.optString("video_hd_url", "");
                        String normal = json.optString("video_url", "");
                        if (hd.startsWith("https://") && (hd.contains(".mp4") || hd.contains(".m3u8"))) {
                            videoUrl = hd;
                        } else if (normal.startsWith("https://") && (normal.contains(".mp4") || normal.contains(".m3u8"))) {
                            videoUrl = normal;
                        }
                    }

                    if (videoUrl != null && !videoUrl.isEmpty()) {
                        final String finalUrl = clean(videoUrl);
                        MAIN.post(() -> callback.onSuccess(finalUrl, "X"));
                    } else {
                        error(callback, "التغريدة لا تحتوي على فيديو مباشر");
                    }
                } catch (Exception e) {
                    error(callback, "خطأ في تحليل رد تويتر");
                }
            }
        });
    }

    private static String extractTwitterTweetId(String url) {
        Matcher m = Pattern.compile("status(?:es)?/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String platformFor(String value) {
        try {
            String host = Uri.parse(value).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.me")) return "Facebook";
            if (host.contains("threads.net") || host.contains("threads.com")) return "Threads";
            if (host.contains("instagram.com")) return "Instagram";
            if (host.contains("tiktok.com")) return "TikTok";
            if (host.contains("twitter.com") || host.contains("x.com") || host.contains("t.co")) return "X";
        } catch (Exception ignored) {}
        return null;
    }

    private static String findMeta(String html) {
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Matcher p = PROP.matcher(tag), c = CONTENT.matcher(tag);
            if (!p.find() || !c.find()) continue;
            String key = p.group(1).toLowerCase(Locale.ROOT);
            if (key.equals("og:video") || key.equals("og:video:url") || key.equals("og:video:secure_url") || key.equals("twitter:player:stream")) {
                String value = clean(c.group(1));
                if (isHttp(value) && !value.contains(".jpg") && !value.contains(".png")) return value;
            }
        }
        return null;
    }

    private static String findProgressiveMedia(String html) {
        for (Pattern pattern : PROGRESSIVE_PATTERNS) {
            Matcher m = pattern.matcher(html);
            while (m.find()) {
                String candidate = clean(m.group(m.groupCount()));
                if (isHttp(candidate) && (candidate.contains(".mp4") || candidate.contains("video") || candidate.contains(".m3u8"))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String findMp4(String html) {
        Matcher m = MP4.matcher(html);
        while (m.find()) {
            String candidate = clean(m.group());
            if (isHttp(candidate) && !candidate.contains(".jpg") && !candidate.contains(".png") && !candidate.contains(".webp")) {
                return candidate;
            }
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        return value.trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\u0026", "&").replace("\\u003D", "=")
                .replace("&amp;", "&");
    }

    private static boolean isHttp(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Uri u = Uri.parse(value);
            String scheme = u.getScheme();
            return u.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) { return false; }
    }

    private static void error(ExtractorCallback callback, String message) {
        MAIN.post(() -> callback.onError(message));
    }
}
