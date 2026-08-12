# artemisd — CPU/Battery Churn Audit

Audit of commit `2bb5b34a` (artemisd daemon). Written 2026-08-12 so work can be
handed off. Package: `com.termux.diana.root.noir`. Device: blazer (crDroid 16).

## TL;DR

The overlay works, but **"hide" saves almost no power** — hidden draws nearly the
same as shown. That's the whole churn complaint. The stream runs a full-rate
(120fps AV1) pipeline 24/7 in the background regardless of the show/hide toggle.

There are two *independent* reasons hide doesn't idle, plus a few smaller items.

---

## Root cause: "hide" does not actually throttle anything

`ArtemisDaemonService.hideOverlay()` calls `mStream.setTargetFps(1)`. That is the
only thing hide does to the pipeline. It fails to save power for **two separate
reasons**, either of which alone defeats it:

### 1. The render thread never re-reads `targetFps` (client-side bug)

`MediaCodecDecoderRenderer.startRendererThread()` captures the cadence **once**, as
`final` locals, at thread start:

- `final int tfps = (targetFps > 0 ? targetFps : 60);`  — line ~1185
- `final long streamPeriodNs = 1e9 / tfps;`             — line ~1186
- `final long periodNs = preferLowerDelays ? vsyncPeriodNs : max(...);` — line ~1193

The while-loop (line ~1220 onward) **never reads the `volatile targetFps` again**.
So `setTargetFps(1)` writes the field, but the pacing loop keeps running at the fps
the stream *started* with. Present cadence never drops. Making the field `volatile`
(the commit's change) accomplishes nothing here because nothing re-reads it in the
loop.

`setTargetFps()` also calls `applySurfaceFrameRate(renderTarget, 1)` →
`Surface.setFrameRate(1, ...)`. On an LTPO panel that *can* let the display drop
refresh (a real but small panel-power win). It does **not** touch decode or network.

### 2. `setTargetFps` only throttles *presentation*, not decode (design)

Even with #1 fixed, `targetFps` only governs the **output/present** side (which
decoded buffers to render vs drop). Every incoming frame is still queued into
MediaCodec by `submitDecodeUnit()` and hardware-decoded. So `setTargetFps(1)` can
never make the client cheaper — the decoder does the same work at 1 or 120.

**Where the battery actually goes:** bandwidth is NOT the problem — a static 120fps
AV1 desktop is only ~379 kB/s (tiny skip-frames), confirmed by the operator. The cost
is the **per-frame hardware decode + the SoC waking 120×/sec** to service each frame,
which prevents the chip from idling. That's the drain, shown or hidden.

**Net (before fix): hidden ≈ shown**, because we decoded every frame regardless.

---

## Secondary findings

### 3. Audio stream is negotiated but never plays (waste, easy win)

`StreamConfiguration` is built with `enableLocalAudioPlayback(playHostAudio)` and
config `audioEnabled=true`. Operator confirms **audio never plays on the Pixel via
Sunshine**. If audio is negotiated, Sunshine still encodes + sends the audio stream
→ wasted network + a decode for nothing. **Fix: set `audioEnabled=false`** (or pass
`enableLocalAudioPlayback(false)`) so the host never sends audio. Zero behavior loss.

### 4. Choreographer path NPEs in Service mode (dormant landmine)

In the new `Context` constructor, `this.activity = null`. `doFrame()` (line ~1085)
does `activity.getWindowManager()...` → **NPE if the Choreographer path runs**.
It only runs when `prefs.framePacing == BALANCED`. Default is `"latency"`, so it's
currently dormant — but if the operator ever picks "Smoothest/Balanced" pacing, the
daemon crashes → reconnect churn. **Fix: in `doFrame`, use `context` and a cached
vsync offset instead of `activity`, or null-guard it.**

### 5. Hidden window shrinks to 1×1 → surface resize + wasted GPU

`ArtemisOverlayWindow.setVisible(false)` sets `params.width/height = 1` and alpha 0.
The `SurfaceView` buffer gets resized to 1×1; the decoder keeps rendering full-res
frames scaled into it — wasted GPU composite, and the resize itself churns the
BufferQueue. **Fix: on hide, keep the window size and just set alpha 0 / move
off-screen (`params.x = -displayWidth`), OR — better — stop rendering entirely (see
decision A/C).** Do not resize to 1×1.

### 6. Idle render thread wakes ~500×/s

With `preferLowerDelays=false`, `getOutputDequeueTimeoutUs()` returns
`preferLowerDelaysTimeoutUs` (StreamController sets 2000µs). The renderer thread
blocks at most 2ms per dequeue → ~500 wakeups/sec even when idle. Negligible at full
stream, but wasteful in any low-frame state. Only worth touching if we keep a warm
low-fps stream (decision C).

---

## The fix (chosen + implemented): pause decode while hidden, keep the connection up

Decision (settled with operator): **never drop the stream / never reconnect.** Since
bandwidth is already near-free, we don't need the host to slow down — we just stop the
*client* from decoding while hidden. This gives the "1fps is virtually free" behavior
without touching the connection.

**Mechanism** — gate at the input boundary `submitDecodeUnit()`
(`MediaCodecDecoderRenderer.java:1754`), the sole native entry for frame data:

- **Hide:** `decodePaused = true`. `submitDecodeUnit` returns `DR_OK` immediately
  without queuing to MediaCodec → hardware decoder idles, SoC stops per-frame wakeups.
  The `NvConnection` stays fully up; Sunshine keeps streaming, we just discard.
- **Show:** `decodePaused = false` arms `needIdrOnResume`. The next `submitDecodeUnit`
  returns `MoonBridge.n_IDR` (-1) → host sends a fresh keyframe → decode resumes.
  First frame on show arrives in ~one IDR round-trip (sub-100ms on Tailscale/LAN),
  effectively instant. No reconnect, no surface teardown.

This makes findings #1 and #6 irrelevant to the hidden state (the decoder isn't
running), and `setTargetFps(1)` is kept only as an LTPO panel-refresh hint.

### Implemented in this pass

- `MediaCodecDecoderRenderer`: `volatile decodePaused` / `needIdrOnResume`,
  `setDecodePaused(boolean)`, and the drop/IDR gate at the top of `submitDecodeUnit`.
- `StreamController.setDecodePaused(boolean)` → forwards to the decoder.
- `ArtemisDaemonService.hideOverlay()` pauses decode (+ `setTargetFps(1)` hint);
  `showOverlay()` resumes (arms the IDR).
- `ArtemisConfig.audioEnabled` default → `false` (stops the host sending audio).
- `ArtemisOverlayWindow.setVisible(false)` (finding #5): no longer resizes the window
  to 1×1 — hide is now alpha 0 + NOT_FOCUSABLE/NOT_TOUCHABLE only, so the SurfaceView
  BufferQueue isn't churned on every toggle. With decode paused there are no new
  frames to composite, so the transparent full-size layer is effectively free.

---

## Still open (not yet done — safe to hand off)

1. **Choreographer NPE guard** (finding #4): replace `activity.getWindowManager()` in
   `doFrame` with a `context`-based lookup + null guard. Dormant unless pacing is set
   to "Smoothest/Balanced", but a real crash if so. — `MediaCodecDecoderRenderer.java:1085`
2. **Existing on-device config**: the `audioEnabled=false` change is only a *default*
   (fresh installs). If `~/.config/artemis/artemis.conf` already exists on blazer with
   `audioEnabled: true`, edit it there too.
3. **Verify on-device**: after install, toggle hide and confirm battery/CPU actually
   drop (e.g. `dumpsys batterystats` / `top` for the decoder thread), and that show
   re-syncs cleanly (IDR arrives, no green/garbage frames).

## Build / deploy reminder

`./gradlew :app:assembleRelease` (signing via `TERMUX_KEYSTORE` from
`~/.gradle/gradle.properties`), deploy with `blazer-sysapp-update install` — **never
remount** to update the system app.
