package com.aboezzat.share;

import org.json.JSONObject;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Optional remote extraction adapter.
 * The user must configure an API instance they are authorized to use.
 * Only redirect/tunnel URLs are returned; the original page is never treated as media.
 */
final class PlatformExtractor {
    interface Callback {
        void onResult(String mediaUrl, String message);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private PlatformExtractor() {}

    static void resolve(String apiBase, String pageUrl, Callback callback) {
        new Thread(() -> {
            String found = "";
            String message = "لم يُرجع محرك الاستخراج رابط بث مباشر.";
            try {
                String base = apiBase == null ? "" : apiBase.trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                if (!(base.startsWith("https://") || base.startsWith("http://"))) {
                    throw new IllegalArgumentException("عنوان API غير مضبوط");
                }
                JSONObject payload = new JSONObject();
                payload.put("url", pageUrl);
                Request request = new Request.Builder()
                        .url(base + "/")
                        .header("Accept", "application/json")
                        .post(RequestBody.create(payload.toString(), JSON))
                        .build();
                try (Response response = CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        message = "خادم الاستخراج ردّ برمز HTTP " + response.code();
                    } else {
                        JSONObject result = new JSONObject(response.body().string());
                        String status = result.optString("status", "").toLowerCase(Locale.ROOT);
                        String candidate = result.optString("url", "");
                        if ("picker".equals(status)) {
                            org.json.JSONArray items = result.optJSONArray("picker");
                            if (items != null) {
                                for (int i = 0; i < items.length(); i++) {
                                    JSONObject item = items.optJSONObject(i);
                                    if (item == null) continue;
                                    String type = item.optString("type", "").toLowerCase(Locale.ROOT);
                                    String itemUrl = item.optString("url", "");
                                    if (("video".equals(type) || "audio".equals(type))
                                            && isHttpUrl(itemUrl) && !itemUrl.equals(pageUrl)) {
                                        candidate = itemUrl;
                                        break;
                                    }
                                }
                            }
                        }
                        if (("redirect".equals(status) || "tunnel".equals(status)
                                || "picker".equals(status))
                                && isHttpUrl(candidate) && !candidate.equals(pageUrl)) {
                            found = candidate;
                            message = "تم العثور على رابط وسائط من محرك الاستخراج (" + status + ").";
                        } else {
                            message = "المحرك لم يُرجع رابط وسائط صالحًا (الحالة: "
                                    + (status.isEmpty() ? "غير معروفة" : status) + ").";
                        }
                    }
                }
            } catch (Exception e) {
                message = "تعذّر الاتصال بمحرك الاستخراج: " + e.getMessage();
            }
            callback.onResult(found, message);
        }, "PlatformExtractor").start();
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://"))
                && value.length() < 8192;
    }
}
