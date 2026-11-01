# Technical comparison and implementation roadmap

This document treats `AboEzzat-Share` as a read-only reference and targets only `AboEzzat-share-new` and `AboEzzat-Receiver`.

## Verified baseline (repository inspection)

- Share-New currently has `BrowserEngine`, `MediaDetector`, `PlatformExtractor`, and `CastingManager` source files.
- `MediaDetector` performs best-effort HTTP page inspection and scans HTML/known media URL patterns. It does not prove universal WebView request interception or extraction from protected/DRM streams.
- `PlatformExtractor` is an optional adapter to a separately configured HTTP API; it is not a cloud-managed rules engine by itself.
- `CastingManager` implements local receiver discovery/control over TCP 8081 and a separate video/audio URL sending path. The current visible sender path sends URLs, not a structured JSON command with arbitrary request headers.
- Receiver depends on AndroidX Media3 ExoPlayer, HLS, DASH and UI artifacts. Its cloud-control document is a contract only; Firebase registration, heartbeat, command polling/acknowledgement and remote diagnostics are not yet live.
- The cloud-admin web page and deny-by-default Firebase rules are a foundation. They do not make remote commands operational without app integration, project configuration, rules/emulator testing and device testing.

## Target architecture

1. **Fast local cast:** retain discovery and playback over the trusted LAN. Do not expose TCP 8081 to the public internet.
2. **Media detection:** use WebView request observation plus DOM extraction where permitted, deduplicate candidates, and label confidence/source. Handle blob URLs separately; a blob URL is not directly playable on another device without a supported local transfer/bridge.
3. **Request context:** define an explicit, size-limited playback request contract for URL plus only required headers (e.g. Referer/User-Agent/Origin). Do not forward cookies or authorization tokens by default. Validate and allow-list header names, redact secrets from logs, and reject unsafe schemes.
4. **Media3 playback:** map supported headers to Media3 HTTP data-source request properties. Test HLS/DASH, redirects, expiring URLs, separate audio/video tracks and failure reporting. Only merge tracks when formats and timelines are compatible; avoid promising that headers bypass CORS or site authorization.
5. **Cloud fallback:** Firebase Auth + per-owner device records, heartbeat, expiring allow-listed commands, deduplication, acknowledgements and offline-safe behavior. The cloud is a command relay, not a media proxy; local and cloud control must use the same validated command model.
6. **Remote extractor/rules:** treat remote rules as signed/versioned configuration with schema validation, TTL, rollback and safe defaults. Never execute arbitrary downloaded JavaScript or accept untrusted regex/selectors without bounds and tests.
7. **Operations:** device list, remote controls, diagnostics, bounded history, version/update metadata, account isolation and security audit.

## Acceptance checklist

- [ ] Local discovery and one-tap play continue to work with no Firebase configuration.
- [ ] HLS, MP4 and DASH happy-path tests; separate audio/video test; malformed/expired URL tests.
- [ ] Request headers reach the receiver and are applied only to the intended media request; secrets are redacted.
- [ ] Background delivery is tested against Android background-start restrictions on real Android TV/Fire TV devices.
- [ ] Cloud commands expire, are idempotent, are owner-scoped, and receive an acknowledgement.
- [ ] Two-user Firebase Emulator tests prove account isolation; unauthenticated access is denied.
- [ ] Remote rules can be rolled back and cannot execute arbitrary downloaded code.
- [ ] CI build and unit/instrumentation tests pass before merge.

## Important limits

Successful compilation does not prove playback compatibility, cloud operation, background launch behavior or cross-network availability. A remote URL extractor cannot reliably resolve every protected site, DRM stream, CAPTCHA, login-gated source or short-lived token. CORS is a browser policy; a native Media3 request is not a general-purpose authorization bypass.
