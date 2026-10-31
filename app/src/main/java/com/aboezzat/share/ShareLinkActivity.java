package com.aboezzat.share;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles Android shares without opening the Share UI. Runs the same configured
 * extraction engines as MainActivity, then sends the best direct result to Receiver.
 */
public class ShareLinkActivity extends Activity {
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<MediaCandidate> candidates = new ArrayList<>();
    private int pendingEngines;
    private String activeReceiverIp = "";
    private boolean selectedReceiver;
    private boolean extractionFinalized;
    private String originalSharedUrl = "";

    private final Runnable extractionTimeout = () -> finishExtraction(
            "انتهت مهلة محركات الاستخراج؛ هبعت الرابط الأصلي للريسيفر ليحاول تشغيله.");

    private final Runnable activityTimeout = () -> finishWithMessage(
            "لسه ماوصلتش للريسيفر. اتأكد إن AboEzzat Receiver مفتوح والجهازين على نفس شبكة Wi‑Fi.");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mainHandler.postDelayed(activityTimeout, 40000L);
        String url = extractUrl(getIntent());
        if (url == null) {
            finish();
            return;
        }
        originalSharedUrl = url;

        // Honor the IP explicitly selected in Share settings. A temporary failure
        // must not trigger discovery that silently switches to another receiver.
        String savedIp = getSharedPreferences("aboezzat_browser", MODE_PRIVATE)
                .getString("receiver_ip", "").trim();
        if (!savedIp.isEmpty()) {
            activeReceiverIp = savedIp;
            selectedReceiver = true;
            startConfiguredExtraction(url);
            return;
        }

        CastingManager.discover(getApplicationContext(), devices -> mainHandler.post(() -> {
            if (completed.get()) return;
            String receiverIp = (devices == null || devices.isEmpty()) ? null : devices.get(0);
            if (receiverIp == null) {
                finishWithMessage("مش لاقي AboEzzat Receiver؛ افتح Share واضغط إعداد IP لاختيار الجهاز، وتأكد إن الجهازين على نفس شبكة Wi‑Fi.");
                return;
            }
            activeReceiverIp = receiverIp;
            selectedReceiver = false;
            getSharedPreferences("aboezzat_browser", MODE_PRIVATE).edit()
                    .putString("receiver_ip", receiverIp).apply();
            startConfiguredExtraction(url);
        }));
    }

    /** Run all enabled local/remote extractors in parallel; RapidAPI is strictly optional. */
    private void startConfiguredExtraction(String pageUrl) {
        if (isYouTubeUrl(pageUrl)) {
            sendResolvedToReceiver(pageUrl, Collections.emptyMap(),
                    "رابط YouTube هيتبعت للريسيفر عشان يفتح SmartTube أو تطبيق YouTube.");
            return;
        }

        android.content.SharedPreferences prefs =
                getSharedPreferences("aboezzat_browser", MODE_PRIVATE);
        boolean useFast = isEngineEnabled(prefs, "fast");
        boolean useOkHttp = isEngineEnabled(prefs, "okhttp");
        String apiKey = prefs.getString("rapid_api_key", "").trim();
        boolean useRapid = isEngineEnabled(prefs, "rapidapi") && !apiKey.isEmpty();
        String apiBase = prefs.getString("extractor_api_base", "").trim();
        String engineToken = prefs.getString("social_engine_token", "").trim();
        boolean useSocial = isEngineEnabled(prefs, "socialengine")
                && !apiBase.isEmpty() && engineToken.length() >= 32;

        pendingEngines = (useFast ? 1 : 0) + (useOkHttp ? 1 : 0)
                + (useRapid ? 1 : 0) + (useSocial ? 1 : 0);
        if (pendingEngines == 0) {
            sendResolvedToReceiver(pageUrl, Collections.emptyMap(),
                    "لا يوجد محرك استخراج مفعّل؛ أرسلت الرابط الأصلي للريسيفر.");
            return;
        }

        mainHandler.postDelayed(extractionTimeout, 11000L);
        String cookies = "";
        try {
            CookieManager manager = CookieManager.getInstance();
            cookies = manager.getCookie(pageUrl);
        } catch (Exception ignored) { }
        final String pageCookies = cookies;

        if (useFast) {
            FastMediaExtractor.extractDirectMedia(pageUrl, pageCookies,
                    new FastMediaExtractor.ExtractorCallback() {
                @Override public void onSuccess(String mediaUrl, String platform) {
                    addCandidate(mediaUrl, "FastMediaExtractor / " + platform,
                            Collections.emptyMap());
                }
                @Override public void onError(String error) { engineFailed(); }
            });
        }
        if (useOkHttp) {
            OkHttpPageExtractor.extract(pageUrl, pageCookies, new OkHttpPageExtractor.Callback() {
                @Override public void onSuccess(String mediaUrl) {
                    addCandidate(mediaUrl, "OkHttp Page Parser", Collections.emptyMap());
                }
                @Override public void onError(String reason) { engineFailed(); }
            });
        }
        if (useRapid) {
            RapidApiExtractor.resolve(apiKey, pageUrl, (mediaUrl, mediaType, message) -> {
                if (mediaUrl != null && !mediaUrl.trim().isEmpty()
                        && BrowserEngine.isWebUrl(mediaUrl) && !mediaUrl.equals(pageUrl)) {
                    addCandidate(mediaUrl, "RapidAPI", Collections.emptyMap());
                } else {
                    engineFailed();
                }
            });
        }
        if (useSocial) {
            PlatformExtractor.resolve(apiBase, engineToken, pageUrl, pageCookies, (result, message) -> {
                if (result != null && result.url != null && !result.url.trim().isEmpty()
                        && BrowserEngine.isWebUrl(result.url) && !result.url.equals(pageUrl)) {
                    addCandidate(result.url, "AboEzzat Social Engine", result.headers);
                } else {
                    engineFailed();
                }
            });
        }
    }

    private boolean isEngineEnabled(android.content.SharedPreferences prefs, String key) {
        return prefs.getBoolean("engine_enabled_" + key, true);
    }

    private void addCandidate(String url, String engine, Map<String, String> headers) {
        mainHandler.post(() -> {
            if (completed.get() || extractionFinalized) return;
            if (url != null && BrowserEngine.isWebUrl(url) && !url.equals(originalSharedUrl)) {
                boolean duplicate = false;
                for (MediaCandidate candidate : candidates) {
                    if (candidate.url.equals(url)) { duplicate = true; break; }
                }
                if (!duplicate) candidates.add(new MediaCandidate(url, engine, headers));
            }
            engineFinished();
        });
    }

    private void engineFailed() {
        mainHandler.post(this::engineFinished);
    }

    private void engineFinished() {
        if (completed.get() || extractionFinalized) return;
        if (pendingEngines > 0) pendingEngines--;
        if (pendingEngines == 0) finishExtraction(null);
    }

    private void finishExtraction(String fallbackMessage) {
        if (completed.get() || extractionFinalized) return;
        extractionFinalized = true;
        mainHandler.removeCallbacks(extractionTimeout);
        if (!candidates.isEmpty()) {
            MediaCandidate best = candidates.get(0);
            for (MediaCandidate candidate : candidates) {
                if (qualityScore(candidate.url) > qualityScore(best.url)) best = candidate;
            }
            sendResolvedToReceiver(best.url, best.headers,
                    "تم استخراج رابط مباشر بواسطة " + best.engine + " من " + candidates.size() + " نتيجة.");
        } else {
            sendResolvedToReceiver(originalSharedUrl, Collections.emptyMap(),
                    fallbackMessage == null
                            ? "لم يعثر أي محرك على رابط مباشر؛ أرسلت الرابط الأصلي للريسيفر."
                            : fallbackMessage);
        }
    }

    private int qualityScore(String url) {
        if (url == null) return 0;
        Matcher matcher = Pattern.compile("(?i)(2160|1440|1080|720|540|480|360|240)p").matcher(url);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException ignored) { }
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*\\.(mp4|m4v|webm)(?:[?#].*)?$")) return 3;
        if (lower.contains(".m3u8") || lower.contains(".mpd")) return 2;
        return 1;
    }

    private void sendResolvedToReceiver(String url, Map<String, String> headers, String note) {
        if (completed.get()) return;
        CastingManager.Callback callback = (success, message) -> mainHandler.post(() -> {
            if (success) finishWithMessage(note + " تم إرسال الطلب إلى Receiver.");
            else if (selectedReceiver) {
                finishWithMessage("الجهاز المختار " + activeReceiverIp
                        + " لم يستجب. احتفظنا بعنوانه؛ افتح إعداد IP في Share لاختبار الاتصال أو اختيار جهاز آخر. "
                        + message);
            } else {
                finishWithMessage("ماقدرتش أبعت الوسائط للشاشة: " + message);
            }
        });
        if (headers != null && !headers.isEmpty()) {
            CastingManager.sendWithHeaders(activeReceiverIp, url, headers, callback);
        } else {
            CastingManager.send(activeReceiverIp, url, callback);
        }
    }

    private boolean isYouTubeUrl(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("youtu.be") || host.equals("youtube.com")
                    || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com")
                    || host.endsWith(".youtube-nocookie.com");
        } catch (Exception ignored) { return false; }
    }

    @Override
    protected void onDestroy() {
        completed.set(true);
        mainHandler.removeCallbacks(extractionTimeout);
        mainHandler.removeCallbacks(activityTimeout);
        super.onDestroy();
    }

    private String extractUrl(Intent intent) {
        if (intent == null) return null;
        String candidate = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            candidate = sharedText == null ? null : sharedText.toString();
        }
        if (candidate == null || candidate.trim().isEmpty()) return null;
        Matcher matcher = Pattern.compile("(?i)https?://[^\\s<>\\\"']+").matcher(candidate);
        if (!matcher.find()) return null;
        String url = matcher.group().replaceAll("[),.?!;،؛؟\\]}]+$", "");
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return null;
            return url;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void finishWithMessage(String message) {
        if (!completed.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(extractionTimeout);
            mainHandler.removeCallbacks(activityTimeout);
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private static final class MediaCandidate {
        final String url;
        final String engine;
        final Map<String, String> headers;
        MediaCandidate(String url, String engine, Map<String, String> headers) {
            this.url = url;
            this.engine = engine;
            this.headers = headers == null ? Collections.emptyMap()
                    : new LinkedHashMap<>(headers);
        }
    }
}
