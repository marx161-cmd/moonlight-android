# Pixel Quick Tap → artemisd toggle

Wires the Pixel native **Quick Tap** (double-tap the back of the phone) to
show/hide the artemisd streaming overlay. Applied live 2026-08-12.

## How it flows

Pixel Quick Tap → Cybersyn `external_trigger:"quick_tap"` → profile
**QuickTapSidebarTrigger** → task **"Artemis: toggle overlay"** → runs
`~/.termux/tasker/artemis-toggle.sh` (as root) → sends
`OVERLAY_TOGGLE` intent to `ArtemisDaemonService`.

From cold (daemon stopped) the first Quick Tap launches + shows it; the next hides
it. Locking the phone (screen-off) also hides it — the hard escape hatch.

## What was changed on-device

- New script `~/.termux/tasker/artemis-toggle.sh` (700, system:system).
- New Cybersyn task **"Artemis: toggle overlay"** (`script.termux.run`, root).
- Profile **QuickTapSidebarTrigger** repointed from task 140 to the new task
  (done via `REPLACE_BY_NAME` import, so the profile id changed; that's expected).
- Task **140 "QuickTap: show volume slider"** left parked (unused) for revert.

**Nothing** in `key_triggers.json` / the volume keys was touched — Quick Tap is a
separate system gesture that already fed Cybersyn.

## Test it

Double-tap the back of the phone. First tap shows the overlay, second hides it.
If anything feels stuck, press power (screen-off) — that always hides it.

## Re-apply (if the DB is ever reset)

```sh
cd ~/builds/android/artemis-patch/cybersyn-quicktap
# 1. reinstall the toggle script
adb -s 100.69.13.12:5555 push artemis-toggle.sh /data/local/tmp/artemis-toggle.sh
adb -s 100.69.13.12:5555 shell "su -c 'D=/data/data/com.termux/files/home/.termux/tasker/artemis-toggle.sh; cp /data/local/tmp/artemis-toggle.sh \$D && chown system:system \$D && chmod 700 \$D && restorecon \$D && rm -f /data/local/tmp/artemis-toggle.sh'"
# 2. import the profile+task bundle (adb shell has the DUMP permission the receiver needs)
B64=$(base64 -w0 apply_bundle.json)
adb -s 100.69.13.12:5555 shell "am broadcast -a com.termux.cybersyn.action.IMPORT_BUNDLE -p com.termux.cybersyn --es com.termux.cybersyn.extra.BUNDLE_BASE64 '$B64' --ez com.termux.cybersyn.extra.REPLACE_BY_NAME true --ez com.termux.cybersyn.extra.ACKNOWLEDGE_RISK true --ez com.termux.cybersyn.extra.ENABLED true"
```

## Revert (Quick Tap back to the volume slider)

```sh
cd ~/builds/android/artemis-patch/cybersyn-quicktap
B64=$(base64 -w0 revert_bundle.json)
adb -s 100.69.13.12:5555 shell "am broadcast -a com.termux.cybersyn.action.IMPORT_BUNDLE -p com.termux.cybersyn --es com.termux.cybersyn.extra.BUNDLE_BASE64 '$B64' --ez com.termux.cybersyn.extra.REPLACE_BY_NAME true --ez com.termux.cybersyn.extra.ACKNOWLEDGE_RISK true --ez com.termux.cybersyn.extra.ENABLED true"
```

## Manual escape (if ever needed, no Quick Tap)

```sh
adb -s 100.69.13.12:5555 shell "su -c 'am start-service -n com.termux.diana.root.noir/com.limelight.overlay.ArtemisDaemonService -a com.termux.diana.action.OVERLAY_STOP'"
```

## Notes / gotchas

- Imports go through `ACTION_IMPORT_BUNDLE`, never raw sqlite — the app is live with
  WAL, and raw edits risk corruption + the `collisionMode` enum trap.
- Imported profiles arrive disabled + risk-flagged; `ACKNOWLEDGE_RISK=true` +
  `ENABLED=true` is what enables them in the same call.
- Cybersyn shares UID 1000 with Diana, so the toggle intent delivers fine; plain
  `adb shell` (UID 2000) cannot send it directly — hence the toggle script runs the
  `am` as root.
