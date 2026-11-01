package com.aboezzat.share;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Private AboEzzat Social Engine client. Secrets and cookies are never logged or forwarded to Receiver. */
final class PlatformExtractor {
    static final class MediaResult {
        final String url;
        final String type;
        final String quality;
        final java.util.Map<String, String> headers;
        MediaResult(String url, String type, String quality, java.util.Map<String, String> headers) {
            this.url = url; this.type = type; this.quality = quality;
            this.headers = headers == null ? java.util.Collections.emptyMap() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(headers));
        }
    }
    interface Callback { void onResult(MediaResult result, String message); }
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).build();

    private PlatformExtractor() {}

    static void resolve(String apiBase, String token, String pageUrl, String cookies, Callback callback) {
        new Thread(() -> {
            MediaResult found = null;
            String message = "لم يُرجع Social Engine رابط بث مباشر.";
            try {
                String base = apiBase == null ? "" : apiBase.trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                if (!(base.startsWith("https://") || base.startsWith("http://")))
                    throw new IllegalArgumentException("عنوان Social Engine غير مضبوط");
                String endpoint = base.endsWith("/api/v1/extract") ? base : base + "/api/v1/extract";
                String secret = token == null ? "" : token.trim();
                if (secret.length() < 32) throw new IllegalArgumentException("مفتاح Social Engine غير مضبوط");

                JSONObject payload = new JSONObject();
                payload.put("url", pageUrl);
                if (cookies != null && !cookies.trim().isEmpty()) payload.put("cookies", cookies.trim());
                Request request = new Request.Builder().url(endpoint)
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + secret)
                        .post(RequestBody.create(payload.toString(), JSON)).build();

                try (Response response = CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        message = "Social Engine ردّ برمز HTTP " + response.code();
                    } else {
                        JSONObject result = new JSONObject(response.body().string());
                        if (!result.optBoolean("success", false)) {
                            message = result.optString("message", "لم ينجح استخراج الرابط.");
                        } else {
                            JSONArray formats = result.optJSONArray("formats");
                            int bestScore = Integer.MIN_VALUE;
                            if (formats != null) for (int i = 0; i < formats.length(); i++) {
                                JSONObject format = formats.optJSONObject(i);
                                if (format == null) continue;
                                String candidate = format.optString("url", "").trim();
                                if (!isHttpUrl(candidate) || candidate.equals(pageUrl)) continue;
                                String type = format.optString("type", "unknown");
                                int score = qualityScore(format.optString("quality", "")) * 10;
                                if ("mp4".equalsIgnoreCase(type)) score += 3;
                                else if ("hls".equalsIgnoreCase(type)) score += 2;
                                else if ("dash".equalsIgnoreCase(type)) score += 1;
                                if (score > bestScore) {
                                    bestScore = score;
                                    java.util.Map<String, String> safeHeaders = new java.util.LinkedHashMap<>();
                                    JSONObject rawHeaders = format.optJSONObject("headers");
                                    if (rawHeaders != null) {
                                        java.util.Iterator<String> headerKeys = rawHeaders.keys();
                                        while (headerKeys.hasNext() && safeHeaders.size() < 5) {
                                            String key = headerKeys.next();
                                            String lower = key.toLowerCase(java.util.Locale.ROOT);
                                            String value = rawHeaders.optString(key, "");
                                            if (("user-agent".equals(lower) || "referer".equals(lower)
                                                    || "origin".equals(lower) || "accept".equals(lower)
                                                    || "accept-language".equals(lower))
                                                    && value.length() <= 2048
                                                    && !value.contains("\r") && !value.contains("\n")) {
                                                safeHeaders.put(key, value);
                                            }
                                        }
                                    }
                                    found = new MediaResult(candidate, type, format.optString("quality", ""), safeHeaders);
                                }
                            }
                            message = found == null
                                    ? result.optString("message", "لا توجد صيغة بث مباشرة قابلة للإرسال.")
                                    : "تم استخراج رابط من Social Engine.";
                        }
                    }
                }
            } catch (Exception e) {
                // Do not include request data, cookies, or bearer token in error messages.
                message = "تعذّر الاتصال بـ Social Engine أو قراءة رده: " + e.getClass().getSimpleName();
            }
            callback.onResult(found, message);
        }, "AboEzzatSocialEngine").start();
    }

    private static int qualityScore(String quality) {
        if (quality == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d{3,4})\\s*p", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(quality);
        if (!matcher.find()) return 0;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (Exception ignored) { return false; }
    }
}
