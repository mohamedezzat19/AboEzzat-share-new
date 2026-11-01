package com.aboezzat.share;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable description of a media stream captured from a shared page. */
public final class CapturedMedia {
    public final String url;
    public final String title;
    public final String posterUrl;
    public final String mimeType;
    public final String sourcePage;
    public final Map<String, String> headers;
    public final long capturedAt;

    public CapturedMedia(String url, String title, String posterUrl, String mimeType,
                         String sourcePage, Map<String, String> headers) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.posterUrl = posterUrl == null ? "" : posterUrl;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.sourcePage = sourcePage == null ? "" : sourcePage;
        this.headers = headers == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.capturedAt = System.currentTimeMillis();
    }
}
