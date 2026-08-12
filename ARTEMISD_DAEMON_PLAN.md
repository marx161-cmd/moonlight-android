# artemisd — Persistent Streaming Daemon Plan

This is a patch to **Diana (`com.termux.diana.root.noir`)** — not a new system app.
Same package, same `sharedUserId="android.uid.system"` (UID 1000), same platform
signing, same source tree, same APPS_MANIFEST row, same deploy/build/promote workflow.

## STATUS — 2026-08-12 (build + live-verified on Pixel)

| Component | Status |
|-----------|--------|
| Overlay window + SurfaceView | ✅ live |
| SurfaceView z-order (setZOrderOnTop) | ✅ fixed black screen |
| QS tile toggle (Artemis Overlay) | ✅ live |
| Show/hide/toggle/stop intent actions | ✅ live |
| Stream connection (Moonlight → Apollo) | ✅ live |
| AV1 hardware decode @120fps | ✅ live |
| Runtime resolution from display (was stale 2340x1080) | ✅ fixed black screen |
| Input forwarding (key/touch/pointer) | ✅ wired, needs on-device verify |
| Runtime FPS setter (setTargetFps) | ✅ wired |
| CLI scripts (artemis-toggle/show/hide/stop) | ✅ on device |
| Socket server (artemisd.sock) | ⏳ not yet built (QS tile + intents suffice for now) |
| Boot startup | ❌ skipped (user: start on first launch) |
| Stream cert/uniqueId read | ✅ from Diana files + DB |

### Key implementation fixes discovered during build

1. **`MediaCodecHelper.initialize()` required** before `MediaCodecDecoderRenderer` —
   the Service must call it in `onCreate()` with a non-null `glRenderer`
   (from `GlPreferences.readPreferences(this).glRenderer`). NPE if null.
2. **`MediaCodecDecoderRenderer` needs a `Context` overload** — original constructor
   took `Activity`; added a `Context`-accepting overload (the `Activity` field is
   stored but never used after construction).
3. **App launch needs UUID** — `NvConnection.launchApp()` uses `getAppUUID()`, so
   `StreamConfiguration.setApp()` must receive a full `NvApp(name, uuid, id, hdr)`.
   Desktop UUID for comrade: `D35489BC-EF06-D101-D003-1F39FECBE5CD`.
4. **`SurfaceView.setZOrderOnTop(true)`** — without it the SurfaceView surface sits
   at `z=-2` behind the window, and the overlay's background covers the video
   (black screen). Also `holder.setFormat(PixelFormat.TRANSLUCENT)`.
5. **Stream resolution must come from the live display** — hardcoding 2340x1080
   (stale) on a 1080x2410 portrait screen produced a second black-screen case.
   Read `DisplayMetrics.widthPixels/heightPixels` at connect time.

## Architecture

```
~/.termux/boot/20-artemisd                    ← Termux:Boot (already on device)
  │  am start-foreground-service
  ▼
com.termux.diana.root.noir                    ← EXISTING system priv-app
  │
  ├─ .Game (Activity)                         ← UNTOUCHED fallback path
  │     └── StreamController (delegated)      ← refactored to use extracted core
  │
  └─ .overlay.ArtemisDaemonService (NEW)      ← persistent foreground daemon
        │
        ├─ ArtemisConfig                       ← reads JSON from
        │                                         $HOME/.config/artemis/artemis.conf
        │
        ├─ ArtemisOverlayWindow                ← WindowManager + SurfaceView
        │     │    TYPE_APPLICATION_OVERLAY (2038) — NOT a sub-window type,
        │     │    so SurfaceView's internal window token chains correctly
        │     │    through WMS → surfaceCreated() fires cleanly
        │     │
        │     └─ StreamContainer (SurfaceView) ← EXISTING widget, refactored init()
        │
        ├─ StreamController                    ← extracted from Game.java:795-878
        │     ├─ NvConnection                  ← EXISTING
        │     ├─ MediaCodecDecoderRenderer     ← EXISTING + setTargetFps(int)
        │     ├─ MoonBridge (JNI)              ← EXISTING
        │     └─ ControllerHandler + input     ← EXISTING, wired via Game helpers
        │
        └─ ArtemisSocketServer                 ← LocalServerSocket
              └─ $PREFIX/tmp/artemisd.sock     ← filesystem namespace (nc -U works)
                    ▲
$PREFIX/bin/artemis   ← CLI shell wrapper
```

## Show/Hide Mechanics (Never Stop Connection)

**Hide** (artemis hide / toggle to hidden):

1. `setTargetFps(1)` on MediaCodecDecoderRenderer — pacing loop naturally
   presents ~1 frame/sec. Decoder keeps running, GOP stays alive, Surface
   never destroyed.
2. `mRootView.setAlpha(0.0f)` — compositor skips blending this layer.
3. LayoutParams: add `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`.
4. Release pointer capture if held.
5. `windowManager.updateViewLayout(mRootView, params)`.

**Show** (artemis show / toggle to visible):

1. `setTargetFps(config.getStreamFps())` — restore full 120fps cadence.
2. `mRootView.setAlpha(1.0f)`.
3. LayoutParams: clear `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`.
4. `updateViewLayout(...)`, `requestFocus()`, `requestPointerCapture()`.

**SurfaceView never removed:** `surfaceDestroyed()` never fires. No decoder
null-deref trap. No `vo=null` reattach crash. This is the whole point.

## Runtime FPS Regulation

MediaCodecDecoderRenderer already has:

- `targetFps` field — set once in `setup()` at :798, read in the adaptive
  pacing loop at :1176 to compute `streamPeriodNs`
- `applySurfaceFrameRate(surface, targetFps)` at :2412 — hints SurfaceFlinger via
  `surface.setFrameRate()` (API 30+)

**Two additions (~10 lines total):**

1. `MediaCodecDecoderRenderer.setTargetFps(int fps)`:
   ```java
   public void setTargetFps(int fps) {
       this.targetFps = fps;
       applySurfaceFrameRate(/*current surface*/, fps);
   }
   ```
2. In the pacing loop at :1176, re-read `targetFps` every iteration instead of
   once at init. The division `1_000_000_000 / fps` is trivial overhead.

That's it. No new frame-drop counter. No stream renegotiation. The existing
adaptive pacing infrastructure is the engine — just make `targetFps` mutable
at runtime.

## Input Wiring — Use Existing Game-Level Helpers

Do NOT route keys/motion directly into `ControllerHandler`. Use the existing
Game.java dispatch helpers that already handle modifier tracking, virtual key
filtering, device ID checks, and touch context routing:

| Event | Entry point | Line | Wire in overlay as |
|---|---|---|---|
| Key down | `Game.handleKeyDown(KeyEvent)` | :2030 | `onKeyDown` → `streamController.handleKeyDown(event)` |
| Key up | `Game.handleKeyUp(KeyEvent)` | :2121 | `onKeyUp` → `streamController.handleKeyUp(event)` |
| Motion | `Game.handleMotionEvent(View, MotionEvent)` | :3452 | `onGenericMotionEvent` + `onTouchEvent` → controller |
| Relative mouse | `onCapturedPointer(View, MotionEvent)` | :529-536 | `setOnCapturedPointerListener` → `handleMotionEvent(view, event)` |

Pointer capture: call `requestPointerCapture()` after show + focus, wire
`setOnCapturedPointerListener` to forward into `handleMotionEvent`.

## Socket IPC

**Transport:** `LocalSocket` + `LocalSocketAddress(path, Namespace.FILESYSTEM)`.

Abstract namespace (`LocalServerSocket("name")`) does NOT work with `nc -U` —
the CLI must work with `nc -U $PREFIX/tmp/artemisd.sock`. Filesystem namespace
is the only viable option.

**Path:** `$PREFIX/tmp/artemisd.sock` → resolves to
`/data/data/com.termux/files/usr/tmp/artemisd.sock`.

**Permissions:** `chmod 666` after bind (npud pattern at
`npud/src/main.rs:629`). Critical for root-launched instances.

**Protocol:** Newline-delimited JSON, one line per request/response.

```
→ {"cmd":"status"}
← {"connected":true,"host":"100.108.8.60","fps":120,"codec":"av1",
     "visible":true,"bitrate":"40 Mbps","latency":"7ms","decoder":"c2.google.av1.decoder"}

→ {"cmd":"show"}
← {"ok":true}

→ {"cmd":"hide"}
← {"ok":true}

→ {"cmd":"toggle"}
← {"ok":true,"visible":false}

→ {"cmd":"connect"}
← {"ok":true}

→ {"cmd":"disconnect"}
← {"ok":true,"state":"disconnected"}

→ {"cmd":"config"}
← {"host":"100.108.8.60","port":47989,"httpsPort":47984,...}

→ {"cmd":"config reload"}
← {"ok":true}
```

Unknown commands return `{"ok":false,"error":"unknown command: <cmd>"}`.

## Foreground Service (targetSdk 34)

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".overlay.ArtemisDaemonService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="game-streaming" />
</service>
```

Runtime:
```java
startForeground(NOTIFICATION_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
```

Lifecycle: `START_STICKY` — must survive process death. Not `START_NOT_STICKY`
(which Termux:Float uses — its terminal sessions aren't meant to survive).

## Config File

**Format:** JSON (Gson 2.13.1 already in `build.gradle:198`). TOML would be
a needless new dependency.

**Path:** `$HOME/.config/artemis/artemis.conf` — follows npud convention
(`~/.config/npud/npud.conf`). All UID 1000 apps share Termux home read+write.

```json
{
  "host": "100.108.8.60",
  "port": 47989,
  "httpsPort": 47984,
  "width": 2340,
  "height": 1080,
  "fps": 120,
  "bitrate": 40,
  "codec": "auto",
  "inputOnly": false,
  "autoReconnect": true,
  "reconnectDelaySec": 5,
  "audioEnabled": true
}
```

Default values embedded in `ArtemisConfig.java`. First launch writes defaults if
file missing. `config reload` re-reads without daemon restart.

## Reference Patterns to Steal

| Pattern | Source | What to steal |
|---|---|---|
| Service + WindowManager.addView() + TYPE_APPLICATION_OVERLAY + foreground notification | `TermuxFloatService.java:43-99` + `TermuxFloatView.java:181-206` | Foreground Service lifecycle, layout inflation, addView, updateViewLayout, show/hide flags, UID 1000 context |
| Socket server (accept loop, chmod, thread-per-conn) | npud `src/main.rs:615-662` | Bind path, permissions, accept loop pattern → translated to Java `LocalServerSocket` + `LocalSocketAddress.Namespace.FILESYSTEM` |
| Java socket client | SpectreBoard `NpudClient.kt` | `LocalSocket` + `LocalSocketAddress(path, FILESYSTEM)` client pattern |
| Boot startup (setsid, logs) | npud `boot/20-npud` | Termux:Boot script structure, env vars, log redirection |
| Socket health probe watchdog | Cybersyn `npud-watchdog.yaml` | Time-tick trigger, socket probe (not pgrep), restart via am start |
| Stream extraction from Activity | `61-diana-minus-one-overlay.md` Step 2 | StreamController design, SurfaceCallback interface, Game.java delegation |
| Overlay window design | `61-diana-minus-one-overlay.md` Step 3 | WindowManager params, FLAG_NOT_FOCUSABLE, scroll/touch management |
| System app privilege model | `70-crdroid-rom.md` | `ro.control_privapp_permissions=log`, no OTA for manifest changes, signing trap, UID-1000 invariants |

## Files

### New Files (all in Diana's `app/src/main/java/com/limelight/`)

| # | File | Lines | Purpose |
|---|------|-------|---------|
| 1 | `overlay/ArtemisDaemonService.java` | ~200 | Foreground Service: owns lifecycle, wires window+stream+socket |
| 2 | `overlay/ArtemisOverlayWindow.java` | ~150 | WindowManager overlay + SurfaceView, show/hide with LayoutParams toggle |
| 3 | `overlay/ArtemisSocketServer.java` | ~120 | LocalServerSocket: accept loop, parse JSON commands, dispatch to daemon |
| 4 | `overlay/StreamController.java` | ~250 | Extracted streaming core from Game.java:795-878 — NvConnection, decoder, audio, input |
| 5 | `overlay/ArtemisConfig.java` | ~80 | Parse `~/.config/artemis/artemis.conf` JSON, defaults, reload |
| 6 | `overlay/SurfaceCallback.java` | ~15 | Interface: decouples StreamContainer.init() from Game/Activity dependency |
| 7 | `res/layout/overlay_artemis.xml` | ~20 | Layout: FrameLayout → StreamContainer (MATCH_PARENT both axes) |

### Files on Device

| # | File | Purpose |
|---|------|---------|
| 8 | `~/.termux/boot/20-artemisd` (chmod 700) | Boot script: `setsid` pattern from npud, starts foreground service |
| 9 | `$PREFIX/bin/artemis` | CLI: shell wrapper encoding JSON commands over `nc -U` |
| 10 | `~/.config/artemis/artemis.conf` | JSON config, first-run created from defaults |
| 11 | Cybersyn `artemis-watchdog` profile | Socket health probe, auto-restart |

### Modified Files

| # | File | What changes |
|---|------|-------------|
| 12 | `AndroidManifest.xml` | Add `<service>` for ArtemisDaemonService + 2 permissions |
| 13 | `ui/StreamContainer.java` | `init()` takes `SurfaceCallback` instead of `Game` |
| 14 | `Game.java` | Delegate stream startup to `StreamController` (preserve Activity path) |
| 15 | `binding/video/MediaCodecDecoderRenderer.java` | Add `setTargetFps(int)`, make pacing loop re-read targetFps |

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Window type | `TYPE_APPLICATION_OVERLAY` (2038) | Not a sub-window → SurfaceView token works. Termux:Float proves it on our ROM |
| FGS type | `specialUse` only, subtype `game-streaming` | TargetSdk 34, single clean type, no multi-type complexity |
| Config format | JSON | Gson 2.13.1 already in build.gradle, zero new deps |
| Config path | `$HOME/.config/artemis/artemis.conf` | npud convention, UID 1000 writable |
| Hide FPS | `setTargetFps(1)` — decoder keeps running, pacing loop drops presentation | No connection teardown. No new protocol code. Surface never destroyed |
| Show FPS | `setTargetFps(config.getStreamFps())` — restore 120fps cadence | Same path, reverse direction |
| Pointer capture timing | Via `OnFocusChangeListener`: request pointer capture only after `hasFocus=true` callback | `requestPointerCapture()` must fire AFTER WMS grants focus — calling it inline with `updateViewLayout()` races focus assignment |
| System gesture exclusion | `params.exclusionRects = [full screen rect]` when overlay visible (API 29+) | Prevents crDroid back-gesture from eating edge touches on the stream overlay |
| Surface persistence | SurfaceView never removed from window, alpha toggle only | `surfaceDestroyed()` never fires → no decoder null-deref |
| Visibility toggle | alpha + flags + pointer capture release | Clean, no surface lifecycle side effects |
| Socket | `LocalSocket + Namespace.FILESYSTEM` | `nc -U` only works with filesystem sockets |
| Boot | Termux:Boot, `setsid` + `&` | npud pattern, no init.rc, no SELinux domain needed |
| Supervision | Cybersyn socket STATUS probe | Same as npud watchdog — socket probe, not pgrep |
| CLI protocol | JSON, newline-delimited | Matches npud style, trivial to parse |
| Input dispatch | Route through Game helper methods, not raw ControllerHandler | Preserves modifier tracking, virtual key filtering, device checks |
| Relative mouse | Pointer capture + `onCapturedPointer` → `handleMotionEvent` | Mirrors Game.java:529-536 |
| Building | Same `./gradlew :app:assembleRelease` | Same tree, same signing, no new gradle config needed |
| Deploying | `blazer-sysapp-update install com.termux.diana.root.noir <apk>` | Same system app update workflow |
| ROM promotion | Rebuild, resign, update APPS_MANIFEST.md row 24 hash | Same promotion path, no new manifest entries |

## Implementation Order

| Phase | Steps | Gating | Est. Hours |
|-------|-------|--------|-----------|
| **POC** | `ArtemisOverlayWindow` + stub `ArtemisDaemonService` — deploy to Pixel: inflate overlay window, verify `surfaceCreated()` fires, prove one gamepad keydown lands, prove one captured-mouse delta arrives | **Everything gates on this** | 2-3 |
| **1** | `SurfaceCallback.java` → refactor `StreamContainer.init()` | — | 0.5 |
| **2** | `StreamController.java` — extract from `Game.java:795-878`, wire into `Game.java` as delegation | 1 | 3-4 |
| **3** | `ArtemisConfig.java` — JSON config loader with defaults | — | 1 |
| **4** | `MediaCodecDecoderRenderer.setTargetFps(int)` + pacing loop runtime read | — | 0.5 |
| **5** | `ArtemisDaemonService` + `ArtemisOverlayWindow` — full wiring: create window on service start, connect stream on surface available, show/hide with FPS toggle + pointer capture | POC + 2 + 4 | 2-3 |
| **6** | `ArtemisSocketServer.java` + `artemis` CLI script | — | 2 |
| **7** | `AndroidManifest.xml` changes (service + permissions + FGS subtype) | — | 0.5 |
| **8** | Boot script `20-artemisd` + Cybersyn watchdog | — | 0.5 |

**Total: ~12-14 hours**

## POC Test Verification

Before extracting `StreamController`, deploy a minimal stub that proves the
window + surface + input chain works:

1. `ArtemisDaemonService` starts, inflates `overlay_artemis.xml`, calls
   `WindowManager.addView()` with `TYPE_APPLICATION_OVERLAY`.
2. Logcat confirms `surfaceCreated()` fires (SurfaceView's holder callback).
3. Add a temporary `onKeyDown` listener → log the keycode. Connect a gamepad
   or Bluetooth keyboard, press a button, confirm the event arrives.
4. Call `requestPointerCapture()` on the root view, wire
   `setOnCapturedPointerListener` → log the `MotionEvent.getAxisValue(AXIS_RELATIVE_X/Y)`.
   Move a mouse, confirm relative deltas arrive.

If all three pass, everything downstream is routine refactoring.

## Deployment (Ad-Hoc Test → ROM Promotion)

```bash
# 1. Build (on comrade, always)
cd ~/builds/android/artemis-patch
./gradlew :app:assembleRelease

# 2. Sign (handled by gradle signingConfig — TERMUX_KEYSTORE from ~/.gradle/gradle.properties)

# 3. Deploy test loop
blazer-sysapp-update install com.termux.diana.root.noir \
  app/build/outputs/apk/root_noir/release/app-root-noir-release.apk

# 4. Start daemon (first time, or after crash)
adb shell am start-foreground-service \
  -n com.termux.diana.root.noir/.overlay.ArtemisDaemonService

# 5. CLI
artemis status
artemis toggle

# 6. ROM promotion (when stable)
# copy APK to device/google/blazer-secur/prebuilts-apk/com.termux.diana.apk
# update APPS_MANIFEST.md row 24: sha256, source ref
# blazer-ota-guard promoted com.termux.diana.root.noir <apk>
# blazer-ota-guard preflight → mka bacon
```

## Boot & Supervision

**Boot script** (`~/.termux/boot/20-artemisd`, chmod 700):

```bash
#!/data/data/com.termux/files/usr/bin/bash
PREFIX=/data/data/com.termux/files/usr
setsid am start-foreground-service \
  -n com.termux.diana.root.noir/.overlay.ArtemisDaemonService \
  >> "$PREFIX/tmp/artemisd.log" 2>&1 &
```

**Cybersyn watchdog** — copy of npud-watchdog pattern, swapping:
- Socket path: `$PREFIX/tmp/artemisd.sock`
- Probe command: `echo '{"cmd":"status"}' | nc -w 2 -U <path>`
- Restart command: `am start-foreground-service -n com.termux.diana.root.noir/.overlay.ArtemisDaemonService`
- `useRoot: false` (service runs as UID 1000, socket is 666)

## CLI Script

`$PREFIX/bin/artemis`:

```bash
#!/bin/sh
SOCK="$PREFIX/tmp/artemisd.sock"
case "${1:-status}" in
  status)     echo '{"cmd":"status"}' | nc -U "$SOCK" ;;
  show)       echo '{"cmd":"show"}' | nc -U "$SOCK" ;;
  hide)       echo '{"cmd":"hide"}' | nc -U "$SOCK" ;;
  toggle)     echo '{"cmd":"toggle"}' | nc -U "$SOCK" ;;
  connect)    echo '{"cmd":"connect"}' | nc -U "$SOCK" ;;
  disconnect) echo '{"cmd":"disconnect"}' | nc -U "$SOCK" ;;
  config)     echo '{"cmd":"config"}' | nc -U "$SOCK" ;;
  reload)     echo '{"cmd":"config reload"}' | nc -U "$SOCK" ;;
  *) echo "usage: artemis {status|show|hide|toggle|connect|disconnect|config|reload}" ;;
esac
```
