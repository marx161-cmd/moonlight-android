# artemisd / overlay scope

Append-only. If a decision changes, add a new dated entry saying what changed
and why — never edit or delete an earlier entry.

## 2026-08-20 — artemisd (Service-based daemon) abandoned; restart as a real Activity

**Context:** artemisd was a persistent `Service`-based Moonlight overlay daemon
(`com.limelight.overlay` package: `ArtemisDaemonService`, `StreamController`,
`ArtemisOverlayWindow`, `ArtemisConfig`, etc.), built over 2+ weeks with
several full restarts. A full audit against `Game.java` (stock Diana's real
streaming Activity) on 2026-08-20 found 13 concrete divergences across 7 of
10 audited files — not isolated bugs, a systemic pattern: the daemon
reimplemented pieces of Game.java's connection/decoder/pacing/display-mode
logic as new hand-rolled code instead of literally porting it, and in the
worst case (`ArtemisDaemonService`) sourced fps/bitrate from an entirely
separate config store (`ArtemisConfig`, its own JSON-backed settings with its
own defaults) instead of the real Diana settings screen. Flagged items
included: "Warp Drive" frame pacing never applied, "Use Virtual Display" and
"Tight Vsync" (both enabled in the user's real settings) never wired, HDR
completely unwired, bitrate ignoring metered-network state, and the
client-side display-mode-matching logic being a rough stand-in for Game.java's
real algorithm. Full findings are in that session's conversation log, not
duplicated here.

Root cause discussed and agreed: `Game.java` is an `Activity`; the daemon is
a `Service` with no Activity behind it, so nothing in the daemon could
directly reuse Game.java's real Activity-bound code — everything had to be
either extracted into shared classes (done properly once, for input:
`GameInputController`, lifted verbatim out of Game.java, both callers use the
same code) or reimplemented from scratch (done for everything else, which is
what actually went wrong).

**Decision:** ditch the Service/no-Activity architecture entirely. The
overlay becomes `Game.java` itself, cold-launched via intent with the
connection details pre-filled (host, app id, cert — whatever the existing
computer-list/app-list picker already passes to start Game.java normally),
skipping that picker UI so it auto-connects immediately on launch. This is
not "extract shared logic into new helper classes" (rejected as unnecessary
extra surgery) — it's "just launch the real thing directly." No new
connection/decoder/pacing/config code gets written at all; every setting and
every code path is the real one, automatically, because it's literally the
same class Diana already uses.

**Explicit tradeoff accepted, discussed and agreed:** artemisd's core design
goal ("never drop the connection, hide = pause decode, show = instant
resume") is given up. A plain Activity gets torn down on backgrounding, same
as vanilla Diana already does — so hiding/showing the overlay means a real
reconnect each time, not an instant resume. Measured host/network latency
this session (2-14ms host processing, single-digit ms network) suggests this
reconnect will be fast, not the multi-second stall a cold connection from
scratch might imply, but it is a real, felt difference from what artemisd
was built to do. User's explicit call: "I don't want to chase features
anymore" — simple and correct over persistent and drift-prone.

**Rollback base:** commit `b095f2b4` (2026-07-24) on the `moonlight-noir`
branch — the last commit before the `overlay/` package was introduced
(`2bb5b34a`, same day). 19 commits of artemisd work sit on top of it.

**Plan (not yet executed):**
1. Clone fresh from `b095f2b4` (or branch from it in the existing repo).
2. Cherry-pick from the artemisd commits whatever has no Game.java
   equivalent and wasn't flagged by the audit — trigger/launch plumbing
   (widget, shortcut, Quick Tap wiring), repointed at launching Game.java
   directly instead of `ArtemisDaemonService`. Anything Service-lifecycle-
   specific (screen-off hide/show, the persistent notification, the
   overlay Window/Surface management) does not carry over — it doesn't
   apply to a plain Activity launch.
3. Do NOT cherry-pick `StreamController.java`, `ArtemisDaemonService.java`,
   `ArtemisOverlayWindow.java`, or `ArtemisConfig.java` — these are exactly
   the files the audit flagged; none of their logic should survive into the
   new approach.
4. RESOLVED (2026-08-20, verified by reading the actual source): no new
   connection/launch code is needed at all. Stock Diana already ships
   `ShortcutTrampoline` (app/src/main/java/com/limelight/ShortcutTrampoline.java)
   — takes a saved computer UUID/name (+ optional app UUID/ID/name) as intent
   extras, resolves the computer via `ComputerManagerService`, sends
   Wake-on-LAN if needed, polls until online+paired, then launches `Game.class`
   fully configured via the real `ServerHelper.createStartIntent()` — no
   picker UI. This is the exact mechanism stock app/PC home-screen shortcuts
   already use (`ServerHelper.createAppShortcutIntent()` /
   `createPcShortcutIntent()`). "Cold start, auto-connect" = fire this intent
   at `ShortcutTrampoline` with the right extras. Zero new Java code for the
   connection/launch path.
5. Per [[feedback_strip_down_fork_mandatory_diff_audit]] (standing rule as of
   2026-08-20): before this is called done, run the same kind of audit again
   — confirm nothing new got hand-rolled in the trigger/launch wiring.

## 2026-08-21 — late-night session: keyboard spacer, anchor-drag, PiP crop attempt

**Context:** returned to stock Diana (branch `pre-artemisd`) after the artemisd
abandonment. This session covered Apollo (host, `~/apollo-xrandr`) dynamic
resolution registration, AuraOrbit-lite Quick Tap wiring, and several Diana-side
features. Only the Diana-side changes are logged here; Apollo/AuraOrbit have
their own scope.md entries in their own repos.

**Shipped and confirmed working:**
- Anchor-drag gesture (`app/src/main/java/com/limelight/ui/AnchorDragGestureRecognizer.java`,
  new file): hold one finger, drag a second → holds Super + relative mouse move on
  comrade (i3 Mod+drag). Ported from the abandoned artemisd overlay daemon's
  `ArtemisGestureRecognizer` (commit 7f4797ec, `moonlight-noir` branch) — only the
  anchor-drag row, since edge-back/bottom-home already work natively via Android's
  own system gesture nav (stock Diana doesn't suppress system gestures the way the
  overlay had to). Wired as a touch pre-filter in `Game.onTouch()`, Host methods call
  `conn.sendKeyboardInput`/`sendMouseMove` directly — no daemon/overlay indirection.
- `Game.publishCybersynFifo(topic, payload)` (in `Game.java`): a general-purpose
  writer into Cybersyn's ingest FIFO, same mechanism as SpectreBoard's
  `CybersynFifo.kt`/`cybersyn-send`, same file (`/data/data/com.termux/files/usr/tmp/cybersyn-pub.fifo`),
  works because Diana shares UID 1000 with the com.termux family. Currently only
  used by `keyboard_visible` (see below); kept as a reusable primitive for future work.
- IME-visibility → host keyboard-spacer fix: SpectreBoard used to publish
  `cybersyn/hid/keyboard_visible` from its own global IME lifecycle
  (`onStartInputView`/`onFinishInputView`), which fired for *any* app's text field
  (SpectreBoard is the system default IME) — popped the host-side i3 spacer for
  unrelated typing anywhere on the phone. Removed that publish from SpectreBoard
  (`LatinIME.java`); Diana now publishes it itself from a `WindowInsets` IME-visibility
  listener on its own root view (`Game.java`, near the existing frame-rate-setup
  block), debounced 200ms (Android's IME show/hide reports multiple true/false flips
  mid-animation). Host side (`~/homelab/cybersyn-hid-relay.py`): the spacer is now a
  persistent Tk-window-turned-back-to-urxvt (see below) that gets resized instead of
  spawned/killed per toggle, follows your currently-focused workspace on every show
  (`_move_spacer_to_focused_workspace`), height computed live as
  `primary_output_height * 0.30` (was a fixed 885px tuned for one resolution,
  broke once tonight's dynamic multi-resolution Apollo work landed), fully
  transparent (`_NET_WM_WINDOW_OPACITY=0`, window id read straight from
  `i3-msg get_tree`'s JSON — no `xdotool` involved, since `xdotool search`
  mysteriously hangs when invoked from inside this specific running service,
  reproduced repeatedly, root cause never found), `no_focus` registered in
  `~/.config/i3/config` so it never steals keystrokes, `-sb` (not `-scrollBar false`,
  which is malformed urxvt syntax) to drop the scrollbar. All confirmed working live.

**Tried and reverted (2026-08-21, same session) — do not re-attempt without new info:**
- PiP top-third crop via `setSourceRectHint`/`setAspectRatio` cropped to 1/3 height:
  didn't achieve a real crop, just resized/squeezed content — Android's PiP source
  rect hint is (at most) an enter/exit animation shape hint, not an ongoing content
  crop, at least not observably on this device/Android version.
- PiP top-third crop via directly scaling the stream SurfaceView (anisotropic Y-only
  scale + restore on exit): visibly disturbed the layout, user rejected on sight.
- Auto-rotate-to-landscape on PiP entry via `rotateScreen()` (client-side
  `setRequestedOrientation`, no reconnect): tried from `onConfigurationChanged`
  first (raced against `rotateScreen()`'s own secondary config-change event, wrong
  enter/exit sequencing), then from the dedicated `onPictureInPictureModeChanged`
  callback (same symptom persisted — Android's PiP window appears to ignore the
  Activity's requested orientation while actually PiP-windowed, so the rotate only
  visibly took effect on exit, colliding with something re-asserting normal
  orientation right after — root cause not confirmed).
- Host-side rotate-instead-of-client-side: Diana publishes `cybersyn/hid/pip_mode`
  (on PiP enter/exit) and `cybersyn/hid/device_orientation` (on physical device
  rotation, via `OrientationEventListener`, ±20° hysteresis buckets) over the same
  FIFO mechanism as `keyboard_visible`; host (`cybersyn-hid-relay.py`,
  `handle_pip_mode`/`handle_device_orientation`/`_set_vkms_landscape`) rotates the
  primary VKMS output via plain `xrandr --rotate`. Built, deployed, self-tested via
  manual `mosquitto_pub` (looked correct in isolation) — but live on-device it was
  "all over the place," reactions untrustworthy. **Reverted from Diana** (the
  `OrientationEventListener` field/instantiation/onDestroy-cleanup and the
  `onPictureInPictureModeChanged` override are removed entirely — check
  `git diff` against this point if picking this back up). **NOT reverted from
  `cybersyn-hid-relay.py`**: `handle_pip_mode`/`handle_device_orientation`/
  `_set_vkms_landscape`/`_get_primary_output_name` and the two topic
  subscriptions are still there, just dormant (nothing publishes to those topics
  with Diana's rotate code removed) — harmless to leave, but worth cleaning up if
  this approach is abandoned for good rather than resumed.

**Next time picking this up:** start by finding out *why* it was "all over the
place" — is it the sensor bucketing genuinely flapping despite the hysteresis
zones, multiple Diana sessions/stale FIFO writes, or something in how
`_set_vkms_landscape` interacts with an active Apollo capture session
(`x11grab` mid-capture during a live `xrandr --rotate`)? Wasn't diagnosed with
real evidence (logcat/journalctl) before the revert decision — this session
just ran out of steam late at night, not out of hypotheses.

## 2026-08-25 — Mobile streaming profile (session 1): top-align, metered
bitrate, IME spacer toggle, custom OSC keys

**Context:** user needs artemis usable over German mobile data (unreliable,
variable-bandwidth cellular, e.g. DB trains). Worked through what's actually
tunable in stock Diana (this branch, `pre-artemisd` — the daemon approach
above is dead, ignore it for anything below).

**Shipped and confirmed working, live-tested:**
- Fps: recommended ~15-20 over ~10 as the floor — at a fixed bitrate ceiling,
  fps and bandwidth aren't independent (rate control divides one bit budget
  across however many frames/sec), so lower fps gives more bits-per-frame
  headroom exactly when motion (dragging/scrolling) needs it, at the cost of
  input-echo cadence, not bandwidth. User confirmed 15fps "surprisingly fine."
- Resolution: kept near-native width, portrait-shaped (1200x1920, user's own
  direct entry, not auto-inverted) rather than downscaling both dimensions —
  AV1 handles flat/text desktop content efficiently even at low bitrate, so
  legibility was prioritized over raw pixel-count savings.
- `meteredBitrate` (`PreferenceConfiguration`/`Game.java:549,798`) is real,
  live, automatic — `Game.java` already switches to it whenever
  `ConnectivityManager.isActiveNetworkMetered()` is true, which is universal
  for cellular regardless of the user's data plan (metered is a transport-type
  classification, not a billing concept — confirmed live via `dumpsys
  connectivity`, Vodafone DE LTE + the Tailscale VPN network both correctly
  lack `NOT_METERED`). No manual profile-switch needed for bitrate specifically,
  only for resolution/fps.
- **Top-align bug, root-caused and fixed:** the "streaming picture pins to top
  instead of center" toggle (`checkbox_enable_view_top_center` /
  `alignDisplayTopCenter`, wired into `Game.java`'s `streamContainer` gravity +
  `StreamContainer.onMeasure()`'s aspect-fit sizing) was flaky for
  ~multiple prior sessions ("never really got it to stick"). Root cause:
  `autoInvertVideoResolution` (default **true**) is designed for
  landscape-typed-then-auto-inverted profiles; this profile's resolution was
  entered directly as portrait-shaped, so whenever portrait was correctly
  detected, the invert logic "helpfully" swapped it back to landscape shape —
  wrong for this setup, producing a mismatched aspect-fit that looked like
  gravity wasn't sticking. Fix: `autoOrientation` **on** (so the window
  correctly locks portrait via `setPreferredOrientationForActivity()` instead
  of the hardcoded-landscape fallback) + `autoInvertVideoResolution` **off**
  (so the directly-entered 1200x1920 never gets swapped regardless of detected
  orientation). Confirmed live: top-align sticks correctly now, window opens
  in portrait immediately, no manual rotate needed.
- **`suppressImeSpacer` toggle** (new `PreferenceConfiguration` field +
  `Game.java`/`GameMenu.java`): profiles with a shortened stream height
  (top-aligned, black padding below reserved for the keyboard) don't need the
  host's i3 keyboard-spacer (`cybersyn-hid-relay.py`) to ALSO carve out space —
  redundant double-reservation. Gated at
  `Game.publishKeyboardVisibleToCybersyn()` — when set, the IME-visibility
  publish to Cybersyn is skipped entirely, host spacer never triggers.
  Deliberately placed as an in-stream quick-menu toggle
  (`GameMenu.showAdvancedMenu`, next to Toggle HUD — "Disable/Enable Host
  Keyboard Spacer"), NOT a Settings-screen checkbox — user explicitly rejected
  Settings as "a bad place," wanted it reachable while actively streaming.
  Session-only flip like `toggleHUD()`/`toggleZoomMode()`, no persistence.
  Built as `root` release variant (`assembleRootRelease` — **not** debug,
  debug gets `applicationIdSuffix` and installs as a separate package,
  doesn't update the real app) via `blazer-sysapp-update install`.
- **Custom on-screen-keyboard button layout, pixel-exact via direct
  SharedPreferences write** (not manual dragging): confirmed on real device
  measurements (screen 1280x2856, stream 1200x1920 top-pinned+h-centered →
  black band x:[0,1280] y:[1920,2856]). Format: `keyBoardVirtualControllerElement
  .getConfiguration()/loadConfiguration()` — flat JSON per elementId
  (`{"LEFT","TOP","WIDTH","HEIGHT","ENABLED","HIDDEN"}`, raw pixels, no
  scaling), stored in SharedPreferences file named by the `keyboard_axi_list`
  pref (default `"OSC_Keyboard"`, on-device
  `/data/data/com.termux.diana.root.noir/shared_prefs/OSC_Keyboard.xml`).
  ElementIds are fully deterministic from `assets/config/keyboard.json` (e.g.
  `key_113`=Ctrl, `key_61`=Tab) plus the always-added
  `BUILTIN_I3_SHORTCUTS` (`builtin_i3_0..11`) and the F1/F2/F3→i3 remap
  (`remap_key_131/132/133`, NOT the same as the 12 builtins — user only uses
  the 3 F-key remaps, confirmed). 14-button curated set (Esc/Tab/Ctrl/Alt/
  Win/C/V + ←/↑/↓/→/i3-Q/i3-D/i3-F) written directly, ~74 other default
  elements left as-is (already `HIDDEN:true` from the user's own earlier
  manual-drag attempts).
  **Real incident, fixed same session:** first attempt used 14 sequential
  `adb shell su -c sed -i` calls against the LIVE file while Diana was still
  running — raced with the app's own concurrent writes to the same file,
  corrupted it (15KB→43KB, duplicated/garbled entries). Fixed by
  force-stopping Diana first, rebuilding the full file in one local pass from
  a clean pre-edit backup, pushing as a single atomic write, restoring
  original `system:system` ownership + `660` perms + SELinux context
  (`u:object_r:system_app_data_file:s0`). **Lesson: never edit a live app's
  SharedPreferences file in place while that app's process is running —
  always force-stop first, always do the edit as one atomic write, always
  keep a pre-edit backup.**

**Not built this session, correctly de-scoped as future work (see next
entry):** the current IME-spacer replacement (`suppressImeSpacer`) still
requires a *separate, pre-shrunk mobile profile* — it doesn't make the live
stream itself resize dynamically when the on-screen keyboard toggles during
normal (non-mobile-profile) use. That's the next entry below.

## 2026-08-25 — Weekend-project idea: live in-place resolution switching
(replace the i3 spacer hack with an actually-resizing stream)

**Context:** the current host-side IME spacer (`cybersyn-hid-relay.py`, a
persistent Tk-window-turned-urxvt that i3 reflows around when the on-screen
keyboard shows) works but is, in the user's words, "hillbilly" — it doesn't
resize the actual video, just carves out dead space around it. Discussed
whether the stream's negotiated resolution could change live, in place, with
zero visible reconnect/blip, triggered by the same keyboard show/hide signal
already wired (`Game.java`'s WindowInsets IME-visibility listener → Cybersyn
FIFO → today drives the spacer; would instead/also drive this).

**Why a reconnect-based switch doesn't work for this specific use case:**
unlike the mobile-profile switch above (a rare, deliberate, once-per-trip
action where a brief reconnect is fine — and was already measured cheap: 2-14ms
host processing, single-digit ms network, per the 2026-08-16 session), a
keyboard-triggered resize needs to happen every single time the on-screen
keyboard toggles during normal use — potentially dozens of times a session.
A visible black-frame/reconnect blip on every keyboard toggle would be worse
UX than the current spacer, not better.

**Core technical approach (discussed, not yet spiked):**
1. **Host (`~/apollo-xrandr`, has its own `scope.md` entry — see there for
   the host-side plan):** VAAPI can't resize an existing encode context's
   coded dimensions in place, so two pre-warmed `VAContext`/surface sets are
   needed (one per resolution: full height, keyboard-shrunk height) —
   created once at Apollo startup, not per-switch. New splice mechanism
   needed in Apollo's `stream.cpp` encode/RTP-send loop: hot-swap which
   context is feeding the *same ongoing* video session, forcing an IDR/
   keyframe at the switch boundary. **This is the one genuinely unverified
   piece — Apollo's current architecture wasn't checked for whether it can
   support this kind of live source-swap without deeper restructuring. Spike
   this first**, before investing in client-side work, since if it doesn't
   fit cleanly the whole approach needs rethinking.
2. **Client (this repo):** relies on Android `MediaCodec`'s adaptive-playback
   capability — an in-band resolution change via a new SPS mid-stream,
   surfaced as `INFO_OUTPUT_FORMAT_CHANGED`, no decoder teardown, no Surface
   rebind. Long-established for H.264/HEVC. Whether the Tensor G5's AV1
   decoder path (`c2.google.av1.decoder`, the codec currently in daily use)
   supports it specifically is unconfirmed — **but this is a codec-choice
   knob, not a feasibility gate**: H.264/HEVC/AV1 are all already fully
   supported end-to-end on both sides today, so worst case this feature uses
   HEVC instead of AV1, full stop, no architecture change needed.
3. **Client UI hook already exists, no new code needed for this part:**
   `StreamContainer.setDesiredAspectRatio()` (built for the top-align work
   above) already handles a `requestLayout()`-driven resize of the video
   rect within its container. `MediaCodecDecoderRenderer` just needs to
   detect the format-changed event and call it with the new dimensions.

**Open/unconfirmed (be honest, don't treat any of this as decided):**
- Apollo `stream.cpp`'s actual amenability to a live context-swap — not
  checked, this is the real spike.
- Whether the client needs an explicit pre-signal for an incoming resolution
  change, or can rely purely on detecting the new SPS.
- Continuous resource cost of holding two live VAAPI encode + VKMS capture
  pipelines simultaneously for the *entire* normal desktop session (not just
  a bounded "trip mode" window) — bigger commitment than the mobile-profile
  case above.

**Not started.** This is a scoping discussion only, captured before any
implementation begins, per the standing append-only-scope-doc rule.

## 2026-08-26 — Full touch/gesture support for Artemis (client side): fix
`AnchorDragGestureRecognizer` stealing multi-finger touch in Multi touch mode

**Context:** companion entry to `~/apollo-xrandr/scope.md`'s matching
2026-08-26 entry (host-side half + the full narrative: started from a single
ask, "pinch to resize," but scoped up into general extensible touch/gesture
support after discussion, not one hardcoded gesture). Read that entry for
the host-side architecture (stock libinput tap-to-click for 2-finger-tap
right-click, plus an in-process libinput-linked gesture engine for
pinch/swipe/rotate/hold, dispatched to i3 actions).

**Bug found this session:** `AnchorDragGestureRecognizer`'s "anchor anywhere
outside the top-left corner zone" branch (→ `LEFT_CLICK_DRAG`/
`RIGHT_CLICK_DRAG`) fires for *any* two-finger touch that matches "finger 1
held ~200ms+, finger 2 lands nearby" — which is indistinguishable from the
start of a real pinch or two-finger scroll. Confirmed live consequence: in
**Multi touch** mode (`enableMultiTouchScreen && !touchscreenTrackpad`,
where `trySendTouchEvent()` should relay real per-finger touch straight to
the host), every two-finger gesture gets hijacked into a click-drag before
it ever reaches `trySendTouchEvent()` — the host never sees real multi-touch
data at all. The corner-anchored `WINDOW_MOVE` case is unaffected (a
deliberate 120dp-corner grab isn't something a normal pinch/scroll would
land on).

**Decision (2026-08-26):** in native touchscreen mode specifically, the
anchor-drag recognizer should only claim the corner-anchored `WINDOW_MOVE`
case — never the anywhere-else click-drag, since two-finger touches
everywhere else need to reach `trySendTouchEvent()` untouched for the host
gesture engine (or any host app's own multi-touch handling) to see them.
Trackpad/normal-mouse-mode behavior is unchanged (click-drag anywhere still
makes sense there — there's no competing raw-touch consumer in those modes).
A doc-comment describing this exception was added to
`AnchorDragGestureRecognizer.java` already; the actual gating logic (a
`Host.isNativeTouchscreenModeActive()` check, backed by
`prefConfig.enableMultiTouchScreen && !prefConfig.touchscreenTrackpad`, same
predicate `Game.java`'s own `trySendTouchEvent` gate already uses) is **not
yet implemented**.

**Also noted, no client change needed:** Diana's "Trackpad" mouse mode
(`touchscreenTrackpad`, mouse-emulation, `TrackpadContext.java`) and
apollo-xrandr's `inputtino::Trackpad` device (host-side, libinput-gesture-
capable) share a name but are unrelated — the former never calls
`sendTouchEvent()` at all, so none of this host-side work receives anything
unless the user has Diana's mouse mode set to **Multi touch** specifically.

**Not started** (beyond the doc comment). Scoping discussion only, captured
before implementation begins, per the standing append-only-scope-doc rule.

## 2026-08-29 — Client-side gating fix implemented

The `Host.isNativeTouchscreenModeActive()` gating described above is now
implemented: `AnchorDragGestureRecognizer.Host` gained the method, the
recognizer's `ACTION_POINTER_DOWN` case returns `false` (declines the
gesture) when `!anchorInCorner && host.isNativeTouchscreenModeActive()`,
and `Game.java` implements it as
`prefConfig.enableMultiTouchScreen && !prefConfig.touchscreenTrackpad` --
same predicate `trySendTouchEvent`'s own gate already uses. Corner-anchored
`WINDOW_MOVE` is untouched in every mode. Compiles clean
(`compileRootDebugJavaWithJavac`). Not yet installed/live-tested on device.

This was the explicit dependency named in `~/apollo-xrandr/scope.md`'s
matching 2026-08-26 entry, item 4 of Stage 1 ("until
`AnchorDragGestureRecognizer` stops hijacking two-finger touches, no real
multi-finger data reaches this pipeline") -- unblocks that host-side work.

## 2026-08-30 — Pixel bar removal + gesture-driven kiosk/PiP menu, scoping

Started as "the Pixel i3 bar setup is awkward, redo it" and evolved twice
during discussion into a different, larger shape. Recording the final
decision plus the two rejected intermediate ones, since both were real
alternatives actually discussed, not just discarded first drafts.

**Rejected shape 1:** collapse the two-row i3bar (10 touch buttons split
across two `bar {}` blocks on `Virtual-2-3`, workspace_buttons duplicated
on both as a workaround for non-deterministic edge-stacking -- see
[[project_pixel_i3_mobile_bar]]) into one row by hiding 6 of the buttons
behind a single rofi "MENU" launcher. Superseded before any code was
written -- see shape 2.

**Rejected shape 2:** keep the bar removed, but open the merged menu via a
host-side libinput gesture (pinch/hold) through apollo-xrandr's Stage 2
in-process dispatch table (`~/apollo-xrandr/scope.md`, "Stage 2 -- real
gesture engine, extensible", not yet built). Superseded once the user
pointed out OwnDroid/Dhizuku kiosk mode already existed as a half-finished
prior project and was the better fit -- see decision below. Stage 2 itself
is NOT abandoned; it stays a separate, real future TODO for its original
purpose (e.g. pinch-to-resize), untouched by this work.

**Decision (final):** remove i3bar from `Virtual-2-3` entirely -- full
Pixel screen, no docked bar, no bar-edge-clipping problem to solve at all.
Menu access + Home-button lockdown instead go through kiosk mode, which
the user had partially built before getting ROM control and stopped using
because exiting it cleanly wasn't wireable at the time. That's no longer
true (own platform-signed crDroid, root, arbitrary key remap), so the plan
is to finish it:

- **Kiosk engage/disengage:** `com.rosan.dhizuku` is already live Device
  Owner (org-owned flag true, confirmed via `adb dumpsys device_policy`
  2026-06-13 snapshot in `pixel-ui-stack-publish-2026-06-13/docs/
  DEVICE_OWNER_POLICY.md`), OwnDroid (`com.bintianqi.owndroid`) is the
  policy UI on top. `~/.config/cybersyn/rules/kiosk-artemis.yaml` already
  does real Home suppression (`cmd statusbar send-disable-flag home
  recents statusbar-expansion notification-peek quick-settings`, not just
  immersive window flags) plus a governor tweak, gated on `app_foreground`
  for Diana. **Confirmed dead code, not just stale**: `app_foreground` is
  not a trigger type Cybersyn's engine implements anywhere (`grep` across
  `~/builds/crossplatform/Cybersyn/app/src/main` for `app_foreground`/
  `APP_FOREGROUND` returns nothing) -- this file predates `cybersynctl`
  entirely and was never actually appliable, not merely pointed at the
  stale pre-rename package id (`com.limelight.root.noirdebug` vs. the real
  `com.termux.diana.root.noir`). Needs a real rewrite in the schema
  `cybersynctl apply` actually consumes (tasks + profiles + `EVENT`
  context, `event: external_trigger`) -- same proven shape as
  `examples/quicktap-sidebar.yaml`.
- **Power button -> exit kiosk:** goes through Cybersyn's existing evdev
  key-hijack layer (`KeyTriggerConfig.kt`, the same proven path as
  `vol_down_double`/`vol_up_long`), as a new named trigger. This is a
  deliberate, scoped exception to the standing rule in
  [[project_cybersyn_key_hijack]] ("Power is never consumed", there to
  preserve long-press-power and the power+vol-down screenshot chord) --
  the exception must be gated on kiosk-profile-active state so Power stays
  untouched everywhere else.
- **Home-gesture attempt -> minimize Diana to PiP:** does NOT go through
  Cybersyn/evdev at all -- gesture-nav's Home swipe is a compositor-level
  touch reveal, not a hardware keycode, so evdev never sees it. Catches
  client-side in `Game.java`'s `onSystemUiVisibilityChange`, specifically
  the `else if ((visibility & HIDE_NAVIGATION) == 0)` branch (~line 4221)
  -- this already fires today on exactly a bottom-edge nav-reveal attempt
  (distinct from the sibling `if` branch, which fires on status-bar/
  notification-shade reveals instead) and currently just re-hides the bar
  after 2s with no other effect. Add a direct
  `enterPictureInPictureMode()` call there. No new IPC needed for this
  half since it's fully in-process.
- Menu content itself (the old two-bar button set + workspace controls)
  still needs a real host, most likely a floating/scratchpad rofi window
  toggled from wherever the user ends up wanting it -- not decided where
  that trigger lives yet, deprioritized behind the kiosk/PiP mechanics.

**Known real unknown, not yet live-tested:** whether `cmd statusbar
send-disable-flag home` (SystemUI-level) and Diana's own
`onSystemUiVisibilityChange` client-side hook coexist as expected --
i.e., whether the nav-bar-reveal event Diana listens for still fires once
Home is disabled at the SystemUI level, or whether the flag suppresses the
reveal animation/callback entirely. Needs a live check before relying on
it, not a guessable fact.

**Not started.** Scoping discussion only, captured before implementation
begins, per the standing append-only-scope-doc rule.

**2026-08-30 follow-up — split off:** the Home/Back real-gesture-recognition
question grew into a full SystemUI/quickstep source patch (same category as
[[project_dockedpip_inset_docked_pip]], user explicitly invoked its proven
reliability as the reason to go this deep rather than settle for an
indirect workaround). Moved to its own project:
`~/builds/android/kiosk-gestures/scope.md` — read that file for the
Back-vs-Home mechanism findings and current decision; this file keeps only
the kiosk-engage/power-exit/PiP/menu-hosting pieces above.
