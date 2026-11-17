# Android / BOOX test results

Tested November 16, 2026, on the USB-connected **BOOX NoteAir6C**, Android **16**, physical display **1860 × 2480**. The observed test orientation was landscape, with a native WebView viewport of approximately 1322 × 844 CSS pixels. No horizontal document overflow was observed in the tested chat view.

Branch: `android/boox-apk`. APK: `android/build/LearnHarness.apk`, package `ai.learnharness.android`.

Final APK SHA-256:

```text
B23DA10C1C88440DCF981DDCDE3411859AD4861AB9D11F56160245067A4DE5AE
```

## Passed on the actual tablet

| Check | Observed result |
| --- | --- |
| Build and install | Java/Android SDK build completed; apksigner verified v2/v3 signatures; ADB install succeeded. |
| USB connection | Authenticated pairing and the full application loaded through `adb reverse`. |
| Wi-Fi connection | Paired to the PC Wi-Fi address; removed USB forwarding and verified the app continued to work. |
| Existing desktop profile | Loaded existing conversations and the user's configured ChatGPT model selections. No provider model changes were made. |
| Real provider response | Submitted a short BOOX connection-test lesson through the tablet UI. `gpt-6-astra` returned an explanation of 2 + 2; it rendered on the BOOX and was persisted in the existing desktop profile. This was a real provider call, not a mocked response. |
| Persistence and reconnect | Stopped the PC backend/gateway, observed the unavailable-PC message, restarted through `start-android.ps1 -UseDesktopProfile`, and reloaded the saved test response. |
| Attachments | Opened Android's native picker, selected a 70-byte text fixture, verified its bytes arrived on the PC, and observed its path chip in the composer. This initial upload used the separate checkout test profile before switching to the desktop profile. |
| Downloads | Generated a text blob through the WebView, saved it through Android's document provider, and read back `LearnHarness Android download verified.` from the chosen tablet folder. |
| Microphone | Android permission flow worked. Chromium logs identified the need for `MODIFY_AUDIO_SETTINGS`; after adding it, `getUserMedia({audio:true})` returned a stream and the test immediately stopped all tracks. No recording was retained or sent for transcription. |
| Native Back | Returned from the test chat to the preceding app page using Android Back. |
| Graph and plugins | Knowledge-graph landing view rendered; all four core plugins were present in the plugin registry. Their existing disabled state was preserved. |
| Saved pairing | Repeated APK updates and process restarts reused the saved Wi-Fi connection successfully. |

The test created one clearly labeled BOOX connection-test conversation in the desktop profile, an upload fixture in the separate checkout profile, and download fixtures on the tablet. Existing chats were not edited or deleted.

## Automated checks

- TypeScript `tsc --noEmit`: passed.
- Gateway integration tests: **2 passed**. These exercise all-route authorization, request-body forwarding, credential stripping, unbuffered SSE, upload persistence, filename containment, and declared-size rejection.
- Focused attachment, native-helper, and tracing tests: **49 passed** after updating the tracing policy assertion to include Android credentials and build artifacts.
- Changed frontend/config lint: no errors. The repository ESLint configuration ignores the gateway script; `node --check` also passed for the gateway and its integration tests.
- Production Next output compiled, generated its pages, and was served successfully during the device tests. The installed Next.js version emitted an existing security advisory, and `next start` emitted its existing standalone-output warning. Dependency versions were not upgraded in this port.

The first full repository regression run reported **5,221 passed, 52 failed, 11 skipped**. The failing files were rerun against an isolated unchanged baseline: most failures reproduced there. Two native-helper timing failures passed when rerun alone. One failure was an exact tracing-exclusion assertion affected by this port; it was updated to test the new secret exclusions and now passes. Baseline-only failures also appeared in the reference/authoring tests. The full suite was not rerun after that focused fix; it must not be described as entirely passing.

Local diagnostic logs and screenshots are under ignored `.android-tools/` (not shipped): `regression-tests.log`, `baseline-tests.log`, `final-focused-tests.log`, `reconnect-test.json`, and `boox-final.png`.

## Remaining limits

- Interactive workflows of disabled plugins were not enabled or exercised. Their registry/build inclusion was checked.
- Microphone capture passed; end-to-end dictation/transcription was not tested because that uses a separately configured speech provider.
- New OAuth login flows, every desktop tool, every attachment type, long background sessions, and other Android devices/orientations were not exhaustively tested.
- This APK requires the PC companion. Desktop tools run on the PC; it is not a standalone Android port of the Node backend.
- The repository's existing Next.js security advisory remains and should be addressed separately.
