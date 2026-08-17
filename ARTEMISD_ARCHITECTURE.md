# artemisd — how the persistent streaming overlay works

`artemisd` turns this Moonlight/Artemis fork (**Diana**, `com.termux.diana.root.noir`)
into an always-alive, summon-anywhere desktop stream: the Moonlight stream renders into a
**Service-owned WindowManager overlay** that you toggle on/off with a Pixel Quick Tap,
**without ever tearing down the stream connection**. This doc explains the architecture and
the hard-won platform gotchas — written partly so the same pattern can be evaluated for
**mpv** (see the last section).

---

## 1. The core idea

A normal Moonlight session lives inside `Game` (an **Activity**). When you leave the
Activity, Android destroys its Surface and the stream connection tears down — reconnecting
costs an RTSP handshake (~1–2s) every time.

artemisd removes that "frontend": the stream is hosted in a **Service** that owns a
`TYPE_APPLICATION_OVERLAY` window. That window (and its Surface) persist independent of any
Activity, so the connection stays up across app switches, screen locks, and toggles. Showing
and hiding is a *client-side render decision*, not a connection event.

Two things made this viable:
1. **The Surface is never destroyed** while the daemon runs — so the decoder's render target
   is stable and the connection stays healthy.
2. **Hide = pause decoding, not stop the stream.** Resume re-syncs with one IDR frame
   (sub-100ms), so "show" feels instant even though the connection was never dropped.

---

## 2. Components

| File | Role |
|---|---|
| `overlay/ArtemisDaemonService.java` | The foreground Service (`specialUse`). Owns the overlay + stream + input, handles `OVERLAY_SHOW/HIDE/TOGGLE/STOP` intents, the screen-off escape, and the Cybersyn hid-mode gate. Implements `GameInputController.Host`. |
| `overlay/ArtemisOverlayWindow.java` | The `TYPE_APPLICATION_OVERLAY` window + its `SurfaceView`. Owns window sizing/opacity/immersive, and forwards raw input events to the controller. |
| `overlay/StreamController.java` | Thin wrapper that builds the **real** `NvConnection` + `MediaCodecDecoderRenderer` and starts the stream. Exposes `getConnection()`. |
| `input/GameInputController.java` | The **real Artemis input pipeline, lifted verbatim out of `Game`** — touch/trackpad contexts, multitouch, sensitivity, drag-drop, pen, mouse capture, `ControllerHandler`, `KeyboardTranslator`. Activity-independent (`Context` + reference `View` + `NvConnection` + `InputCaptureProvider` + a `Host` callback). |
| `binding/input/capture/OverlayPointerCaptureProvider.java` | Service-safe `InputCaptureProvider` for the overlay's relative-mouse path (the standard one needs an Activity). |
| `overlay/NoAudioRenderer.java` | Discards host audio — opens **no** AudioTrack (see the AoC gotcha). |
| `overlay/ArtemisConfig.java` | JSON config at `~/.config/artemis/artemis.conf` (host, port, fps, bitrate…). |
| `overlay/ArtemisTileService.java` | Quick Settings tile toggle. |
| `cybersyn-quicktap/` | The Pixel **Quick Tap → `OVERLAY_TOGGLE`** wiring (Cybersyn profile+task bundle, toggle script, README). |

### Data flow (shown)
```
Quick Tap (back-tap) ─▶ Cybersyn external_trigger ─▶ profile ─▶ am start-service OVERLAY_TOGGLE
   │
   ▼
ArtemisDaemonService.showOverlay()
   ├─ StreamController.connect()  → NvConnection + MediaCodecDecoderRenderer (real Moonlight)
   ├─ waits for SurfaceView surfaceCreated (async) → connectStreamToSurface()
   │     ├─ decoder.setRenderTarget(surface); resume decode; IDR
   │     ├─ build GameInputController(service, conn, prefs, rootView, captureProvider, host)
   │     └─ overlay.setVisible(true)   ← focus + immersive
   └─ write cybersyn-hidmode = "amd"   ← vol keys drive comrade gyro/click
```

---

## 3. Show / hide model (the important part)

- **Show:** make the overlay visible + focusable, resume decode. First frame after a hide
  comes from a requested **IDR** (`MediaCodecDecoderRenderer.submitDecodeUnit` returns
  `DR_NEED_IDR` once on resume), so it re-syncs immediately.
- **Hide:** move the window off-screen + `setDecodePaused(true)`. The decoder's
  `submitDecodeUnit` then **drops every frame** (`DR_OK`, no MediaCodec work) so the hardware
  decoder and SoC idle. The `NvConnection` stays fully up the whole time.
- **Never** stop the connection on hide — that's the whole point.

Why not just lower FPS on hide? Because the host keeps sending at the negotiated rate; the
only client-side lever that actually idles the SoC is to stop *consuming* frames. Bandwidth
is a non-issue (a static 120fps AV1 desktop is ~360 KB/s).

---

## 4. Input — reuse, don't reimplement

The overlay does **not** hand-roll input. `GameInputController` is the same tuned dispatch
`Game` uses, so the overlay gets trackpad + absolute touch (switched by the `mouse_mode`
pref), multitouch, sensitivity, drag-drop, pen, mouse capture, and keyboard translation
identically to the main app. The overlay's `View` listeners forward raw `KeyEvent`/
`MotionEvent`/captured-pointer straight into it. Activity-only actions (on-screen keyboard
toggle, game menu, pan/zoom) are routed through a small `Host` callback the Service
implements — e.g. **3-finger tap → `Host.toggleKeyboard()` → summon SpectreBoard**.
(Gamepad is currently unwired: `ControllerHandler` needs a real Activity.)

---

## 5. Control & escape hatches

- **Toggle:** Pixel Quick Tap → `OVERLAY_TOGGLE` (see `cybersyn-quicktap/`), or the QS tile.
- **Screen-off auto-hides** the overlay (a `SCREEN_OFF` receiver) — the guaranteed way out if
  the focused overlay ever traps input.
- **Hard stop:** `am start-service … -a …OVERLAY_STOP` (must be UID 1000 / root; a plain adb
  shell is UID 2000 and gets "not exported from uid 1000").
- Never `am force-stop` the package — shared UID 1000 kills sibling daemons (sshd/npud).

---

## 6. Platform gotchas (the expensive lessons)

These are the non-obvious things that cost real debugging. **Most are UID/compositor/display
issues, not stream issues** — which is exactly what would matter for mpv too.

1. **Shared UID 1000 frame-rate override.** `Surface.setFrameRate(n)` is applied by Android as
   a **per-UID** frame-rate override. Diana shares UID 1000 (`android.uid.system`) with
   `com.termux.shadereditor` (the live wallpaper), so an attempt to throttle the *hidden*
   stream to 1fps silently throttled the **wallpaper** to 1fps — the whole phone "lagged" the
   instant the overlay left the screen, invisible in CPU/GPU/mem/IO stats. Fix: **clear** the
   hint (`setFrameRate(0)`) on hide; never force a low per-UID rate.
2. **Opaque overlay to occlude the wallpaper.** A `TRANSLUCENT` + `setZOrderOnTop` SurfaceView
   never marks the wallpaper occluded, so the GLSL wallpaper kept rendering at 120fps *behind*
   the opaque video, saturating the display compositor (dpu flip stalls, HWC missed frames).
   Fix: opaque window + opaque holder, no `setZOrderOnTop` (matches how `Game` renders) → the
   wallpaper is occluded and paused while streaming.
3. **Full display size, not `MATCH_PARENT`.** WindowManager clips an overlay's `MATCH_PARENT`
   to the non-decor area (e.g. 1080×2360 vs the real 1080×2410), leaving a nav-bar gap at the
   bottom and letting the status bar overlap the top. Fix: size to `getRealSize()`, add
   `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + immersive-sticky, and request the stream at the
   same real size so it fills 1:1.
4. **IME target.** For SpectreBoard to render **above** the stream, the overlay must be the
   IME's target — do **not** set `FLAG_ALT_FOCUSABLE_IM` (that makes it focusable for keys but
   attaches the IME to the window behind, i.e. under the stream).
5. **Audio drives the AoC.** Moonlight always opens a **low-latency** AudioTrack; that uses the
   Pixel's always-on-compute audio DSP (`aoc` HAL) which pinned ~a full core even though
   nothing was audible. `enableLocalAudioPlayback(false)` only stops *host*-side playback, not
   the client track. Audio can't be skipped (moonlight-common-c aborts the connection if audio
   init fails), so `NoAudioRenderer` returns success from `setup()` but opens **no** track and
   discards PCM. Note: **rendering silence into a track would still feed JamesDSP + the AoC** —
   the win is opening *no track at all*.
6. **Bitrate units.** `StreamConfiguration.setBitrate` is **Kbps**; the config value is Mbps →
   multiply by 1000. (Passing 40 meant 40 Kbps and macroblocked everything.)
7. **Cybersyn hid-mode gate.** Volume-key gyro/click on comrade is gated by the file
   `/data/data/com.termux/files/usr/tmp/cybersyn-hidmode` (`"amd"` = on), watched via
   `FileObserver`. SpectreBoard writes it when its trackpad is up; the overlay writes the same
   gate while shown. (Single shared file → the two surfaces don't coordinate; edge case if both
   are up at once.)

---

## 7. Applying this pattern to mpv

The transferable architecture is: **a Service-owned `TYPE_APPLICATION_OVERLAY` window hosting a
`SurfaceView`, with the heavy engine kept alive across show/hide instead of torn down.** That
maps onto mpv cleanly in principle — mpv already renders to a Surface, and "persistent playback"
was the goal that got reverted before (see the `mpv_rebuild` history: Phase-2 persistent
playback reverted after a crash chain).

**What carries over directly:**
- The **overlay window** mechanics (real-size, opaque-to-occlude-wallpaper, immersive, cutout
  mode) — items 2–4 above apply to *any* fullscreen overlay, not just streaming.
- The **shared-UID frame-rate trap** (item 1) — mpv is also `com.termux.*` / UID 1000, so any
  `Surface.setFrameRate` it does can throttle the wallpaper the same way. **This is probably the
  single most important thing to check for an mpv overlay.**
- The **audio/AoC** consideration (item 5) if mpv opens a low-latency track.
- The **screen-off escape** + Quick Tap toggle wiring (`cybersyn-quicktap/`) — reusable as-is.

**What's different / harder for mpv:**
- mpv is its own player, not a Moonlight connection — "keep the engine alive, pause rendering"
  means keeping the mpv core + demuxer/decoder alive while not presenting. mpv has its own
  pause/keep-open semantics (`--keep-open`, `--idle`) rather than a decode-submit gate.
- No "connection" to preserve, so the show/hide cost is just re-attaching/resuming the Surface,
  not an IDR re-sync. That's *simpler* than the Moonlight case.
- The reverted crash chain suggests the risk is in **Surface lifecycle** (detach/reattach mpv's
  render context to a persistent overlay Surface) rather than in the overlay hosting itself.
  That's the part to prototype first.

**Suggested first probe for mpv:** stand up the bare overlay Service (real-size, opaque,
immersive) hosting an mpv Surface, verify (a) the wallpaper is occluded and *not* throttled on
hide, and (b) mpv survives Surface detach/reattach on toggle. If those two hold, the rest of the
artemisd pattern (Quick Tap toggle, screen-off escape, hid-mode gate) drops in unchanged.

---

## 8. Build / deploy

`./gradlew :app:assembleRelease` (signing via `TERMUX_KEYSTORE` from
`~/.gradle/gradle.properties`), deploy with `blazer-sysapp-update install
com.termux.diana.root.noir <apk>` — **never remount**. A config/window change needs the daemon
process to actually restart (a Quick Tap only toggles the running one): `OVERLAY_STOP` then kill
the pid, then re-summon.

### 8.1 Strip mode (2026-08-18, live+verified)

`ArtemisConfig.stripMode` (persisted in `artemis.conf`) switches what a Quick Tap's next
`OVERLAY_SHOW` draws:
- **false (default):** the existing full kiosk overlay, unchanged — real `GameInputController`
  input stack, IME target, immersive fullscreen, `NOT_FOCUSABLE`/`NOT_TOUCHABLE` cleared.
- **true:** a view-only top slice, `stripFraction` (default 0.3) of the display height. Decode
  stays full-resolution (`setStreamBufferSize` unchanged) — only the *window* shrinks
  (`ArtemisOverlayWindow.show(stripMode, ...)`), while the `SurfaceView` inside stays laid out at
  full display height, top-aligned. A window clips its content to its own bounds, so this crops
  to the top slice instead of squishing the whole desktop down — confirmed live via screenshot
  (video title cut cleanly at the strip's bottom edge, not scaled). `NOT_FOCUSABLE`/
  `NOT_TOUCHABLE` stay SET (same pair the fully-hidden state already used) so touches fall
  through to whatever's underneath and the rest of the phone works normally; no
  `GameInputController`/gesture recognizer/pointer-capture wiring happens in this mode, and the
  gyro/click `cybersyn-hidmode` gate is left alone (`"android"`, not `"amd"`).
- Toggled via a second QS tile, `ArtemisStripModeTileService` ("Artemis: Full" / "Artemis:
  Strip"), separate from the existing `ArtemisTileService` (quick-menu popup). It's a pure
  preference flip — deliberately does NOT poke the running daemon, since
  `ArtemisDaemonService.onStartCommand` always constructs `ArtemisOverlayWindow` even for an
  action that isn't SHOW, so starting the service just to reshape an already-open overlay risked
  a stray invisible-window flash. The new mode takes effect on the *next* show, not live mid-
  session. **User must manually add the new tile to their Quick Settings panel once** (Android
  doesn't let an app auto-pin a QS tile).
- `ArtemisOverlayWindow.setVisible(boolean)` was renamed/split into `show(stripMode, displayWidth,
  displayHeight, stripFraction)` and `hide()` — the two former branches of the old single method,
  now with `show()` also branching on `stripMode` for width/height/gravity/flags/immersive/
  gesture-exclusion. `hide()` itself is unchanged (still the off-screen-position trick, see
  §above). Battery-neutral by design: this is a UX toggle, not a battery toggle — strip mode
  decodes exactly as much as fullscreen.
