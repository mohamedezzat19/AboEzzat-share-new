package com.aboezzat.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

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
    private boolean candidatePickerScheduled;
    private final Runnable candidatePickerRunnable = () -> showCandidatePicker();
    private android.webkit.WebView captureWebView;
    private BackgroundCaptureBridge backgroundCaptureBridge;
    private String originalSharedUrl = "";
    private boolean tikTokApiAttempted;

    // TikWM fast path from the Cast backup; existing extraction engines remain fallback.
    private final OkHttpClient tikTokClient = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

    private static final String SAFE_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private static final List<String> AD_DOMAINS = Arrays.asList(
            "doubleclick.net", "googleads", "pagead2", "adservice.google",
            "googletagmanager", "adcolony", "criteo", "adnxs", "outbrain",
            "taboola", "popads", "popcash", "propellerads", "adsterra",
            "exoclick", "smartadserver", "scorecardresearch",
            "zedo", "adroll", "pubmatic", "rubiconproject"
    );

    private final Runnable extractionTimeout = () -> {
        captureWindowClosed = true;
        captureWindowOpen = false;
        destroyBackgroundCapture();
        finishExtraction("انتهت مهلة الالتقاط السريع ولم يظهر رابط فيديو مباشر.");
    };

    private final Runnable captureScanRunnable = new Runnable() {
        @Override public void run() {
            android.webkit.WebView view = captureWebView;
            if (view == null || completed.get() || captureWindowClosed) return;
            scanBackgroundVideo(view);
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private final Runnable activityTimeout = () -> finishWithMessage(
            "انتهت المهلة المحددة للمشاركة. اتأكد إن AboEzzat Receiver مفتوح والجهازين على نفس شبكة Wi‑Fi.");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mainHandler.postDelayed(activityTimeout, 45000L);

        candidates.clear();
        extractionFinalized = false;

        String url = extractUrl(getIntent());
        if (url == null) {
            finish();
            return;
        }
        originalSharedUrl = url;

        android.content.SharedPreferences settings =
                getSharedPreferences("aboezzat_browser", MODE_PRIVATE);
        android.content.SharedPreferences legacyPrefs =
                getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);

        int port = settings.getInt("receiver_port", 8081);
        CastingManager.setReceiverPort(port);

        String savedIp = settings.getString("receiver_ip", "").trim();
        if (savedIp.isEmpty()) {
            savedIp = legacyPrefs.getString("tv_ip", "").trim();
        }

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
                finishWithMessage("مش لاقي AboEzzat Receiver؛ افتح التطبيق واضغط إعداد IP لاختيار الجهاز.");
                return;
            }
            activeReceiverIp = receiverIp;
            selectedReceiver = false;
            getSharedPreferences("aboezzat_browser", MODE_PRIVATE).edit()
                    .putString("receiver_ip", receiverIp).apply();
            getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE).edit()
                    .putString("tv_ip", receiverIp).apply();
            startConfiguredExtraction(url);
        }));
    }

    private boolean isWebpageUrl(String url) {
        if (url == null) return true;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("tiktok.com/@") || lower.contains("/live?") || lower.endsWith("/live")
                || lower.contains("instagram.com/p/") || lower.contains("instagram.com/reel/")
                || lower.contains("threads.net/@");
    }

    private void startConfiguredExtraction(String pageUrl) {
        if (isTikTokUrl(pageUrl) && !pageUrl.toLowerCase(Locale.ROOT).contains("/live")
                && !tikTokApiAttempted) {
            tikTokApiAttempted = true;
            extractTikTokViaTikWM(pageUrl);
            return;
        }
        if (isYouTubeUrl(pageUrl)) {
            sendResolvedToReceiver(pageUrl, Collections.emptyMap(),
                    "رابط YouTube؛ جاري الإرسال للتشغيل المباشر.");
            return;
        }

        if (MediaDetector.isDirectVideoUrl(pageUrl) && !isWebpageUrl(pageUrl)) {
            sendResolvedToReceiver(pageUrl, Collections.emptyMap(), "رابط فيديو مباشر، جاري الإرسال.");
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
                + (useRapid ? 1 : 0) + (useSocial ? 1 : 0) + 1;

        boolean useWebView = true;
        captureWindowOpen = true;
        captureWindowClosed = false;

        startBackgroundCapture(pageUrl);

        long extractionDeadline = useSocial ? 30000L : (useRapid ? 22000L : 14000L);
        mainHandler.postDelayed(extractionTimeout, extractionDeadline);

        String cookies = "";
        try {
            CookieManager manager = CookieManager.getInstance();
            cookies = manager.getCookie(pageUrl);
        } catch (Exception ignored) { }
        final String pageCookies = cookies;

        MediaDetector.inspectPage(this, pageUrl, (url, kind, quality) -> {
            addCandidate(url, "MediaDetector (" + kind + " " + quality + ")", Collections.emptyMap(), kind);
        });

        if (useFast) {
            FastMediaExtractor.extractDirectMedia(pageUrl, pageCookies,
                    new FastMediaExtractor.ExtractorCallback() {
                @Override public void onSuccess(String mediaUrl, String platform) {
                    addCandidate(mediaUrl, "FastMediaExtractor / " + platform,
                            Collections.emptyMap(), null);
                }
                @Override public void onError(String error) { engineFailed(); }
            });
        }

        if (useOkHttp) {
            OkHttpPageExtractor.extract(pageUrl, pageCookies, new OkHttpPageExtractor.Callback() {
                @Override public void onSuccess(String mediaUrl) {
                    addCandidate(mediaUrl, "OkHttp Page Parser", Collections.emptyMap(), null);
                }
                @Override public void onError(String reason) { engineFailed(); }
            });
        }

        if (useRapid) {
            RapidApiExtractor.resolve(apiKey, pageUrl, (mediaUrl, mediaType, message) -> {
                if (mediaUrl != null && !mediaUrl.trim().isEmpty()
                        && BrowserEngine.isWebUrl(mediaUrl) && !mediaUrl.equals(pageUrl)) {
                    addCandidate(mediaUrl, "RapidAPI", Collections.emptyMap(),
                            "الفيديو".equals(mediaType) ? "video" : null);
                } else {
                    engineFailed();
                }
            });
        }

        if (useSocial) {
            PlatformExtractor.resolve(apiBase, engineToken, pageUrl, pageCookies, (result, message) -> {
                if (result != null && result.url != null && !result.url.trim().isEmpty()
                        && BrowserEngine.isWebUrl(result.url) && !result.url.equals(pageUrl)) {
                    addCandidate(result.url, "AboEzzat Social Engine", result.headers, result.type);
                } else {
                    engineFailed();
                }
            });
        }
    }

    private void extractTikTokViaTikWM(String pageUrl) {
        try {
            String apiUrl = "https://www.tikwm.com/api/?url="
                    + URLEncoder.encode(pageUrl, "UTF-8");
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", SAFE_MOBILE_UA)
                    .build();

            tikTokClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    mainHandler.post(() -> startConfiguredExtraction(pageUrl));
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    String mediaUrl = null;
                    try (Response safeResponse = response) {
                        if (safeResponse.isSuccessful() && safeResponse.body() != null) {
                            JSONObject json = new JSONObject(safeResponse.body().string());
                            if (json.optInt("code", -1) == 0) {
                                JSONObject data = json.optJSONObject("data");
                                if (data != null) mediaUrl = data.optString("play", "");
                            }
                        }
                    } catch (Exception ignored) { }

                    final String result = mediaUrl;
                    mainHandler.post(() -> {
                        if (completed.get()) return;
                        if (result != null && !result.trim().isEmpty()) {
                            String direct = result.trim();
                            if (!direct.startsWith("http")) direct = "https://www.tikwm.com" + direct;
                            Map<String, String> headers = new LinkedHashMap<>();
                            headers.put("referer", "https://www.tiktok.com/");
                            headers.put("origin", "https://www.tiktok.com");
                            headers.put("user-agent", SAFE_MOBILE_UA);
                            sendResolvedToReceiver(direct, headers,
                                    "تم استخراج رابط TikTok عبر TikWM وإرساله.");
                        } else {
                            startConfiguredExtraction(pageUrl);
                        }
                    });
                }
            });
        } catch (Exception error) {
            startConfiguredExtraction(pageUrl);
        }
    }

    private boolean isEngineEnabled(android.content.SharedPreferences prefs, String key) {
        return prefs.getBoolean("engine_enabled_" + key, true);
    }

    private boolean isDeclaredVideoFormat(String value) {
        if (value == null) return false;
        String type = value.trim().toLowerCase(Locale.ROOT);
        // A generic "video" label is not enough to trust an arbitrary URL:
        // WebView may report a page/preview URL. Require a concrete format or MIME.
        return type.equals("mp4") || type.equals("hls")
                || type.equals("dash") || type.equals("flv") || type.equals("live-flv")
                || type.equals("video/mp4")
                || type.equals("application/vnd.apple.mpegurl")
                || type.equals("application/x-mpegurl")
                || type.equals("application/dash+xml");
    }

    private boolean isAudioUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav)(?:[?#].*)?$");
    }

    private boolean isImageUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|avif)(?:[?#].*)?$");
    }

    private boolean isDeclaredAudioFormat(String value) {
        if (value == null) return false;
        String type = value.toLowerCase(Locale.ROOT);
        return type.contains("audio") || type.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav).*");
    }

    private void addCandidate(String rawUrl, String engine, Map<String, String> headers, String declaredType) {
        mainHandler.post(() -> {
            if (completed.get() || extractionFinalized) return;
            String url = MediaDetector.cleanMediaUrl(rawUrl);
            if (url == null || !BrowserEngine.isWebUrl(url) || url.equals(originalSharedUrl) || isWebpageUrl(url)
                    || isImageUrl(url)
                    || (!MediaDetector.isDirectVideoUrl(url) && !isDeclaredVideoFormat(declaredType)
                    && !isDeclaredAudioFormat(declaredType))) {
                engineFinished();
                return;
            }

            for (MediaCandidate existing : candidates) {
                if (existing.url.equals(url)) {
                    // Engine callback still needs to be counted even when another engine
                    // already discovered the same URL, otherwise extraction waits for timeout.
                    engineFinished();
                    return;
                }
            }
            String kind = isDeclaredAudioFormat(declaredType) || isAudioUrl(url) ? "صوت فقط" : "فيديو";
            MediaCandidate candidate = new MediaCandidate(url, engine, headers, kind);

            // Facebook and TikTok use the original one-tap cast flow:
            // send the first valid direct media URL immediately, without
            // opening the quality/candidate dialog.
            if (isOneTapCastPlatform() && "فيديو".equals(kind)) {
                candidates.add(candidate);
                extractionFinalized = true;
                captureWindowClosed = true;
                captureWindowOpen = false;
                mainHandler.removeCallbacks(extractionTimeout);
                mainHandler.removeCallbacks(candidatePickerRunnable);
                destroyBackgroundCapture();
                sendResolvedToReceiver(candidate.url, candidate.headers,
                        "تم إرسال فيديو " + (isFacebookUrl(originalSharedUrl) ? "Facebook" : "TikTok") + " مباشرة.");
                return;
            }

            candidates.add(candidate);
            // Instagram / Threads / X can report additional variants several
            // seconds later, so keep collecting until the extraction deadline.
            // Other platforms retain the short picker window.
            if (!requiresFullCandidateWindow()) {
                mainHandler.postDelayed(candidatePickerRunnable, 3500L);
            }
        });
    }

    // Backup Cast ShareActivity behavior: send the first valid direct media URL
    // immediately for social share intents, without opening a quality picker.
    // Keep YouTube's dedicated routing and TikTok's TikWM fast path unchanged.
    private boolean isOneTapCastPlatform() {
        String lower = originalSharedUrl == null ? "" : originalSharedUrl.toLowerCase(Locale.ROOT);
        return isFacebookUrl(lower) || isTikTokUrl(lower)
                || lower.contains("instagram.com/")
                || lower.contains("threads.net/") || lower.contains("threads.com/")
                || lower.contains("x.com/") || lower.contains("twitter.com/");
    }

    private boolean isFacebookUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("facebook.com/") || lower.contains("fb.watch/")
                || lower.contains("fb.com/");
    }

    private boolean isTikTokUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("tiktok.com/") || lower.contains("vm.tiktok.com/")
                || lower.contains("vt.tiktok.com/");
    }

    private boolean requiresFullCandidateWindow() {
        String lower = originalSharedUrl == null ? "" : originalSharedUrl.toLowerCase(Locale.ROOT);
        return lower.contains("instagram.com/")
                || lower.contains("threads.net/") || lower.contains("threads.com/")
                || lower.contains("x.com/") || lower.contains("twitter.com/");
    }

    private void showCandidatePicker() {
        mainHandler.removeCallbacks(candidatePickerRunnable);
        if (completed.get() || extractionFinalized || candidates.isEmpty()) return;
        extractionFinalized = true;
        captureWindowClosed = true;
        captureWindowOpen = false;
        mainHandler.removeCallbacks(extractionTimeout);
        destroyBackgroundCapture();

        String[] labels = new String[candidates.size()];
        boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            MediaCandidate item = candidates.get(i);
            String shortUrl = item.url.length() > 65 ? item.url.substring(0, 62) + "…" : item.url;
            labels[i] = (item.kind.equals("صوت فقط") ? "🎵 صوت فقط" : "▶ فيديو")
                    + "  •  " + item.engine + "\n" + shortUrl;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("اختار الفيديو أو الصوت")
                .setMessage("حدد العناصر اللي عايز تبعتها للشاشة.")
                .setMultiChoiceItems(labels, checked, (d, which, selected) -> checked[which] = selected)
                .setNegativeButton("إلغاء", (d, which) -> finishWithMessage("تم إلغاء المشاركة."))
                .setPositiveButton("إرسال المحدد", (d, which) -> {
                    List<MediaCandidate> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(candidates.get(i));
                    if (selected.isEmpty()) {
                        finishWithMessage("ما اخترتش أي رابط.");
                        return;
                    }
                    sendSelectedCandidates(selected);
                }).create();
        dialog.setOnCancelListener(d -> finishWithMessage("تم إلغاء المشاركة."));
        dialog.show();
    }

    private void sendSelectedCandidates(List<MediaCandidate> selected) {
        MediaCandidate video = null, audio = null;
        for (MediaCandidate item : selected) {
            boolean isAudio = item.kind.equals("صوت فقط");
            if (isAudio && audio == null) audio = item;
            else if (!isAudio && video == null) video = item;
        }
        if (video != null && audio != null) {
            CastingManager.sendTracks(activeReceiverIp, video.url, audio.url, (success, message) -> mainHandler.post(() -> {
                if (success) finishWithMessage("تم إرسال الفيديو والصوت معًا.");
                else finishWithMessage("تعذر تشغيل الفيديو والصوت معًا: " + message);
            }));
        } else {
            // If the user selected a mix of audio/video but Receiver cannot pair
            // them (for example, more than one audio track), never accidentally
            // send an audio URL as the main video. Prefer the first video candidate.
            MediaCandidate first = video != null ? video : audio;
            if (first == null) first = selected.get(0);
            sendResolvedToReceiver(first.url, first.headers,
                    selected.size() > 1 ? "تم إرسال أول فيديو محدد؛ تشغيل عدة عناصر يحتاج دعم قائمة تشغيل." : "تم إرسال العنصر المحدد.");
        }
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
        mainHandler.removeCallbacks(extractionTimeout);
        destroyBackgroundCapture();
        if (!candidates.isEmpty()) {
            // showCandidatePicker() owns finalization. Do not set extractionFinalized
            // here, because its guard would otherwise suppress the picker.
            showCandidatePicker();
        } else {
            String reason = fallbackMessage == null
                    ? "لم يتم العثور على رابط فيديو مباشر صالح."
                    : fallbackMessage;
            finishWithMessage(reason);
        }
    }

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
            settings.setUserAgentString(SAFE_MOBILE_UA);

            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(view, true);

            backgroundCaptureBridge = new BackgroundCaptureBridge();
            view.addJavascriptInterface(backgroundCaptureBridge, "AboEzzatShareCapture");
            view.setBackgroundColor(android.graphics.Color.BLACK);
            view.setAlpha(0.01f);
            view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(
                        android.webkit.WebView webView,
                        android.webkit.WebResourceRequest request) {
                    String resourceUrl = request.getUrl() == null ? "" : request.getUrl().toString();
                    String lower = resourceUrl.toLowerCase(Locale.ROOT);

                    for (String ad : AD_DOMAINS) {
                        if (lower.contains(ad)) {
                            return new android.webkit.WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
                        }
                    }

                    boolean junk = lower.contains(".ts") || lower.contains("seg-")
                            || lower.contains("fragment") || lower.contains(".js") || lower.contains(".css")
                            || lower.contains("preview") || lower.contains("google_ads")
                            || lower.contains("rsrc.php") || lower.contains("ad-interrupting")
                            || (lower.contains("twimg.com") && (lower.contains("/audio/") || lower.contains(".m4a")));

                    // صيد مباشر لروابط بث تيك توك الحي الحقيقية
                    boolean isTikTokLiveStream = lower.contains("tiktokcdn.com")
                            && (lower.contains("pull-flv") || lower.contains("pull-hls") || lower.contains(".flv") || lower.contains(".m3u8"))
                            && (lower.contains("sign=") || lower.contains("_session_id="));

                    boolean isDirect = isTikTokLiveStream
                            || (lower.contains(".mp4") && !lower.contains("bytestart="))
                            || lower.contains(".m3u8") || lower.contains("pull-flv") || lower.contains("pull-hls")
                            || lower.contains("video_playback") || lower.contains("&mime=video")
                            || (lower.contains("cdninstagram.com") && lower.contains(".mp4"));

                    if (!junk && isDirect && BrowserEngine.isWebUrl(resourceUrl) && !isWebpageUrl(resourceUrl)) {
                        String clean = MediaDetector.cleanMediaUrl(resourceUrl);
                        BackgroundCaptureBridge bridge = backgroundCaptureBridge;
                        if (bridge != null) bridge.onNetworkMediaFound(clean);
                    }
                    return super.shouldInterceptRequest(webView, request);
                }

                @Override
                public void onPageFinished(android.webkit.WebView webView, String loadedUrl) {
                    super.onPageFinished(webView, loadedUrl);
                    if (webView != captureWebView || captureWindowClosed || completed.get()) return;
                    installBackgroundVideoHook(webView);
                    scanBackgroundVideo(webView);

                    // تحفيز تشغيل البث في صفحات تيك توك وإنستجرام
                    String triggerPlayJs = "(function(){try{" +
                            "  var v = document.querySelector('video');" +
                            "  if(v){ v.muted=true; v.play(); }" +
                            "  var btn = document.querySelector('.xgplayer-play, [data-e2e=\"live-play-btn\"], .play-btn, svg[class*=\"play\"]');" +
                            "  if(btn){ btn.click(); }" +
                            "}catch(e){}})();";
                    webView.evaluateJavascript(triggerPlayJs, null);
                }
            });

            root.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
            view.loadUrl(pageUrl);
            mainHandler.removeCallbacks(captureScanRunnable);
            mainHandler.postDelayed(captureScanRunnable, 1000L);
        } catch (Exception ignored) {
            captureWindowOpen = false;
            captureWindowClosed = true;
            if (pendingEngines == 0) mainHandler.post(() -> finishExtraction(
                    "تعذّر تشغيل الالتقاط الخلفي."));
        }
    }

    private void installBackgroundVideoHook(android.webkit.WebView view) {
        String js = "(function(){try{if(window.__aboezzatShareHook)return;window.__aboezzatShareHook=true;"
                + "function scan(){var vs=document.querySelectorAll(\"video\");"
                + "for(var i=0;i<vs.length;i++){var v=vs[i];try{v.muted=true;v.play();}catch(e){}"
                + "var u=v.currentSrc||v.src;if(u&&/^https?:/i.test(u)&&!/\\/(?:live|@)/i.test(u)&&window.AboEzzatShareCapture){"
                + "window.AboEzzatShareCapture.onActiveVideoFound(u);return;}}}"
                + "document.addEventListener(\"play\",function(e){scan();},true);"
                + "document.addEventListener(\"loadeddata\",scan,true);scan();}catch(e){}})();";
        try { view.evaluateJavascript(js, null); } catch (Exception ignored) { }
    }

    private void scanBackgroundVideo(android.webkit.WebView view) {
        if (view == null || view != captureWebView || captureWindowClosed || completed.get()) return;
        String js = "(function(){try{"
                + "var vs=document.querySelectorAll(\"video\");"
                + "for(var i=0;i<vs.length;i++){"
                + "var u=vs[i].currentSrc||vs[i].src;"
                + "if(u&&/^https?:/i.test(u)&&!/\\/(?:live|@)/i.test(u)){window.AboEzzatShareCapture.onActiveVideoFound(u);return;}"
                + "}"
                + "}catch(e){}})();";
        try { view.evaluateJavascript(js, null); } catch (Exception ignored) { }
    }

    private final class BackgroundCaptureBridge {
        void onNetworkMediaFound(String url) {
            if (url == null || url.isEmpty()) return;
            mainHandler.post(() -> acceptCapturedMedia(url,
                    "تم التقاط تدفق البث وإرساله للشاشة."));
        }

        @android.webkit.JavascriptInterface
        public void onActiveVideoFound(String url) {
            mainHandler.post(() -> acceptCapturedMedia(url,
                    "تم التقاط الفيديو النشط وإرساله مباشرة للشاشة."));
        }
    }

    private void acceptCapturedMedia(String rawUrl, String note) {
        if (completed.get() || captureWindowClosed || extractionFinalized) return;
        String url = MediaDetector.cleanMediaUrl(rawUrl);
        if (!BrowserEngine.isWebUrl(url) || url.equals(originalSharedUrl) || isWebpageUrl(url)) return;
        String lower = url.toLowerCase(Locale.ROOT);
        String declared = lower.matches(".*\\.(mp3|m4a|aac|ogg|opus|wav)(?:[?#].*)?$") ? "audio" : "video";
        addCandidate(url, "WebView capture", Collections.emptyMap(), declared);
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
        String lower = url.toLowerCase(Locale.ROOT);

        if (lower.contains(".m3u8") || lower.contains("/live/") || lower.contains("pull-hls")) return 5000;
        if (lower.contains(".flv") || lower.contains("pull-flv")) return 4500;
        if (lower.contains(".mpd")) return 4000;

        Matcher matcher = Pattern.compile("(?i)(2160|1440|1080|720|540|480|360|240)p").matcher(url);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException ignored) { }
        }

        if (lower.matches(".*\\.(mp4|m4v|webm)(?:[?#].*)?$")) return 720;
        return 1;
    }

    private void sendResolvedToReceiver(String rawUrl, Map<String, String> headers, String note) {
        if (completed.get()) return;
        final String url = MediaDetector.cleanMediaUrl(rawUrl);

        CastingManager.Callback callback = (success, message) -> mainHandler.post(() -> {
            if (success) {
                finishWithMessage(note + " Receiver استلم طلب التشغيل. تأكيد تشغيل الفيديو نفسه غير متاح من رد الشبكة.");
            } else if (selectedReceiver) {
                finishWithMessage("الجهاز المختار " + activeReceiverIp + " لم يستجب: " + message);
            } else {
                finishWithMessage("تعذر الإرسال للشاشة: " + message);
            }
        });

        Map<String, String> playbackHeaders = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    playbackHeaders.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }

        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("tiktokcdn.com") || lower.contains("byteoversea") || originalSharedUrl.contains("tiktok.com")) {
            playbackHeaders.put("referer", "https://www.tiktok.com/");
            playbackHeaders.put("origin", "https://www.tiktok.com");
            playbackHeaders.put("user-agent", SAFE_MOBILE_UA);
        } else if (lower.contains("cdninstagram.com") || originalSharedUrl.contains("instagram.com")) {
            playbackHeaders.put("referer", "https://www.instagram.com/");
            playbackHeaders.put("origin", "https://www.instagram.com");
            playbackHeaders.put("user-agent", SAFE_MOBILE_UA);
        } else if (originalSharedUrl.contains("threads.net")) {
            playbackHeaders.put("referer", "https://www.threads.net/");
            playbackHeaders.put("origin", "https://www.threads.net");
            playbackHeaders.put("user-agent", SAFE_MOBILE_UA);
        } else if (originalSharedUrl.contains("x.com") || originalSharedUrl.contains("twitter.com")
                || lower.contains("twimg.com") || lower.contains("twitter.com")) {
            playbackHeaders.put("referer", "https://x.com/");
            playbackHeaders.put("origin", "https://x.com");
            playbackHeaders.put("user-agent", SAFE_MOBILE_UA);
        } else {
            playbackHeaders.putIfAbsent("user-agent", SAFE_MOBILE_UA);
            playbackHeaders.putIfAbsent("referer", originalSharedUrl);
        }

        playbackHeaders.putIfAbsent("accept", "*/*");
        try {
            String mediaCookies = CookieManager.getInstance().getCookie(url);
            if (mediaCookies == null || mediaCookies.trim().isEmpty()) {
                mediaCookies = CookieManager.getInstance().getCookie(originalSharedUrl);
            }
            if (mediaCookies != null && !mediaCookies.trim().isEmpty()) {
                playbackHeaders.put("cookie", mediaCookies);
            }
        } catch (Exception ignored) { }

        CastingManager.sendWithHeaders(activeReceiverIp, url, playbackHeaders, callback);
    }

    private boolean isYouTubeUrl(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
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
        mainHandler.removeCallbacks(candidatePickerRunnable);
        destroyBackgroundCapture();
        super.onDestroy();
    }

    private String extractUrl(Intent intent) {
        if (intent == null) return null;
        StringBuilder sharedContent = new StringBuilder();

        try {
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                CharSequence sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) sharedContent.append(sharedText).append(' ');
            }
        } catch (Exception ignored) { }

        try {
            android.content.ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    android.content.ClipData.Item item = clip.getItemAt(i);
                    if (item == null) continue;
                    CharSequence text = item.getText();
                    if (text != null) sharedContent.append(text).append(' ');
                    Uri itemUri = item.getUri();
                    if (itemUri != null) sharedContent.append(itemUri.toString()).append(' ');
                }
            }
        } catch (Exception ignored) { }

        try {
            Object stream = intent.getExtras() == null ? null
                    : intent.getExtras().get(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) sharedContent.append(stream.toString()).append(' ');
            else if (stream instanceof CharSequence) sharedContent.append(stream).append(' ');
        } catch (Exception ignored) {}

        try {
            Uri data = intent.getData();
            if (data != null) sharedContent.append(data.toString()).append(' ');
        } catch (Exception ignored) { }

        Matcher matcher = Pattern.compile("(?i)https?://[^\\s<>\\\"']+")
                .matcher(sharedContent.toString());
        while (matcher.find()) {
            String url = matcher.group()
                    .replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&")
                    .replace("&quot;", "").replace("&#39;", "")
                    .replaceAll("[),.?!;،؛؟\\]}]+$", "");
            try {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if (uri.getHost() != null
                        && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                    return url;
                }
            } catch (Exception ignored) { }
        }
        return null;
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
        if (android.os.Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(5000L);
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
        final String kind;
        final Map<String, String> headers;
        MediaCandidate(String url, String engine, Map<String, String> headers, String kind) {
            this.url = url;
            this.engine = engine;
            this.kind = kind == null ? "فيديو" : kind;
            this.headers = headers == null ? Collections.emptyMap()
                    : new LinkedHashMap<>(headers);
        }
    }
}
