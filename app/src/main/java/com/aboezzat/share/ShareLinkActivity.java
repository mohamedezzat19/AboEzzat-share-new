package com.aboezzat.share;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private long startedAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startedAt = SystemClock.elapsedRealtime();
        mainHandler.postDelayed(timeoutTask, 18000L);
        String url = extractUrl(getIntent());
        if (url == null) {
            finish();
            return;
        }

        CastingManager.discover(getApplicationContext(), devices -> {
            String receiverIp = null;
            if (devices != null) {
                for (String candidate : devices) {
                    if (CastingManager.isReceiver(candidate)) {
                        receiverIp = candidate;
                        break;
                    }
                }
            }

            if (receiverIp == null) {
                finishWithMessage("مش لاقي AboEzzat Receiver؛ تأكد إن الجهازين على نفس شبكة Wi‑Fi والريسيفر شغال.");
                return;
            }

            CastingManager.send(receiverIp, url, (success, message) ->
                    finishWithMessage(success
                            ? "اتبعَت الرابط للشاشة؛ لو المنصة مدعومة هيبدأ التشغيل تلقائيًا."
                            : "ماقدرتش أبعت الرابط للشاشة: " + message));
        });
    }

    private String extractUrl(Intent intent) {
        if (intent == null) return null;
        String candidate = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            candidate = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (candidate == null || candidate.trim().isEmpty()) return null;

        Matcher matcher = Pattern.compile("(?i)https?://[^\\s<>\\\"']+").matcher(candidate);
        if (!matcher.find()) return null;
        String url = matcher.group().replaceAll("[),.]+$", "");
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
