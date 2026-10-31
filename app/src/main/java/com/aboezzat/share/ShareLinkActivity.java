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
    private static final String NOTIFICATION_CHANNEL = "aboezzat_extraction_progress";
    private static final int NOTIFICATION_ID = 4708;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<MediaCandidate> candidates = new ArrayList<>();
    private int pendingEngines;
    private String activeReceiverIp = "";
    private boolean selectedReceiver;
    private boolean extractionFinalized;
    private boolean captureWindowOpen;
    private boolean captureWindowClosed = true;
    private android.webkit.WebView captureWebView;
    private BackgroundCaptureBridge backgroundCaptureBridge;
    private String originalSharedUrl = "";

    private final Runnable extractionTimeout = () -> {
        captureWindowClosed = true;
        destroyBackgroundCapture();
        finishExtraction("انتهت مهلة الالتقاط والمحركات؛ هبعت الرابط الأصلي للريسيفر.");
    };
    private final Runnable captureScanRunnable = new Runnable() {
        @Override public void run() {
            android.webkit.WebView view = captureWebView;
            if (view == null || completed.get() || captureWindowClosed) return;
            scanBackgroundVideo(view);
            mainHandler.postDelayed(this, 1200L);
        }
    };

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
        boolean useWebView = isEngineEnabled(prefs, "webview");
        captureWindowOpen = useWebView;
        captureWindowClosed = !useWebView;
        if (useWebView) startBackgroundCapture(pageUrl);
        if (pendingEngines == 0 && !useWebView) {
            finishWithMessage("لا يوجد محرك استخراج مفعّل. لم يتم إرسال صفحة الموقع إلى Receiver لأنها ليست رابط فيديو مباشر.");
            return;
        }

        mainHandler.postDelayed(extractionTimeout, useWebView ? 22000L : 11000L);
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
        if (captureWindowOpen && !captureWindowClosed) return;
        extractionFinalized = true;
        mainHandler.removeCallbacks(extractionTimeout);
        destroyBackgroundCapture();
        if (!candidates.isEmpty()) {
            MediaCandidate best = candidates.get(0);
            for (MediaCandidate candidate : candidates) {
                if (qualityScore(candidate.url) > qualityScore(best.url)) best = candidate;
            }
            sendResolvedToReceiver(best.url, best.headers,
                    "تم استخراج رابط مباشر بواسطة " + best.engine + " من " + candidates.size() + " نتيجة.");
        } else {
            String reason = fallbackMessage == null
                    ? "لم يعثر أي محرك على رابط فيديو مباشر."
                    : fallbackMessage;
            finishWithMessage(reason + " لم نرسل رابط صفحة الموقع إلى Receiver حتى لا يظهر كأنه فيديو صالح.");
        }
    }


    /** Hidden WebView fallback mirrors the legacy browser capture without opening Share UI. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void startBackgroundCapture(String pageUrl) {
        try {
            android.widget.FrameLayout root = new android.widget.FrameLayout(this);
            root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            setContentView(root);
            android.webkit.WebView view = new android.webkit.WebView(this);
            captureWebView = view;
            BrowserEngine.configure(view);
            android.webkit.WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(view, true);
            backgroundCaptureBridge = new BackgroundCaptureBridge();
            view.addJavascriptInterface(backgroundCaptureBridge, "AboEzzatShareCapture");
            view.setBackgroundColor(android.graphics.Color.BLACK);
            view.setAlpha(0.01f);
            view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.setWebChromeClient(new android.webkit.WebChromeClient());
            view.setWebViewClient(new android.webkit.WebViewClient() {
                /**
                 * Legacy AboEzzat Cast capture path: inspect actual network resources.
                 * Social sites often expose the media request before currentSrc is readable.
                 * This callback runs off the UI thread; the bridge posts accepted URLs to main.
                 */
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                        android.webkit.WebView webView,
                        android.webkit.WebResourceRequest request) {
                    String resourceUrl = request.getUrl() == null ? "" : request.getUrl().toString();
                    String lower = resourceUrl.toLowerCase(java.util.Locale.ROOT);
                    boolean junk = lower.contains(".ts") || lower.contains("seg-")
                            || lower.contains("preview") || lower.contains("google_ads")
                            || lower.contains("ad-interrupting");
                    boolean media = lower.contains(".mp4") || lower.contains(".m3u8")
                            || lower.contains(".mpd") || lower.contains(".webm")
                            || lower.contains(".flv") || lower.contains("pull-")
                            || lower.contains("mime=video") || lower.contains("video_playback")
                            || lower.contains("/videoplayback")
                            || lower.contains("/ext_tw_video/")
                            || lower.contains("/amplify_video/");
                    if (!junk && media && BrowserEngine.isWebUrl(resourceUrl)) {
                        BackgroundCaptureBridge bridge = backgroundCaptureBridge;
                        if (bridge != null) bridge.onNetworkMediaFound(resourceUrl);
                    }
                    return super.shouldInterceptRequest(webView, request);
                }

                @Override public void onPageFinished(android.webkit.WebView webView, String loadedUrl) {
                    super.onPageFinished(webView, loadedUrl);
                    if (webView != captureWebView || captureWindowClosed || completed.get()) return;
                    installBackgroundVideoHook(webView);
                    scanBackgroundVideo(webView);
                }
            });
            root.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
            view.loadUrl(pageUrl);
            mainHandler.removeCallbacks(captureScanRunnable);
            mainHandler.postDelayed(captureScanRunnable, 1500L);
        } catch (Exception ignored) {
            captureWindowOpen = false;
            captureWindowClosed = true;
            if (pendingEngines == 0) mainHandler.post(() -> finishExtraction(
                    "تعذّر تشغيل الالتقاط الخلفي؛ هبعت الرابط الأصلي للريسيفر."));
        }
    }

    private void installBackgroundVideoHook(android.webkit.WebView view) {
        String js = "(function(){try{if(window.__aboezzatShareHook)return;window.__aboezzatShareHook=true;"
                + "function scan(){var vs=document.querySelectorAll(\"video\");var best=null,area=0;"
                + "for(var i=0;i<vs.length;i++){var v=vs[i],r=v.getBoundingClientRect();"
                + "var visible=r.width>80&&r.height>60&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;"
                + "if(visible&&r.width*r.height>area){best=v;area=r.width*r.height;}}"
                + "if(best){best.muted=true;if(best.paused){var p=best.play();if(p&&p.catch)p.catch(function(){});}}}"
                + "document.addEventListener(\"play\",function(e){if(e.target&&e.target.tagName===\"VIDEO\")scan();},true);"
                + "document.addEventListener(\"loadeddata\",scan,true);scan();}catch(e){}})();";
        try { view.evaluateJavascript(js, null); } catch (Exception ignored) { }
    }

    private void scanBackgroundVideo(android.webkit.WebView view) {
        if (view == null || view != captureWebView || captureWindowClosed || completed.get()) return;
        String js = "(function(){try{var vs=document.querySelectorAll(\"video\"),best=null,area=0;"
                + "for(var i=0;i<vs.length;i++){var v=vs[i],r=v.getBoundingClientRect();"
                + "var visible=r.width>80&&r.height>60&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;"
                + "var ad=!!v.closest(\"[data-ad-preview],[class*=ad-],[id*=ad-]\");"
                + "var active=!v.paused&&!v.ended&&v.currentTime>0&&v.readyState>=2;"
                + "if(visible&&!ad&&active&&r.width*r.height>area){best=v;area=r.width*r.height;}}"
                + "if(best){var u=best.currentSrc||best.src||\"\";if(!/^https?:/i.test(u)){"
                + "var es=performance.getEntriesByType(\"resource\"),now=performance.now();"
                + "for(var j=es.length-1;j>=0;j--){var x=es[j].name||\"\";"
                + "if(now-es[j].startTime<90000&&/^https?:/i.test(x)&&"
                + "/\\.(mp4|m3u8|mpd|webm)(?:[?#]|$)|video_playback|\\/videoplayback|\\/ext_tw_video\\/|\\/amplify_video\\//i.test(x)){u=x;break;}}}"
                + "if(/^https?:/i.test(u)&&window.AboEzzatShareCapture)window.AboEzzatShareCapture.onActiveVideoFound(u);}"
                + "}catch(e){}})();";
        try { view.evaluateJavascript(js, null); } catch (Exception ignored) { }
    }

    private final class BackgroundCaptureBridge {
        void onNetworkMediaFound(String url) {
            if (url == null || url.isEmpty()) return;
            mainHandler.post(() -> acceptCapturedMedia(url,
                    "تم التقاط طلب ملف الفيديو من شبكة الموقع."));
        }

        @android.webkit.JavascriptInterface
        public void onActiveVideoFound(String url) {
            mainHandler.post(() -> acceptCapturedMedia(url,
                    "تم التقاط الفيديو النشط من صفحة المشاركة وإرساله إلى Receiver."));
        }
    }

    private void acceptCapturedMedia(String url, String note) {
        if (completed.get() || captureWindowClosed || !BrowserEngine.isWebUrl(url)
                || url.equals(originalSharedUrl)) return;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mpd")
                || lower.contains(".webm") || lower.contains(".flv") || lower.contains("pull-")
                || lower.contains("mime=video") || lower.contains("video_playback")
                || lower.contains("/videoplayback") || lower.contains("/ext_tw_video/")
                || lower.contains("/amplify_video/"))) return;
        captureWindowClosed = true;
        captureWindowOpen = false;
        extractionFinalized = true;
        mainHandler.removeCallbacks(extractionTimeout);
        destroyBackgroundCapture();
        sendResolvedToReceiver(url, Collections.emptyMap(), note);
    }

    private void destroyBackgroundCapture() {
        mainHandler.removeCallbacks(captureScanRunnable);
        android.webkit.WebView old = captureWebView;
        captureWebView = null;
        if (old != null) {
            try { old.stopLoading(); } catch (Exception ignored) { }
            try { old.removeJavascriptInterface("AboEzzatShareCapture"); } catch (Exception ignored) { }
            try { old.destroy(); } catch (Exception ignored) { }
        }
        backgroundCaptureBridge = null;
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
        destroyBackgroundCapture();
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

    private void showResultNotification(String message) {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    NOTIFICATION_CHANNEL, "استخراج الفيديو", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
                : new android.app.Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AboEzzat Share")
                .setContentText(message)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(message))
                .setCategory(android.app.Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setAutoCancel(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(6000L);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private void finishWithMessage(String message) {
        if (!completed.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(extractionTimeout);
            mainHandler.removeCallbacks(activityTimeout);
            captureWindowClosed = true;
            destroyBackgroundCapture();
            showResultNotification(message);
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
