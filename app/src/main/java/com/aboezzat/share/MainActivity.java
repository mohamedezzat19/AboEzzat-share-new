package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
    private ValueCallback<Uri[]> browserFileCallback;
    private LinearLayout root, header, content, bottom, browserTools, mediaPanel;
    private FrameLayout contentFrame;
    private EditText address;
    private WebView web;
    private TextView pageTitle, connectionLabel, mediaLabel, castActionButton;
    private final LinkedHashMap<String,String> media = new LinkedHashMap<>();
    private final ArrayList<String> mediaOrder = new ArrayList<>();
    private String currentPage = "home";
    private String receiverIp = "";
    private boolean receiverConnected = false;
    private boolean discoveryInProgress = false;
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
        receiverIp = prefs.getString("receiver_ip", "");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildShell();
        setContentView(root);
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

        // Preserve shared-link behavior, while also accepting shared local media files.
        if (android.content.Intent.ACTION_SEND.equals(action)) {
            String shared = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            if (shared != null && !shared.trim().isEmpty()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?i)https?://[^\\s<>\\\"']+")
                        .matcher(shared);
                if (matcher.find()) {
                    String sharedUrl = matcher.group().replaceAll("[),.]+$", "");
                    String normalized = BrowserEngine.normalizeAddress(sharedUrl);
                    showBrowser();
                    if (address != null) {
                        address.setText(normalized);
                        if (web != null) web.loadUrl(normalized);
                    }
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
    }

    private TextView button(String label, int color, int fg) {
        TextView b = text(label, 14, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(shape(color, color == PANEL ? 0 : PANEL2, 13));
        b.setPadding(dp(12),dp(9),dp(12),dp(9));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private void nav(String label, String page) {
        TextView b = text(label, 11, MUTED, true);
        b.setGravity(Gravity.CENTER);
        bottom.addView(b, new LinearLayout.LayoutParams(0,-1,1));
        b.setOnClickListener(v -> {
            if ("home".equals(page)) showHome();
            else if ("browser".equals(page)) showBrowser();
            else if ("media".equals(page)) showMedia();
            else showLibrary();
        });
    }

    private void showToolsMenu() {
        String[] items = {"إعداد جهاز الاستقبال", "مركز الوسائط", "معرض الصور", "قائمة التشغيل", "الوسائط المرسلة مؤخرًا", "تنزيل الصفحة الحالية"};
        new AlertDialog.Builder(this).setTitle("أدوات AboEzzat Share").setItems(items, (dialog, which) -> {
            if (which == 0) receiverSettings();
            else if (which == 1) showMedia();
            else if (which == 2) showPhoneGallery();
            else if (which == 3) showLibrary();
            else if (which == 4) showRecentCastDialog();
            else downloadCurrent();
        }).show();
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

    private void showHome() {
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
                TextView icon = text(sites[j][0], 24, j == 1 ? Color.rgb(86,150,255) : GOLD, true);
                icon.setGravity(Gravity.CENTER);
                card.addView(icon, lp(-1, 34));
                TextView label = text(sites[j][1], 11, WHITE, true);
                label.setGravity(Gravity.CENTER);
                card.addView(label, lp(-1, 24));
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
        addressRow.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView clear=button("×",PANEL2,WHITE);
        LinearLayout.LayoutParams clp=lp(42,48);clp.setMargins(dp(5),0,0,0);addressRow.addView(clear,clp);
        clear.setOnClickListener(v -> address.setText(""));
        TextView go=button("Go",BLUE,WHITE);
        LinearLayout.LayoutParams gp=lp(52,48);gp.setMargins(dp(5),0,0,0);addressRow.addView(go,gp);
        browserTools.addView(addressRow);
        LinearLayout controls=row();
        addSmallControl(controls,"‹",()->{if(web.canGoBack())web.goBack();});
        addSmallControl(controls,"›",()->{if(web.canGoForward())web.goForward();});
        addSmallControl(controls,"↻",()->web.reload());
        addSmallControl(controls,"⌂",()->showHome());
        addSmallControl(controls,"☆",()->saveBookmark());
        content.addView(browserTools,lp(-1,-2));
        pageTitle=text("جاهز للتصفح",11,MUTED,false);
        content.addView(pageTitle,lp(-1,24));
        if(web.getParent()!=null) ((android.view.ViewGroup)web.getParent()).removeView(web);
        content.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        mediaPanel=column();
        mediaPanel.setPadding(dp(9),dp(6),dp(9),dp(6));
        mediaPanel.setBackgroundColor(PANEL);
        mediaLabel=text("المصادر المكتشفة: "+mediaOrder.size(),12,GOLD,true);
        mediaPanel.addView(mediaLabel,lp(-1,22));
        LinearLayout castActions=row();
        TextView cast=button("▶ إرسال",GOLD,BG);
        castActionButton=cast;
        TextView best=button("أفضل جودة",PANEL2,WHITE);
        TextView list=button("القائمة",PANEL2,WHITE);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(42),1);
        ap.setMargins(dp(2),0,dp(2),0);
        castActions.addView(cast,new LinearLayout.LayoutParams(0,dp(42),1));
        castActions.addView(best,new LinearLayout.LayoutParams(0,dp(42),1));
        castActions.addView(list,new LinearLayout.LayoutParams(0,dp(42),1));
        mediaPanel.addView(castActions,lp(-1,44));
        cast.setOnClickListener(v->castCurrent());
        best.setOnClickListener(v->castBestQuality());
        list.setOnClickListener(v->showMedia());
        content.addView(mediaPanel,lp(-1,70));
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
        CookieManager.getInstance().setAcceptCookie(true);
        web.setBackgroundColor(BG);
        web.setWebChromeClient(new WebChromeClient(){
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
                saveHistory(url);
                // Supplement WebView request sniffing with a best-effort HTML/media scan.
                MediaDetector.inspectPage(url, (mediaUrl, kind, quality) -> detectMedia(mediaUrl));
                String js="(function(){var a=[];document.querySelectorAll('video,audio,source').forEach(function(e){var u=e.currentSrc||e.src;if(u&&u.indexOf('blob:')!==0)a.push(u)});return JSON.stringify(a)})()";
                view.evaluateJavascript(js,value->{
                    if(value!=null){
                        try{
                            String decoded=org.json.JSONTokener.class.getName()==null?"[]":value;
                            if(decoded.startsWith("\"")&&decoded.endsWith("\"")) decoded=decoded.substring(1,decoded.length()-1).replace("\\\"","\"").replace("\\\\","\\");
                            JSONArray arr=new JSONArray(decoded);
                            for(int i=0;i<arr.length();i++) detectMedia(arr.optString(i));
                        }catch(Exception ignored){}
                    }
                });
            }
        });
        web.setDownloadListener((url,ua,disposition,mime,length)->downloadUrl(url,mime));
    }

    private void addSmallControl(LinearLayout row,String label,Runnable run){
        TextView b=button(label,PANEL,WHITE);
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

    private void sendRemoteCommand(String command) {
        if ("prev".equals(command) || "next".equals(command)) {
            Toast.makeText(this, "السابق والتالي يحتاجان قائمة تشغيل على الريسيفر، وهي غير مدعومة حاليًا.", Toast.LENGTH_LONG).show();
            return;
        }
        if (receiverIp == null || receiverIp.trim().isEmpty()) {
            Toast.makeText(this, "حدّد عنوان IP للريسيفر أولًا من إعدادات الاستقبال.", Toast.LENGTH_LONG).show();
            return;
        }
        CastingManager.sendCommand(receiverIp.trim(), command, (success, message) ->
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()));
    }

    private void showMedia(){
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
        remote.setPadding(dp(10),dp(8),dp(10),dp(8));
        remote.setBackground(shape(PANEL2,PANEL,14));
        remote.addView(text("ريموت التشغيل",15,WHITE,true),lp(-1,28));
        LinearLayout r1 = row();
        TextView prev = button("⏮ السابق",PANEL,WHITE);
        TextView play = button("▶ / ⏸",BLUE,WHITE);
        TextView next = button("التالي ⏭",PANEL,WHITE);
        r1.addView(prev,new LinearLayout.LayoutParams(0,dp(42),1));
        r1.addView(play,new LinearLayout.LayoutParams(0,dp(42),1));
        r1.addView(next,new LinearLayout.LayoutParams(0,dp(42),1));
        remote.addView(r1,lp(-1,dp(44)));
        LinearLayout r2 = row();
        TextView rw = button("⏪ -10 ث",PANEL,WHITE);
        TextView ff = button("+10 ث ⏩",PANEL,WHITE);
        TextView stop = button("■ إيقاف",PANEL,WHITE);
        r2.addView(rw,new LinearLayout.LayoutParams(0,dp(42),1));
        r2.addView(ff,new LinearLayout.LayoutParams(0,dp(42),1));
        r2.addView(stop,new LinearLayout.LayoutParams(0,dp(42),1));
        remote.addView(r2,lp(-1,dp(44)));
        LinearLayout r3 = row();
        TextView volumeDown = button("خفض الصوت",PANEL,WHITE);
        TextView mute = button("كتم / تشغيل الصوت",PANEL2,GOLD);
        TextView volumeUp = button("رفع الصوت",PANEL,WHITE);
        r3.addView(volumeDown,new LinearLayout.LayoutParams(0,dp(42),1));
        r3.addView(mute,new LinearLayout.LayoutParams(0,dp(42),1));
        r3.addView(volumeUp,new LinearLayout.LayoutParams(0,dp(42),1));
        remote.addView(r3,lp(-1,dp(44)));
        volumeDown.setOnClickListener(v -> sendRemoteCommand("volume_down"));
        mute.setOnClickListener(v -> sendRemoteCommand("mute"));
        volumeUp.setOnClickListener(v -> sendRemoteCommand("volume_up"));
        prev.setOnClickListener(v -> sendRemoteCommand("prev"));
        play.setOnClickListener(v -> sendRemoteCommand("play_pause"));
        next.setOnClickListener(v -> sendRemoteCommand("next"));
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
            LinearLayout.LayoutParams cp=lp(-1,-2);cp.topMargin=dp(7);list.addView(card,cp);
        }
        if(mediaOrder.isEmpty()){
            TextView empty=text("لسه مفيش مصادر مكتشفة.\nافتح موقع فيديو من المتصفح وشغّل المقطع، وبعدها ارجع هنا.",14,MUTED,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(18),dp(30),dp(18),dp(30));list.addView(empty,lp(-1,130));
        }
        scroll.addView(list);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView open=button("الرجوع للمتصفح",PANEL2,WHITE);content.addView(open,lp(-1,46));open.setOnClickListener(v->showBrowser());
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
        new AlertDialog.Builder(this).setTitle("إعداد جهاز الاستقبال").setView(box)
            .setPositiveButton("حفظ", (d,w)->{
                String normalized = normalizeReceiverIp(input.getText().toString());
                if (normalized == null) {
                    Toast.makeText(this, "اكتب عنوان IPv4 صحيح مثل 192.168.1.20", Toast.LENGTH_LONG).show();
                    return;
                }
                receiverIp=normalized;
                prefs.edit().putString("receiver_ip",receiverIp).apply();refreshConnectionLabel();
                Toast.makeText(this, "تم حفظ عنوان الاستقبال: " + receiverIp,Toast.LENGTH_SHORT).show();
            }).setNeutralButton("اختبار الاتصال",(d,w)->{
                String ip = normalizeReceiverIp(input.getText().toString());
                if (ip == null) {
                    Toast.makeText(this, "اكتب عنوان IPv4 صحيح مثل 192.168.1.20", Toast.LENGTH_LONG).show();
                    return;
                }
                testReceiver(ip);
            }).setNegativeButton("إلغاء",null).show();
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
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(ip, 8081), 1800);
                ok = true;
            } catch (Exception ignored) { }
            final boolean connected = ok;
            runOnUiThread(() -> {
                receiverConnected = connected;
                refreshConnectionLabel();
                if (!connected) discoverReceiverInBackground();
            });
        }, "ReceiverConnectionMonitor").start();
    }

    private synchronized void discoverReceiverInBackground() {
        if (discoveryInProgress || isFinishing() || isDestroyed()) return;
        discoveryInProgress = true;
        CastingManager.discover(this, devices -> {
            if (devices == null || devices.isEmpty()) {
                runOnUiThread(() -> discoveryInProgress = false);
                return;
            }
            // Only accept a discovered device after its TCP receiver port responds.
            for (String candidate : devices) {
                if (candidate == null || candidate.trim().isEmpty()) continue;
                boolean ok = false;
                try (Socket socket = new Socket()) {
                    socket.connect(new java.net.InetSocketAddress(candidate, 8081), 1200);
                    ok = true;
                } catch (Exception ignored) { }
                if (ok) {
                    final String verifiedIp = candidate;
                    runOnUiThread(() -> {
                        receiverIp = verifiedIp;
                        receiverConnected = true;
                        prefs.edit().putString("receiver_ip", receiverIp).apply();
                        refreshConnectionLabel();
                        discoveryInProgress = false;
                    });
                    return;
                }
            }
            runOnUiThread(() -> discoveryInProgress = false);
        });
    }

    private void testReceiver(String ip){
        if(ip.isEmpty()){Toast.makeText(this,"اكتب عنوان IP أولاً",Toast.LENGTH_SHORT).show();return;}
        new Thread(()->{
            boolean ok=false;
            try(Socket s=new Socket()){s.connect(new java.net.InetSocketAddress(ip,8081),2500);ok=true;}catch(Exception ignored){}
            final boolean result=ok;
            runOnUiThread(()->{
                if (result) {
                    receiverConnected = true;
                    receiverIp = ip;
                    prefs.edit().putString("receiver_ip", receiverIp).apply();
                    refreshConnectionLabel();
                }
                Toast.makeText(this,result?"الاتصال ناجح وتم حفظ عنوان الريسيفر":"فشل الاتصال على "+ip+":8081. تأكد أن الهاتف والريسيفر على نفس شبكة الواي فاي وأن المنفذ 8081 متاح.",Toast.LENGTH_LONG).show();
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

    private void castUrl(String url){
        if(receiverIp.isEmpty()){new AlertDialog.Builder(this).setTitle("إعداد الاستقبال").setMessage("لازم تحفظ عنوان IP الظاهر في تطبيق AboEzzat Receiver أولاً.").setPositiveButton("ضبط الآن",(d,w)->receiverSettings()).setNegativeButton("إلغاء",null).show();return;}
        final String ip=receiverIp;
        rememberCastUrl(url);
        Toast.makeText(this,"جاري إرسال الوسائط إلى الشاشة…",Toast.LENGTH_SHORT).show();
        CastingManager.send(ip, url, (success, message) -> runOnUiThread(() -> {
            receiverConnected = success;
            refreshConnectionLabel();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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

    private void showLibrary(){
        currentPage="library";resetContent();
        addSection(content,"مكتبتك","المواقع المحفوظة وآخر الصفحات");
        addUrlCollection(content,"☆ المفضلة","bookmarks");
        addUrlCollection(content,"◷ سجل التصفح","history");
        TextView clear=button("مسح سجل التصفح",PANEL2,MUTED);LinearLayout.LayoutParams p=lp(-1,44);p.topMargin=dp(12);content.addView(clear,p);
        clear.setOnClickListener(v->{prefs.edit().remove("history").apply();showLibrary();Toast.makeText(this,"تم مسح السجل",Toast.LENGTH_SHORT).show();});
    }

    private void addUrlCollection(LinearLayout parent,String title,String key){
        parent.addView(text(title,16,GOLD,true),lp(-1,38));
        JSONArray a;
        try{a=new JSONArray(prefs.getString(key,"[]"));}catch(Exception e){a=new JSONArray();}
        if(a.length()==0){parent.addView(text("لا توجد عناصر بعد",12,MUTED,false),lp(-1,30));return;}
        for(int i=0;i<a.length();i++){
            String url=a.optString(i);
            TextView item=button(url,PANEL,WHITE);item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);item.setMaxLines(2);item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams p=lp(-1,44);p.setMargins(0,dp(3),0,dp(3));parent.addView(item,p);
            item.setOnClickListener(v->{showBrowser();address.setText(url);loadAddress();});
            item.setOnLongClickListener(v->{new AlertDialog.Builder(this).setMessage("حذف من القائمة؟").setPositiveButton("حذف",(d,w)->{removeSaved(key,url);showLibrary();}).setNegativeButton("إلغاء",null).show();return true;});
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
            if(fileName.length()>120)fileName=fileName.substring(fileName.length()-120);
            if(fileName.isEmpty()||".".equals(fileName)||"..".equals(fileName))fileName="media_download";
            req.setTitle("AboEzzat Share download");
            req.setDescription("تنزيل من المتصفح");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(false);
            req.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,fileName);
            if(mime!=null)req.setMimeType(mime);
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
        selectedMediaUri = localMediaQueue.get(Math.max(0, Math.min(currentLocalMediaIndex, localMediaQueue.size()-1)));
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
            }).setNegativeButton("إغلاق", null).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
            if (!slideshowQueue.isEmpty()) {
                currentSlideIndex = 0;
                selectedImageUri = slideshowQueue.get(0);
                showPhoneGallery();
                if (slideshowQueue.size() > 1) Toast.makeText(this, "تم اختيار " + slideshowQueue.size() + " صور. تقدر تبدأ عرض الشرائح تلقائيًا من نفس الصفحة.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override public void onBackPressed(){
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