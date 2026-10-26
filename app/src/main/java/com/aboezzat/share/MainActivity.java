package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

public class MainActivity extends Activity {

    private EditText addressBar;
    private WebView webView;
    private TextView mediaCount;
    private ArrayAdapter<String> mediaAdapter;

    private final ArrayList<String> mediaLabels = new ArrayList<>();
    private final LinkedHashMap<String, String> mediaSources =
            new LinkedHashMap<>();

    private static final String[] MEDIA_EXTENSIONS = {
            ".mp4", ".m3u8", ".mpd", ".webm", ".mov", ".m4v"
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        addressBar = findViewById(R.id.addressBar);
        webView = findViewById(R.id.webView);
        mediaCount = findViewById(R.id.mediaCount);

        Button goButton = findViewById(R.id.goButton);
        Button sendButton = findViewById(R.id.sendButton);
        ListView mediaList = findViewById(R.id.mediaList);

        mediaAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                mediaLabels
        );

        mediaList.setAdapter(mediaAdapter);

        mediaList.setOnItemClickListener((parent, view, position, id) -> {
            String url = new ArrayList<>(mediaSources.keySet()).get(position);
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        });

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request) {

                detectMedia(request.getUrl().toString());

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                addressBar.setText(url);
            }
        });

        goButton.setOnClickListener(v -> loadAddress());

        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {

                loadAddress();
                return true;
            }

            return false;
        });

        sendButton.setOnClickListener(v -> {
            if (mediaSources.isEmpty()) {
                Toast.makeText(
                        this,
                        "لم يتم اكتشاف رابط فيديو بعد",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(
                        this,
                        "اكتشاف الروابط يعمل مبدئيًا، وسيتم ربط الإرسال لاحقًا",
                        Toast.LENGTH_LONG
                ).show();
            }
        });

        webView.loadUrl("https://www.google.com");
    }

    private void loadAddress() {
        String input = addressBar.getText().toString().trim();

        if (input.isEmpty()) {
            return;
        }

        String url = input;

        if (!url.matches("(?i)^https?://.*")) {
            if (input.contains(" ") || !input.contains(".")) {
                url = "https://www.google.com/search?q="
                        + input.replace(" ", "+");
            } else {
                url = "https://" + input;
            }
        }

        addressBar.setText(url);
        webView.loadUrl(url);
    }

    private synchronized void detectMedia(String url) {
        String lower = url.toLowerCase(Locale.ROOT);

        boolean isMedia = false;

        for (String extension : MEDIA_EXTENSIONS) {
            if (lower.contains(extension)) {
                isMedia = true;
                break;
            }
        }

        if (!isMedia || mediaSources.containsKey(url)) {
            return;
        }

        runOnUiThread(() -> {
            if (mediaSources.containsKey(url)) {
                return;
            }

            mediaSources.put(url, url);
            mediaLabels.add(createMediaLabel(url));

            mediaAdapter.notifyDataSetChanged();

            mediaCount.setText(
                    "المصادر المكتشفة: " + mediaLabels.size()
            );
        });
    }

    private String createMediaLabel(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        String type;

        if (lower.contains(".m3u8")) {
            type = "HLS";
        } else if (lower.contains(".mpd")) {
            type = "DASH";
        } else if (lower.contains(".webm")) {
            type = "WEBM";
        } else if (lower.contains(".mp4")
                || lower.contains(".m4v")) {
            type = "MP4";
        } else {
            type = "وسائط";
        }

        String host = android.net.Uri.parse(url).getHost();

        if (host == null) {
            host = "مصدر فيديو";
        }

        return type + "  •  " + host;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }

        super.onDestroy();
    }
                   }
