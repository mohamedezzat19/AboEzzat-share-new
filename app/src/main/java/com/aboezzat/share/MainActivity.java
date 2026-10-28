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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
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
    private TextView pageTitle, connectionLabel, mediaLabel, castActionButton;
    private final LinkedHashMap<String,String> media = new LinkedHashMap<>();
    private final ArrayList<String> mediaOrder = new ArrayList<>();
    private String currentPage = "home";
    private String receiverIp = "";
    private boolean receiverConnected = false;
    private boolean discoveryInProgress = false;
    private int receiverMonitorCycles = 0;
    private String pendingPlatformUrl;
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
        receiverIp = "";
        prefs.edit().remove("receiver_ip").apply();
        restoreSavedMediaQueues();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildShell();
        setContentView(shellOverlay);
        showHome();
        handleSharedIntent(getIntent());
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
            String shared = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            if (shared != null && !shared.trim().isEmpty()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?i)https?://[^\\s<>\\\"']+")
                        .matcher(shared);
                if (matcher.find()) {
                    String sharedUrl = matcher.group().replaceAll("[),.]+$", "");
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
        // First open shared links inside Share's persistent WebView so the user's own
        // platform login session can resolve media URLs before the Receiver fallback.
        if (!BrowserEngine.isWebUrl(url)) {
            sendPlatformLinkToReceiver(url);
            return;
        }
        final int generation = ++authenticatedResolveGeneration;
        showBrowser();
        if (address != null) address.setText(url);
        if (web == null) createWebView();
        Toast.makeText(this, "جاري فتح الرابط بمتصفح Share واستخدام جلسة تسجيل الدخول المحفوظة…", Toast.LENGTH_LONG).show();
        web.loadUrl(url);
        receiverMonitorHandler.postDelayed(() -> resolveSharedVideoFromBrowser(url, generation, 0), 1200L);
    }

    private void resolveSharedVideoFromBrowser(String pageUrl, int generation, int attempt) {
        if (generation != authenticatedResolveGeneration || web == null || isFinishing()) return;
        final String js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));var v=vs.find(function(x){return !x.paused&&!x.ended&&x.readyState>=2&&(x.currentSrc||x.src);})||vs.find(function(x){return x.readyState>=2&&(x.currentSrc||x.src);});return v?(v.currentSrc||v.src||''):'';})()";
        web.evaluateJavascript(js, value -> {
            if (generation != authenticatedResolveGeneration || isFinishing()) return;
            try {
                Object parsed = new org.json.JSONTokener(value == null ? "\"\"" : value).nextValue();
                String mediaUrl = parsed == null ? "" : String.valueOf(parsed);
                if (!mediaUrl.isEmpty() && !"null".equals(mediaUrl)
                        && !mediaUrl.startsWith("blob:") && !mediaUrl.startsWith("data:")
                        && isVideoCandidate(mediaUrl) && !mediaUrl.equals(pageUrl)) {
                    currentPlayingVideoUrl = mediaUrl;
                    Toast.makeText(this, "تم العثور على رابط الفيديو من جلسة المتصفح؛ جاري إرساله للشاشة…", Toast.LENGTH_LONG).show();
                    castUrl(mediaUrl);
                    return;
                }
            } catch (Exception ignored) { }
            if (attempt < 18) {
                receiverMonitorHandler.postDelayed(() -> resolveSharedVideoFromBrowser(pageUrl, generation, attempt + 1), 1000L);
            } else {
                Toast.makeText(this, "لم يظهر رابط فيديو مباشر داخل المتصفح؛ سأرسل رابط الصفحة للريسيفر كخطة بديلة.", Toast.LENGTH_LONG).show();
                sendPlatformLinkToReceiver(pageUrl);
            }
        });
    }

    private void sendPlatformLinkToReceiver(String url) {
        // Always discover the live receiver; never trust a saved or stale IP.
        pendingPlatformUrl = url;
        Toast.makeText(this, "جاري العثور على عنوان الريسيفر الحالي وإرسال الفيديو…", Toast.LENGTH_LONG).show();
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

        TextView ip = text("📡  جهاز الاستقبال: " + (receiverIp == null || receiverIp.isEmpty() ? "غير محدد" : receiverIp), 12, GOLD, true);
        ip.setPadding(dp(10), dp(12), dp(10), dp(12));
        ip.setBackground(shape(PANEL, PANEL2, 12));
        LinearLayout.LayoutParams ipp = lp(-1, -2); ipp.topMargin = dp(12); ipp.bottomMargin = dp(12);
        drawerPanel.addView(ip, ipp);
        ScrollView scroll = new ScrollView(this);
        LinearLayout items = column();
        String[][] entries = {
            {"🌐", "المتصفح وقناص الفيديو", "browser"},
            {"📁", "وسائط الهاتف", "phone_media"},
            {"📺", "تشغيل IPTV", "iptv"},
            {"🕒", "الوسائط الأخيرة", "recent"},
            {"📑", "قائمة التشغيل", "playlist"},
            {"🎞️", "الوسائط المحفوظة", "saved_media"},
            {"⭐", "العلامات المرجعية", "bookmarks"},
            {"📜", "سجل التصفح", "history"},
            {"🔥", "المواقع الأكثر زيارة", "popular"},
            {"📥", "التنزيلات", "downloads"},
            {"📡", "إعداد جهاز الاستقبال (TV IP)", "receiver"},
            {"💾", "نسخة احتياطية واستعادة", "backup"},
            {"⚙️", "أذونات التطبيق", "settings"},
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
        else if ("phone_media".equals(key)) showPhoneGallery();
        else if ("iptv".equals(key)) showIptvDialog();
        else if ("recent".equals(key)) showRecentCastDialog();
        else if ("playlist".equals(key)) showLocalPlaylist();
        else if ("saved_media".equals(key)) showSavedMedia();
        else if ("bookmarks".equals(key)) showLibrary("bookmarks");
        else if ("history".equals(key)) showLibrary("history");
        else if ("popular".equals(key)) showPopularSites();
        else if ("downloads".equals(key)) openDownloads();
        else if ("receiver".equals(key)) receiverSettings();
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
        for (String key : new String[]{"bookmarks", "history", "saved_media_urls", "recent_cast_urls", "local_media_queue", "slideshow_queue"}) {
            backup.put(key, prefs.getString(key, key.endsWith("_queue") || key.equals("history") || key.equals("bookmarks") || key.equals("saved_media_urls") || key.equals("recent_cast_urls") ? "[]" : ""));
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
            for (String key : new String[]{"bookmarks", "history", "saved_media_urls", "recent_cast_urls", "local_media_queue", "slideshow_queue"}) {
                String value = backup.optString(key, "[]");
                new JSONArray(value);
                edit.putString(key, value);
            }
            edit.putInt("local_media_index", Math.max(0, backup.optInt("local_media_index", 0)));
            edit.putInt("slideshow_index", Math.max(0, backup.optInt("slideshow_index", 0)));
            edit.remove("receiver_ip");
            edit.apply();
            receiverIp = "";
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
        if (requestCode == REQUEST_MICROPHONE && pendingWebPermissionRequest != null) {
            android.webkit.PermissionRequest request = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            try {
                if (granted) request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else request.deny();
            } catch (Exception ignored) { }
            if (!granted) Toast.makeText(this, "لازم تسمح بالمايك لاستخدام البحث الصوتي", Toast.LENGTH_LONG).show();
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

        LinearLayout hero = column();
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(28,63,130), Color.rgb(19,31,63), Color.rgb(40,29,66)}));
        ((GradientDrawable) hero.getBackground()).setCornerRadius(dp(22));
        hero.addView(text("متصفح وإرسال الوسائط", 21, WHITE, true), lp(-1, 38));
        hero.addView(text("افتح موقعك واكتشف روابط الفيديو والصوت بسرعة", 12, Color.rgb(207,220,246), false), lp(-1, 28));
        TextView start = button("افتح المتصفح  ↗", BLUE, WHITE);
        LinearLayout.LayoutParams sp = lp(-1, 46); sp.topMargin = dp(10);
        hero.addView(start, sp);
        start.setOnClickListener(v -> { showBrowser(); if (web != null && web.getUrl() == null) web.loadUrl("https://www.google.com"); });
        page.addView(hero, lp(-1, -2));

        LinearLayout.LayoutParams gap = lp(-1, 14); page.addView(new View(this), gap);
        addSection(page, "وصول سريع للمنصات", "اختار المنصة وافتحها مباشرة");
        String[][] sites = {
            {"▶", "YouTube", "https://m.youtube.com"},
            {"f", "Facebook", "https://m.facebook.com"},
            {"♪", "TikTok", "https://www.tiktok.com"},
            {"◎", "Instagram", "https://www.instagram.com"},
            {"𝕏", "X / Twitter", "https://x.com"},
            {"＠", "Threads", "https://www.threads.net"}
        };
        for (int i = 0; i < sites.length; i += 3) {
            LinearLayout r = row();
            for (int j = i; j < Math.min(i + 3, sites.length); j++) {
                final String url = sites[j][2];
                LinearLayout card = column();
                card.setGravity(Gravity.CENTER);
                card.setPadding(dp(5), dp(10), dp(5), dp(10));
                card.setBackground(shape(PANEL, PANEL2, 16));
                TextView icon = makeSocialIcon(j);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
                iconParams.gravity = Gravity.CENTER_HORIZONTAL;
                card.addView(icon, iconParams);
                TextView label = text(sites[j][1], 11, WHITE, true);
                label.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams labelParams = lp(-1, 22);
                labelParams.topMargin = dp(5);
                card.addView(label, labelParams);
                card.setOnClickListener(v -> openQuickSite(url));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(82), 1);
                cp.setMargins(dp(3), dp(4), dp(3), dp(4));
                r.addView(card, cp);
            }
            page.addView(r, lp(-1, -2));
        }

        LinearLayout.LayoutParams gap2 = lp(-1, 12); page.addView(new View(this), gap2);
        addSection(page, "أقسام التطبيق", "كل أدواتك في كروت واضحة");
        LinearLayout r1 = row();
        addCard(r1, "◎", "المتصفح", "تصفح وابحث وأرسل", () -> showBrowser());
        addCard(r1, "▣", "وسائط الهاتف", "الفيديوهات والملفات", () -> showMedia());
        page.addView(r1, lp(-1, -2));
        LinearLayout r2 = row();
        addCard(r2, "▧", "معرض الصور", "عرض الصور والملفات", () -> showPhoneGallery());
        addCard(r2, "♫", "قائمة التشغيل", "ملفات الصوت والفيديو المختارة", () -> showLocalPlaylist());
        page.addView(r2, lp(-1, -2));
        LinearLayout r3 = row();
        addCard(r3, "⇩", "التنزيلات", "فتح ملفات التنزيل", () -> openDownloads());
        addCard(r3, "⚙", "إعداد الاستقبال", "عنوان IP والمنفذ", () -> receiverSettings());
        page.addView(r3, lp(-1, -2));
        LinearLayout r4 = row();
        addCard(r4, "◷", "الوسائط الأخيرة", "إعادة إرسال رابط سابق", () -> showRecentCastDialog());
        addCard(r4, "▤", "بث IPTV", "إضافة رابط بث مباشر", () -> showIptvUrlDialog());
        page.addView(r4, lp(-1, -2));
        LinearLayout r5 = row();
        addCard(r5, "★", "الوسائط المحفوظة", "روابط محفوظة للإرسال والتنزيل", () -> showSavedMedia());
        addCard(r5, "☆", "المفضلة والسجل", "المواقع والصفحات السابقة", () -> showLibrary());
        page.addView(r5, lp(-1, -2));
        page.addView(text("ملاحظة: اكتشاف الوسائط يختلف حسب الموقع، وبعض المنصات تستخدم بثًا محميًا لا يمكن التقاطه أو إرساله مباشرة.", 11, MUTED, false), lp(-1, -2));
        scroll.addView(page);
        content.addView(scroll, lp(-1, -1));
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
        TextView clear=button("×",PANEL2,WHITE);
        clear.setContentDescription("مسح عنوان الرابط");
        LinearLayout.LayoutParams clp=lp(32,42);clp.setMargins(dp(3),0,0,0);addressRow.addView(clear,clp);
        clear.setOnClickListener(v -> address.setText(""));
        TextView go=button("↵",BLUE,WHITE);
        go.setContentDescription("فتح الرابط أو تنفيذ البحث");
        LinearLayout.LayoutParams gp=lp(34,42);gp.setMargins(dp(3),0,0,0);addressRow.addView(go,gp);
        browserTools.addView(addressRow);
        LinearLayout controls=row();
        addSmallControl(controls,"‹",()->{if(web.canGoBack())web.goBack();});
        addSmallControl(controls,"›",()->{if(web.canGoForward())web.goForward();});
        addSmallControl(controls,"↻",()->web.reload());
        addSmallControl(controls,"⌂",()->showHome());
        addSmallControl(controls,"☆",()->saveBookmark());
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
    private void createWebView() {
        web=new WebView(this);
        BrowserEngine.configure(web);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
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
                runOnUiThread(() -> {
                    boolean wantsAudio = false;
                    for (String resource : request.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsAudio = true;
                    }
                    if (!wantsAudio) { request.deny(); return; }
                    if (android.os.Build.VERSION.SDK_INT >= 23 &&
                            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingWebPermissionRequest = request;
                        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
                    } else {
                        request.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
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
                popupSettings.setJavaScriptEnabled(true);
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
                });
                popupWeb.setWebChromeClient(new WebChromeClient() {
                    @Override public void onCloseWindow(WebView window) {
                        if (!isFinishing() && dialog.isShowing()) dialog.dismiss();
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
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                detectMedia(request.getUrl().toString());
                return super.shouldInterceptRequest(view,request);
            }
            @Override public void onPageFinished(WebView view,String url){
                if(address!=null && "browser".equals(currentPage)) address.setText(url);
                try { CookieManager.getInstance().flush(); } catch (Exception ignored) { }
                saveHistory(url);
                // Do not scan every video/image URL on the page. The TV button targets only the active player.
                refreshCurrentVideoIndicator();
            }
        });
        web.setDownloadListener((url,ua,disposition,mime,length)->downloadUrl(url,mime));
    }

    private String currentPlayingVideoUrl = "";

    private void refreshCurrentVideoIndicator() {
        if (web == null) return;
        String js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));var v=vs.find(function(x){return !x.paused&&!x.ended&&x.readyState>=2&&(x.currentSrc||x.src);})||vs.find(function(x){return x.readyState>=2&&(x.currentSrc||x.src);});return v?(v.currentSrc||v.src||''):'';})()";
        web.evaluateJavascript(js, value -> {
            try {
                Object parsed = new org.json.JSONTokener(value == null ? "\"\"" : value).nextValue();
                String url = parsed == null ? "" : String.valueOf(parsed);
                if (!url.isEmpty() && !"null".equals(url) && !url.startsWith("blob:") && isVideoCandidate(url)) {
                    currentPlayingVideoUrl = url;
                    if (castActionButton != null) {
                        castActionButton.setText("▣");
                        castActionButton.setTextColor(GOLD);
                        castActionButton.setBackground(shape(PANEL2, GOLD, 50));
                        castActionButton.setContentDescription("تم العثور على فيديو قابل للإرسال");
                        AlphaAnimation pulse = new AlphaAnimation(1.0f, 0.45f);
                        pulse.setDuration(700);
                        pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
                        pulse.setRepeatCount(2);
                        castActionButton.startAnimation(pulse);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void captureCurrentPlayingVideo() {
        if (web == null || !"browser".equals(currentPage)) {
            Toast.makeText(this, "افتح الفيديو في المتصفح أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        String js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));var v=vs.find(function(x){return !x.paused&&!x.ended&&x.readyState>=2&&(x.currentSrc||x.src);});if(!v)return '';return v.currentSrc||v.src||'';})()";
        web.evaluateJavascript(js, value -> {
            try {
                Object parsed = new org.json.JSONTokener(value == null ? "\"\"" : value).nextValue();
                String url = parsed == null ? "" : String.valueOf(parsed);
                if (url.isEmpty() || url.startsWith("blob:") || !isVideoCandidate(url)) {
                    Toast.makeText(this, "شغّل الفيديو المطلوب أولًا؛ القناص يلتقط الفيديو النشط فقط وليس صور الصفحة.", Toast.LENGTH_LONG).show();
                    return;
                }
                currentPlayingVideoUrl = url;
                castUrl(url);
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
            ok = lowerUrl.contains("mime=video") || lowerUrl.contains("mime=audio")
                    || lowerUrl.contains("video_playback") || lowerUrl.contains("/videoplayback")
                    || lowerUrl.contains("pull-") || lowerUrl.contains("format=mp4")
                    || lowerUrl.contains("type=video") || lowerUrl.contains("type=audio")
                    || lowerUrl.contains("contenttype=video") || lowerUrl.contains("content-type=video")
                    || lowerUrl.contains("fbcdn.net") && (lowerUrl.contains("video") || lowerUrl.contains("audio"));
        }
        if(!ok||media.containsKey(url))return;
        runOnUiThread(()->{
            if(media.containsKey(url))return;
            media.put(url,url);mediaOrder.add(url);
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1f);
        p.setMargins(dp(2), dp(1), dp(2), dp(1));
        return p;
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
        remote.setPadding(dp(6),dp(5),dp(6),dp(5));
        remote.setBackground(shape(PANEL2,PANEL,14));
        remote.addView(text("ريموت التشغيل",14,WHITE,true),lp(-1,dp(24)));
        LinearLayout r1 = row();
        TextView rw = button("⏪ 10 ث",PANEL,WHITE);
        TextView play = button("▶  /  ⏸",BLUE,WHITE);
        TextView ff = button("10 ث ⏩",PANEL,WHITE);
        r1.addView(rw,remoteButtonParams());
        r1.addView(play,remoteButtonParams());
        r1.addView(ff,remoteButtonParams());
        remote.addView(r1,lp(-1,dp(38)));
        LinearLayout r2 = row();
        TextView volumeDown = button("− صوت",PANEL,WHITE);
        TextView mute = button("🔇 كتم",PANEL2,GOLD);
        TextView volumeUp = button("+ صوت",PANEL,WHITE);
        TextView stop = button("■ إيقاف",PANEL,WHITE);
        r2.addView(volumeDown,remoteButtonParams());
        r2.addView(mute,remoteButtonParams());
        r2.addView(volumeUp,remoteButtonParams());
        r2.addView(stop,remoteButtonParams());
        LinearLayout.LayoutParams remoteRowParams=lp(-1,dp(38));
        remoteRowParams.topMargin=dp(3);
        remote.addView(r2,remoteRowParams);
        volumeDown.setOnClickListener(v -> sendRemoteCommand("volume_down"));
        mute.setOnClickListener(v -> sendRemoteCommand("mute"));
        volumeUp.setOnClickListener(v -> sendRemoteCommand("volume_up"));
        play.setOnClickListener(v -> sendRemoteCommand("play_pause"));
        rw.setOnClickListener(v -> sendRemoteCommand("rw"));
        ff.setOnClickListener(v -> sendRemoteCommand("ff"));
        stop.setOnClickListener(v -> sendRemoteCommand("stop"));
        content.addView(remote,lp(-1,-2));
        TextView savedButton=button("الوسائط المحفوظة ("+readSavedMedia().length()+")",PANEL2,GOLD);
        LinearLayout.LayoutParams savedParams=lp(-1,42);savedParams.topMargin=dp(6);content.addView(savedButton,savedParams);
        savedButton.setOnClickListener(v -> showSavedMedia());
        TextView info=button("عرض المصادر المكتشفة ("+mediaOrder.size()+")",PANEL2,GOLD);
        LinearLayout.LayoutParams ip=lp(-1,44);ip.topMargin=dp(6);content.addView(info,ip);
        ScrollView scroll=new ScrollView(this);scroll.setVisibility(View.GONE);LinearLayout list=column();
        info.setOnClickListener(v -> {
            boolean show = scroll.getVisibility() != View.VISIBLE;
            scroll.setVisibility(show ? View.VISIBLE : View.GONE);
            info.setText((show ? "إخفاء المصادر المكتشفة (" : "عرض المصادر المكتشفة (") + mediaOrder.size() + ")");
        });
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
                        .setTitle("اختار عنوان Receiver الصحيح")
                        .setMessage("البحث قد يعثر على أجهزة أخرى على الشبكة. اختار العنوان الظاهر داخل تطبيق Receiver، أو ألغِ واكتب العنوان يدويًا.")
                        .setItems(choices, (dialog, which) -> input.setText(choices[which]))
                        .setNegativeButton("إلغاء", null)
                        .show();
                }
            }));
        });
        box.addView(input,lp(-1,52));
        TextView permissions = button("إدارة أذونات التطبيق في إعدادات أندرويد", PANEL2, WHITE);
        box.addView(permissions, lp(-1, 46));
        permissions.setOnClickListener(v -> openAppPermissionSettings());
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
            connectionLabel.setText("● جهاز الاستقبال: غير متصل — جاري البحث تلقائيًا");
        } else {
            connectionLabel.setText((receiverConnected ? "● متصل بالريسيفر: " : "● آخر عنوان للريسيفر (غير مؤكد): ") + receiverIp + ":8081");
        }
    }

    private void monitorReceiverConnection() {
        final String ip = receiverIp == null ? "" : receiverIp.trim();
        if (ip.isEmpty()) {
            receiverConnected = false;
            refreshConnectionLabel();
            discoverReceiverInBackground();
            return;
        }
        new Thread(() -> {
            boolean ok = false;
             ok = CastingManager.isReceiver(ip);
            final boolean connected = ok;
            runOnUiThread(() -> {
                receiverConnected = connected;
                if (!connected) receiverIp = "";
                refreshConnectionLabel();
                if (!connected || (++receiverMonitorCycles % 4 == 0)) {
                    discoverReceiverInBackground();
                }
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
            // Only accept a discovered device after its TCP receiver port responds.
            for (String candidate : devices) {
                if (candidate == null || candidate.trim().isEmpty()) continue;
                boolean ok = false;
                 ok = CastingManager.isReceiver(candidate);
                if (ok) {
                    final String verifiedIp = candidate;
                    runOnUiThread(() -> {
                        receiverIp = verifiedIp;
                        receiverConnected = true;
                        refreshConnectionLabel();
                        discoveryInProgress = false;
                        String pendingUrl = pendingPlatformUrl;
                        pendingPlatformUrl = null;
                        if (pendingUrl != null) castUrl(pendingUrl);
                    });
                    return;
                }
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
                    
                    refreshConnectionLabel();
                }
                Toast.makeText(this,result?"تم التحقق من عنوان الريسيفر الحالي":"العنوان لا يستجيب كتطبيق AboEzzat Receiver على المنفذ 8081.",Toast.LENGTH_LONG).show();
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
        return lower.contains("mime=video") || lower.contains("video_playback")
                || lower.contains("/videoplayback") || lower.contains("format=mp4")
                || lower.contains("type=video") || lower.contains("contenttype=video")
                || lower.contains("content-type=video");
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
            else continue;
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
            if (!success) discoverReceiverInBackground();
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
        addSection(content, title, "افتح أي عنصر للرجوع إليه، واضغط مطولًا لحذفه");

        if (showBookmarks) addUrlCollection(content, "☆ المفضلة", "bookmarks", focusKey);
        if (showHistory) {
            addUrlCollection(content, "◷ سجل التصفح", "history", focusKey);
            TextView clear = button("مسح سجل التصفح", PANEL2, MUTED);
            LinearLayout.LayoutParams p = lp(-1, 44);
            p.topMargin = dp(12);
            content.addView(clear, p);
            clear.setOnClickListener(v -> {
                prefs.edit().remove("history").apply();
                showLibrary("history".equals(focusKey) ? "history" : null);
                Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void addUrlCollection(LinearLayout parent, String title, String key, String focusKey){
        parent.addView(text(title,16,GOLD,true),lp(-1,38));
        JSONArray a;
        try{a=new JSONArray(prefs.getString(key,"[]"));}catch(Exception e){a=new JSONArray();}
        if(a.length()==0){parent.addView(text("لا توجد عناصر بعد",12,MUTED,false),lp(-1,30));return;}
        for(int i=0;i<a.length();i++){
            String url=a.optString(i);
            TextView item=button(url,PANEL,WHITE);item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);item.setMaxLines(2);item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams p=lp(-1,44);p.setMargins(0,dp(3),0,dp(3));parent.addView(item,p);
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
            req.addRequestHeader("User-Agent", System.getProperty("http.agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"));
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
            req.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,fileName);
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
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
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
                Toast.makeText(this, success ? "تم إرسال " + mediaName + " للريسيفر" : message, Toast.LENGTH_LONG).show();
                if (!success) discoverReceiverInBackground();
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

    @Override protected void onDestroy(){
        receiverMonitorHandler.removeCallbacks(receiverMonitorRunnable);
        slideshowRunning = false;
        slideshowHandler.removeCallbacks(slideshowRunnable);
        if (browserFileCallback != null) { browserFileCallback.onReceiveValue(null); browserFileCallback = null; }
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        localMediaServerRunning = false;
        try { if (localMediaServer != null) localMediaServer.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}