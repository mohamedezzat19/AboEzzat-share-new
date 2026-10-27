package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.Socket;
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
    private LinearLayout root, header, content, bottom, browserTools, mediaPanel;
    private FrameLayout contentFrame;
    private EditText address;
    private WebView web;
    private TextView pageTitle, connectionLabel, mediaLabel, castActionButton;
    private final LinkedHashMap<String,String> media = new LinkedHashMap<>();
    private final ArrayList<String> mediaOrder = new ArrayList<>();
    private String currentPage = "home";
    private String receiverIp = "";
    private SharedPreferences prefs;
    private boolean loadingHome = false;

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
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
    }

    private void handleSharedIntent(android.content.Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_SEND.equals(intent.getAction())) return;
        String shared = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
        if (shared == null || shared.trim().isEmpty()) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)https?://[^\\s<>\\\"']+")
                .matcher(shared);
        if (!matcher.find()) {
            Toast.makeText(this, "لم يتم العثور على رابط في المشاركة", Toast.LENGTH_SHORT).show();
            return;
        }
        String sharedUrl = matcher.group().replaceAll("[),.]+$", "");
        String normalized = BrowserEngine.normalizeAddress(sharedUrl);
        showBrowser();
        if (address != null) {
            address.setText(normalized);
            if (web != null) web.loadUrl(normalized);
        }
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
        String[] items = {"إعداد جهاز الاستقبال", "مركز الوسائط", "معرض الصور", "قائمة التشغيل", "تنزيل الصفحة الحالية"};
        new AlertDialog.Builder(this).setTitle("أدوات AboEzzat Share").setItems(items, (dialog, which) -> {
            if (which == 0) receiverSettings();
            else if (which == 1) showMedia();
            else if (which == 2) showPhoneGallery();
            else if (which == 3) showLibrary();
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
        addCard(r2, "♫", "قائمة التشغيل", "المصادر المحفوظة", () -> showLibrary());
        page.addView(r2, lp(-1, -2));
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
        addSection(content, "معرض الصور", "الصور والملفات المحلية");
        TextView note = text("لعرض صور الهاتف وإدارتها، افتح معرض الصور من تطبيق النظام. صلاحيات الوصول للملفات تعتمد على إصدار أندرويد.", 14, MUTED, false);
        note.setPadding(dp(8), dp(12), dp(8), dp(12));
        content.addView(note, lp(-1, -2));
        TextView open = button("فتح معرض الصور", BLUE, WHITE);
        content.addView(open, lp(-1, 48));
        open.setOnClickListener(v -> {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                startActivity(android.content.Intent.createChooser(intent, "اختار صورة"));
            } catch (Exception e) {
                Toast.makeText(this, "مفيش تطبيق معرض صور متاح على الجهاز", Toast.LENGTH_LONG).show();
            }
        });
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
        String[] exts={".mp4",".m3u8",".mpd",".webm",".mov",".m4v",".mp3",".m4a",".aac",".ogg",".wav"};
        for(String e:exts){if(lower.endsWith(e)){ok=true;break;}}
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

    private void showMedia(){
        currentPage="media";resetContent();
        addSection(content,"مركز الوسائط","اختار مصدرًا مكتشفًا لإرساله إلى جهاز الاستقبال");
        TextView info=button("عدد المصادر المكتشفة: "+mediaOrder.size(),PANEL2,GOLD);
        LinearLayout.LayoutParams ip=lp(-1,44);ip.topMargin=dp(6);content.addView(info,ip);
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();
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
            download.setOnClickListener(v->downloadUrl(url, "application/octet-stream"));
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
        String u=url.toLowerCase(Locale.ROOT);
        if(u.contains(".m3u8"))return "HLS";
        if(u.contains(".mpd"))return "DASH";
        if(u.contains(".mp3")||u.contains(".m4a")||u.contains(".aac"))return "صوت";
        return "فيديو";
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
            CastingManager.discover(devices -> runOnUiThread(() -> {
                if (devices.isEmpty()) {
                    discover.setText("لم يتم العثور عليه — اكتب IP يدويًا");
                    Toast.makeText(this, "تأكد أن Receiver مفتوح وعلى نفس شبكة الواي فاي", Toast.LENGTH_LONG).show();
                } else {
                    input.setText(devices.get(0));
                    discover.setText("تم العثور على " + devices.size() + " جهاز — تم اختيار أول جهاز");
                }
            }));
        });
        box.addView(input,lp(-1,52));
        new AlertDialog.Builder(this).setTitle("إعداد جهاز الاستقبال").setView(box)
            .setPositiveButton("حفظ", (d,w)->{
                receiverIp=input.getText().toString().trim().replace("http://","").replace("https://","");
                if(receiverIp.contains(":"))receiverIp=receiverIp.substring(0,receiverIp.indexOf(':'));
                prefs.edit().putString("receiver_ip",receiverIp).apply();refreshConnectionLabel();
                Toast.makeText(this,receiverIp.isEmpty()?"لم يتم حفظ عنوان": "تم حفظ عنوان الاستقبال",Toast.LENGTH_SHORT).show();
            }).setNeutralButton("اختبار الاتصال",(d,w)->{
                String ip=input.getText().toString().trim().replace("http://","").replace("https://","");
                if(ip.contains(":"))ip=ip.substring(0,ip.indexOf(':'));
                testReceiver(ip);
            }).setNegativeButton("إلغاء",null).show();
    }

    private void refreshConnectionLabel(){
        if(connectionLabel!=null)connectionLabel.setText(receiverIp.isEmpty()?"● جهاز الاستقبال: لم يُضبط بعد — اضغط ⚙":"● جهاز الاستقبال: "+receiverIp+":8081");
    }

    private void testReceiver(String ip){
        if(ip.isEmpty()){Toast.makeText(this,"اكتب عنوان IP أولاً",Toast.LENGTH_SHORT).show();return;}
        new Thread(()->{
            boolean ok=false;
            try(Socket s=new Socket()){s.connect(new java.net.InetSocketAddress(ip,8081),2500);ok=true;}catch(Exception ignored){}
            final boolean result=ok;
            runOnUiThread(()->Toast.makeText(this,result?"الاتصال ناجح":"لم نقدر نتصل. تأكد من الشبكة وعنوان IP وتشغيل Receiver.",Toast.LENGTH_LONG).show());
        }).start();
    }

    private void castBestQuality() {
        String best = null;
        int bestScore = -1;
        for (String candidate : mediaOrder) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (!(lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".webm") || lower.contains(".m4v"))) continue;
            String quality = MediaDetector.quality(candidate);
            int score = 0;
            try { score = Integer.parseInt(quality.replace("p", "")); } catch (Exception ignored) {}
            if (score > bestScore) { bestScore = score; best = candidate; }
        }
        if (best == null) { castCurrent(); return; }
        castUrl(best);
    }

    private void castCurrent(){
        String url=web==null?null:web.getUrl();
        if(url==null||url.isEmpty()){Toast.makeText(this,"افتح صفحة أولاً",Toast.LENGTH_SHORT).show();return;}
        String best=null;
        for(String candidate:mediaOrder){if(candidate.contains(".m3u8")||candidate.contains(".mp4")||candidate.contains(".mpd")||candidate.contains(".webm")){best=candidate;break;}}
        castUrl(best==null?url:best);
    }

    private void castUrl(String url){
        if(receiverIp.isEmpty()){new AlertDialog.Builder(this).setTitle("إعداد الاستقبال").setMessage("لازم تحفظ عنوان IP الظاهر في تطبيق AboEzzat Receiver أولاً.").setPositiveButton("ضبط الآن",(d,w)->receiverSettings()).setNegativeButton("إلغاء",null).show();return;}
        final String ip=receiverIp;
        Toast.makeText(this,"جاري إرسال الوسائط إلى الشاشة…",Toast.LENGTH_SHORT).show();
        CastingManager.send(ip, url, (success, message) -> runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()));
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

    private void downloadCurrent(){
        String url=web==null?null:web.getUrl();
        if(url==null){Toast.makeText(this,"مفيش صفحة مفتوحة",Toast.LENGTH_SHORT).show();return;}
        downloadUrl(url,"application/octet-stream");
    }

    private void downloadUrl(String url,String mime){
        try{
            DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
            req.setTitle("AboEzzat Share download");
            req.setDescription("تنزيل من المتصفح");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverMetered(true);req.setAllowedOverRoaming(false);
            req.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,Uri.parse(url).getLastPathSegment()==null?"download":Uri.parse(url).getLastPathSegment().replaceAll("[^a-zA-Z0-9._-]","_"));
            if(mime!=null)req.setMimeType(mime);
            dm.enqueue(req);Toast.makeText(this,"بدأ التنزيل — هتلاقيه في إشعارات الجهاز",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"تعذّر بدء التنزيل: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    @Override public void onBackPressed(){
        if("browser".equals(currentPage)&&web!=null&&web.canGoBack()){web.goBack();return;}
        if(!"home".equals(currentPage)){showHome();return;}
        super.onBackPressed();
    }

    @Override protected void onDestroy(){
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        super.onDestroy();
    }
}