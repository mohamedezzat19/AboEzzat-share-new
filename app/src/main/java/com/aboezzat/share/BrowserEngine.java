package com.aboezzat.share;

import android.net.Uri;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.net.URLEncoder;

public final class BrowserEngine {
    private BrowserEngine() {}

    public static void configure(WebView web) {
        WebSettings s = web.getSettings();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        }
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Keep user-initiated target=_blank windows working, but disallow
        // JavaScript-created popups that do not follow a user gesture.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
    }

    public static String normalizeAddress(String input) {
        if (input == null) return "";
        input = input.trim();
        if (input.isEmpty()) return "";
        if (input.matches("(?i)^https?://.*")) return input;
        if (input.contains(" ") || !input.contains(".")) {
            try { return "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8"); }
            catch (Exception ignored) { return "https://www.google.com"; }
        }
        return "https://" + input;
    }

    public static boolean isWebUrl(String value) {
        if (value == null) return false;
        String scheme = Uri.parse(value).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}