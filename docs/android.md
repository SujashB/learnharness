# LearnHarness on BOOX / Android

This branch adds a PC-connected Android APK. The entire existing Next.js application runs on the PC; the tablet displays it in Android WebView. Agent execution, provider credentials, conversations, knowledge graph, and plugins remain on the PC. The PC must be awake and reachable. This is not a standalone/offline Android backend.

## Start the PC companion

From the repository in PowerShell, after `npm ci`, `npm run plugins:setup`, and `npm run build`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-android.ps1 -UseDesktopProfile
```

`-UseDesktopProfile` uses `%APPDATA%\LearnHarness\data` and the existing desktop credential directory. Close the desktop app before sharing its profile; do not run two backends against the same profile. Omit the switch for this checkout's separate `data/` and `auth.json` profile. The launcher checks both ports before starting and runs the two processes in hidden windows.

The launcher selects the Windows `Wi-Fi` IPv4 address. Pass `-PcAddress 192.168.x.x` for another interface or an explicit address. The backend binds only to `127.0.0.1:3000`; the authenticated TLS gateway listens on port 3443. If Windows Firewall blocks it, allow **only the gateway port** from your tablet/local network. No firewall settings are changed by these scripts.

The pairing string is written to `.android-pairing/pairing.txt`. Open the APK, paste that string, and select **Connect**. The APK remembers it in its private app storage (Android backup is disabled). Treat the pairing string as a password: it grants full access to this single-user app, including agent execution and settings. Never share or commit it.

To stop the companion:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stop-android.ps1
```

The stop script verifies process IDs, executable names, and start times before stopping only the processes recorded by the launcher. Logs are in `.android-pairing/`. Use **Retry** in the APK after a temporary disconnection. **Connection → Change** replaces the saved pairing; **Forget** removes it from that tablet. To revoke the shared pairing token, stop the companion, remove `.android-pairing/device-token`, then restart and re-pair. Forgetting a tablet does not revoke an already copied token.

The locally generated certificate lasts one year. To renew it, stop the companion, move `server.key` and `server.crt` out of `.android-pairing/`, restart, and re-pair. Keep both the token and certificate fingerprint private. If the PC's IP changes, restart the companion with the new address and pair again.

## Build and install the APK

Requirements: Java 17, Android SDK platform 35 and build-tools 35.0.0. No Android Studio, Gradle, or third-party Android runtime library is required. The build uses the SDK's aapt2, javac, D8, zipalign, and apksigner directly.

This workspace's portable tools are in the ignored `.android-tools/` directory. For another machine, install those official tools and pass their paths:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-android.ps1 -SdkRoot C:\Android\Sdk -JdkRoot C:\Java\jdk-17
# With the project-local tools:
npm run android:build
adb install -r android/build/LearnHarness.apk
```

Output: `android/build/LearnHarness.apk`, package `ai.learnharness.android`, minimum Android 8/API 26, target API 35. This is a locally signed development APK, not a Play Store release. Keep `.android-tools/android-debug.keystore` to install future builds over this one without uninstalling.

For USB testing, use `adb reverse tcp:3443 tcp:3443` and pair with a string whose `url` is `https://127.0.0.1:3443` (retain the same token and fingerprint). Remove the forwarding with `adb reverse --remove tcp:3443` to prove Wi-Fi independence. USB debugging is not needed for normal Wi-Fi use.

WebView debugging is off for normal launches. Local testing can enable it explicitly:

```powershell
adb shell am force-stop ai.learnharness.android
adb shell am start -n ai.learnharness.android/.MainActivity --ez webviewDebug true
```

Force-stop and reopen normally when finished.

## Android behavior and boundaries

- The paperclip uploads tablet files to `<learning data>/android-uploads/`, then inserts their PC paths into the existing composer. Limit: 25 MiB per file. Files are retained with learning data; deletion is manual. Desktop path/folder attachment behavior is unchanged.
- Browser and generated-blob downloads use Android's document Save dialog, up to 50 MiB per file. Pending bytes are cached privately so Android can recreate the activity while the picker is open.
- Microphone capture requests Android permission and grants only audio capture to the paired local app origin. Speech-to-text still requires the existing app's dictation provider configuration. No camera permission is requested.
- Android Back uses WebView navigation history. External HTTP(S)/mail links open their associated Android apps. PC-side OAuth callbacks and installed tools remain PC-side operations; ChatGPT sign-in callbacks must be completed on the PC.
- Plugin code and tools run on the PC. The installed core plugins retain their existing enabled/disabled settings. This port does not enable plugins or change provider models.

The Android app uses a cookie-protected, loopback-only relay to preserve same-origin POST, uploads, secure-context audio, and SSE. The relay pins the PC certificate's SHA-256 fingerprint, checks certificate validity, and sends the access token only over TLS to that PC. It never bypasses WebView SSL errors. The PC gateway authenticates all requests before forwarding to Next, strips transport credentials and forwarding headers, and streams responses without buffering. The local relay does not accept arbitrary upstream URLs.

Android tooling, APK output, signing keys, and pairing credentials are excluded from Git and Next standalone tracing. Do not bundle `.android-tools/` or `.android-pairing/` into a distribution.

## Validation

```powershell
npm run android:test
npm run typecheck
$env:NODE_ENV = 'test'
node --import tsx --test tests/provider-tracing.test.ts tests/file-path-drop-zone.test.tsx tests/file-path-chip.test.tsx tests/file-path-preload.test.ts tests/file-paths.test.ts
```

See [Android device test results](android-test-results.md) for the tested device, actual outcomes, and remaining limits.
