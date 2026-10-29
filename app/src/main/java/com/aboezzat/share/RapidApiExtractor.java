package com.aboezzat.share;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Adapter for the same Social Download All In One RapidAPI endpoint used by
 * the supplied Black Hole Extractor project. It resolves links only; Receiver
 * remains responsible for playback and image display.
 */
final class RapidApiExtractor {
    interface Callback {
        void onResult(String mediaUrl, String mediaType, String message);
    }

    private static final String API_URL =
            "https://social-download-all-in-one.p.rapidapi.com/v1/social/autolink";
    private static final String API_HOST = "social-download-all-in-one.p.rapidapi.com";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private RapidApiExtractor() {}

    static void resolve(String apiKey, String pageUrl, Callback callback) {
        new Thread(() -> {
            String mediaUrl = "";
            String mediaType = "وسائط";
            String message = "خدمة Black Hole لم تُرجع رابط وسائط مباشرًا.";
            try {
                String key = apiKey == null ? "" : apiKey.trim();
                if (key.isEmpty()) throw new IllegalArgumentException("مفتاح RapidAPI غير مضبوط");

                JSONObject payload = new JSONObject();
                payload.put("url", pageUrl);
                Request request = new Request.Builder()
                        .url(API_URL)
                        .post(RequestBody.create(payload.toString(), JSON))
                        .header("x-rapidapi-key", key)
                        .header("x-rapidapi-host", API_HOST)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .build();

                try (Response response = CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        message = "خدمة RapidAPI ردّت برمز HTTP " + response.code()
                                + ". تحقق من المفتاح والاشتراك وحدود الاستخدام.";
                    } else {
                        JSONObject result = new JSONObject(response.body().string());
                        JSONArray medias = result.optJSONArray("medias");
                        if (medias != null && medias.length() > 0) {
                            // Prefer video, then audio, then image; keep a valid fallback
                            // in case the service does not provide a type field.
                            JSONObject chosen = null;
                            for (int pass = 0; pass < 4 && chosen == null; pass++) {
                                for (int i = 0; i < medias.length(); i++) {
                                    JSONObject item = medias.optJSONObject(i);
                                    if (item == null) continue;
                                    String candidate = item.optString("url", "").trim();
                                    if (!isHttpUrl(candidate) || candidate.equals(pageUrl)) continue;
                                    String ext = item.optString("extension", "").toLowerCase(Locale.ROOT);
                                    String type = item.optString("type", "").toLowerCase(Locale.ROOT);
                                    boolean video = type.contains("video") || isVideoExtension(ext);
                                    boolean audio = type.contains("audio") || isAudioExtension(ext);
                                    boolean image = type.contains("image") || isImageExtension(ext);
                                    if ((pass == 0 && video) || (pass == 1 && audio)
                                            || (pass == 2 && image) || pass == 3) {
                                        chosen = item;
                                        break;
                                    }
                                }
                            }
                            if (chosen != null) {
                                mediaUrl = chosen.optString("url", "").trim();
                                String ext = chosen.optString("extension", "").toLowerCase(Locale.ROOT);
                                String type = chosen.optString("type", "").toLowerCase(Locale.ROOT);
                                if (type.contains("audio") || isAudioExtension(ext)) mediaType = "الصوت";
                                else if (type.contains("image") || isImageExtension(ext)) mediaType = "الصورة";
                                else mediaType = "الفيديو";
                                message = "تم استخراج رابط " + mediaType + " مباشر.";
                            } else {
                                message = "الاستجابة لم تحتوِ على رابط HTTP صالح للوسائط.";
                            }
                        } else {
                            message = "الخدمة لم تُرجع عناصر داخل medias. قد لا يكون الموقع مدعومًا.";
                        }
                    }
                }
            } catch (Exception e) {
                String detail = e.getMessage();
                message = "تعذّر استخراج الرابط عبر RapidAPI"
                        + (detail == null || detail.trim().isEmpty() ? "." : ": " + detail);
            }
            callback.onResult(mediaUrl, mediaType, message);
        }, "BlackHoleRapidApiExtractor").start();
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://"))
                && value.length() < 8192;
    }

    private static boolean isVideoExtension(String ext) {
        return ext.equals("mp4") || ext.equals("m4v") || ext.equals("webm")
                || ext.equals("mov") || ext.equals("m3u8") || ext.equals("mpd")
                || ext.equals("mkv") || ext.equals("avi");
    }

    private static boolean isAudioExtension(String ext) {
        return ext.equals("mp3") || ext.equals("m4a") || ext.equals("aac")
                || ext.equals("wav") || ext.equals("ogg") || ext.equals("opus")
                || ext.equals("flac");
    }

    private static boolean isImageExtension(String ext) {
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("webp") || ext.equals("gif") || ext.equals("bmp")
                || ext.equals("avif");
    }
}
