# AboEzzat Share — Cloud administration foundation

This directory documents the cloud-admin foundation for managing AboEzzat Share and Receiver outside the home network.

## Important status

This is a **foundation, not a live remote-control service yet**. The Android apps currently use local-network discovery/control. Remote operation requires a Firebase project, Authentication configuration, secure rules deployment, and app-side integration before any device can appear online or accept commands.

## Planned admin capabilities

- **Devices:** register Receiver instances under the authenticated owner's account; show last heartbeat and online/stale status.
- **Remote:** enqueue allow-listed playback commands (play URL, pause, resume, stop, seek, volume) with command IDs, expiry and acknowledgements.
- **Diagnostics:** app version, last-seen timestamp, sanitized error events, and connection status.
- **Updates:** report installed version and publish update metadata; never silently install an APK outside Android's supported install/update flow.
- **History:** timestamped command and playback events with retention limits.
- **Security:** Firebase Authentication, per-user data isolation, short-lived commands, validation, audit events, and no credentials in the repository.

## Suggested Realtime Database layout

```text
/users/{uid}/devices/{deviceId}
/users/{uid}/commands/{commandId}
/users/{uid}/events/{eventId}
/users/{uid}/settings
```

A Receiver should only consume commands addressed to its registered device ID. It must validate command type, payload length, URL scheme (HTTPS by default), expiry, and replay/duplicate command IDs before execution. Do not expose the current local HTTP control port 8081 directly to the public internet.

## Setup when ready to connect a real cloud project

1. Create a Firebase project and enable a sign-in provider.
2. Register the Android applications using their existing application IDs.
3. Configure the web admin app with that project's public Firebase web configuration (never put service-account credentials in a client).
4. Deploy `firebase/database.rules.json` and verify authenticated access with the Firebase Emulator Suite.
5. Integrate authentication and device heartbeats into both Android apps, then add command polling/acknowledgements and diagnostics.
6. Test with two accounts and verify that one account cannot read or control the other's devices.

The included rules are deliberately deny-by-default until a user is authenticated and can access only their own UID subtree. They are a baseline and must be emulator-tested before production deployment.
