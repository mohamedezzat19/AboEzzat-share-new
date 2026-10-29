package com.aboezzat.share;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles shared web links without opening the main Share interface.
 * This activity is intentionally translucent and finishes after the cast request.
 */
public class ShareLinkActivity extends Activity {
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutTask = () -> finishWithMessage(
            "لسه ماوصلتش للريسيفر. اتأكد إن AboEzzat Receiver مفتوح والجهازين على نفس شبكة Wi‑Fi.");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mainHandler.postDelayed(timeoutTask, 40000L);
        String url = extractUrl(getIntent());
        if (url == null) {
            finish();
            return;
        }

        // Honor the IP explicitly selected in Share settings. A temporary failure
        // must not trigger discovery that silently switches to another receiver.
        String savedIp = getSharedPreferences("aboezzat_browser", MODE_PRIVATE)
                .getString("receiver_ip", "").trim();
        if (!savedIp.isEmpty()) {
            sendToReceiver(savedIp, url, true);
            return;
        }

        CastingManager.discover(getApplicationContext(), devices -> {
            // Discovery only returns addresses that answered the Receiver ping.
            String receiverIp = (devices == null || devices.isEmpty()) ? null : devices.get(0);
            if (receiverIp == null) {
                finishWithMessage("مش لاقي AboEzzat Receiver؛ افتح Share واضغط إعداد IP لاختيار الجهاز، وتأكد إن الجهازين على نفس شبكة Wi‑Fi.");
                return;
            }
            // Save the first verified receiver so subsequent shares keep using the same device.
            getSharedPreferences("aboezzat_browser", MODE_PRIVATE).edit()
                    .putString("receiver_ip", receiverIp).apply();
            sendToReceiver(receiverIp, url, false);
        });
    }


    private void sendToReceiver(String receiverIp, String url, boolean wasSelected) {
        String apiKey = getSharedPreferences("aboezzat_browser", MODE_PRIVATE)
                .getString("rapid_api_key", "").trim();
        if (apiKey.isEmpty()) {
            // First-time setup must happen in Share, where the key prompt is shown.
            // After the user saves a key, future shares can be resolved in the background.
            try {
                Intent setupIntent = new Intent(this, MainActivity.class);
                setupIntent.setAction(Intent.ACTION_VIEW);
                setupIntent.setData(Uri.parse(url));
                setupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(setupIntent);
                finish();
            } catch (Exception e) {
                finishWithMessage("افتح Share مرة واحدة واضبط مفتاح RapidAPI لاستخدام استخراج Black Hole.");
            }
            return;
        }
        RapidApiExtractor.resolve(apiKey, url, (mediaUrl, mediaType, message) -> {
            if (completed.get()) return;
            if (mediaUrl != null && !mediaUrl.trim().isEmpty() && !mediaUrl.equals(url)) {
                sendResolvedToReceiver(receiverIp, mediaUrl, wasSelected, true,
                        "تم استخراج " + mediaType + " مباشر");
            } else {
                sendResolvedToReceiver(receiverIp, url, wasSelected, false, message);
            }
        });
    }

    private void sendResolvedToReceiver(String receiverIp, String url, boolean wasSelected,
                                        boolean extracted, String extractionMessage) {
        CastingManager.send(receiverIp, url, (success, message) -> {
            if (success) {
                finishWithMessage((extracted ? extractionMessage + "؛ " : "")
                        + "اتبعَت الوسائط للشاشة.");
            } else if (wasSelected) {
                finishWithMessage("الجهاز المختار " + receiverIp + " لم يستجب. احتفظنا بعنوانه؛ افتح إعداد IP في Share لاختبار الاتصال أو اختيار جهاز آخر. " + message);
            } else {
                finishWithMessage("ماقدرتش أبعت الوسائط للشاشة: " + message);
            }
        });
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(timeoutTask);
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

        // Search the full shared text (including multiline shares), then remove
        // punctuation commonly appended by messaging apps without touching the query.
        Matcher matcher = Pattern.compile("(?i)https?://[^\\s<>\\\"']+").matcher(candidate);
        if (!matcher.find()) return null;
        String url = matcher.group().replaceAll("[),.?!;،؛؟\\]}]+$", "");
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null ||
                    !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            return url;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void finishWithMessage(String message) {
        if (!completed.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(timeoutTask);
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
