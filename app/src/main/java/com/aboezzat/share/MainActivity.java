package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.animation.AlphaAnimation;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 13, 29);
    private static final int PANEL = Color.rgb(17, 27, 49);
    private static final int PANEL2 = Color.rgb(24, 37, 64);
    private static final int WHITE = Color.rgb(244, 247, 255);
    private static final int MUTED = Color.rgb(157, 174, 203);
    private static final int BLUE = Color.rgb(86, 132, 255);
    private static final int GOLD = Color.rgb(255, 196, 87);
    private static final String PREFS = "aboezzat_browser";
    private static final int PICK_IMAGE_REQUEST = 901;
    private static final int PICK_BROWSER_FILE_REQUEST = 903;
    private static final int CREATE_BACKUP_REQUEST = 905;
    private static final int RESTORE_BACKUP_REQUEST = 906;
    private ValueCallback<Uri[]> browserFileCallback;
    private View fullscreenVideoView;
    private WebChromeClient.CustomViewCallback fullscreenVideoCallback;
    private int orientationBeforeFullscreen = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private static final int REQUEST_MICROPHONE = 904;
    private static final int REQUEST_NOTIFICATIONS = 907;
    private static final int REQUEST_NATIVE_VOICE = 908;
    private android.speech.SpeechRecognizer nativeSpeechRecognizer;
    private EditText pendingVoiceTarget;
    private static final String EXTRACTION_CHANNEL = "aboezzat_extraction_progress";
    private static final int EXTRACTION_NOTIFICATION_ID = 4708;
    private volatile boolean extractionTracking = false;
    private android.webkit.PermissionRequest pendingWebPermissionRequest;
    private LinearLayout root, header, content, bottom, browserTools, mediaPanel;
    private FrameLayout contentFrame, shellOverlay;
    private LinearLayout drawerPanel;
    private View drawerScrim;
    private float drawerTouchStartX, drawerTouchStartY;
    private boolean drawerGestureTracking = false;
    private EditText address;
    private WebView web;
    private final ArrayList<WebView> browserTabs = new ArrayList<>();
    private final ArrayList<String> browserTabTitles = new ArrayList<>();
    private int activeBrowserTab = -1;
    private TextView pageTitle, connectionLabel, mediaLabel, castActionButton;
    private final LinkedHashMap<String,String> media = new LinkedHashMap<>();
    private final ArrayList<String> mediaOrder = new ArrayList<>();
    private final LinkedHashMap<String, Boolean> selectedTrackUrls = new LinkedHashMap<>();
    private String currentPage = "home";
    private String receiverIp = "";
    private boolean receiverConnected = false;
    private boolean discoveryInProgress = false;
    private int receiverMonitorCycles = 0;
    private String pendingPlatformUrl;
    private String pendingAutoCastPageUrl = "";
    private final Runnable autoCastDetectedMediaRunnable = () -> {
        if (web == null || isFinishing() || !"browser".equals(currentPage)) return;
        String activePage = web.getUrl();
        if (activePage == null || !activePage.equals(pendingAutoCastPageUrl)
                || !isDirectPlatformUrl(activePage)) return;
        // Wait for the page to expose its available quality variants, then choose a direct stream.
        castBestQuality();
    };
    private final android.os.Handler receiverMonitorHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable receiverMonitorRunnable = new Runnable() {
        @Override public void run() {
            monitorReceiverConnection();
            receiverMonitorHandler.postDelayed(this, 7000);
        }
    };
    private SharedPreferences prefs;
    private boolean loadingHome = false;
    private Uri selectedImageUri;
    private volatile Uri selectedMediaUri;
    private final java.util.concurrent.ConcurrentHashMap<String, Uri> localMediaSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.ArrayList<Uri> localMediaQueue = new java.util.ArrayList<>();
    private final java.util.ArrayList<Uri> slideshowQueue = new java.util.ArrayList<>();
    private int currentSlideIndex = 0;
    private final android.os.Handler slideshowHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean slideshowRunning = false;
    private final Runnable slideshowRunnable = new Runnable() {
        @Override public void run() {
            if (!slideshowRunning || slideshowQueue.isEmpty()) return;
            currentSlideIndex = (currentSlideIndex + 1) % slideshowQueue.size();
            prefs.edit().putInt("slideshow_index", currentSlideIndex).apply();
            selectedImageUri = slideshowQueue.get(currentSlideIndex);
            showPhoneGallery();
            serveSelectedImageAndCast();
            slideshowHandler.postDelayed(this, 5000);
        }
    };
    private int currentLocalMediaIndex = 0;
    private static final int PICK_LOCAL_MEDIA_REQUEST = 902;
    private volatile ServerSocket localMediaServer;
    private volatile boolean localMediaServerRunning = false;

    @Override @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prepareExtractionNotifications();
        // Keep the user-selected Receiver across launches; never erase a valid selection on startup.
        receiverIp = prefs.getString("receiver_ip", "").trim();
        receiverConnected = false;
        restoreSavedMediaQueues();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildShell();
        setContentView(shellOverlay);
        showHome();
        handleSharedIntent(getIntent());
        // If Android killed the process while the browser was in the background,
        // reopen the last page on a normal launcher restart (not on an incoming share).
        Intent launchIntent = getIntent();
        boolean incomingShare = launchIntent != null &&
                (Intent.ACTION_VIEW.equals(launchIntent.getAction()) ||
                 Intent.ACTION_SEND.equals(launchIntent.getAction()) ||
                 Intent.ACTION_SEND_MULTIPLE.equals(launchIntent.getAction()));
        if (state == null && !incomingShare &&
                prefs.getBoolean("browser_was_active", false)) {
            String lastUrl = prefs.getString("last_browser_url", "");
            if (BrowserEngine.isWebUrl(lastUrl)) {
                showBrowser();
                if (web != null) web.loadUrl(lastUrl);
            }
        }
        receiverMonitorHandler.post(receiverMonitorRunnable);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
    }

    private void handleSharedIntent(android.content.Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        // Accept links opened from other apps as well as shared text/files.
        if (android.content.Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data == null) return;

            String scheme = data.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                String sharedUrl = BrowserEngine.normalizeAddress(data.toString());
                // For externally opened links, try screen playback first rather than launching
                // the embedded browser. The Receiver reports when no direct stream can be resolved.
                sendPlatformLinkToScreen(sharedUrl);
                return;
            }

            if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                acceptSharedMedia(java.util.Collections.singletonList(data));
                return;
            }
        } else if (android.content.Intent.ACTION_SEND.equals(action)) {
            CharSequence sharedText = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
            String shared = sharedText == null ? null : sharedText.toString();
            if (shared != null && !shared.trim().isEmpty()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?i)https?://[^\\s<>\\\"']+")
                        .matcher(shared);
                if (matcher.find()) {
                    String sharedUrl = matcher.group().replaceAll("[),.?!;،؛؟\\]}]+$", "");
                    String normalized = BrowserEngine.normalizeAddress(sharedUrl);
                    // A shared URL is treated as a playback request first; Receiver resolves
                    // direct media when the public page exposes a playable stream.
                    sendPlatformLinkToScreen(normalized);
                    return;
                }
            }

            Uri stream = intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM);
            if (stream != null) {
                acceptSharedMedia(java.util.Collections.singletonList(stream));
                return;
            }
        } else if (android.content.Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            java.util.ArrayList<Uri> streams = intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM);
            if (streams != null && !streams.isEmpty()) {
                acceptSharedMedia(streams);
                return;
            }
        }
    }

    private int authenticatedResolveGeneration = 0;

    private void sendPlatformLinkToScreen(String url) {
        // Share owns remote extraction; Receiver remains a media playback endpoint.
        receiverMonitorHandler.removeCallbacks(autoCastDetectedMediaRunnable);
        pendingAutoCastPageUrl = "";
        if (url == null || !BrowserEngine.isWebUrl(url)) {
            sendPlatformLinkToReceiver(url);
            return;
        }
        String apiKey = prefs == null ? "" : prefs.getString("rapid_api_key", "").trim();
        if (apiKey.isEmpty()) {
            promptForRapidApiKey(url);
            return;
        }
        extractWithRapidApi(url, apiKey);
    }

    private void promptForRapidApiKey(String pageUrl) {
        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("RapidAPI key");
        keyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = column();
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(text("للاستخراج المباشر، أدخل مفتاح RapidAPI لخدمة Social Download All In One. سيُحفظ المفتاح على هذا الجهاز داخل Share فقط.", 13, MUTED, false));
        box.addView(keyInput, lp(-1, 52));
        new AlertDialog.Builder(this)
                .setTitle("إعداد محرك استخراج Black Hole")
                .setView(box)
                .setPositiveButton("حفظ واستخراج", (dialog, which) -> {
                    String key = keyInput.getText().toString().trim();
                    if (key.isEmpty()) {
                        Toast.makeText(this, "أدخل مفتاح RapidAPI أو أرسل الرابط للريسيفر بدون استخراج الخدمة.", Toast.LENGTH_LONG).show();
                        sendPlatformLinkToReceiver(pageUrl);
                        return;
                    }
                    prefs.edit().putString("rapid_api_key", key).apply();
                    extractWithRapidApi(pageUrl, key);
                })
                .setNeutralButton("إرسال الرابط بدون استخراج", (dialog, which) -> sendPlatformLinkToReceiver(pageUrl))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void extractWithRapidApi(String pageUrl, String apiKey) {
        Toast.makeText(this, "Share بيستخرج رابط الوسائط من خدمة Black Hole…", Toast.LENGTH_LONG).show();
        RapidApiExtractor.resolve(apiKey, pageUrl, (mediaUrl, mediaType, message) -> runOnUiThread(() -> {
            if (isFinishing()) return;
            if (mediaUrl != null && !mediaUrl.isEmpty()
                    && BrowserEngine.isWebUrl(mediaUrl) && !mediaUrl.equals(pageUrl)) {
                currentPlayingVideoUrl = mediaUrl;
                Toast.makeText(this, "تم استخراج " + mediaType + " مباشر؛ جاري إرساله إلى Receiver…", Toast.LENGTH_LONG).show();
                castUrl(mediaUrl);
            } else {
                Toast.makeText(this, message + " هبعت الرابط الأصلي للريسيفر من غير فتح متصفح Share.", Toast.LENGTH_LONG).show();
                sendPlatformLinkToReceiver(pageUrl);
            }
        }));
    }

    private void resolveSharedVideoFromBrowser(String pageUrl, int generation, int attempt) {
        if (generation != authenticatedResolveGeneration || web == null || isFinishing()) return;
        final String js = "(function(){"
                + "var out=[];function add(u){if(u&&typeof u==='string'&&/^https?:\\/\\//i.test(u)&&out.indexOf(u)<0)out.push(u);}"
                + "var vs=[].slice.call(document.querySelectorAll('video'));"
                + "vs.sort(function(a,b){return (Number(!a.paused&& !a.ended)*2+a.readyState)-(Number(!b.paused&&!b.ended)*2+b.readyState);}).reverse();"
                + "vs.forEach(function(v){add(v.currentSrc);add(v.src);[].slice.call(v.querySelectorAll('source')).forEach(function(s){add(s.src);});});"
                + "var rs=performance.getEntriesByType('resource')||[];"
                + "for(var i=rs.length-1;i>=0&&out.length<40;i--){var u=rs[i].name||'';"
                + "if(/\\.(mp4|m4v|webm|mov|m3u8|mpd)([?#]|$)/i.test(u)||/videoplayback|video_playback|mime=video|type=video|contenttype=video|bytevc|\\bavc1\\b|hevc|\\bdash\\b/i.test(u))add(u);}"
                + "return JSON.stringify(out.slice(0,40));})()";
        web.evaluateJavascript(js, value -> {
            if (generation != authenticatedResolveGeneration || isFinishing()) return;
            try {
                Object parsed = new org.json.JSONTokener(value == null ? "\"[]\"" : value).nextValue();
                String listJson = parsed == null ? "[]" : String.valueOf(parsed);
                JSONArray candidates = new JSONArray(listJson);
                for (int i = 0; i < candidates.length(); i++) {
                    String mediaUrl = candidates.optString(i, "");
                    if (!mediaUrl.isEmpty() && !mediaUrl.equals(pageUrl)
                            && !mediaUrl.startsWith("blob:") && !mediaUrl.startsWith("data:")
                            && BrowserEngine.isWebUrl(mediaUrl) && isVideoCandidate(mediaUrl)) {
                        currentPlayingVideoUrl = mediaUrl;
                        Toast.makeText(this, "تم العثور على رابط فيديو من موارد الصفحة؛ جاري إرساله للشاشة…", Toast.LENGTH_LONG).show();
                        castUrl(mediaUrl);
                        return;
                    }
                }
            } catch (Exception ignored) { }
            // The WebView request interceptor may already have captured a CDN stream
            // even when JavaScript cannot see it in the DOM/performance resource list.
            String captured = currentPlayingVideoUrl;
            if (captured == null || captured.isEmpty() || !isVideoCandidate(captured)
                    || captured.equals(pageUrl) || captured.startsWith("blob:")
                    || captured.startsWith("data:")) {
                captured = null;
                for (int i = mediaOrder.size() - 1; i >= 0; i--) {
                    String candidate = mediaOrder.get(i);
                    if (isVideoCandidate(candidate) && !candidate.equals(pageUrl)
                            && !candidate.startsWith("blob:") && !candidate.startsWith("data:")) {
                        captured = candidate;
                        break;
                    }
                }
            }
            if (captured != null && !captured.isEmpty()) {
                currentPlayingVideoUrl = captured;
                Toast.makeText(this, "تم التقاط رابط البث من طلبات الفيديو؛ جاري إرساله للشاشة…", Toast.LENGTH_LONG).show();
                castUrl(captured);
                return;
            }
            // Poll the authenticated page for longer because social players can load streams late.
            // Never send the page URL itself.
            if (attempt < 40) {
                receiverMonitorHandler.postDelayed(() -> resolveSharedVideoFromBrowser(pageUrl, generation, attempt + 1), 500L);
            } else {
                startRemoteExtractionFallback(pageUrl, generation);
            }
        });
    }

    private void startRemoteExtractionFallback(String pageUrl, int generation) {
        if (generation != authenticatedResolveGeneration || isFinishing()) return;
        String configuredApi = prefs == null ? "" : prefs.getString("extractor_api_base", "").trim();
        if (configuredApi.isEmpty()) {
            // Do not silently send shared links to a third-party extraction service.
            // Let Receiver try its own configured resolver, then use its TV webpage fallback.
            Toast.makeText(this,
                    "لم يظهر رابط مباشر في Share؛ جاري إرسال الرابط للريسيفر ليجرب الاستخراج والتشغيل على الشاشة…",
                    Toast.LENGTH_LONG).show();
            sendPlatformLinkToReceiver(pageUrl);
            return;
        }
        Toast.makeText(this, "لم يظهر رابط مباشر محليًا؛ بجرب محرك الاستخراج الذي ضبطته…", Toast.LENGTH_LONG).show();
        runRemoteExtraction(pageUrl, generation, configuredApi);
    }

    private void runRemoteExtraction(String pageUrl, int generation, String apiBase) {
        Toast.makeText(this, "المحرك الأول لم يعثر على رابط؛ جاري تجربة محرك الاستخراج الإضافي…", Toast.LENGTH_LONG).show();
        PlatformExtractor.resolve(apiBase, pageUrl, (mediaUrl, message) -> runOnUiThread(() -> {
            if (generation != authenticatedResolveGeneration || isFinishing()) return;
            if (mediaUrl != null && !mediaUrl.isEmpty()
                    && BrowserEngine.isWebUrl(mediaUrl) && !mediaUrl.equals(pageUrl)
                    && !mediaUrl.startsWith("blob:") && !mediaUrl.startsWith("data:")) {
                currentPlayingVideoUrl = mediaUrl;
                Toast.makeText(this, "تم استخراج رابط من المحرك الإضافي؛ جاري إرساله إلى Receiver…", Toast.LENGTH_LONG).show();
                castUrl(mediaUrl);
            } else {
                Toast.makeText(this, message + " جاري تمرير الرابط للريسيفر ليجرب تشغيل صفحة الموقع على الشاشة.", Toast.LENGTH_LONG).show();
                sendPlatformLinkToReceiver(pageUrl);
            }
        }));
    }

    private void sendPlatformLinkToReceiver(String url) {
        // Prefer the receiver explicitly selected by the user. Only discover when none is saved.
        if (receiverIp != null && !receiverIp.trim().isEmpty()) {
            castUrl(url);
            return;
        }
        pendingPlatformUrl = url;
        Toast.makeText(this, "جاري العثور على عنوان الريسيفر وإرسال الفيديو…", Toast.LENGTH_LONG).show();
        discoverReceiverInBackground();
    }

    private boolean isDirectPlatformUrl(String value) {
        if (isYouTubeUrl(value)) return true;
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("tiktok.com") || host.endsWith(".tiktok.com")
                    || host.equals("instagram.com") || host.endsWith(".instagram.com")
                    || host.equals("threads.net") || host.endsWith(".threads.net")
                    || host.equals("threads.com") || host.endsWith(".threads.com")
                    || host.equals("facebook.com") || host.endsWith(".facebook.com")
                    || host.equals("fb.watch") || host.endsWith(".fb.watch")
                    || host.equals("fb.me") || host.endsWith(".fb.me")
                    || host.equals("m.facebook.com") || host.equals("lm.facebook.com")
                    || host.equals("x.com") || host.endsWith(".x.com")
                    || host.equals("twitter.com") || host.endsWith(".twitter.com")
                    || host.equals("vimeo.com") || host.endsWith(".vimeo.com")
                    || host.equals("twitch.tv") || host.endsWith(".twitch.tv")
                    || host.equals("clips.twitch.tv")
                    || host.equals("dailymotion.com") || host.endsWith(".dailymotion.com")
                    || host.equals("reddit.com") || host.endsWith(".reddit.com")
                    || host.equals("redd.it")
                    || host.equals("snapchat.com") || host.endsWith(".snapchat.com")
                    || host.equals("streamable.com") || host.endsWith(".streamable.com")
                    || host.equals("rumble.com") || host.endsWith(".rumble.com")
                    || host.equals("bilibili.com") || host.endsWith(".bilibili.com")
                    || host.equals("pinterest.com") || host.endsWith(".pinterest.com")
                    || host.equals("likee.video") || host.endsWith(".likee.video")
                    || host.equals("kick.com") || host.endsWith(".kick.com")
                    || host.equals("odysee.com") || host.endsWith(".odysee.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isYouTubeUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("youtu.be")
                    || host.equals("youtube.com")
                    || host.endsWith(".youtube.com")
                    || host.equals("youtube-nocookie.com")
                    || host.endsWith(".youtube-nocookie.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void acceptSharedMedia(java.util.List<Uri> streams) {
        localMediaQueue.clear();
        for (Uri uri : streams) {
            if (uri == null) continue;
            try {
                getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // Most ACTION_SEND grants are temporary, so keep using the granted URI directly.
            }
            localMediaQueue.add(uri);
        }
        if (localMediaQueue.isEmpty()) {
            Toast.makeText(this, "المشاركة لا تحتوي على ملفات قابلة للفتح", Toast.LENGTH_LONG).show();
            return;
        }
        currentLocalMediaIndex = 0;
        selectedMediaUri = localMediaQueue.get(0);
        showHome();
        Toast.makeText(this, "تم استلام " + localMediaQueue.size() + " ملف؛ جاري تجهيز الإرسال", Toast.LENGTH_SHORT).show();
        playSelectedLocalMedia();
    }

    private int dp(float n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setPadding(dp(2), dp(2), dp(2), dp(2));
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }

    private void buildShell() {
        shellOverlay = new FrameLayout(this);
        shellOverlay.setBackgroundColor(BG);
        root = column();
        root.setBackgroundColor(BG);
        header = column();
        header.setPadding(dp(18), dp(12), dp(18), dp(12));
        header.setBackgroundColor(BG);
        LinearLayout brandRow = row();
        TextView logo = text("▶", 24, GOLD, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(shape(PANEL2, GOLD, 15));
        brandRow.addView(logo, lp(48,48));
        LinearLayout brandText = column();
        brandText.setPadding(dp(12),0,0,0);
        brandText.addView(text("ABO EZZAT", 19, WHITE, true));
        brandText.addView(text("SHARE  •  متصفح وإرسال الوسائط", 11, MUTED, false));
        brandRow.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView receiver = button("☰", PANEL2, WHITE);
        receiver.setContentDescription("القائمة");
        receiver.setOnClickListener(v -> showToolsMenu());
        brandRow.addView(receiver, lp(46,44));
        header.addView(brandRow);
        connectionLabel = text("", 12, MUTED, false);
        connectionLabel.setPadding(dp(4),dp(8),0,0);
        header.addView(connectionLabel);
        root.addView(header);
        contentFrame = new FrameLayout(this);
        content = column();
        contentFrame.addView(content, new FrameLayout.LayoutParams(-1,-1));
        root.addView(contentFrame, new LinearLayout.LayoutParams(-1,0,1));
        bottom = row();
        bottom.setPadding(dp(5),dp(7),dp(5),dp(7));
        bottom.setBackgroundColor(PANEL);
        root.addView(bottom, lp(-1,64));
        nav("⌂\nالرئيسية", "home");
        nav("◎\nالمتصفح", "browser");
        nav("▣\nالوسائط", "media");
        nav("☆\nالمكتبة", "library");
        refreshConnectionLabel();
        shellOverlay.addView(root, new FrameLayout.LayoutParams(-1, -1));
        buildSideDrawer();
        installDrawerGestures();
    }

    private TextView button(String label, int color, int fg) {
        TextView b = text(label, 14, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(label);
        b.setBackground(shape(color, color == PANEL ? 0 : PANEL2, 13));
        b.setPadding(dp(12),dp(9),dp(12),dp(9));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private void nav(String label, String page) {
        TextView b = text(label, 11, MUTED, true);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(label.replace("\n", " "));
        bottom.addView(b, new LinearLayout.LayoutParams(0,-1,1));
        b.setOnClickListener(v -> {
            if ("home".equals(page)) showHome();
            else if ("browser".equals(page)) showBrowser();
            else if ("media".equals(page)) showMedia();
            else showLibrary();
        });
    }

    private void buildSideDrawer() {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x99000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeSideDrawer());
        shellOverlay.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        drawerPanel = column();
        drawerPanel.setPadding(dp(16), dp(22), dp(16), dp(18));
        drawerPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(20, 29, 49), Color.rgb(12, 18, 33)}));
        drawerPanel.setElevation(dp(20));
        drawerPanel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(dp(300), -1, Gravity.RIGHT | Gravity.TOP);
        shellOverlay.addView(drawerPanel, drawerLp);
        drawerPanel.setTranslationX(dp(310));

        LinearLayout brand = row();
        TextView mark = text("▶", 24, GOLD, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(PANEL2, GOLD, 14));
        brand.addView(mark, lp(48, 48));
        LinearLayout brandText = column();
        brandText.setPadding(dp(10), 0, 0, 0);
        brandText.addView(text("ABO EZZAT SHARE", 17, WHITE, true), lp(-1, 27));
        brandText.addView(text("القائمة الرئيسية", 11, MUTED, false), lp(-1, 22));
        brand.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
        drawerPanel.addView(brand, lp(-1, 52));

        ScrollView scroll = new ScrollView(this);
        LinearLayout items = column();
        String[][] entries = {
            {"🌐", "المتصفح وقناص الفيديو", "browser"},
            {"📡", "جهاز الاستقبال", "receiver"},
            {"📁", "وسائط الهاتف", "phone_media"},
            {"📺", "تشغيل IPTV", "iptv"},
            {"🕒", "الوسائط الأخيرة", "recent"},
            {"📑", "قائمة التشغيل", "playlist"},
            {"🎞️", "الوسائط المحفوظة", "saved_media"},
            {"⭐", "العلامات المرجعية", "bookmarks"},
            {"📜", "سجل التصفح", "history"},
            {"🔥", "المواقع الأكثر زيارة", "popular"},
            {"📥", "التنزيلات", "downloads"},
            {"💾", "نسخة احتياطية واستعادة", "backup"},
            {"🚪", "خروج", "exit"}
        };
        for (String[] entry : entries) {
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(4), dp(12), dp(4));
            item.setBackground(shape(PANEL, PANEL2, 12));
            TextView icon = text(entry[0], 21, GOLD, true);
            icon.setGravity(Gravity.CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, lp(38, 44));
            TextView title = text(entry[1], 13, "exit".equals(entry[2]) ? Color.rgb(255, 100, 100) : WHITE, true);
            title.setGravity(Gravity.CENTER_VERTICAL);
            item.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams itemLp = lp(-1, 48);
            itemLp.bottomMargin = dp(5);
            items.addView(item, itemLp);
            item.setContentDescription(entry[1]);
            item.setFocusable(true);
            item.setOnClickListener(v -> handleDrawerItem(entry[2]));
        }
        scroll.addView(items);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = dp(4);
        drawerPanel.addView(scroll, scrollLp);
    }

    private void openSideDrawer() {
        if (drawerPanel == null || drawerScrim == null || drawerScrim.getVisibility() == View.VISIBLE) return;
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.animate().translationX(0).setDuration(220).start();
    }

    private void closeSideDrawer() {
        if (drawerPanel == null || drawerScrim == null || drawerScrim.getVisibility() != View.VISIBLE) return;
        drawerPanel.animate().translationX(dp(310)).setDuration(180).withEndAction(() -> {
            if (drawerScrim != null) drawerScrim.setVisibility(View.GONE);
        }).start();
    }

    private void installDrawerGestures() {
        View.OnTouchListener listener = (v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                drawerTouchStartX = event.getRawX();
                drawerTouchStartY = event.getRawY();
                drawerGestureTracking = drawerTouchStartX > getResources().getDisplayMetrics().widthPixels - dp(36)
                        || (drawerScrim != null && drawerScrim.getVisibility() == View.VISIBLE);
                return false;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP && drawerGestureTracking) {
                float dx = event.getRawX() - drawerTouchStartX;
                float dy = event.getRawY() - drawerTouchStartY;
                if (Math.abs(dx) > dp(70) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                    if (dx < 0) openSideDrawer(); else closeSideDrawer();
                }
                drawerGestureTracking = false;
            }
            return false;
        };
        contentFrame.setOnTouchListener(listener);
        header.setOnTouchListener(listener);
        shellOverlay.setOnTouchListener(listener);
    }

    private void handleDrawerItem(String key) {
        closeSideDrawer();
        if ("browser".equals(key)) showBrowser();
        else if ("phone_media".equals(key)) openLocalMediaPicker();
        else if ("iptv".equals(key)) showIptvDialog();
        else if ("recent".equals(key)) showRecentCastDialog();
        else if ("playlist".equals(key)) showLocalPlaylist();
        else if ("saved_media".equals(key)) showSavedMedia();
        else if ("bookmarks".equals(key)) showLibrary("bookmarks");
        else if ("history".equals(key)) showLibrary("history");
        else if ("popular".equals(key)) showPopularSites();
        else if ("downloads".equals(key)) openDownloads();
        else if ("receiver".equals(key)) showReceiverPicker();
        else if ("settings".equals(key)) openAppPermissionSettings();
        else if ("backup".equals(key)) showBackupOptions();
        else if ("exit".equals(key)) finish();
    }

    private void showBackupOptions() {
        new AlertDialog.Builder(this).setTitle("النسخ الاحتياطي والاستعادة")
                .setItems(new String[]{"تصدير نسخة احتياطية", "استعادة نسخة احتياطية"}, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        intent.putExtra(Intent.EXTRA_TITLE, "AboEzzat-Share-backup.json");
                        startActivityForResult(intent, CREATE_BACKUP_REQUEST);
                    } else {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, RESTORE_BACKUP_REQUEST);
                    }
                }).setNegativeButton("إلغاء", null).show();
    }

    private JSONObject createBackupJson() throws org.json.JSONException {
        JSONObject backup = new JSONObject();
        backup.put("format", "AboEzzatShareBackup");
        backup.put("version", 1);
        for (String key : new String[]{"bookmarks", "history", "saved_media_urls", "recent_cast_urls", "local_media_queue", "slideshow_queue", "custom_sites_json"}) {
            backup.put(key, prefs.getString(key, key.endsWith("_queue") || key.equals("history") || key.equals("bookmarks") || key.equals("saved_media_urls") || key.equals("recent_cast_urls") || key.equals("custom_sites_json") ? "[]" : ""));
        }
        backup.put("local_media_index", prefs.getInt("local_media_index", 0));
        backup.put("slideshow_index", prefs.getInt("slideshow_index", 0));
        return backup;
    }

    private void exportBackup(Uri destination) {
        try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new java.io.IOException("تعذر فتح ملف النسخة");
            out.write(createBackupJson().toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Toast.makeText(this, "تم تصدير النسخة الاحتياطية", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل تصدير النسخة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importBackup(Uri source) {
        try (InputStream in = getContentResolver().openInputStream(source)) {
            if (in == null) throw new java.io.IOException("تعذر قراءة الملف");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = in.read(chunk)) != -1) buffer.write(chunk, 0, count);
            JSONObject backup = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            if (!"AboEzzatShareBackup".equals(backup.optString("format"))) throw new IllegalArgumentException("الملف ليس نسخة احتياطية متوافقة");
            SharedPreferences.Editor edit = prefs.edit();
            for (String key : new String[]{"bookmarks", "history", "saved_media_urls", "recent_cast_urls", "local_media_queue", "slideshow_queue", "custom_sites_json"}) {
                String value = backup.optString(key, "[]");
                new JSONArray(value);
                edit.putString(key, value);
            }
            edit.putInt("local_media_index", Math.max(0, backup.optInt("local_media_index", 0)));
            edit.putInt("slideshow_index", Math.max(0, backup.optInt("slideshow_index", 0)));
            // Keep the selected receiver separate from media/browser backup contents.
            edit.apply();
            restoreSavedMediaQueues();
            Toast.makeText(this, "تمت استعادة البيانات. راجع صلاحيات الملفات المحلية إذا لم تفتح بعض العناصر.", Toast.LENGTH_LONG).show();
            showHome();
        } catch (Exception e) {
            Toast.makeText(this, "فشلت الاستعادة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showPopularSites() {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        JSONArray history;
        try { history = new JSONArray(prefs.getString("history", "[]")); }
        catch (Exception e) { history = new JSONArray(); }
        for (int i = 0; i < history.length(); i++) {
            String url = history.optString(i, "");
            if (!BrowserEngine.isWebUrl(url)) continue;
            String host = Uri.parse(url).getHost();
            if (host == null || host.trim().isEmpty()) continue;
            host = host.toLowerCase(Locale.ROOT);
            Integer oldCount = counts.get(host);
            counts.put(host, oldCount == null ? 1 : oldCount + 1);
        }
        java.util.ArrayList<java.util.Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>(counts.entrySet());
        java.util.Collections.sort(sorted, (a, b) -> b.getValue().compareTo(a.getValue()));
        if (sorted.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("المواقع الأكثر زيارة")
                    .setMessage("لسه مفيش سجل تصفح كفاية لعرض المواقع الأكثر زيارة.")
                    .setPositiveButton("فتح المتصفح", (d, w) -> showBrowser())
                    .setNegativeButton("إغلاق", null).show();
            return;
        }
        String[] labels = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++)
            labels[i] = (i + 1) + ". " + sorted.get(i).getKey() + " — " + sorted.get(i).getValue() + " زيارة";
        new AlertDialog.Builder(this).setTitle("المواقع الأكثر زيارة")
                .setItems(labels, (d, which) -> {
                    showBrowser();
                    address.setText("https://" + sorted.get(which).getKey());
                    loadAddress();
                }).setNegativeButton("إغلاق", null).show();
    }

    private void showIptvDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("http:// أو https:// رابط قناة IPTV");
        new AlertDialog.Builder(this).setTitle("تشغيل IPTV").setMessage("أدخل رابط بث مباشر يدعمه جهاز الاستقبال.")
                .setView(input).setPositiveButton("إرسال للتلفزيون", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (BrowserEngine.isWebUrl(url)) castUrl(url);
                    else Toast.makeText(this, "أدخل رابطًا صحيحًا يبدأ بـ http أو https", Toast.LENGTH_LONG).show();
                }).setNegativeButton("إلغاء", null).show();
    }

    private void showToolsMenu() {
        openSideDrawer();
    }

    private void exitVideoFullscreen() {
        if (fullscreenVideoView == null) return;
        try { shellOverlay.removeView(fullscreenVideoView); } catch (Exception ignored) { }
        fullscreenVideoView = null;
        if (fullscreenVideoCallback != null) {
            try { fullscreenVideoCallback.onCustomViewHidden(); } catch (Exception ignored) { }
            fullscreenVideoCallback = null;
        }
        setRequestedOrientation(orientationBeforeFullscreen);
        header.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        content.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NATIVE_VOICE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startNativeVoiceRecognition();
            } else {
                pendingVoiceTarget = null;
                Toast.makeText(this, "اسمح للميكروفون من إعدادات أندرويد لاستخدام البحث الصوتي داخل التطبيق.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_MICROPHONE) {
            android.webkit.PermissionRequest request = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                if (request != null) {
                    // A WebView permission request can expire while Android's runtime
                    // permission dialog is open. Try to grant the original request first;
                    // if it expired, tell the user to reload so the site can request again.
                    confirmWebsiteMicrophonePermission(request);
                } else {
                    Toast.makeText(this,
                            "تم السماح بميكروفون التطبيق. ارجع للصفحة واضغط البحث الصوتي مرة أخرى.",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                if (request != null) {
                    try { request.deny(); } catch (Exception ignored) { }
                }
                Toast.makeText(this,
                        "الميكروفون غير مسموح للتطبيق. فعّله من إعدادات Android ثم أعد محاولة البحث الصوتي.",
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this,
                        "إشعارات تقدم الاستخراج متوقفة. افتح إعدادات التطبيق وفعّل الإشعارات للسماح بظهورها في لوحة إشعارات أندرويد.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void resetContent() {
        content.removeAllViews();
        content.setPadding(dp(16),dp(4),dp(16),dp(10));
    }

    private void addSection(LinearLayout parent, String title, String subtitle) {
        parent.addView(text(title,19,WHITE,true), lp(-1,34));
        if (subtitle != null) parent.addView(text(subtitle,12,MUTED,false), lp(-1,28));
    }

    private TextView actionCard(String icon, String title, String desc, Runnable action) {
        LinearLayout card = column();
        card.setPadding(dp(14),dp(12),dp(14),dp(12));
        card.setBackground(shape(PANEL, PANEL2, 17));
        TextView i = text(icon,22,GOLD,true);
        card.addView(i, lp(-1,30));
        card.addView(text(title,15,WHITE,true), lp(-1,28));
        card.addView(text(desc,11,MUTED,false), lp(-1,-2));
        card.setOnClickListener(v -> action.run());
        return cardAsText(card);
    }

    private TextView cardAsText(LinearLayout card) {
        // A card is embedded in a TextView-compatible click surface through its parent.
        TextView proxy = text("", 1, WHITE, false);
        proxy.setVisibility(View.GONE);
        card.setTag(proxy);
        return proxy;
    }

    private void addCard(LinearLayout parent, String icon, String title, String desc, Runnable action) {
        LinearLayout card = column();
        card.setPadding(dp(14),dp(12),dp(14),dp(12));
        card.setBackground(shape(PANEL, PANEL2, 17));
        card.addView(text(icon,22,GOLD,true), lp(-1,30));
        card.addView(text(title,15,WHITE,true), lp(-1,28));
        card.addView(text(desc,11,MUTED,false), lp(-1,-2));
        card.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1);
        p.setMargins(dp(4),dp(4),dp(4),dp(4));
        parent.addView(card,p);
    }

    private TextView makeSocialIcon(int index) {
        String glyph;
        int foreground = Color.WHITE;
        int background;
        switch (index) {
            case 0: // YouTube
                glyph = "▶";
                background = Color.rgb(255, 0, 0);
                break;
            case 1: // Facebook
                glyph = "f";
                background = Color.rgb(24, 119, 242);
                break;
            case 2: // TikTok
                glyph = "♪";
                background = Color.rgb(8, 8, 8);
                break;
            case 3: // Instagram
                glyph = "◎";
                background = Color.rgb(193, 53, 132);
                break;
            case 4: // X
                glyph = "𝕏";
                background = Color.rgb(0, 0, 0);
                break;
            default: // Threads
                glyph = "@";
                background = Color.rgb(18, 18, 18);
                break;
        }
        TextView icon = text(glyph, index == 1 ? 27 : 23, foreground, true);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        GradientDrawable iconBackground = new GradientDrawable();
        if (index == 3) {
            iconBackground.setColors(new int[]{
                    Color.rgb(255, 196, 72),
                    Color.rgb(245, 75, 106),
                    Color.rgb(193, 53, 132),
                    Color.rgb(88, 81, 219)
            });
            iconBackground.setOrientation(GradientDrawable.Orientation.BL_TR);
        } else {
            iconBackground.setColor(background);
        }
        iconBackground.setCornerRadius(dp(10));
        iconBackground.setStroke(dp(1), Color.argb(55, 255, 255, 255));
        icon.setBackground(iconBackground);
        if (index == 2) {
            icon.setShadowLayer(dp(1), dp(1), 0, Color.rgb(0, 220, 220));
        }
        return icon;
    }

    private void showHome() {
        header.setVisibility(View.VISIBLE); bottom.setVisibility(View.VISIBLE);
        currentPage = "home";
        resetContent();
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(0, dp(4), 0, dp(16));

        // Compact Google-style address/search bar replaces the old welcome card.
        LinearLayout searchBar = row();
        searchBar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        searchBar.setPadding(dp(10), dp(5), dp(7), dp(5));
        searchBar.setBackground(shape(PANEL, PANEL2, 18));
        TextView searchIcon = text("⌕", 27, BLUE, true);
        searchIcon.setGravity(Gravity.CENTER);
        searchIcon.setContentDescription("بحث");
        searchBar.addView(searchIcon, lp(38, 44));

        EditText homeSearch = new EditText(this);
        homeSearch.setSingleLine(true);
        homeSearch.setTextSize(15);
        homeSearch.setTextColor(WHITE);
        homeSearch.setHintTextColor(MUTED);
        homeSearch.setHint("ابحث في جوجل أو اكتب رابط موقع");
        homeSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        homeSearch.setImeOptions(EditorInfo.IME_ACTION_GO);
        homeSearch.setSelectAllOnFocus(false);
        homeSearch.setPadding(dp(7), 0, dp(7), 0);
        homeSearch.setBackgroundColor(Color.TRANSPARENT);
        searchBar.addView(homeSearch, new LinearLayout.LayoutParams(0, dp(46), 1));

        TextView voiceSearch = button("🎙", PANEL2, GOLD);
        voiceSearch.setContentDescription("البحث الصوتي");
        searchBar.addView(voiceSearch, lp(40, 40));
        voiceSearch.setOnClickListener(v -> beginNativeVoiceSearch(homeSearch));

        TextView clearSearch = button("×", PANEL2, MUTED);
        clearSearch.setContentDescription("مسح البحث");
        clearSearch.setPadding(dp(8), 0, dp(8), 0);
        searchBar.addView(clearSearch, lp(36, 40));
        clearSearch.setOnClickListener(v -> homeSearch.setText(""));

        TextView goSearch = button("➜", BLUE, WHITE);
        goSearch.setContentDescription("بحث أو فتح الرابط");
        goSearch.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams goSearchParams = lp(40, 40);
        goSearchParams.setMargins(dp(4), 0, 0, 0);
        searchBar.addView(goSearch, goSearchParams);

        Runnable submitHomeSearch = () -> {
            String query = homeSearch.getText().toString().trim();
            if (query.isEmpty()) {
                homeSearch.setError("اكتب كلمة للبحث أو رابط الموقع");
                homeSearch.requestFocus();
                return;
            }
            showBrowser();
            address.setText(query);
            loadAddress();
        };
        goSearch.setOnClickListener(v -> submitHomeSearch.run());
        homeSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitHomeSearch.run();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams searchBarParams = lp(-1, 58);
        searchBarParams.setMargins(0, dp(6), 0, dp(10));
        page.addView(searchBar, searchBarParams);
        addSection(page, "منصاتك ومواقعك", "اضغط على البطاقة لفتح الموقع، واضغط + لإضافة أي موقع جديد");
        LinearLayout siteGrid = null;
        int siteCountInRow = 0;
        String[][] sites = {
            {"YouTube", "https://m.youtube.com"},
            {"Facebook", "https://m.facebook.com"},
            {"TikTok", "https://www.tiktok.com"},
            {"Instagram", "https://www.instagram.com"},
            {"X / Twitter", "https://x.com"},
            {"Threads", "https://www.threads.net"}
        };
        for (String[] site : sites) {
            if (siteCountInRow == 0) { siteGrid = row(); page.addView(siteGrid, lp(-1, -2)); }
            addWebsiteCard(siteGrid, site[0], site[1], -1, false);
            siteCountInRow = (siteCountInRow + 1) % 3;
        }
        JSONArray customSites = readCustomSites();
        for (int i = 0; i < customSites.length(); i++) {
            JSONObject item = customSites.optJSONObject(i);
            if (item == null) continue;
            String siteUrl = item.optString("url", "").trim();
            if (!BrowserEngine.isWebUrl(siteUrl)) continue;
            String siteName = item.optString("name", "").trim();
            if (siteName.isEmpty()) siteName = siteHostLabel(siteUrl);
            if (siteCountInRow == 0) { siteGrid = row(); page.addView(siteGrid, lp(-1, -2)); }
            addWebsiteCard(siteGrid, siteName, siteUrl, i, true);
            siteCountInRow = (siteCountInRow + 1) % 3;
        }
        if (siteCountInRow == 0) { siteGrid = row(); page.addView(siteGrid, lp(-1, -2)); }
        LinearLayout addSiteCard = column();
        addSiteCard.setGravity(Gravity.CENTER);
        addSiteCard.setPadding(dp(5), dp(8), dp(5), dp(8));
        addSiteCard.setBackground(shape(PANEL, GOLD, 16));
        TextView plus = text("+", 30, GOLD, true);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(shape(PANEL2, GOLD, 12));
        LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        plusParams.gravity = Gravity.CENTER_HORIZONTAL;
        addSiteCard.addView(plus, plusParams);
        TextView addLabel = text("إضافة موقع", 11, WHITE, true);
        addLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams addLabelParams = lp(-1, 22);
        addLabelParams.topMargin = dp(4);
        addSiteCard.addView(addLabel, addLabelParams);
        addSiteCard.setContentDescription("إضافة موقع جديد إلى بطاقات المنصات");
        addSiteCard.setOnClickListener(v -> showAddCustomSiteDialog());
        LinearLayout.LayoutParams addCardParams = new LinearLayout.LayoutParams(0, dp(94), 1);
        addCardParams.setMargins(dp(3), dp(4), dp(3), dp(4));
        siteGrid.addView(addSiteCard, addCardParams);
        if (siteCountInRow != 0) {
            for (int i = siteCountInRow; i < 3; i++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, dp(94), 1);
                spacerParams.setMargins(dp(3), dp(4), dp(3), dp(4));
                siteGrid.addView(spacer, spacerParams);
            }
        }

        // These tools already live in the sidebar; avoid duplicate home cards.
        page.addView(text("ملاحظة: اكتشاف الوسائط يختلف حسب الموقع، وبعض المنصات تستخدم بثًا محميًا لا يمكن التقاطه أو إرساله مباشرة.", 11, MUTED, false), lp(-1, -2));
        scroll.addView(page);
        content.addView(scroll, lp(-1, -1));
    }

    private JSONArray readCustomSites() {
        try { return new JSONArray(prefs.getString("custom_sites_json", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void showAddCustomSiteDialog() {
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("الصق رابط الموقع هنا");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextColor(WHITE);
        url.setHintTextColor(MUTED);
        url.setPadding(dp(12), 0, dp(12), 0);
        new AlertDialog.Builder(this).setTitle("إضافة موقع إلى البطاقات").setMessage("الصق الرابط فقط؛ سيُستخرج اسم الموقع وأيقونته تلقائيًا.")
                .setView(url)
                .setPositiveButton("إضافة", (d, which) -> {
                    String siteUrl = BrowserEngine.normalizeAddress(url.getText().toString().trim());
                    if (!BrowserEngine.isWebUrl(siteUrl)) {
                        Toast.makeText(this, "اكتب رابطًا صحيحًا يبدأ بـ http أو https", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String siteName = siteHostLabel(siteUrl);
                    JSONArray oldSites = readCustomSites();
                    JSONArray updated = new JSONArray();
                    for (int i = 0; i < oldSites.length(); i++) {
                        JSONObject item = oldSites.optJSONObject(i);
                        if (item == null) continue;
                        if (siteUrl.equalsIgnoreCase(item.optString("url", ""))) continue;
                        updated.put(item);
                    }
                    JSONObject item = new JSONObject();
                    try { item.put("name", siteName); item.put("url", siteUrl); updated.put(item); }
                    catch (org.json.JSONException ignored) { }
                    prefs.edit().putString("custom_sites_json", updated.toString()).apply();
                    showHome();
                    Toast.makeText(this, "اتضاف الموقع مع بطاقات المنصات", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("إلغاء", null).show();
    }

    private String siteHostLabel(String siteUrl) {
        try {
            String host = Uri.parse(siteUrl).getHost();
            if (host == null || host.trim().isEmpty()) return "موقع جديد";
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (host.startsWith("m.")) host = host.substring(2);
            String[] parts = host.split("\\.");
            String base = parts.length > 1 ? parts[parts.length - 2] : parts[0];
            if (base.isEmpty()) return host;
            return base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1);
        } catch (Exception ignored) { return "موقع جديد"; }
    }

    private void addWebsiteCard(LinearLayout parent, String name, String siteUrl, int customIndex, boolean custom) {
        LinearLayout card = column();
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(5), dp(8), dp(5), dp(8));
        card.setBackground(shape(PANEL, PANEL2, 16));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(2), dp(2), dp(2), dp(2));
        logo.setBackground(shape(Color.WHITE, Color.argb(50, 255, 255, 255), 12));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(logo, logoParams);
        loadWebsiteFavicon(logo, siteUrl);
        TextView label = text(name, 11, WHITE, true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = lp(-1, 22);
        labelParams.topMargin = dp(4);
        card.addView(label, labelParams);
        card.setContentDescription("فتح " + name);
        card.setOnClickListener(v -> openQuickSite(siteUrl));
        if (custom) {
            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("إزالة الموقع")
                        .setMessage("هل تريد إزالة «" + name + "» من بطاقات المواقع؟")
                        .setPositiveButton("إزالة", (d, which) -> removeCustomSite(customIndex))
                        .setNegativeButton("إلغاء", null).show();
                return true;
            });
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(94), 1);
        cp.setMargins(dp(3), dp(4), dp(3), dp(4));
        parent.addView(card, cp);
    }

    private void loadWebsiteFavicon(ImageView target, String siteUrl) {
        target.setImageDrawable(null);
        target.setTag(siteUrl);
        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            Bitmap bitmap = null;
            try {
                String host = Uri.parse(siteUrl).getHost();
                if (host == null || host.trim().isEmpty()) return;
                URL iconUrl = new URL("https://www.google.com/s2/favicons?domain=" +
                        URLEncoder.encode(host, "UTF-8") + "&sz=128");
                connection = (HttpURLConnection) iconUrl.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (target.getTag() == null || !siteUrl.equals(target.getTag())) return;
                if (result != null) target.setImageBitmap(result);
                else {
                    TextView fallback = text("◉", 23, BLUE, true);
                    fallback.setGravity(Gravity.CENTER);
                    target.setImageDrawable(new android.graphics.drawable.BitmapDrawable(getResources(),
                            createFallbackSiteBitmap(siteHostLabel(siteUrl))));
                }
            });
        }, "site-favicon-loader");
        worker.setDaemon(true);
        worker.start();
    }

    private Bitmap createFallbackSiteBitmap(String label) {
        int size = dp(96);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(BLUE);
        canvas.drawRoundRect(0, 0, size, size, dp(18), dp(18), paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(size * 0.42f);
        String glyph = label == null || label.isEmpty() ? "W" : label.substring(0, 1).toUpperCase(Locale.ROOT);
        android.graphics.Rect bounds = new android.graphics.Rect();
        paint.getTextBounds(glyph, 0, glyph.length(), bounds);
        canvas.drawText(glyph, size / 2f, size / 2f - bounds.exactCenterY(), paint);
        return bitmap;
    }

    private void removeCustomSite(int removeIndex) {
        JSONArray oldSites = readCustomSites();
        JSONArray updated = new JSONArray();
        for (int i = 0; i < oldSites.length(); i++) {
            if (i == removeIndex) continue;
            JSONObject item = oldSites.optJSONObject(i);
            if (item != null) updated.put(item);
        }
        prefs.edit().putString("custom_sites_json", updated.toString()).apply();
        showHome();
    }

    private void openQuickSite(String url) {
        showBrowser();
        address.setText(url);
        loadAddress();
    }

    private void showPhoneGallery() {
        currentPage = "gallery";
        resetContent();
        addSection(content, "معرض الصور", "اختار صورة من جهازك واعرض معاينتها");
        TextView note = text("اختار صورة لعرضها هنا. إرسال الملفات المحلية للتلفزيون يحتاج مشاركة الملف عبر اتصال يمكن لجهاز الاستقبال الوصول إليه.", 14, MUTED, false);
        note.setPadding(dp(8), dp(12), dp(8), dp(12));
        content.addView(note, lp(-1, -2));
        TextView open = button("اختيار عدة صور", BLUE, WHITE);
        content.addView(open, lp(-1, 48));
        open.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_IMAGE_REQUEST);
            } catch (Exception e) {
                Toast.makeText(this, "مفيش تطبيق لاختيار الصور متاح على الجهاز", Toast.LENGTH_LONG).show();
            }
        });
        if (selectedImageUri != null) {
            android.widget.ImageView preview = new android.widget.ImageView(this);
            preview.setImageURI(selectedImageUri);
            preview.setAdjustViewBounds(true);
            preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams pp = lp(-1, 280);
            pp.topMargin = dp(12);
            content.addView(preview, pp);
        }
        TextView send = button("إرسال الصورة إلى التلفزيون", GOLD, BG);
        LinearLayout.LayoutParams sp = lp(-1, 48); sp.topMargin = dp(10);
        content.addView(send, sp);
        send.setEnabled(selectedImageUri != null);
        send.setAlpha(selectedImageUri != null ? 1f : 0.5f);
        send.setOnClickListener(v -> {
            if (selectedImageUri == null) {
                Toast.makeText(this, "اختار صورة الأول", Toast.LENGTH_SHORT).show();
                return;
            }
            serveSelectedImageAndCast();
        });
        if (slideshowQueue.size() > 1) {
            TextView slide = button(slideshowRunning ? "إيقاف عرض الشرائح" : "عرض الشرائح تلقائيًا كل 5 ثوانٍ", PANEL2, GOLD);
            LinearLayout.LayoutParams slp=lp(-1,46); slp.topMargin=dp(8); content.addView(slide,slp);
            slide.setOnClickListener(v -> {
                if (slideshowRunning) {
                    slideshowRunning = false;
                    slideshowHandler.removeCallbacks(slideshowRunnable);
                    slide.setText("عرض الشرائح تلقائيًا كل 5 ثوانٍ");
                } else {
                    slideshowRunning = true;
                    currentSlideIndex = 0;
                    selectedImageUri = slideshowQueue.get(0);
                    serveSelectedImageAndCast();
                    slideshowHandler.postDelayed(slideshowRunnable, 5000);
                    slide.setText("إيقاف عرض الشرائح");
                }
            });
        }
        TextView back = button("الرجوع للرئيسية", PANEL2, WHITE);
        LinearLayout.LayoutParams bp = lp(-1, 44); bp.topMargin = dp(8);
        content.addView(back, bp);
        back.setOnClickListener(v -> showHome());
    }

    private void quickLink(LinearLayout parent,String title,String url) {
        TextView t=button(title+"     ↗",PANEL,WHITE);
        LinearLayout.LayoutParams p=lp(-1,48);p.topMargin=dp(6);
        parent.addView(t,p);
        t.setOnClickListener(v->{showBrowser(); address.setText(url);loadAddress();});
    }

    private void showBrowser() {
        header.setVisibility(View.GONE); bottom.setVisibility(View.GONE);
        currentPage="browser";
        resetContent();
        if(web==null) createWebView();
        ensureBrowserTabRegistered();
        browserTools=column();
        LinearLayout addressRow=row();
        address=new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setTextColor(WHITE);
        address.setHintTextColor(MUTED);
        address.setHint("ابحث أو اكتب رابط الموقع");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(dp(12),0,dp(12),0);
        address.setBackground(shape(PANEL2,BLUE,14));
        TextView capture=button("▣",PANEL2,GOLD);
        capture.setContentDescription("التقاط الفيديو المشغّل حاليًا وإرساله للتلفزيون");
        capture.setBackground(shape(PANEL2,GOLD,50));
        LinearLayout.LayoutParams cap=lp(34,34);cap.setMargins(0,0,dp(5),0);addressRow.addView(capture,cap);
        capture.setOnClickListener(v -> captureCurrentPlayingVideo());
        addressRow.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView voice=button("🎙",PANEL2,GOLD);
        voice.setContentDescription("البحث الصوتي باستخدام الميكروفون");
        LinearLayout.LayoutParams voiceParams=lp(34,34);voiceParams.setMargins(0,0,dp(4),0);
        addressRow.addView(voice,voiceParams);
        voice.setOnClickListener(v -> beginNativeVoiceSearch(address));
        TextView clear=button("×",PANEL2,WHITE);
        clear.setContentDescription("مسح عنوان الرابط");
        LinearLayout.LayoutParams clp=lp(32,42);clp.setMargins(dp(3),0,0,0);addressRow.addView(clear,clp);
        clear.setOnClickListener(v -> address.setText(""));
        TextView go=button("↵",BLUE,WHITE);
        go.setContentDescription("فتح الرابط أو تنفيذ البحث");
        LinearLayout.LayoutParams gp=lp(34,42);gp.setMargins(dp(3),0,0,0);addressRow.addView(go,gp);
        browserTools.addView(addressRow);
        LinearLayout controls=row();
        addSmallControl(controls,"‹",()->{if(web.canGoBack())web.goBack();else Toast.makeText(this,"مفيش صفحة سابقة",Toast.LENGTH_SHORT).show();});
        addSmallControl(controls,"›",()->{if(web.canGoForward())web.goForward();else Toast.makeText(this,"مفيش صفحة تالية",Toast.LENGTH_SHORT).show();});
        addSmallControl(controls,"↻",()->web.reload());
        addSmallControl(controls,"⌂",()->showHome());
        addSmallControl(controls,"☆",()->saveBookmark());
        addSmallControl(controls,"▣",()->showBrowserTabsDialog());
        addSmallControl(controls,"＋",()->openNewBrowserTab());
        addSmallControl(controls,"⚙",()->showBrowserSettingsDialog());
        content.addView(browserTools,lp(-1,-2));
        pageTitle=text("جاهز للتصفح",11,MUTED,false);
        pageTitle.setVisibility(View.GONE);
        content.addView(pageTitle,lp(-1,0));
        if(web.getParent()!=null) ((android.view.ViewGroup)web.getParent()).removeView(web);
        content.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        // Keep browser navigation controls, but move video capture into the compact TV icon beside the URL.
        castActionButton = capture;
        mediaLabel = null;
        mediaPanel = null;
        LinearLayout.LayoutParams navParams = lp(-1,44);
        navParams.topMargin = dp(2);
        content.addView(controls, navParams);
        go.setOnClickListener(v->loadAddress());
        address.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_GO || (event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER && event.getAction()==KeyEvent.ACTION_DOWN)){loadAddress();return true;} return false;
        });
        if(web.getUrl()!=null) address.setText(web.getUrl());
        refreshConnectionLabel();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureBrowserTabRegistered() {
        if (web == null) return;
        int existing = browserTabs.indexOf(web);
        if (existing >= 0) {
            activeBrowserTab = existing;
            return;
        }
        browserTabs.add(web);
        browserTabTitles.add("تبويب " + browserTabs.size());
        activeBrowserTab = browserTabs.size() - 1;
    }

    private void openNewBrowserTab() {
        if (web != null) ensureBrowserTabRegistered();
        resetContent();
        createWebView();
        ensureBrowserTabRegistered();
        showBrowser();
        if (address != null) address.setText("");
    }

    private void switchBrowserTab(int index) {
        if (index < 0 || index >= browserTabs.size()) return;
        activeBrowserTab = index;
        web = browserTabs.get(index);
        showBrowser();
    }

    private void showBrowserTabsDialog() {
        if (web != null) ensureBrowserTabRegistered();
        if (browserTabs.isEmpty()) {
            openNewBrowserTab();
            return;
        }
        String[] labels = new String[browserTabs.size()];
        for (int i = 0; i < browserTabs.size(); i++) {
            WebView tab = browserTabs.get(i);
            String title = i < browserTabTitles.size() ? browserTabTitles.get(i) : "";
            if (tab.getUrl() != null && !tab.getUrl().trim().isEmpty() && !"about:blank".equals(tab.getUrl())) {
                labels[i] = (i == activeBrowserTab ? "● " : "") + (title == null || title.trim().isEmpty() ? tab.getUrl() : title);
            } else {
                labels[i] = (i == activeBrowserTab ? "● " : "") + "تبويب جديد " + (i + 1);
            }
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("علامات التبويب المفتوحة (" + browserTabs.size() + ")")
                .setItems(labels, (d, which) -> switchBrowserTab(which))
                .setPositiveButton("＋ تبويب جديد", (d, which) -> openNewBrowserTab());
        if (browserTabs.size() > 1) {
            dialog.setNegativeButton("إغلاق التبويب الحالي", (d, which) -> closeCurrentBrowserTab());
        }
        dialog.setNeutralButton("إلغاء", null).show();
    }

    private void closeCurrentBrowserTab() {
        if (browserTabs.size() <= 1) {
            Toast.makeText(this, "لازم تسيب تبويب واحد مفتوح على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = Math.max(0, Math.min(activeBrowserTab, browserTabs.size() - 1));
        WebView removed = browserTabs.remove(index);
        if (index < browserTabTitles.size()) browserTabTitles.remove(index);
        try { removed.stopLoading(); removed.destroy(); } catch (Exception ignored) { }
        activeBrowserTab = Math.max(0, index - 1);
        web = browserTabs.get(activeBrowserTab);
        showBrowser();
    }

    private void createWebView() {
        web=new WebView(this);
        BrowserEngine.configure(web);
        SharedPreferences browserPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        WebSettings browserSettings = web.getSettings();
        browserSettings.setJavaScriptEnabled(browserPrefs.getBoolean("browser_javascript", true));
        boolean acceptCookies = browserPrefs.getBoolean("browser_cookies", true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(acceptCookies);
        cookies.setAcceptThirdPartyCookies(web, acceptCookies);
        if (browserPrefs.getBoolean("browser_desktop_mode", false)) {
            browserSettings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }
        browserSettings.setMediaPlaybackRequiresUserGesture(
                !browserPrefs.getBoolean("browser_autoplay", true));
        web.setBackgroundColor(BG);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenVideoView != null) { callback.onCustomViewHidden(); return; }
                fullscreenVideoView = view;
                fullscreenVideoCallback = callback;
                orientationBeforeFullscreen = getRequestedOrientation();
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                header.setVisibility(View.GONE);
                bottom.setVisibility(View.GONE);
                content.setVisibility(View.GONE);
                shellOverlay.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
            @Override public void onHideCustomView() { exitVideoFullscreen(); }
            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) {
                runOnUiThread(() -> requestBrowserMicrophonePermission(request));
            }
            @Override public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingWebPermissionRequest == request) {
                        pendingWebPermissionRequest = null;
                        Log.i("AboEzzatBrowser", "Website canceled its microphone permission request");
                    }
                });
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (browserFileCallback != null) {
                    browserFileCallback.onReceiveValue(null);
                    browserFileCallback = null;
                }
                browserFileCallback = filePathCallback;
                try {
                    Intent picker = new Intent(Intent.ACTION_GET_CONTENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("*/*");
                    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams != null && fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(picker, "اختار ملفًا"), PICK_BROWSER_FILE_REQUEST);
                    return true;
                } catch (Exception e) {
                    browserFileCallback.onReceiveValue(null);
                    browserFileCallback = null;
                    Toast.makeText(MainActivity.this, "تعذّر فتح اختيار الملفات", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                final WebView popupWeb = new WebView(MainActivity.this);
                WebSettings popupSettings = popupWeb.getSettings();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    popupSettings.setSafeBrowsingEnabled(true);
                }
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setAllowFileAccess(false);
                popupSettings.setAllowContentAccess(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    popupSettings.setAllowFileAccessFromFileURLs(false);
                    popupSettings.setAllowUniversalAccessFromFileURLs(false);
                }
                popupSettings.setDomStorageEnabled(true);
                popupSettings.setDatabaseEnabled(true);
                popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);
                popupSettings.setSupportMultipleWindows(true);
                popupSettings.setUserAgentString(view.getSettings().getUserAgentString());
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWeb, true);

                final android.app.Dialog dialog = new android.app.Dialog(MainActivity.this);
                dialog.setContentView(popupWeb);
                dialog.setOnDismissListener(d -> {
                    try { popupWeb.stopLoading(); popupWeb.destroy(); } catch (Exception ignored) { }
                });
                dialog.show();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                }
                popupWeb.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        String scheme = uri != null ? uri.getScheme() : null;
                        return scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
                    }
                    @Override public boolean onRenderProcessGone(WebView child, android.webkit.RenderProcessGoneDetail detail) {
                        try { child.destroy(); } catch (Exception ignored) { }
                        if (!isFinishing() && dialog.isShowing()) dialog.dismiss();
                        Toast.makeText(MainActivity.this, "تم إغلاق نافذة الويب بعد تعطلها", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });
                popupWeb.setWebChromeClient(new WebChromeClient() {
                    @Override public void onCloseWindow(WebView window) {
                        if (!isFinishing() && dialog.isShowing()) dialog.dismiss();
                    }
                    @Override public void onPermissionRequest(android.webkit.PermissionRequest request) {
                        runOnUiThread(() -> requestBrowserMicrophonePermission(request));
                    }
                    @Override public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
                        runOnUiThread(() -> {
                            if (pendingWebPermissionRequest == request) {
                                pendingWebPermissionRequest = null;
                                Log.i("AboEzzatBrowser", "Popup website canceled its microphone permission request");
                            }
                        });
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupWeb);
                resultMsg.sendToTarget();
                return true;
            }
            @Override public void onProgressChanged(WebView view,int progress){
                if(pageTitle!=null) pageTitle.setText(progress<100?"تحميل الصفحة… "+progress+"%":"تم تحميل الصفحة");
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri u=request.getUrl();
                if("http".equals(u.getScheme())||"https".equals(u.getScheme())) return false;
                return true;
            }
            @Override public boolean onRenderProcessGone(WebView crashedView, android.webkit.RenderProcessGoneDetail detail) {
                final int crashedIndex = browserTabs.indexOf(crashedView);
                if (crashedIndex >= 0) {
                    // Android may terminate a renderer shared by more than one WebView.
                    // Rebuild every tab to avoid retaining sibling WebViews backed by a dead renderer.
                    java.util.ArrayList<String> savedUrls = new java.util.ArrayList<>();
                    java.util.ArrayList<String> savedTitles = new java.util.ArrayList<>();
                    for (int i = 0; i < browserTabs.size(); i++) {
                        WebView tab = browserTabs.get(i);
                        String url = "";
                        try {
                            String candidate = tab.getUrl();
                            if (BrowserEngine.isWebUrl(candidate)) url = candidate;
                        } catch (Exception ignored) { }
                        if (url.isEmpty() && i == activeBrowserTab) {
                            String lastKnownUrl = prefs.getString("last_browser_url", "");
                            if (BrowserEngine.isWebUrl(lastKnownUrl)) url = lastKnownUrl;
                        }
                        savedUrls.add(url);
                        savedTitles.add(i < browserTabTitles.size() ? browserTabTitles.get(i) : "تبويب " + (i + 1));
                    }
                    final int restoreIndex = Math.max(0, Math.min(activeBrowserTab, savedUrls.size() - 1));
                    for (WebView tab : new java.util.ArrayList<>(browserTabs)) {
                        try { tab.stopLoading(); } catch (Exception ignored) { }
                        try { tab.destroy(); } catch (Exception ignored) { }
                    }
                    browserTabs.clear();
                    browserTabTitles.clear();

                    for (int i = 0; i < savedUrls.size(); i++) {
                        createWebView();
                        WebView freshTab = web;
                        browserTabs.add(freshTab);
                        browserTabTitles.add(savedTitles.get(i));
                    }
                    activeBrowserTab = restoreIndex;
                    web = browserTabs.get(activeBrowserTab);
                    showBrowser();

                    // Renderer termination is often caused by memory pressure. Reopening every
                    // heavy page at once can immediately trigger another crash. Keep the other
                    // tabs available as fresh tabs, but restore only the page the user was viewing.
                    final WebView activeFreshTab = browserTabs.get(restoreIndex);
                    final String activeRestoreUrl = savedUrls.get(restoreIndex);
                    if (!activeRestoreUrl.isEmpty()) {
                        receiverMonitorHandler.postDelayed(() -> {
                            if (!isFinishing() && browserTabs.contains(activeFreshTab)
                                    && BrowserEngine.isWebUrl(activeRestoreUrl)) {
                                try { activeFreshTab.loadUrl(activeRestoreUrl); } catch (Exception ignored) { }
                            }
                        }, 500L);
                    }
                    Toast.makeText(MainActivity.this,
                            "أندرويد أوقف عملية عرض الصفحة؛ أعدنا تجهيز المتصفح ومحاولة استعادة الصفحة",
                            Toast.LENGTH_LONG).show();
                    return true;
                }

                // A crashed popup is isolated and closed; it is not part of the tab collection.
                try { crashedView.stopLoading(); } catch (Exception ignored) { }
                try { crashedView.destroy(); } catch (Exception ignored) { }
                Toast.makeText(MainActivity.this, "تعذّر عرض نافذة الويب وتم إغلاقها", Toast.LENGTH_SHORT).show();
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                String requestUrl = request.getUrl().toString();
                if (isKnownAdRequest(requestUrl)) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                detectMedia(requestUrl);
                return super.shouldInterceptRequest(view,request);
            }
            @Override public void onPageFinished(WebView view,String url){
                if (BrowserEngine.isWebUrl(url)) {
                    // Keep a lightweight recovery point so a renderer crash cannot erase
                    // the only copy of the active page URL.
                    prefs.edit().putString("last_browser_url", url).apply();
                }
                if (view == web) {
                    if(address!=null && "browser".equals(currentPage)) address.setText(url);
                    int tabIndex = browserTabs.indexOf(view);
                    if (tabIndex >= 0) {
                        String title = view.getTitle();
                        browserTabTitles.set(tabIndex, title == null || title.trim().isEmpty() ? url : title);
                    }
                }
                try { CookieManager.getInstance().flush(); } catch (Exception ignored) { }
                saveHistory(url);
                saveHistoryTitle(url, view.getTitle());
                // Restore the legacy browser's lightweight media sniffing and YouTube ad-skip helper.
                scanPageMediaAndSkipAds(view);
                if (view == web) refreshCurrentVideoIndicator();
            }
        });
        web.setDownloadListener((url,ua,disposition,mime,length)->downloadUrl(url,mime));
    }

    private String currentPlayingVideoUrl = "";

    private void refreshCurrentVideoIndicator() {
        if (web == null) return;
        String js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));"
                + "var v=vs.find(function(x){return !x.paused&&!x.ended&&x.readyState>=2&&(x.currentSrc||x.src);})"
                + "||vs.find(function(x){return !x.paused&&!x.ended&&(x.currentSrc||x.src);});"
                + "return v?(v.currentSrc||v.src||''):'';})()";
        web.evaluateJavascript(js, value -> {
            try {
                Object parsed = new org.json.JSONTokener(value == null ? "\"\"" : value).nextValue();
                String url = parsed == null ? "" : String.valueOf(parsed);
                if (!url.isEmpty() && !"null".equals(url)
                        && !url.startsWith("blob:") && isVideoCandidate(url)) {
                    currentPlayingVideoUrl = url;
                    if (castActionButton != null) {
                        castActionButton.setText("▣");
                        castActionButton.setTextColor(GOLD);
                        castActionButton.setBackground(shape(PANEL2, GOLD, 50));
                        castActionButton.setContentDescription("تم العثور على فيديو قابل للإرسال");
                        AlphaAnimation pulse = new AlphaAnimation(1.0f, 0.35f);
                        pulse.setDuration(700);
                        pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
                        pulse.setRepeatCount(2);
                        castActionButton.startAnimation(pulse);
                    }
                }
            } catch (Exception ignored) { }
        });
    }

    private void captureCurrentPlayingVideo() {
        if (web == null || !"browser".equals(currentPage)) {
            Toast.makeText(this, "افتح الفيديو في المتصفح أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        // Some players expose a blob: URL to the DOM while their real HTTP(S)
        // media segments are only visible to WebView's request sniffer.
        String js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));"
                + "var v=vs.find(function(x){return !x.paused&&!x.ended&&x.readyState>=2;})"
                + "||vs.find(function(x){return !x.paused&&!x.ended;});"
                + "return v?(v.currentSrc||v.src||''):'';})()";
        web.evaluateJavascript(js, value -> {
            try {
                Object parsed = new org.json.JSONTokener(value == null ? "\"\"" : value).nextValue();
                String url = parsed == null ? "" : String.valueOf(parsed);
                if (!url.isEmpty() && !url.startsWith("blob:") && isVideoCandidate(url)) {
                    currentPlayingVideoUrl = url;
                    castUrl(url);
                    return;
                }

                // Fall back to the latest direct video URL detected from this browser's
                // network requests. Do not send blob:, page URLs, audio, or images.
                String candidate = null;
                if (currentPlayingVideoUrl != null && isVideoCandidate(currentPlayingVideoUrl)
                        && !currentPlayingVideoUrl.startsWith("blob:")) {
                    candidate = currentPlayingVideoUrl;
                }
                if (candidate == null) {
                    for (int i = mediaOrder.size() - 1; i >= 0; i--) {
                        String found = mediaOrder.get(i);
                        if (isVideoCandidate(found) && !found.startsWith("blob:")
                                && !found.startsWith("data:")) {
                            candidate = found;
                            break;
                        }
                    }
                }
                if (candidate != null) {
                    currentPlayingVideoUrl = candidate;
                    Toast.makeText(this, "الفيديو يستخدم رابط بث مخفي داخل المشغّل؛ بجرب آخر رابط فيديو تم التقاطه…", Toast.LENGTH_SHORT).show();
                    castUrl(candidate);
                    return;
                }
                Toast.makeText(this, "الفيديو شغال، لكن الموقع مخفي رابط البث عن المتصفح. جرّب من غير حماية DRM أو افتح رابط فيديو مباشر.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "مش قادر أحدد رابط الفيديو المشغّل. بعض المواقع تحمي البث أو تستخدم blob.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void addSmallControl(LinearLayout row,String label,Runnable run){
        TextView b=button(label,PANEL,WHITE);
        String description = "‹".equals(label) ? "رجوع للصفحة السابقة"
                : "›".equals(label) ? "تقدم للصفحة التالية"
                : "↻".equals(label) ? "إعادة تحميل الصفحة"
                : "⌂".equals(label) ? "العودة للرئيسية"
                : "☆".equals(label) ? "إضافة للمفضلة" : label;
        b.setContentDescription(description);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);
        p.setMargins(dp(2),dp(2),dp(2),dp(2));
        row.addView(b,p);b.setOnClickListener(v->run.run());
    }

    private void loadAddress(){
        if(address==null)return;
        String input=address.getText().toString().trim();
        if(input.isEmpty())return;
        String url=BrowserEngine.normalizeAddress(input);
        saveHistory(url);
        address.setText(url);
        if(web!=null)web.loadUrl(url);
    }

    /**
     * Restores the old browser's passive media scan and YouTube skip-ad helper.
     * It only records HTTP(S) video sources exposed by the page; it does not try
     * to bypass DRM, login checks, or protected blob streams.
     */
    private boolean isKnownAdRequest(String requestUrl) {
        if (requestUrl == null || requestUrl.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(requestUrl);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String[] adDomains = {
                    "doubleclick.net", "adservice.google.com", "googlesyndication.com",
                    "adcolony.com", "criteo.com", "adnxs.com", "outbrain.com",
                    "taboola.com", "popads.net", "popcash.net", "propellerads.com",
                    "exoclick.com", "smartadserver.com", "scorecardresearch.com",
                    "zedo.com", "adroll.com", "pubmatic.com", "rubiconproject.com"
            };
            for (String domain : adDomains) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
            String path = uri.getPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            return (host.equals("pagead2.googlesyndication.com")
                    || host.equals("googleads.g.doubleclick.net")
                    || host.equals("www.googleadservices.com"))
                    && (lowerPath.contains("pagead") || lowerPath.contains("ads"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void scanPageMediaAndSkipAds(WebView view) {
        if (view == null) return;
        String js = "(function(){try{" +
                "if(!window.__aboezzatAdSkipTimer){" +
                "window.__aboezzatAdSkipTimer=setInterval(function(){try{" +
                "var selectors=['.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-skip-ad-button','.ytp-ad-skip-button-slot','.ytp-ad-overlay-close-button'];" +
                "for(var s=0;s<selectors.length;s++){var nodes=document.querySelectorAll(selectors[s]);" +
                "for(var i=0;i<nodes.length;i++){var n=nodes[i];if(n&&n.offsetParent!==null&&!n.disabled){n.click();}}}" +
                "}catch(e){}},700);}" +
                "var out=[];var videos=document.querySelectorAll('video');" +
                "for(var j=0;j<videos.length;j++){var v=videos[j];var u=v.currentSrc||v.src;" +
                "if(u&&/^https?:/i.test(u)&&out.indexOf(u)<0)out.push(u);}" +
                "var meta=document.querySelector(\"meta[property='og:video'],meta[property='og:video:secure_url']\");" +
                "if(meta&&meta.content&&/^https?:/i.test(meta.content)&&out.indexOf(meta.content)<0)out.push(meta.content);" +
                "return JSON.stringify(out);}catch(e){return '[]';}})()";
        view.evaluateJavascript(js, value -> {
            try {
                Object decoded = new org.json.JSONTokener(value == null ? "\"[]\"" : value).nextValue();
                if (!(decoded instanceof String)) return;
                org.json.JSONArray urls = new org.json.JSONArray((String) decoded);
                for (int i = 0; i < urls.length(); i++) {
                    String url = urls.optString(i, "");
                    if (isVideoCandidate(url)) detectMedia(url);
                }
            } catch (Exception ignored) { }
        });
    }

    private synchronized void detectMedia(String url){
        if(url==null||url.trim().isEmpty()||url.startsWith("blob:")||url.startsWith("data:"))return;
        // Match the media extension in the URL path, not in unrelated query parameters.
        String path=Uri.parse(url).getPath();
        String lower=(path==null?url:path).toLowerCase(Locale.ROOT);
        boolean ok=false;
        String[] exts={".mp4",".m4v",".webm",".mov",".avi",".mkv",".3gp",".ts",".m3u8",".mpd",".mp3",".m4a",".aac",".ogg",".oga",".opus",".wav",".flac",".mid",".midi",".jpg",".jpeg",".png",".webp",".gif",".bmp",".avif"};
        for(String e:exts){if(lower.endsWith(e)){ok=true;break;}}
        // Some services expose media through extensionless endpoints or signed URLs.
        // Use known media-specific URL markers without treating every generic request as media.
        String lowerUrl=url.toLowerCase(Locale.ROOT);
        if (!ok) {
            String host = Uri.parse(url).getHost();
            String lowerHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
            boolean socialVideoHost = lowerHost.contains("tiktokcdn")
                    || lowerHost.contains("tiktokv.com") || lowerHost.contains("byteoversea")
                    || lowerHost.contains("ibytedtos") || lowerHost.contains("muscdn")
                    || lowerHost.contains("fbcdn.net") || lowerHost.contains("fbsbx.com")
                    || lowerHost.contains("cdninstagram.com");
            boolean videoMarker = lowerUrl.contains("mime=video") || lowerUrl.contains("video_playback")
                    || lowerUrl.contains("/videoplayback") || lowerUrl.contains("pull-")
                    || lowerUrl.contains("format=mp4") || lowerUrl.contains("type=video")
                    || lowerUrl.contains("contenttype=video") || lowerUrl.contains("content-type=video")
                    || lowerUrl.contains("video") || lowerUrl.contains("playback")
                    || lowerUrl.contains("bytevc") || lowerUrl.contains("avc1")
                    || lowerUrl.contains("hevc") || lowerUrl.contains("dash");
            ok = (socialVideoHost && videoMarker)
                    || lowerUrl.contains("mime=audio") || lowerUrl.contains("type=audio");
        }
        if(!ok||media.containsKey(url))return;
        runOnUiThread(()->{
            if(media.containsKey(url))return;
            media.put(url,url);mediaOrder.add(url);
            if (isVideoCandidate(url)) currentPlayingVideoUrl = url;
            autoSelectDetectedTrack(url);
            // Automatically cast a captured direct video from the current platform page.
            // Debounce for five seconds so Share can collect multiple quality variants first.
            if (isVideoCandidate(url) && web != null && "browser".equals(currentPage)) {
                String activePage = web.getUrl();
                if (isDirectPlatformUrl(activePage)) {
                    pendingAutoCastPageUrl = activePage;
                    receiverMonitorHandler.removeCallbacks(autoCastDetectedMediaRunnable);
                    receiverMonitorHandler.postDelayed(autoCastDetectedMediaRunnable, 5000L);
                }
            }
            if(mediaLabel!=null)mediaLabel.setText("المصادر المكتشفة: "+mediaOrder.size());
            if(mediaPanel!=null)mediaPanel.setVisibility(View.VISIBLE);
            if(castActionButton!=null) {
                AlphaAnimation pulse = new AlphaAnimation(1.0f, 0.35f);
                pulse.setDuration(650);
                pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
                pulse.setRepeatCount(android.view.animation.Animation.INFINITE);
                castActionButton.startAnimation(pulse);
            }
        });
    }

    private LinearLayout.LayoutParams remoteButtonParams() {
        // Compact, equal-width controls: keep labels inside the button bounds.
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(36), 1f);
        p.setMargins(dp(4), dp(1), dp(4), dp(1));
        return p;
    }

    private void compactRemoteButton(TextView button) {
        button.setTextSize(12);
        button.setPadding(dp(2), dp(1), dp(2), dp(1));
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMaxLines(1);
        button.setHorizontallyScrolling(false);
    }

    private void sendRemoteCommand(String command) {
        if (receiverIp == null || receiverIp.trim().isEmpty()) {
            Toast.makeText(this, "حدّد عنوان IP للريسيفر أولًا من إعدادات الاستقبال.", Toast.LENGTH_LONG).show();
            return;
        }
        CastingManager.sendCommand(receiverIp.trim(), command, (success, message) ->
                runOnUiThread(() -> {
                    if (!success) {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    }
                }));
    }

    private void showMedia(){
        header.setVisibility(View.VISIBLE); bottom.setVisibility(View.VISIBLE);
        currentPage="media";resetContent();
        addSection(content,"مركز الوسائط","اختار ملفات من هاتفك أو أرسل رابطًا اكتشفه المتصفح");
        TextView pickLocal = button("اختيار فيديوهات أو ملفات صوتية من الهاتف", BLUE, WHITE);
        content.addView(pickLocal,lp(-1,48));
        pickLocal.setOnClickListener(v -> openLocalMediaPicker());
        TextView iptv = button("إدخال رابط بث مباشر IPTV", PANEL2, GOLD);
        LinearLayout.LayoutParams iptvParams = lp(-1, 46);
        iptvParams.topMargin = dp(6);
        content.addView(iptv, iptvParams);
        iptv.setOnClickListener(v -> showIptvUrlDialog());
        TextView recentCast = button("الوسائط المرسلة مؤخرًا", PANEL2, WHITE);
        LinearLayout.LayoutParams recentParams = lp(-1, 44);
        recentParams.topMargin = dp(6);
        content.addView(recentCast, recentParams);
        recentCast.setOnClickListener(v -> showRecentCastDialog());
        LinearLayout mediaActions = row();
        TextView playlist = button("قائمة التشغيل (" + localMediaQueue.size() + ")", PANEL2, GOLD);
        TextView playSelected = button("▶ تشغيل المحدد", BLUE, WHITE);
        mediaActions.addView(playlist, new LinearLayout.LayoutParams(0, dp(46), 1));
        mediaActions.addView(playSelected, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams qlp=lp(-1,48); qlp.topMargin=dp(6); content.addView(mediaActions,qlp);
        playlist.setOnClickListener(v -> showLocalPlaylist());
        playSelected.setOnClickListener(v -> {
            if (localMediaQueue.isEmpty()) {
                Toast.makeText(this, "اختار فيديو أو ملف صوتي من الهاتف أولاً", Toast.LENGTH_LONG).show();
                openLocalMediaPicker();
            } else {
                playSelectedLocalMedia();
            }
        });
        if (!localMediaQueue.isEmpty()) {
            Uri current = localMediaQueue.get(Math.min(currentLocalMediaIndex, localMediaQueue.size()-1));
            TextView now = text("يعمل حاليًا: " + getDisplayName(current), 13, WHITE, true);
            now.setPadding(dp(4),dp(8),dp(4),dp(8)); content.addView(now,lp(-1,-2));
        }
        LinearLayout remote = column();
        remote.setPadding(dp(6),dp(4),dp(6),dp(4));
        remote.setBackground(shape(PANEL2,PANEL,14));
        remote.addView(text("ريموت التشغيل",13,WHITE,true),lp(-1,dp(22)));
        LinearLayout r1 = row();
        TextView rw = button("⏪ 10 ث",PANEL,WHITE);
        TextView play = button("▶ / ⏸",BLUE,WHITE);
        TextView ff = button("10 ث ⏩",PANEL,WHITE);
        compactRemoteButton(rw);
        compactRemoteButton(play);
        compactRemoteButton(ff);
        r1.addView(rw,remoteButtonParams());
        r1.addView(play,remoteButtonParams());
        r1.addView(ff,remoteButtonParams());
        remote.addView(r1,lp(-1,dp(38)));
        LinearLayout r2 = row();
        TextView previous = button("⏮ السابق",PANEL,WHITE);
        TextView next = button("التالي ⏭",PANEL,WHITE);
        compactRemoteButton(previous);
        compactRemoteButton(next);
        r2.addView(previous, remoteButtonParams());
        r2.addView(next, remoteButtonParams());
        remote.addView(r2, lp(-1, dp(38)));
        LinearLayout r3 = row();
        TextView volumeDown = button("− صوت",PANEL,WHITE);
        TextView mute = button("🔇 كتم",PANEL2,GOLD);
        TextView volumeUp = button("+ صوت",PANEL,WHITE);
        TextView stop = button("■ إيقاف",PANEL,WHITE);
        compactRemoteButton(volumeDown);
        compactRemoteButton(mute);
        compactRemoteButton(volumeUp);
        compactRemoteButton(stop);
        r3.addView(volumeDown,remoteButtonParams());
        r3.addView(mute,remoteButtonParams());
        r3.addView(volumeUp,remoteButtonParams());
        r3.addView(stop,remoteButtonParams());
        LinearLayout.LayoutParams remoteRowParams=lp(-1,dp(38));
        remoteRowParams.topMargin=dp(4);
        remote.addView(r3,remoteRowParams);
        previous.setOnClickListener(v -> sendRemoteCommand("previous"));
        next.setOnClickListener(v -> sendRemoteCommand("next"));
        volumeDown.setOnClickListener(v -> sendRemoteCommand("volume_down"));
        mute.setOnClickListener(v -> sendRemoteCommand("mute"));
        volumeUp.setOnClickListener(v -> sendRemoteCommand("volume_up"));
        play.setOnClickListener(v -> sendRemoteCommand("play_pause"));
        rw.setOnClickListener(v -> sendRemoteCommand("rw"));
        ff.setOnClickListener(v -> sendRemoteCommand("ff"));
        stop.setOnClickListener(v -> sendRemoteCommand("stop"));
        content.addView(remote,lp(-1,-2));
        TextView info=button("عرض المصادر المكتشفة ("+mediaOrder.size()+")",PANEL2,GOLD);
        LinearLayout.LayoutParams ip=lp(-1,44);ip.topMargin=dp(6);content.addView(info,ip);
        ScrollView scroll=new ScrollView(this);scroll.setVisibility(View.GONE);LinearLayout list=column();
        info.setOnClickListener(v -> {
            boolean show = scroll.getVisibility() != View.VISIBLE;
            scroll.setVisibility(show ? View.VISIBLE : View.GONE);
            info.setText((show ? "إخفاء المصادر المكتشفة (" : "عرض المصادر المكتشفة (") + mediaOrder.size() + ")");
        });
        TextView mergeTracks = button("دمج مسار فيديو وصوت مختارين", PANEL2, GOLD);
        LinearLayout.LayoutParams mergeParams = lp(-1, 42);
        mergeParams.topMargin = dp(5);
        content.addView(mergeTracks, mergeParams);
        mergeTracks.setOnClickListener(v -> sendSelectedMediaTracks());
        for(String url:new ArrayList<>(mediaOrder)){
            LinearLayout card=column();card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(shape(PANEL,PANEL2,14));
            String host=Uri.parse(url).getHost();if(host==null)host=url;
            card.addView(text(mediaType(url)+"  •  "+MediaDetector.quality(url)+"  •  "+host,14,WHITE,true),lp(-1,28));
            TextView small=text(url,10,MUTED,false);small.setMaxLines(2);small.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(small,lp(-1,36));
            LinearLayout actions=row();
            TextView cast=button("إرسال",BLUE,WHITE);
            TextView best=button("أفضل جودة",PANEL2,GOLD);
            TextView download=button("تنزيل",PANEL2,WHITE);
            actions.addView(cast,new LinearLayout.LayoutParams(0,dp(40),1));
            actions.addView(best,new LinearLayout.LayoutParams(0,dp(40),1));
            actions.addView(download,new LinearLayout.LayoutParams(0,dp(40),1));
            card.addView(actions,lp(-1,42));
            cast.setOnClickListener(v->castUrl(url));
            best.setOnClickListener(v->castBestQuality());
            download.setOnClickListener(v->downloadUrl(url, null));
            TextView selectTrack = button(selectedTrackUrls.containsKey(url) ? "✓ محدد لمسارات التشغيل" : "اختيار كمسار فيديو/صوت", PANEL2, WHITE);
            LinearLayout.LayoutParams selectParams = lp(-1, 36);
            selectParams.topMargin = dp(4);
            card.addView(selectTrack, selectParams);
            selectTrack.setOnClickListener(v -> {
                if (selectedTrackUrls.containsKey(url)) {
                    selectedTrackUrls.remove(url);
                    selectTrack.setText("اختيار كمسار فيديو/صوت");
                } else {
                    selectedTrackUrls.put(url, true);
                    selectTrack.setText("✓ محدد لمسارات التشغيل");
                }
            });
            TextView save=button(isInSavedMedia(url) ? "✓ محفوظ" : "＋ حفظ الرابط",PANEL2,GOLD);
            LinearLayout.LayoutParams saveP=lp(-1,38);saveP.topMargin=dp(5);card.addView(save,saveP);
            save.setOnClickListener(v -> { saveMediaUrl(url); showMedia(); });
            LinearLayout.LayoutParams cp=lp(-1,-2);cp.topMargin=dp(7);list.addView(card,cp);
        }
        if(mediaOrder.isEmpty()){
            TextView empty=text("لسه مفيش مصادر مكتشفة.\nافتح موقع فيديو من المتصفح وشغّل المقطع، وبعدها ارجع هنا.",14,MUTED,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(18),dp(30),dp(18),dp(30));list.addView(empty,lp(-1,130));
        }
        scroll.addView(list);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView open=button("الرجوع للمتصفح",PANEL2,WHITE);content.addView(open,lp(-1,46));open.setOnClickListener(v->showBrowser());
    }

    private JSONArray readSavedMedia() {
        try { return new JSONArray(prefs.getString("saved_media_urls", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private boolean isInSavedMedia(String url) {
        JSONArray items = readSavedMedia();
        for (int i = 0; i < items.length(); i++) if (url.equals(items.optString(i))) return true;
        return false;
    }

    private void saveMediaUrl(String url) {
        JSONArray items = readSavedMedia();
        if (isInSavedMedia(url)) {
            Toast.makeText(this, "الرابط محفوظ بالفعل", Toast.LENGTH_SHORT).show();
            return;
        }
        items.put(url);
        prefs.edit().putString("saved_media_urls", items.toString()).apply();
        Toast.makeText(this, "تم حفظ رابط الوسائط", Toast.LENGTH_SHORT).show();
    }

    private void showSavedMedia() {
        currentPage = "saved_media";
        resetContent();
        addSection(content, "الوسائط المحفوظة", "روابط محفوظة تقدر ترسلها أو تنزلها لاحقًا");
        JSONArray items = readSavedMedia();
        if (items.length() == 0) {
            content.addView(text("مفيش روابط محفوظة لسه. احفظ أي مصدر من مركز الوسائط.", 14, MUTED, false), lp(-1, 60));
        } else {
            for (int i = 0; i < items.length(); i++) {
                final String url = items.optString(i);
                if (url.isEmpty()) continue;
                LinearLayout card = column();
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setBackground(shape(PANEL, PANEL2, 14));
                String host = Uri.parse(url).getHost();
                card.addView(text(mediaType(url) + " • " + (host == null ? url : host), 14, WHITE, true), lp(-1, 28));
                TextView link = text(url, 10, MUTED, false);
                link.setMaxLines(2);
                link.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(link, lp(-1, 36));
                LinearLayout actions = row();
                TextView send = button("إرسال", BLUE, WHITE);
                TextView download = button("تنزيل", PANEL2, WHITE);
                TextView remove = button("حذف", PANEL2, GOLD);
                actions.addView(send, new LinearLayout.LayoutParams(0, dp(40), 1));
                actions.addView(download, new LinearLayout.LayoutParams(0, dp(40), 1));
                actions.addView(remove, new LinearLayout.LayoutParams(0, dp(40), 1));
                card.addView(actions, lp(-1, 42));
                send.setOnClickListener(v -> castUrl(url));
                download.setOnClickListener(v -> downloadUrl(url, null));
                remove.setOnClickListener(v -> {
                    JSONArray latest = readSavedMedia();
                    JSONArray kept = new JSONArray();
                    for (int j = 0; j < latest.length(); j++) {
                        String candidate = latest.optString(j);
                        if (!url.equals(candidate)) kept.put(candidate);
                    }
                    prefs.edit().putString("saved_media_urls", kept.toString()).apply();
                    showSavedMedia();
                });
                LinearLayout.LayoutParams cp = lp(-1, -2);
                cp.topMargin = dp(7);
                content.addView(card, cp);
            }
        }
        TextView back = button("الرجوع لمركز الوسائط", PANEL2, WHITE);
        LinearLayout.LayoutParams bp = lp(-1, 44);
        bp.topMargin = dp(12);
        content.addView(back, bp);
        back.setOnClickListener(v -> showMedia());
    }

    private boolean isAudioTrackCandidate(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return "صوت".equals(mediaType(url)) || lower.contains("mime=audio")
                || lower.contains("type=audio") || lower.contains("audio_playback")
                || lower.contains("dash_ln_heaac");
    }

    // Match the legacy browser behavior: preselect one video and one separate audio track when found.
    private void autoSelectDetectedTrack(String url) {
        boolean hasVideo = false;
        boolean hasAudio = false;
        for (String selected : selectedTrackUrls.keySet()) {
            if (isVideoCandidate(selected)) hasVideo = true;
            if (isAudioTrackCandidate(selected)) hasAudio = true;
        }
        if (!hasVideo && isVideoCandidate(url)) selectedTrackUrls.put(url, true);
        if (!hasAudio && isAudioTrackCandidate(url)) selectedTrackUrls.put(url, true);
    }

    private void sendSelectedMediaTracks() {
        String videoUrl = null;
        String audioUrl = null;
        for (String url : new ArrayList<>(selectedTrackUrls.keySet())) {
            if (!Boolean.TRUE.equals(selectedTrackUrls.get(url))) continue;
            String lower = url.toLowerCase(Locale.ROOT);
            boolean audioTrack = "صوت".equals(mediaType(url))
                    || lower.contains("mime=audio") || lower.contains("type=audio")
                    || lower.contains("audio_playback") || lower.contains("dash_ln_heaac");
            if (audioTrack && audioUrl == null) audioUrl = url;
            else if (isVideoCandidate(url) && videoUrl == null) videoUrl = url;
        }
        if (videoUrl == null) {
            Toast.makeText(this, "اختار مسار فيديو أولًا، ويمكنك تحديد مسار صوت إضافي معه", Toast.LENGTH_LONG).show();
            return;
        }
        if (audioUrl == null) {
            castUrl(videoUrl);
            return;
        }
        if (receiverIp.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("إعداد الاستقبال")
                    .setMessage("لازم تكتشف جهاز AboEzzat Receiver أو تضبط عنوانه قبل دمج المسارات.")
                    .setPositiveButton("ضبط الآن", (d, w) -> receiverSettings())
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        final String ip = receiverIp;
        final String selectedVideoUrl = videoUrl;
        final String selectedAudioUrl = audioUrl;
        Toast.makeText(this, "جاري إرسال مساري الفيديو والصوت معًا…", Toast.LENGTH_SHORT).show();
        CastingManager.sendTracks(ip, selectedVideoUrl, selectedAudioUrl, (success, message) -> runOnUiThread(() -> {
            receiverConnected = success;
            refreshConnectionLabel();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (success) {
                rememberCastUrl(selectedVideoUrl);
                rememberCastUrl(selectedAudioUrl);
                trackReceiverExtraction(ip);
            } else if (receiverIp == null || receiverIp.trim().isEmpty()) {
                discoverReceiverInBackground();
            }
        }));
    }

    private String mediaType(String url){
        String path = Uri.parse(url).getPath();
        String u = (path == null ? url : path).toLowerCase(Locale.ROOT);
        if(u.endsWith(".m3u8"))return "HLS";
        if(u.endsWith(".mpd"))return "DASH";
        if(u.endsWith(".jpg")||u.endsWith(".jpeg")||u.endsWith(".png")||u.endsWith(".webp")||u.endsWith(".gif")||u.endsWith(".bmp")||u.endsWith(".avif"))return "صورة";
        if(u.endsWith(".mp3")||u.endsWith(".m4a")||u.endsWith(".aac")||u.endsWith(".ogg")||u.endsWith(".oga")||u.endsWith(".opus")||u.endsWith(".wav")||u.endsWith(".flac")||u.endsWith(".mid")||u.endsWith(".midi"))return "صوت";
        if(u.endsWith(".mp4")||u.endsWith(".m4v")||u.endsWith(".webm")||u.endsWith(".mov")||u.endsWith(".avi")||u.endsWith(".mkv")||u.endsWith(".3gp")||u.endsWith(".ts"))return "فيديو";
        return "وسائط";
    }

    private void showReceiverPicker() {
        Toast.makeText(this, "جاري البحث عن أجهزة الاستقبال على الشبكة…", Toast.LENGTH_SHORT).show();
        CastingManager.discover(this, devices -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (devices == null || devices.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("لم يتم العثور على جهاز")
                        .setMessage("تأكد أن تطبيق AboEzzat Receiver مفتوح وأن الهاتف والتلفزيون على شبكة Wi-Fi نفسها.")
                        .setPositiveButton("إعادة البحث", (d, w) -> showReceiverPicker())
                        .setNeutralButton("إدخال IP يدويًا", (d, w) -> receiverSettings())
                        .setNegativeButton("إلغاء", null)
                        .show();
                return;
            }
            java.util.ArrayList<String> valid = new java.util.ArrayList<>();
            for (String device : devices) {
                String ip = normalizeReceiverIp(device);
                if (ip != null && !valid.contains(ip)) valid.add(ip);
            }
            if (valid.isEmpty()) {
                Toast.makeText(this, "لم يتم العثور على عنوان IP صالح", Toast.LENGTH_LONG).show();
                return;
            }
            // One picker only: each row identifies the Receiver and its IP.
            String[] choices = new String[valid.size()];
            for (int i = 0; i < valid.size(); i++) {
                choices[i] = "AboEzzat Receiver  •  " + valid.get(i);
            }
            new AlertDialog.Builder(this)
                    .setTitle("جهاز الاستقبال — اختر الجهاز")
                    .setItems(choices, (dialog, which) -> {
                        String selectedIp = valid.get(which);
                        receiverIp = selectedIp;
                        receiverConnected = true;
                        prefs.edit().putString("receiver_ip", selectedIp).apply();
                        refreshConnectionLabel();
                    })
                    .setNeutralButton("إعادة البحث", (dialog, which) -> showReceiverPicker())
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("إدخال IP يدويًا", (dialog, which) -> receiverSettings())
                    .show();
        }));
    }

    private void receiverSettings(){
        EditText input=new EditText(this);
        input.setSingleLine(true);input.setHint("مثال: 192.168.1.20");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText(receiverIp);
        LinearLayout box=column();box.setPadding(dp(20),dp(10),dp(20),0);
        box.addView(text("اكتب عنوان IP الظاهر في تطبيق AboEzzat Receiver على نفس شبكة الواي فاي.",13,MUTED,false));
        TextView discover = button("⌕ البحث تلقائيًا عن Receiver", PANEL2, GOLD);
        box.addView(discover, lp(-1, 42));
        discover.setOnClickListener(v -> {
            discover.setText("جاري البحث على شبكة الواي فاي…");
            CastingManager.discover(this, devices -> runOnUiThread(() -> {
                if (devices.isEmpty()) {
                    discover.setText("لم يتم العثور عليه — اكتب IP يدويًا");
                    Toast.makeText(this, "تأكد أن Receiver مفتوح وعلى نفس شبكة الواي فاي", Toast.LENGTH_LONG).show();
                } else {
                    discover.setText("تم العثور على " + devices.size() + " جهاز — اختر يدويًا");
                    String[] choices = devices.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                        .setTitle("اختار جهاز الاستقبال")
                        .setItems(choices, (dialog, which) -> {
                            String selectedIp = normalizeReceiverIp(choices[which]);
                            if (selectedIp == null) {
                                Toast.makeText(this, "عنوان الجهاز المكتشف غير صالح", Toast.LENGTH_LONG).show();
                                return;
                            }
                            input.setText(selectedIp);
                            // The discovery routine already protocol-verified this IP.
                            receiverIp = selectedIp;
                            receiverConnected = true;
                            prefs.edit().putString("receiver_ip", selectedIp).apply();
                            refreshConnectionLabel();
                            Toast.makeText(this, "تم اختيار الريسيفر وحفظه: " + selectedIp, Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton("إلغاء", null)
                        .show();
                }
            }));
        });
        box.addView(input,lp(-1,52));
        new AlertDialog.Builder(this).setTitle("إعداد جهاز الاستقبال").setView(box)
            .setPositiveButton("حفظ", (d,w)->{
                String normalized = normalizeReceiverIp(input.getText().toString());
                if (normalized == null) {
                    Toast.makeText(this, "اكتب عنوان IPv4 صحيح مثل 192.168.1.20", Toast.LENGTH_LONG).show();
                    return;
                }
                testReceiver(normalized);
            }).setNeutralButton("اختبار الاتصال",(d,w)->{
                String ip = normalizeReceiverIp(input.getText().toString());
                if (ip == null) {
                    Toast.makeText(this, "اكتب عنوان IPv4 صحيح مثل 192.168.1.20", Toast.LENGTH_LONG).show();
                    return;
                }
                testReceiver(ip);
            }).setNegativeButton("إلغاء",null).show();
    }

    private void beginNativeVoiceSearch(EditText target) {
        if (target == null) return;
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "خدمة التعرف على الكلام غير متاحة على الجهاز. ثبّت أو فعّل تطبيق Google.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingVoiceTarget = target;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_NATIVE_VOICE);
            return;
        }
        startNativeVoiceRecognition();
    }

    private void startNativeVoiceRecognition() {
        final EditText target = pendingVoiceTarget;
        if (target == null || isFinishing()) return;
        try {
            if (nativeSpeechRecognizer != null) {
                try { nativeSpeechRecognizer.destroy(); } catch (Exception ignored) { }
                nativeSpeechRecognizer = null;
            }
            nativeSpeechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            nativeSpeechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle params) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "اتكلم دلوقتي…", Toast.LENGTH_SHORT).show());
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) {
                    runOnUiThread(() -> {
                        String message = error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                ? "إذن الميكروفون غير متاح. راجع أذونات التطبيق."
                                : error == android.speech.SpeechRecognizer.ERROR_NO_MATCH
                                || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                ? "لم أسمع كلمات واضحة. اضغط الميكروفون وحاول مرة أخرى."
                                : "تعذّر تشغيل البحث الصوتي. تأكد من الإنترنت وتفعيل خدمة Google للصوت.";
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        releaseNativeSpeechRecognizer();
                    });
                }
                @Override public void onResults(android.os.Bundle results) {
                    java.util.ArrayList<String> matches = results == null ? null
                            : results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty() && pendingVoiceTarget == target) {
                        target.setText(matches.get(0));
                        target.setSelection(target.length());
                        target.requestFocus();
                        Toast.makeText(MainActivity.this, "تم تحويل كلامك إلى نص. اضغط بحث للمتابعة.", Toast.LENGTH_SHORT).show();
                    }
                    releaseNativeSpeechRecognizer();
                }
                @Override public void onPartialResults(android.os.Bundle partialResults) { }
                @Override public void onEvent(int eventType, android.os.Bundle params) { }
            });
            android.content.Intent intent = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag());
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            nativeSpeechRecognizer.startListening(intent);
        } catch (Exception e) {
            Log.e("AboEzzatVoice", "Unable to start native voice search", e);
            releaseNativeSpeechRecognizer();
            Toast.makeText(this, "تعذّر بدء البحث الصوتي. تأكد من تفعيل خدمة التعرف على الكلام.", Toast.LENGTH_LONG).show();
        }
    }

    private void releaseNativeSpeechRecognizer() {
        if (nativeSpeechRecognizer != null) {
            try { nativeSpeechRecognizer.cancel(); } catch (Exception ignored) { }
            try { nativeSpeechRecognizer.destroy(); } catch (Exception ignored) { }
            nativeSpeechRecognizer = null;
        }
    }

    private String microphoneOriginKey(android.webkit.PermissionRequest request) {
        if (request == null || request.getOrigin() == null) return null;
        Uri origin = request.getOrigin();
        String scheme = origin.getScheme();
        String host = origin.getHost();
        if (host == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return null;
        return "browser_mic_origin_" + scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                + (origin.getPort() > 0 ? ":" + origin.getPort() : "");
    }

    private void grantWebsiteMicrophone(android.webkit.PermissionRequest request) {
        if (request == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { request.deny(); } catch (Exception ignored) { }
            Toast.makeText(this, "إذن المايك للتطبيق غير مسموح من أندرويد", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String key = microphoneOriginKey(request);
            if (key == null) {
                request.deny();
                Log.w("AboEzzatBrowser", "Denied microphone request from a non-HTTP(S) origin");
                return;
            }
            request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, true).apply();
            Log.i("AboEzzatBrowser", "Granted WebView microphone permission for " + key);
        } catch (Exception e) {
            Log.e("AboEzzatBrowser", "WebView microphone grant failed", e);
            try { request.deny(); } catch (Exception ignored) { }
            Toast.makeText(this,
                    "أندرويد رفض طلب المايك من داخل الصفحة. جرّب إعادة تحميل YouTube أو فتحه في Chrome.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmWebsiteMicrophonePermission(android.webkit.PermissionRequest request) {
        if (request == null) return;
        // The website already initiated this request (e.g. YouTube voice search).
        // Avoid a second app dialog: WebView PermissionRequest may expire while it is shown.
        // Android RECORD_AUDIO is checked independently in requestBrowserMicrophonePermission.
        grantWebsiteMicrophone(request);
    }

    private void requestBrowserMicrophonePermission(android.webkit.PermissionRequest request) {
        if (request == null) return;
        boolean wantsAudio = false;
        boolean requestsOtherResource = false;
        String[] resources = request.getResources();
        if (resources != null) {
            for (String resource : resources) {
                if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    wantsAudio = true;
                } else {
                    requestsOtherResource = true;
                }
            }
        }
        // Only approve a microphone-only request from a normal web origin.
        if (!wantsAudio || requestsOtherResource || microphoneOriginKey(request) == null) {
            try { request.deny(); } catch (Exception ignored) { }
            Log.w("AboEzzatBrowser", "Denied unsupported WebView permission request");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Keep only the latest live website request while Android asks the user.
            if (pendingWebPermissionRequest != null && pendingWebPermissionRequest != request) {
                try { pendingWebPermissionRequest.deny(); } catch (Exception ignored) { }
            }
            pendingWebPermissionRequest = request;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        confirmWebsiteMicrophonePermission(request);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showBrowserSettingsDialog() {
        boolean micGranted = android.os.Build.VERSION.SDK_INT < 23
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean javaScript = web != null && web.getSettings().getJavaScriptEnabled();
        boolean cookies = CookieManager.getInstance().acceptCookie();
        boolean desktop = web != null && web.getSettings().getUserAgentString() != null
                && web.getSettings().getUserAgentString().toLowerCase(Locale.ROOT).contains("x11");
        boolean autoplay = web != null && !web.getSettings().getMediaPlaybackRequiresUserGesture();
        String[] items = {
                (micGranted ? "🎙 المايك: إذن أندرويد مسموح" : "🎙 المايك: فتح/طلب الإذن"),
                "🎙 إرشادات إذن المايك للمواقع",
                "JavaScript: " + (javaScript ? "مفعّل" : "متوقف"),
                "ملفات تعريف الارتباط Cookies: " + (cookies ? "مفعّلة" : "متوقفة"),
                "وضع سطح المكتب: " + (desktop ? "مفعّل" : "متوقف"),
                "تشغيل الفيديو والصوت تلقائيًا: " + (autoplay ? "مفعّل" : "يتطلب ضغط تشغيل"),
                "مسح ذاكرة الصفحات المؤقتة",
                "مسح ملفات تعريف الارتباط وتسجيلات المواقع",
                "إعدادات أذونات Android للتطبيق"
        };
        new AlertDialog.Builder(this).setTitle("إعدادات المتصفح")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        if (micGranted) {
                            new AlertDialog.Builder(this).setTitle("إذن المايك")
                                    .setMessage("إذن المايك للتطبيق مسموح من Android. لازم كمان توافق على طلب الموقع نفسه عند استخدام البحث أو التسجيل الصوتي.")
                                    .setPositiveButton("حسنًا", null)
                                    .setNeutralButton("إعدادات Android", (d, w) -> openAppPermissionSettings()).show();
                        } else if (android.os.Build.VERSION.SDK_INT >= 23
                                && shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
                            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
                        } else {
                            new AlertDialog.Builder(this).setTitle("السماح بالمايك")
                                    .setMessage("لو رفضت الإذن قبل كده، افتح إعدادات التطبيق وفعّل الميكروفون ثم ارجع للمتصفح وأعد تحميل YouTube.")
                                    .setPositiveButton("فتح إعدادات التطبيق", (d, w) -> openAppPermissionSettings())
                                    .setNegativeButton("إلغاء", null).show();
                        }
                    } else if (which == 1) {
                        new AlertDialog.Builder(this).setTitle("إذن المايك للمواقع")
                                .setMessage("فيه إذنان منفصلان: إذن الميكروفون للتطبيق من Android، وإذن الموقع من داخل المتصفح. اسمح فقط للموقع اللي بتثق فيه، ثم أعد تحميل الصفحة إذا لم يظهر طلب الإذن.")
                                .setPositiveButton("حسنًا", null).show();
                    } else if (which == 2) {
                        boolean enabled = !javaScript;
                        for (WebView tab : browserTabs) if (tab != null) tab.getSettings().setJavaScriptEnabled(enabled);
                        if (web != null) web.getSettings().setJavaScriptEnabled(enabled);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("browser_javascript", enabled).apply();
                        Toast.makeText(this, "JavaScript " + (enabled ? "مفعّل" : "متوقف") + "؛ أعد تحميل الصفحة لتطبيق التغيير", Toast.LENGTH_LONG).show();
                    } else if (which == 3) {
                        boolean enabled = !cookies;
                        CookieManager.getInstance().setAcceptCookie(enabled);
                        for (WebView tab : browserTabs) if (tab != null) CookieManager.getInstance().setAcceptThirdPartyCookies(tab, enabled);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("browser_cookies", enabled).apply();
                        Toast.makeText(this, "تم تحديث إعدادات Cookies؛ قد تحتاج لتسجيل الدخول مرة أخرى", Toast.LENGTH_LONG).show();
                    } else if (which == 4) {
                        boolean enableDesktop = !desktop;
                        String ua = enableDesktop
                                ? "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                : WebSettings.getDefaultUserAgent(this);
                        for (WebView tab : browserTabs) if (tab != null) tab.getSettings().setUserAgentString(ua);
                        if (web != null) web.getSettings().setUserAgentString(ua);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("browser_desktop_mode", enableDesktop).apply();
                        Toast.makeText(this, "تم تحديث وضع سطح المكتب؛ أعد تحميل الصفحة", Toast.LENGTH_LONG).show();
                    } else if (which == 5) {
                        boolean enabled = !autoplay;
                        for (WebView tab : browserTabs) if (tab != null) tab.getSettings().setMediaPlaybackRequiresUserGesture(!enabled);
                        if (web != null) web.getSettings().setMediaPlaybackRequiresUserGesture(!enabled);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("browser_autoplay", enabled).apply();
                        Toast.makeText(this, "تم تحديث التشغيل التلقائي للوسائط", Toast.LENGTH_SHORT).show();
                    } else if (which == 6) {
                        for (WebView tab : browserTabs) if (tab != null) tab.clearCache(true);
                        if (web != null) web.clearCache(true);
                        Toast.makeText(this, "تم مسح ذاكرة الصفحات المؤقتة", Toast.LENGTH_SHORT).show();
                    } else if (which == 7) {
                        new AlertDialog.Builder(this).setTitle("مسح بيانات المواقع")
                                .setMessage("سيتم حذف Cookies وبيانات تسجيل الدخول للمواقع من المتصفح.")
                                .setPositiveButton("مسح", (d, w) -> {
                                    CookieManager.getInstance().removeAllCookies(null);
                                    CookieManager.getInstance().flush();
                                    for (WebView tab : browserTabs) if (tab != null) tab.clearHistory();
                                    Toast.makeText(this, "تم مسح Cookies وسجل التصفح", Toast.LENGTH_LONG).show();
                                }).setNegativeButton("إلغاء", null).show();
                    } else if (which == 8) {
                        openAppPermissionSettings();
                    }
                }).setNegativeButton("إغلاق", null).show();
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "افتح إعدادات أندرويد > التطبيقات > AboEzzat Share > الأذونات", Toast.LENGTH_LONG).show();
        }
    }

    private String normalizeReceiverIp(String raw) {
        if (raw == null) return null;
        String ip = raw.trim().replace("http://", "").replace("https://", "");
        int slash = ip.indexOf('/');
        if (slash >= 0) ip = ip.substring(0, slash);
        int colon = ip.indexOf(':');
        if (colon >= 0) ip = ip.substring(0, colon);
        if (!ip.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")) return null;
        return ip;
    }

    private void refreshConnectionLabel(){
        if (connectionLabel == null) return;
        if (receiverIp == null || receiverIp.isEmpty()) {
            connectionLabel.setText("● لا يوجد جهاز محدد — اضغط «جهاز الاستقبال» لاختيار شاشة");
        } else {
            connectionLabel.setText((receiverConnected ? "● الريسيفر متاح: " : "● الجهاز المختار (إعادة المحاولة على نفس العنوان): ") + receiverIp + ":8081");
        }
    }

    private void monitorReceiverConnection() {
        final String ip = receiverIp == null ? "" : receiverIp.trim();
        // No background discovery loop when the user has not selected a device.
        // Discovery is initiated explicitly from Receiver settings instead.
        if (ip.isEmpty()) {
            receiverConnected = false;
            refreshConnectionLabel();
            return;
        }
        new Thread(() -> {
            boolean connected = CastingManager.isReceiver(ip);
            runOnUiThread(() -> {
                // A temporary ping failure must not erase the selected IP or trigger
                // a scan that silently switches the user to a different receiver.
                receiverConnected = connected;
                refreshConnectionLabel();
            });
        }, "ReceiverConnectionMonitor").start();
    }

    private synchronized void discoverReceiverInBackground() {
        if (discoveryInProgress || isFinishing() || isDestroyed()) return;
        discoveryInProgress = true;
        CastingManager.discover(this, devices -> {
            if (devices == null || devices.isEmpty()) {
                runOnUiThread(() -> {
                    discoveryInProgress = false;
                    if (pendingPlatformUrl != null) {
                        pendingPlatformUrl = null;
                        Toast.makeText(this,
                                "لم يتم العثور على الريسيفر الصحيح. تأكد أن الجهازين على نفس شبكة Wi-Fi.",
                                Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            // CastingManager.discover() returns only addresses that already passed
            // the receiver's protocol-level ping, including its subnet-scan fallback.
            // Avoid probing the same verified devices a second time here.
            String verifiedIp = null;
            for (String candidate : devices) {
                if (candidate != null && !candidate.trim().isEmpty()) {
                    verifiedIp = candidate;
                    break;
                }
            }
            if (verifiedIp != null) {
                final String receiverAddress = verifiedIp;
                runOnUiThread(() -> {
                    receiverIp = receiverAddress;
                    receiverConnected = true;
                    prefs.edit().putString("receiver_ip", receiverAddress).apply();
                    refreshConnectionLabel();
                    discoveryInProgress = false;
                    String pendingUrl = pendingPlatformUrl;
                    pendingPlatformUrl = null;
                    if (pendingUrl != null) castUrl(pendingUrl);
                });
                return;
            }
            runOnUiThread(() -> {
                discoveryInProgress = false;
                // A discovery callback may contain stale or malformed candidates.
                // Never leave a pending cast silently stranded after all candidates fail
                // the Receiver's protocol-level ping verification.
                if (pendingPlatformUrl != null) {
                    pendingPlatformUrl = null;
                    Toast.makeText(this,
                            "تم العثور على عناوين غير مؤكدة لكن لم يستجب أيٌّ منها كريسيفر AboEzzat. تأكد من نفس شبكة Wi-Fi وأن تطبيق Receiver يعمل.",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void testReceiver(String ip){
        if(ip.isEmpty()){Toast.makeText(this,"اكتب عنوان IP أولاً",Toast.LENGTH_SHORT).show();return;}
        new Thread(()->{
            boolean ok=false;
             ok = CastingManager.isReceiver(ip);
            final boolean result=ok;
            runOnUiThread(()->{
                if (result) {
                    receiverConnected = true;
                    receiverIp = ip;
                    prefs.edit().putString("receiver_ip", ip).apply();
                    refreshConnectionLabel();
                } else {
                    receiverConnected = false;
                    // Preserve the current saved selection; do not replace it on a failed test.
                    refreshConnectionLabel();
                }
                Toast.makeText(this,result?"تم التحقق من الريسيفر وحفظ عنوانه للاستخدام لاحقًا":"العنوان لا يستجيب كتطبيق AboEzzat Receiver على المنفذ 8081. راجع الشبكة أو جرّب العنوان مرة أخرى.",Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private boolean isVideoCandidate(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return false;
        Uri uri = Uri.parse(candidate);
        String path = uri.getPath();
        String lowerPath = (path == null ? "" : path).toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v")
                || lowerPath.endsWith(".webm") || lowerPath.endsWith(".mov")
                || lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".mpd")) return true;
        if (lowerPath.endsWith(".mp3") || lowerPath.endsWith(".m4a")
                || lowerPath.endsWith(".aac") || lowerPath.endsWith(".ogg")
                || lowerPath.endsWith(".oga") || lowerPath.endsWith(".opus")
                || lowerPath.endsWith(".wav") || lowerPath.endsWith(".flac")
                || lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")
                || lowerPath.endsWith(".png") || lowerPath.endsWith(".webp")
                || lowerPath.endsWith(".gif") || lowerPath.endsWith(".bmp")
                || lowerPath.endsWith(".avif")) return false;
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.contains("mime=audio") || lower.contains("type=audio")
                || lower.contains("mime=image") || lower.contains("type=image")) return false;
        if (lower.contains("mime=video") || lower.contains("video_playback")
                || lower.contains("/videoplayback") || lower.contains("format=mp4")
                || lower.contains("type=video") || lower.contains("contenttype=video")
                || lower.contains("content-type=video") || lower.contains("bytevc")
                || lower.contains("avc1") || lower.contains("hevc") || lower.contains("dash")) return true;
        String host = uri.getHost();
        String lowerHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        // Social platforms often use signed, extensionless CDN URLs. Only accept known
        // video-CDN hosts when the URL also carries a video/playback codec marker.
        boolean socialCdn = lowerHost.contains("tiktokcdn") || lowerHost.contains("tiktokv.com")
                || lowerHost.contains("byteoversea") || lowerHost.contains("ibytedtos")
                || lowerHost.contains("muscdn") || lowerHost.contains("fbcdn.net")
                || lowerHost.contains("fbsbx.com") || lowerHost.contains("cdninstagram.com");
        // CDN links from Meta/X often have no file extension. Recognize their
        // known video path families, while excluding image/audio URLs above.
        boolean knownVideoPath = lower.contains("/v/t42.") || lower.contains("/v/t39.")
                || lower.contains("/v/t16.") || lower.contains("/v/t15.")
                || lower.contains("/ext_tw_video/") || lower.contains("/amplify_video/")
                || lower.contains("/tweet_video/") || lower.contains("/video/t/");
        boolean hasVideoMarker = lower.contains("video") || lower.contains("playback")
                || lower.contains("bytevc") || lower.contains("avc1") || lower.contains("hevc")
                || lower.contains("dash") || lower.contains("v/t16") || lower.contains("v/t39")
                || lower.contains("v/t42") || lower.contains("v/t15") || knownVideoPath;
        // Facebook's signed CDN paths commonly use /v/t15, /v/t39 or /v/t42;
        // Instagram/TikTok/X use their own video path/codec markers.
        return (socialCdn && hasVideoMarker)
                || ("video.twimg.com".equals(lowerHost) && knownVideoPath);
    }

    private void castBestQuality() {
        String best = null;
        int bestScore = -1;
        int bestFormatScore = -1;
        for (String candidate : mediaOrder) {
            if (!isVideoCandidate(candidate)) continue;
            String path = Uri.parse(candidate).getPath();
            String lower = (path == null ? candidate : path).toLowerCase(Locale.ROOT);
            int formatScore;
            if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) formatScore = 3;
            else if (lower.endsWith(".webm") || lower.endsWith(".mov")) formatScore = 2;
            else if (lower.endsWith(".m3u8") || lower.endsWith(".mpd")) formatScore = 1;
            else formatScore = 0; // Extensionless direct platform streams (for example videoplayback).
            String quality = MediaDetector.quality(candidate);
            int score = 0;
            try { score = Integer.parseInt(quality.replace("p", "")); } catch (Exception ignored) {}
            if (score > bestScore || (score == bestScore && formatScore > bestFormatScore)) {
                bestScore = score;
                bestFormatScore = formatScore;
                best = candidate;
            }
        }
        if (best == null) {
            Toast.makeText(this, "ملقيناش مصدر فيديو مباشر مناسب للاختيار التلقائي. اختار مصدرًا من القائمة أو جرّب تشغيل المقطع من الموقع أولًا.", Toast.LENGTH_LONG).show();
            return;
        }
        castUrl(best);
    }

    private void castCurrent(){
        String pageUrl=web==null?null:web.getUrl();
        if(pageUrl==null||pageUrl.isEmpty()){Toast.makeText(this,"افتح صفحة أولاً",Toast.LENGTH_SHORT).show();return;}
        String best=null;
        int bestScore=-1;
        int bestFormatScore=-1;
        for(String candidate:mediaOrder){
            if(!isVideoCandidate(candidate))continue;
            String path=Uri.parse(candidate).getPath();
            String lower=(path==null?candidate:path).toLowerCase(Locale.ROOT);
            int formatScore;
            if(lower.endsWith(".mp4")||lower.endsWith(".m4v"))formatScore=3;
            else if(lower.endsWith(".webm")||lower.endsWith(".mov"))formatScore=2;
            else if(lower.endsWith(".m3u8")||lower.endsWith(".mpd"))formatScore=1;
            else continue;
            String quality=MediaDetector.quality(candidate);
            int score=0;
            try{score=Integer.parseInt(quality.replace("p",""));}catch(Exception ignored){}
            if(score>bestScore||(score==bestScore&&formatScore>bestFormatScore)){
                bestScore=score;bestFormatScore=formatScore;best=candidate;
            }
        }
        if (best == null) {
            Toast.makeText(this, "الصفحة الحالية مش رابط وسائط مباشر. شغّل المقطع واختار مصدرًا من قائمة المصادر المكتشفة.", Toast.LENGTH_LONG).show();
            return;
        }
        castUrl(best);
    }

    private void showIptvUrlDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/live.m3u8");
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("بث مباشر IPTV")
                .setMessage("أدخل رابط بث مباشر متاحًا بدون حماية تمنع التشغيل.")
                .setView(input)
                .setPositiveButton("إرسال إلى التلفزيون", (dialog, which) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    Uri parsed = Uri.parse(url);
                    String scheme = parsed.getScheme();
                    if (url.isEmpty() || parsed.getHost() == null ||
                            !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                        Toast.makeText(this, "أدخل رابطًا صحيحًا يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show();
                        return;
                    }
                    castUrl(url);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void rememberCastUrl(String url) {
        try {
            JSONArray old = new JSONArray(prefs.getString("recent_cast_urls", "[]"));
            JSONArray updated = new JSONArray();
            updated.put(url);
            for (int i = 0; i < old.length() && updated.length() < 20; i++) {
                String previous = old.optString(i);
                if (!url.equals(previous)) updated.put(previous);
            }
            prefs.edit().putString("recent_cast_urls", updated.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void showRecentCastDialog() {
        JSONArray recent;
        try {
            recent = new JSONArray(prefs.getString("recent_cast_urls", "[]"));
        } catch (Exception e) {
            recent = new JSONArray();
        }
        if (recent.length() == 0) {
            new AlertDialog.Builder(this).setTitle("الوسائط المرسلة مؤخرًا")
                    .setMessage("لسه ما بعتش روابط وسائط من التطبيق.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        String[] labels = new String[recent.length()];
        String[] urls = new String[recent.length()];
        for (int i = 0; i < recent.length(); i++) {
            urls[i] = recent.optString(i);
            labels[i] = urls[i];
        }
        new AlertDialog.Builder(this).setTitle("الوسائط المرسلة مؤخرًا")
                .setItems(labels, (dialog, which) -> castUrl(urls[which]))
                .setNeutralButton("مسح السجل", (dialog, which) -> {
                    prefs.edit().remove("recent_cast_urls").apply();
                    Toast.makeText(this, "تم مسح سجل الوسائط المرسلة", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إغلاق", null).show();
    }


    private void prepareExtractionNotifications() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        EXTRACTION_CHANNEL, "استخراج الفيديو", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("حالة محاولات استخراج الفيديو المرسل إلى الريسيفر");
                manager.createNotificationChannel(channel);
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void updateExtractionNotification(String title, String message, boolean ongoing) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, EXTRACTION_CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        manager.notify(EXTRACTION_NOTIFICATION_ID, builder.build());
    }

    private void trackReceiverExtraction(String ip) {
        extractionTracking = true;
        updateExtractionNotification("AboEzzat Share", "تم إرسال الرابط؛ جاري انتظار حالة استخراج الفيديو على الريسيفر…", true);
        Thread tracker = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 90000L;
            boolean sawStatus = false;
            while (extractionTracking && System.currentTimeMillis() < deadline) {
                HttpURLConnection connection = null;
                try {
                    URL statusUrl = new URL("http://" + ip + ":8081/?command=status");
                    connection = (HttpURLConnection) statusUrl.openConnection();
                    connection.setConnectTimeout(1800);
                    connection.setReadTimeout(1800);
                    connection.setRequestProperty("Accept", "application/json");
                    if (connection.getResponseCode() == 200) {
                        StringBuilder body = new StringBuilder();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null && body.length() < 8192) body.append(line);
                        }
                        JSONObject status = new JSONObject(body.toString());
                        boolean extracting = status.optBoolean("extracting", false);
                        int attempt = status.optInt("attempt", 0);
                        String stage = status.optString("status", "waiting");
                        if (extracting) {
                            sawStatus = true;
                            String detail = "محاولة استخراج رقم " + Math.max(1, attempt)
                                    + " من 3 — " + stage + " — عملية واحدة قيد التقدم";
                            updateExtractionNotification("جاري استخراج الفيديو", detail, true);
                        } else if ("direct_playback".equals(stage)) {
                            sawStatus = true;
                            updateExtractionNotification("تم إرسال الفيديو", "الرابط مباشر؛ الريسيفر يتولى تشغيله دون حاجة لاستخراج إضافي.", false);
                            extractionTracking = false;
                            return;
                        } else if ("resolved".equals(stage)) {
                            sawStatus = true;
                            updateExtractionNotification("تم استخراج الفيديو", "الريسيفر استخرج رابط الوسائط وبدأ محاولة التشغيل.", false);
                            extractionTracking = false;
                            return;
                        } else if ("web_fallback".equals(stage)) {
                            sawStatus = true;
                            updateExtractionNotification("تجربة مشغّل الويب", "لم ينجح استخراج رابط مباشر؛ الريسيفر يجرب صفحة الفيديو داخل مشغّل الويب.", false);
                            extractionTracking = false;
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // The receiver can still be resolving or the local network may briefly pause.
                } finally {
                    if (connection != null) connection.disconnect();
                }
                try { Thread.sleep(1400L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (extractionTracking) {
                updateExtractionNotification("حالة استخراج الفيديو", sawStatus
                        ? "انتهت متابعة الحالة؛ تحقق من شاشة الريسيفر."
                        : "تم إرسال الرابط، لكن لم تصل حالة الاستخراج من الريسيفر.", false);
            }
            extractionTracking = false;
        }, "ReceiverExtractionStatus");
        tracker.setDaemon(true);
        tracker.start();
    }

    private void castUrl(String url){
        if(receiverIp.isEmpty()){new AlertDialog.Builder(this).setTitle("إعداد الاستقبال").setMessage("لازم تحفظ عنوان IP الظاهر في تطبيق AboEzzat Receiver أولاً.").setPositiveButton("ضبط الآن",(d,w)->receiverSettings()).setNegativeButton("إلغاء",null).show();return;}
        final String ip=receiverIp;
        rememberCastUrl(url);
        Toast.makeText(this,"جاري إرسال الوسائط إلى الشاشة…",Toast.LENGTH_SHORT).show();
        CastingManager.send(ip, url, (success, message) -> runOnUiThread(() -> {
            receiverConnected = success;
            refreshConnectionLabel();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (success) trackReceiverExtraction(ip);
            if (!success && (receiverIp == null || receiverIp.trim().isEmpty())) discoverReceiverInBackground();
        }));
    }

    private void saveBookmark(){
        String url=web==null?null:web.getUrl();if(url==null)return;
        JSONArray a;
        try{a=new JSONArray(prefs.getString("bookmarks","[]"));}catch(Exception e){a=new JSONArray();}
        for(int i=0;i<a.length();i++)if(url.equals(a.optString(i))){Toast.makeText(this,"الموقع موجود في المفضلة",Toast.LENGTH_SHORT).show();return;}
        a.put(url);prefs.edit().putString("bookmarks",a.toString()).apply();
        Toast.makeText(this,"اتضاف للمفضلة ☆",Toast.LENGTH_SHORT).show();
    }

    private void saveHistory(String url){
        if(url==null||(!url.startsWith("http://")&&!url.startsWith("https://")))return;
        try{
            JSONArray a=new JSONArray(prefs.getString("history","[]"));
            JSONArray b=new JSONArray();b.put(url);
            for(int i=0;i<a.length()&&i<99;i++)if(!url.equals(a.optString(i)))b.put(a.optString(i));
            prefs.edit().putString("history",b.toString()).apply();
        }catch(Exception ignored){}
    }

    private void showLibrary() {
        showLibrary(null);
    }

    private void showLibrary(String focusKey) {
        header.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        currentPage = "library";
        resetContent();

        boolean showBookmarks = focusKey == null || "bookmarks".equals(focusKey);
        boolean showHistory = focusKey == null || "history".equals(focusKey);
        String title = "bookmarks".equals(focusKey) ? "العلامات المرجعية"
                : "history".equals(focusKey) ? "سجل التصفح" : "مكتبتك";

        // The history actions remain fixed while the list itself scrolls.
        LinearLayout page = column();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        addSection(page, title, "افتح عنصرًا للرجوع إليه، واضغط مطولًا لحذفه");
        if (showBookmarks) addUrlCollection(page, "☆ المفضلة", "bookmarks", focusKey);
        if (showHistory) addUrlCollection(page, "◷ سجل التصفح", "history", focusKey);

        if (showHistory) {
            TextView clear = button("🗑  مسح سجل التصفح بالكامل", PANEL2, WHITE);
            clear.setContentDescription("مسح سجل التصفح بالكامل");
            LinearLayout.LayoutParams p = lp(-1, 50);
            p.topMargin = dp(8);
            p.bottomMargin = dp(8);
            content.addView(clear, p);
            clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("مسح سجل التصفح")
                    .setMessage("هل تريد حذف سجل التصفح بالكامل؟ لن يتم حذف المفضلة.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("مسح الكل", (d, w) -> {
                        prefs.edit().remove("history").remove("history_titles").apply();
                        showLibrary("history".equals(focusKey) ? "history" : null);
                        Toast.makeText(this, "تم مسح سجل التصفح بالكامل", Toast.LENGTH_SHORT).show();
                    }).show());
        }
    }

    private void saveHistoryTitle(String url, String pageTitle) {
        if (url == null || pageTitle == null || pageTitle.trim().isEmpty()) return;
        try {
            org.json.JSONObject titles = new org.json.JSONObject(prefs.getString("history_titles", "{}"));
            titles.put(url, pageTitle.trim());
            JSONArray history = new JSONArray(prefs.getString("history", "[]"));
            org.json.JSONObject bounded = new org.json.JSONObject();
            for (int i = 0; i < history.length(); i++) {
                String historyUrl = history.optString(i, "");
                if (titles.has(historyUrl)) bounded.put(historyUrl, titles.optString(historyUrl));
            }
            prefs.edit().putString("history_titles", bounded.toString()).apply();
        } catch (Exception ignored) { }
    }

    private String historyDisplayName(String url, String key) {
        if ("history".equals(key)) {
            try {
                org.json.JSONObject titles = new org.json.JSONObject(prefs.getString("history_titles", "{}"));
                String pageTitle = titles.optString(url, "").trim();
                if (!pageTitle.isEmpty() && !"null".equalsIgnoreCase(pageTitle)) return pageTitle;
            } catch (Exception ignored) { }
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || host.trim().isEmpty()) return url;
            String cleanHost = host.toLowerCase(Locale.ROOT);
            if (cleanHost.startsWith("www.")) cleanHost = cleanHost.substring(4);
            if (path == null || path.isEmpty() || "/".equals(path)) return cleanHost;
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].replace('-', ' ').replace('_', ' ').trim();
                if (!part.isEmpty() && !part.matches("index\\.(html?|php)")) {
                    try { part = java.net.URLDecoder.decode(part, "UTF-8"); } catch (Exception ignored) { }
                    return part + " — " + cleanHost;
                }
            }
            return cleanHost;
        } catch (Exception ignored) { return url; }
    }

    private void addUrlCollection(LinearLayout parent, String title, String key, String focusKey){
        parent.addView(text(title,16,GOLD,true),lp(-1,38));
        JSONArray a;
        try{a=new JSONArray(prefs.getString(key,"[]"));}catch(Exception e){a=new JSONArray();}
        if(a.length()==0){parent.addView(text("لا توجد عناصر بعد",12,MUTED,false),lp(-1,30));return;}
        for(int i=0;i<a.length();i++){
            String url=a.optString(i);
            String displayName=historyDisplayName(url,key);
            TextView item=button(displayName,PANEL,WHITE);
            item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            item.setMaxLines(2);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setContentDescription(displayName + " — " + url);
            LinearLayout.LayoutParams p=lp(-1,52);p.setMargins(0,dp(3),0,dp(3));parent.addView(item,p);
            item.setOnClickListener(v->{showBrowser();address.setText(url);loadAddress();});
            item.setOnLongClickListener(v->{new AlertDialog.Builder(this).setMessage("حذف من القائمة؟").setPositiveButton("حذف",(d,w)->{removeSaved(key,url);showLibrary(focusKey);}).setNegativeButton("إلغاء",null).show();return true;});
        }
    }

    private void removeSaved(String key,String url){
        try{JSONArray old=new JSONArray(prefs.getString(key,"[]"));JSONArray next=new JSONArray();for(int i=0;i<old.length();i++)if(!url.equals(old.optString(i)))next.put(old.optString(i));prefs.edit().putString(key,next.toString()).apply();}catch(Exception ignored){}
    }

    private void openDownloads() {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) {
            android.database.Cursor cursor = null;
            try {
                cursor = dm.query(new DownloadManager.Query());
                java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
                java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                java.util.ArrayList<String> titles = new java.util.ArrayList<>();
                if (cursor != null) {
                    int idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
                    int titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                    int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    while (cursor.moveToNext()) {
                        String title = titleCol >= 0 ? cursor.getString(titleCol) : "";
                        if (title == null || !title.startsWith("AboEzzat Share")) continue;
                        long id = idCol >= 0 ? cursor.getLong(idCol) : -1;
                        int status = statusCol >= 0 ? cursor.getInt(statusCol) : -1;
                        String state = status == DownloadManager.STATUS_SUCCESSFUL ? "اكتمل" :
                                status == DownloadManager.STATUS_FAILED ? "فشل التنزيل" :
                                status == DownloadManager.STATUS_RUNNING ? "جارٍ التنزيل" :
                                status == DownloadManager.STATUS_PENDING ? "في الانتظار" : "قيد المعالجة";
                        ids.add(id);
                        titles.add(title);
                        labels.add(title + " — " + state);
                    }
                }
                if (!ids.isEmpty()) {
                    String[] choices = labels.toArray(new String[0]);
                    new AlertDialog.Builder(this).setTitle("تنزيلات AboEzzat Share")
                            .setItems(choices, (dialog, which) -> {
                                long id = ids.get(which);
                                try {
                                    int status = -1;
                                    android.database.Cursor q = dm.query(new DownloadManager.Query().setFilterById(id));
                                    if (q != null) {
                                        try { if (q.moveToFirst()) status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); }
                                        finally { q.close(); }
                                    }
                                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                                        Toast.makeText(this, "الملف لسه ما اكتملش تنزيله", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    Uri uri = dm.getUriForDownloadedFile(id);
                                    if (uri == null) throw new IllegalStateException("download uri unavailable");
                                    android.content.Intent open = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                                    open.setDataAndType(uri, "*/*");
                                    open.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    startActivity(open);
                                } catch (Exception ex) {
                                    Toast.makeText(this, "تعذّر فتح الملف؛ جرّب من إشعار التنزيل", Toast.LENGTH_LONG).show();
                                }
                            }).setNegativeButton("إغلاق", null).show();
                    return;
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        new AlertDialog.Builder(this).setTitle("التنزيلات")
                .setMessage("مفيش تنزيلات مسجلة من AboEzzat Share لحد دلوقتي. بعد ما تبدأ تنزيل، هتظهر هنا، وتقدر كمان تفتح إشعار التنزيل.")
                .setPositiveButton("فتح تنزيلات الجهاز", (d, w) -> {
                    try { startActivity(new android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
                    catch (Exception e) { Toast.makeText(this, "تعذّر فتح تنزيلات الجهاز", Toast.LENGTH_LONG).show(); }
                }).setNegativeButton("إغلاق", null).show();
    }

    private void downloadCurrent(){
        String url=web==null?null:web.getUrl();
        if(url==null){Toast.makeText(this,"مفيش صفحة مفتوحة",Toast.LENGTH_SHORT).show();return;}
        downloadUrl(url,"application/octet-stream");
    }

    private String downloadMimeType(String url) {
        String path = Uri.parse(url).getPath();
        String u = (path == null ? url : path).toLowerCase(Locale.ROOT);
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".gif")) return "image/gif";
        if (u.endsWith(".avif")) return "image/avif";
        if (u.endsWith(".mp3")) return "audio/mpeg";
        if (u.endsWith(".m4a")) return "audio/mp4";
        if (u.endsWith(".aac")) return "audio/aac";
        if (u.endsWith(".ogg") || u.endsWith(".oga")) return "audio/ogg";
        if (u.endsWith(".opus")) return "audio/opus";
        if (u.endsWith(".wav")) return "audio/wav";
        if (u.endsWith(".flac")) return "audio/flac";
        if (u.endsWith(".mp4") || u.endsWith(".m4v")) return "video/mp4";
        if (u.endsWith(".webm")) return "video/webm";
        if (u.endsWith(".mov")) return "video/quicktime";
        if (u.endsWith(".avi")) return "video/x-msvideo";
        if (u.endsWith(".mkv")) return "video/x-matroska";
        if (u.endsWith(".3gp")) return "video/3gpp";
        if (u.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (u.endsWith(".mpd")) return "application/dash+xml";
        return "application/octet-stream";
    }

    private void downloadUrl(String url,String mime){
        try{
            Uri source=Uri.parse(url);
            if(!("http".equalsIgnoreCase(source.getScheme())||"https".equalsIgnoreCase(source.getScheme()))||source.getHost()==null){
                Toast.makeText(this,"رابط التنزيل لازم يكون HTTP أو HTTPS صالحًا",Toast.LENGTH_LONG).show();
                return;
            }
            String sourcePath = source.getPath();
            String lowerSourcePath = sourcePath == null ? "" : sourcePath.toLowerCase(Locale.ROOT);
            if (lowerSourcePath.endsWith(".m3u8") || lowerSourcePath.endsWith(".mpd")) {
                Toast.makeText(this,
                        "ده رابط قائمة بث متجزّئ مش ملف فيديو كامل؛ استخدم «إرسال» لتشغيله على التلفزيون.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req=new DownloadManager.Request(source);
            // Reuse the browser session for media URLs that require cookies or a page referrer.
            try {
                String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.trim().isEmpty()) req.addRequestHeader("Cookie", cookie);
            } catch (Exception ignored) {}
            try {
                String pageUrl = web == null ? null : web.getUrl();
                if (pageUrl != null && (pageUrl.startsWith("http://") || pageUrl.startsWith("https://"))) {
                    req.addRequestHeader("Referer", pageUrl);
                }
            } catch (Exception ignored) {}
            String downloadUserAgent = null;
            try { if (web != null) downloadUserAgent = web.getSettings().getUserAgentString(); } catch (Exception ignored) {}
            if (downloadUserAgent == null || downloadUserAgent.trim().isEmpty()) downloadUserAgent = System.getProperty("http.agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
            req.addRequestHeader("User-Agent", downloadUserAgent);
            String fileName=source.getLastPathSegment();
            if(fileName==null||fileName.trim().isEmpty()||".".equals(fileName)||"..".equals(fileName))fileName="media_download";
            fileName=fileName.replaceAll("[^a-zA-Z0-9._-]","_");
            if(fileName.length()>100)fileName=fileName.substring(fileName.length()-100);
            if(fileName.isEmpty()||".".equals(fileName)||"..".equals(fileName))fileName="media_download";
            // Avoid DownloadManager replacing an earlier file with the same name.
            int dot = fileName.lastIndexOf('.');
            String stamp = "_" + System.currentTimeMillis();
            fileName = dot > 0 ? fileName.substring(0, dot) + stamp + fileName.substring(dot) : fileName + stamp;
            req.setTitle("AboEzzat Share download");
            req.setDescription("تنزيل من المتصفح");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(false);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,fileName);
            String resolvedMime = mime;
            if (resolvedMime == null || resolvedMime.trim().isEmpty() || "application/octet-stream".equalsIgnoreCase(resolvedMime)) {
                String path = source.getPath();
                String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) resolvedMime = "video/mp4";
                else if (lower.endsWith(".webm")) resolvedMime = "video/webm";
                else if (lower.endsWith(".m3u8")) resolvedMime = "application/vnd.apple.mpegurl";
                else if (lower.endsWith(".mpd")) resolvedMime = "application/dash+xml";
                else if (lower.endsWith(".mp3")) resolvedMime = "audio/mpeg";
                else if (lower.endsWith(".m4a")) resolvedMime = "audio/mp4";
                else if (lower.endsWith(".aac")) resolvedMime = "audio/aac";
                else if (lower.endsWith(".ogg")) resolvedMime = "audio/ogg";
                else if (lower.endsWith(".wav")) resolvedMime = "audio/wav";
                else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) resolvedMime = "image/jpeg";
                else if (lower.endsWith(".png")) resolvedMime = "image/png";
                else if (lower.endsWith(".webp")) resolvedMime = "image/webp";
                else if (lower.endsWith(".gif")) resolvedMime = "image/gif";
                else resolvedMime = "application/octet-stream";
            }
            req.setMimeType(resolvedMime);
            dm.enqueue(req);
            Toast.makeText(this,"بدأ التنزيل — هتلاقيه في إشعارات الجهاز",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"تعذّر بدء التنزيل: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }


    private void serveSelectedImageAndCast() {
        if (receiverIp == null || receiverIp.trim().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("إعداد الاستقبال")
                    .setMessage("احفظ عنوان IP الظاهر في تطبيق AboEzzat Receiver أولاً.")
                    .setPositiveButton("ضبط الآن", (d,w) -> receiverSettings())
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        try {
            if (localMediaServer == null || localMediaServer.isClosed()) {
                localMediaServer = new ServerSocket(8082, 8, InetAddress.getByName("0.0.0.0"));
                localMediaServerRunning = true;
                new Thread(() -> {
                    while (localMediaServerRunning && localMediaServer != null && !localMediaServer.isClosed()) {
                        try {
                            Socket client = localMediaServer.accept();
                            new Thread(() -> serveLocalImage(client), "LocalImageClient").start();
                        } catch (Exception e) {
                            if (localMediaServerRunning) android.util.Log.w("AboEzzatShare", "Local media server", e);
                        }
                    }
                }, "LocalMediaServer").start();
            }
            String phoneIp = findLocalIpv4();
            if (phoneIp == null) {
                Toast.makeText(this, "اتأكد إن الموبايل والتلفزيون على نفس شبكة الواي فاي", Toast.LENGTH_LONG).show();
                return;
            }
            String localUrl = "http://" + phoneIp + ":8082/photo.jpg" + (slideshowQueue.size() > 1 ? "?index=" + currentSlideIndex + "&t=" + System.currentTimeMillis() : "");
            Toast.makeText(this, "جاري إرسال الصورة إلى التلفزيون…", Toast.LENGTH_SHORT).show();
            CastingManager.send(receiverIp.trim(), localUrl, (success, message) -> runOnUiThread(() -> {
                    receiverConnected = success;
                    refreshConnectionLabel();
                    Toast.makeText(this, success ? "تم إرسال رابط الصورة. لو ما ظهرتش، اتأكد إن الجهازين على نفس الشبكة." : message, Toast.LENGTH_LONG).show();
                    if (!success) discoverReceiverInBackground();
            }));
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر تجهيز مشاركة الصورة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private long queryLocalMediaSize(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeColumn = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    long size = cursor.getLong(sizeColumn);
                    if (size >= 0) return size;
                }
            }
        } catch (Exception e) {
            android.util.Log.d("AboEzzatShare", "Unable to query local media size", e);
        }
        return -1L;
    }

    private void serveLocalImage(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(15000);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            if (request == null) return;
            String rangeHeader = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    rangeHeader = line.substring(colon + 1).trim();
                }
            }

            boolean isPhoto = request.startsWith("GET /photo.jpg ");
            if (!isPhoto && request.startsWith("GET /photo.jpg?")) isPhoto = true;
            boolean isMedia = request.startsWith("GET /media ") || request.startsWith("GET /media?");
            String mediaSessionId = null;
            if (isMedia) {
                try {
                    int pathStart = request.indexOf("GET ") + 4;
                    int pathEnd = request.indexOf(' ', pathStart);
                    String path = request.substring(pathStart, pathEnd);
                    int queryStart = path.indexOf('?');
                    if (queryStart >= 0) {
                        for (String param : path.substring(queryStart + 1).split("&")) {
                            if (param.startsWith("id=")) {
                                mediaSessionId = java.net.URLDecoder.decode(param.substring(3), "UTF-8");
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) { mediaSessionId = null; }
            }
            int requestedSlideIndex = -1;
            if (isPhoto && slideshowQueue.size() > 1) {
                try {
                    int pathStart = request.indexOf("GET ") + 4;
                    int pathEnd = request.indexOf(' ', pathStart);
                    String path = request.substring(pathStart, pathEnd);
                    int queryStart = path.indexOf('?');
                    if (queryStart >= 0) {
                        String[] params = path.substring(queryStart + 1).split("&");
                        for (String param : params) {
                            if (param.startsWith("index=")) {
                                requestedSlideIndex = Integer.parseInt(param.substring(6));
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) { requestedSlideIndex = -1; }
            }
            OutputStream out = socket.getOutputStream();
            if (!isPhoto && !isMedia) {
                byte[] body = "Not Found".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: "
                        + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                return;
            }

            // Capture one URI per request. Media URLs carry a session id so later playlist
            // changes cannot make subsequent range requests read a different file.
            final Uri uri = isMedia
                    ? (mediaSessionId == null ? selectedMediaUri : localMediaSessions.get(mediaSessionId))
                    : (requestedSlideIndex >= 0 && requestedSlideIndex < slideshowQueue.size()
                        ? slideshowQueue.get(requestedSlideIndex) : selectedImageUri);
            if (uri == null) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }

            String mime = getContentResolver().getType(uri);
            if (mime == null || mime.trim().isEmpty()) {
                mime = isMedia ? "application/octet-stream" : "image/jpeg";
            }

            try (android.content.res.AssetFileDescriptor afd =
                         getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd == null) throw new java.io.IOException("تعذّر فتح الملف");

                long length = afd.getLength();
                // Some document providers report UNKNOWN_LENGTH from the descriptor even
                // when OpenableColumns exposes the real size. Recover it so Range requests
                // can return 206 instead of an unseekable chunked response.
                if (length < 0) length = queryLocalMediaSize(uri);
                long start = 0L;
                long end = length >= 0 ? length - 1 : -1L;
                boolean partial = false;

                if (rangeHeader != null && rangeHeader.startsWith("bytes=") && length > 0) {
                    String spec = rangeHeader.substring(6).trim();
                    int comma = spec.indexOf(',');
                    if (comma >= 0) spec = spec.substring(0, comma).trim(); // Serve the first requested range.
                    int dash = spec.indexOf('-');
                    try {
                        if (dash < 0) throw new NumberFormatException("invalid range");
                        String left = spec.substring(0, dash).trim();
                        String right = spec.substring(dash + 1).trim();
                        if (left.isEmpty()) {
                            long suffixLength = Long.parseLong(right);
                            if (suffixLength <= 0) throw new NumberFormatException("invalid suffix range");
                            start = Math.max(0L, length - suffixLength);
                            end = length - 1;
                        } else {
                            start = Long.parseLong(left);
                            end = right.isEmpty() ? length - 1 : Long.parseLong(right);
                        }
                        if (start < 0 || start >= length || end < start) throw new NumberFormatException("range out of bounds");
                        end = Math.min(end, length - 1);
                        partial = true;
                    } catch (Exception invalidRange) {
                        out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"
                                + length + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        return;
                    }
                }

                long contentLength = length >= 0 ? (end - start + 1L) : -1L;
                String status = partial ? "206 Partial Content" : "200 OK";
                StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
                        .append("Content-Type: ").append(mime).append("\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Cache-Control: no-store\r\n")
                        .append("Connection: close\r\n");
                if (partial && length >= 0) {
                    headers.append("Content-Range: bytes ").append(start).append('-').append(end)
                            .append('/').append(length).append("\r\n");
                }
                if (contentLength >= 0) {
                    headers.append("Content-Length: ").append(contentLength).append("\r\n");
                } else {
                    headers.append("Transfer-Encoding: chunked\r\n");
                }
                headers.append("\r\n");
                out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));

                try (InputStream in = afd.createInputStream()) {
                    long toSkip = start;
                    while (toSkip > 0) {
                        long skipped = in.skip(toSkip);
                        if (skipped <= 0) {
                            if (in.read() == -1) throw new java.io.IOException("تعذّر الوصول إلى موضع الملف المطلوب");
                            skipped = 1;
                        }
                        toSkip -= skipped;
                    }

                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    if (contentLength >= 0) {
                        long remaining = contentLength;
                        while (remaining > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                            out.write(buffer, 0, n);
                            remaining -= n;
                        }
                    } else {
                        while ((n = in.read(buffer)) != -1) {
                            out.write(Integer.toHexString(n).getBytes(StandardCharsets.US_ASCII));
                            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                            out.write(buffer, 0, n);
                            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                        }
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.flush();
                }
            }
        } catch (Exception e) {
            android.util.Log.w("AboEzzatShare", "Serving local media failed", e);
        }
    }

    private String findLocalIpv4() {
        // Prefer a private LAN IPv4 address on the Wi-Fi interface; avoid VPN/mobile
        // interfaces where possible so the receiver can actually reach this phone.
        String fallback = null;
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String interfaceName = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                boolean likelyWifi = interfaceName.contains("wlan") || interfaceName.contains("wifi")
                        || interfaceName.contains("swlan") || interfaceName.equals("ap0");
                // Avoid advertising VPN/tunnel or cellular addresses to the TV. Those
                // addresses can reach the internet but are commonly unreachable from LAN peers.
                boolean likelyTunnelOrCellular = interfaceName.startsWith("tun")
                        || interfaceName.startsWith("tap") || interfaceName.startsWith("wg")
                        || interfaceName.startsWith("ppp") || interfaceName.startsWith("rmnet")
                        || interfaceName.startsWith("ccmni") || interfaceName.startsWith("pdp")
                        || interfaceName.startsWith("clat") || interfaceName.startsWith("ipsec");
                java.util.Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip == null || ip.startsWith("10.0.2.")) continue;
                    boolean privateLan = ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
                    if (privateLan && likelyWifi) return ip;
                    if (likelyTunnelOrCellular) continue;
                    if (privateLan && fallback == null) fallback = ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private void openLocalMediaPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Show the Android document picker for supported media while allowing mixed multi-select.
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*", "image/*"});
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_LOCAL_MEDIA_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر فتح مستعرض الملفات", Toast.LENGTH_LONG).show();
        }
    }

    private String getDisplayName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return name == null ? "ملف وسائط" : name;
    }

    private void playSelectedLocalMedia() {
        if (localMediaQueue.isEmpty()) return;
        if (receiverIp == null || receiverIp.trim().isEmpty()) {
            receiverSettings();
            Toast.makeText(this, "اضبط عنوان IP للريسيفر أولاً", Toast.LENGTH_LONG).show();
            return;
        }
        currentLocalMediaIndex = Math.max(0, Math.min(currentLocalMediaIndex, localMediaQueue.size()-1));
        prefs.edit().putInt("local_media_index", currentLocalMediaIndex).apply();
        selectedMediaUri = localMediaQueue.get(currentLocalMediaIndex);
        final Uri mediaToPlay = selectedMediaUri;
        final String mediaName = getDisplayName(mediaToPlay);
        final String mediaSessionId = java.util.UUID.randomUUID().toString();
        // Keep a bounded set of sessions for seek/range requests from the receiver.
        if (localMediaSessions.size() >= 20) localMediaSessions.clear();
        localMediaSessions.put(mediaSessionId, mediaToPlay);
        try {
            ensureLocalMediaServer();
            String phoneIp = findLocalIpv4();
            if (phoneIp == null) {
                Toast.makeText(this, "اتأكد إن الموبايل والتلفزيون على نفس شبكة الواي فاي", Toast.LENGTH_LONG).show();
                return;
            }
            String url = "http://" + phoneIp + ":8082/media?id=" + mediaSessionId;
            CastingManager.send(receiverIp.trim(), url, (success, message) -> runOnUiThread(() -> {
                receiverConnected = success;
                refreshConnectionLabel();
                Toast.makeText(this, success ? "تم إرسال " + mediaName + " للريسيفر" : (message + "\nالجهاز المحدد محفوظ؛ لو عايز تبحث عن جهاز آخر افتح «جهاز الاستقبال» بنفسك."), Toast.LENGTH_LONG).show();
                // Do not silently switch to another Receiver after a transient send failure.
                // The user explicitly controls when a new discovery scan is requested.
            }));
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر تجهيز الملف: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void ensureLocalMediaServer() throws java.io.IOException {
        if (localMediaServer != null && !localMediaServer.isClosed()) return;
        localMediaServer = new ServerSocket(8082, 8, InetAddress.getByName("0.0.0.0"));
        localMediaServerRunning = true;
        new Thread(() -> {
            while (localMediaServerRunning && localMediaServer != null && !localMediaServer.isClosed()) {
                try {
                    Socket client = localMediaServer.accept();
                    new Thread(() -> serveLocalImage(client), "LocalMediaClient").start();
                } catch (Exception e) {
                    if (localMediaServerRunning) android.util.Log.w("AboEzzatShare", "Local media server", e);
                }
            }
        }, "LocalMediaServer").start();
    }

    private void restoreSavedMediaQueues() {
        restoreUriQueue("local_media_queue", localMediaQueue);
        restoreUriQueue("slideshow_queue", slideshowQueue);
        currentLocalMediaIndex = Math.max(0, Math.min(prefs.getInt("local_media_index", 0), Math.max(0, localMediaQueue.size() - 1)));
        currentSlideIndex = Math.max(0, Math.min(prefs.getInt("slideshow_index", 0), Math.max(0, slideshowQueue.size() - 1)));
        if (!slideshowQueue.isEmpty()) selectedImageUri = slideshowQueue.get(currentSlideIndex);
    }

    private void restoreUriQueue(String key, java.util.ArrayList<Uri> target) {
        target.clear();
        try {
            JSONArray saved = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String value = saved.optString(i, "");
                if (value.isEmpty()) continue;
                Uri uri = Uri.parse(value);
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    if (stream != null) target.add(uri);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private void saveUriQueue(String key, java.util.ArrayList<Uri> queue) {
        JSONArray saved = new JSONArray();
        for (Uri uri : queue) if (uri != null) saved.put(uri.toString());
        prefs.edit().putString(key, saved.toString()).apply();
    }

    private void showLocalPlaylist() {
        if (localMediaQueue.isEmpty()) {
            Toast.makeText(this, "قائمة التشغيل فارغة؛ اختار ملفات من الهاتف أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[localMediaQueue.size()];
        for (int i=0; i<names.length; i++) names[i] = (i == currentLocalMediaIndex ? "▶ " : "") + (i+1) + ". " + getDisplayName(localMediaQueue.get(i));
        new AlertDialog.Builder(this).setTitle("قائمة التشغيل ("+names.length+")")
            .setItems(names, (d, which) -> {
                currentLocalMediaIndex = which;
                playSelectedLocalMedia();
                showMedia();
            })
            .setNeutralButton("مسح القائمة", (d, which) -> new AlertDialog.Builder(this)
                .setTitle("مسح قائمة التشغيل؟")
                .setMessage("سيتم حذف الملفات من قائمة التطبيق فقط، ولن تُحذف الملفات الأصلية من الهاتف.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("مسح", (confirm, selected) -> {
                    localMediaQueue.clear();
                    currentLocalMediaIndex = 0;
                    selectedMediaUri = null;
                    prefs.edit().remove("local_media_queue").putInt("local_media_index", 0).apply();
                    showMedia();
                    Toast.makeText(this, "تم مسح قائمة التشغيل", Toast.LENGTH_SHORT).show();
                }).show())
            .setNegativeButton("إغلاق", null).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_BACKUP_REQUEST) { if (resultCode == RESULT_OK && data != null && data.getData() != null) exportBackup(data.getData()); return; }
        if (requestCode == RESTORE_BACKUP_REQUEST) { if (resultCode == RESULT_OK && data != null && data.getData() != null) importBackup(data.getData()); return; }
        if (requestCode == PICK_BROWSER_FILE_REQUEST) {
            if (browserFileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        android.content.ClipData clips = data.getClipData();
                        results = new Uri[clips.getItemCount()];
                        for (int i = 0; i < clips.getItemCount(); i++) results[i] = clips.getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                browserFileCallback.onReceiveValue(results);
                browserFileCallback = null;
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_LOCAL_MEDIA_REQUEST) {
            localMediaQueue.clear();
            if (data.getClipData() != null) {
                android.content.ClipData clips = data.getClipData();
                for (int i=0; i<clips.getItemCount(); i++) {
                    Uri uri = clips.getItemAt(i).getUri();
                    localMediaQueue.add(uri);
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                }
            } else if (data.getData() != null) {
                localMediaQueue.add(data.getData());
                try { getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            }
            currentLocalMediaIndex = 0;
            saveUriQueue("local_media_queue", localMediaQueue);
            prefs.edit().putInt("local_media_index", 0).apply();
            showMedia();
            Toast.makeText(this, "تمت إضافة " + localMediaQueue.size() + " ملف إلى قائمة التشغيل", Toast.LENGTH_SHORT).show();
        } else if (requestCode == PICK_IMAGE_REQUEST) {
            slideshowQueue.clear();
            if (data.getClipData() != null) {
                android.content.ClipData clips = data.getClipData();
                for (int i=0; i<clips.getItemCount(); i++) {
                    Uri uri = clips.getItemAt(i).getUri();
                    if (uri != null) {
                        slideshowQueue.add(uri);
                        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                        catch (Exception ignored) { /* Some providers grant temporary-only access. */ }
                    }
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                slideshowQueue.add(uri);
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { /* Some providers grant temporary-only access. */ }
            }
            saveUriQueue("slideshow_queue", slideshowQueue);
            if (!slideshowQueue.isEmpty()) {
                currentSlideIndex = 0;
                prefs.edit().putInt("slideshow_index", 0).apply();
                selectedImageUri = slideshowQueue.get(0);
                showPhoneGallery();
                if (slideshowQueue.size() > 1) Toast.makeText(this, "تم اختيار " + slideshowQueue.size() + " صور. تقدر تبدأ عرض الشرائح تلقائيًا من نفس الصفحة.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override public void onBackPressed(){
        if (fullscreenVideoView != null) { exitVideoFullscreen(); return; }
        if("browser".equals(currentPage)&&web!=null&&web.canGoBack()){web.goBack();return;}
        if(!"home".equals(currentPage)){showHome();return;}
        super.onBackPressed();
    }

    @Override protected void onPause() {
        // Pause visual interaction for each tab, but do NOT call WebView.pauseTimers().
        // pauseTimers() is process-wide for WebViews and can freeze unrelated WebViews
        // or leave background/return transitions in a bad state. Android may reclaim a
        // renderer under memory pressure; onRenderProcessGone handles that separately.
        for (WebView tab : new ArrayList<>(browserTabs)) {
            try { tab.onPause(); } catch (Exception ignored) { }
        }
        if ("browser".equals(currentPage) && web != null) {
            try {
                String url = web.getUrl();
                if (BrowserEngine.isWebUrl(url)) {
                    prefs.edit().putString("last_browser_url", url).apply();
                }
            } catch (Exception ignored) { }
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        for (WebView tab : new ArrayList<>(browserTabs)) {
            try { tab.onResume(); } catch (Exception ignored) { }
        }
        // Do not reload the current page on every resume: preserve the live session and
        // avoid restarting media/login flows unless Android actually killed the renderer.
        prefs.edit().putBoolean("browser_was_active", false).apply();
    }

    @Override protected void onStop() {
        if ("browser".equals(currentPage) && web != null) {
            try {
                String url = web.getUrl();
                if (BrowserEngine.isWebUrl(url)) {
                    prefs.edit()
                            .putString("last_browser_url", url)
                            .putBoolean("browser_was_active", true)
                            .apply();
                }
            } catch (Exception ignored) { }
        } else {
            prefs.edit().putBoolean("browser_was_active", false).apply();
        }
        super.onStop();
    }

    @Override protected void onDestroy(){
        // Invalidate pending authenticated WebView URL-resolution retries before
        // destroying tabs so late JavaScript callbacks cannot act on stale pages.
        authenticatedResolveGeneration++;
        pendingVoiceTarget = null;
        releaseNativeSpeechRecognizer();
        receiverMonitorHandler.removeCallbacks(receiverMonitorRunnable);
        slideshowRunning = false;
        slideshowHandler.removeCallbacks(slideshowRunnable);
        if (browserFileCallback != null) { browserFileCallback.onReceiveValue(null); browserFileCallback = null; }
        if (!browserTabs.isEmpty()) {
            for (WebView tab : new ArrayList<>(browserTabs)) {
                try { tab.stopLoading(); tab.destroy(); } catch (Exception ignored) { }
            }
            browserTabs.clear();
            browserTabTitles.clear();
            web = null;
        } else if(web!=null) {
            web.stopLoading();
            web.destroy();
            web=null;
        }
        localMediaServerRunning = false;
        try { if (localMediaServer != null) localMediaServer.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}