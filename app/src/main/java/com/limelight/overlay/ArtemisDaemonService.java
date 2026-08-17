package com.limelight.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.limelight.GameMenu;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.GlPreferences;
import com.limelight.input.GameInputController;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.computers.ComputerDatabaseManager;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import android.util.Base64;

import java.security.cert.X509Certificate;

public class ArtemisDaemonService extends Service
        implements GameInputController.Host, ArtemisGestureRecognizer.Host, ArtemisMenu.Host,
        com.limelight.binding.video.PerfOverlayListener {

    // --- ArtemisMenu.Host: the QS tile's popup (replaces the old QS toggle now that
    // Quick Tap covers on/off -- see ArtemisTileService). Only options backed by the
    // daemon's own NvConnection are offered; see ArtemisMenu's class doc for what's
    // intentionally left out (needs Game's separate httpConn, never built here).
    private ArtemisMenu mMenu;

    @Override public void onDisconnect() { stopSelf(); }

    @Override public void onToggleKeyboard() { toggleKeyboard(); }

    @Override public void onToggleLandscape() { toggleLandscape(); }

    @Override public void onSendKeys(short[] keys) {
        com.limelight.nvstream.NvConnection c = conn();
        if (c == null) return;
        final byte[] modifier = {(byte) 0};
        for (short key : keys) {
            c.sendKeyboardInput(key, com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);
            modifier[0] |= com.limelight.binding.input.KeyboardTranslator.getModifier(key);
        }
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            com.limelight.nvstream.NvConnection c2 = conn();
            if (c2 == null) return;
            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];
                modifier[0] &= (byte) ~com.limelight.binding.input.KeyboardTranslator.getModifier(key);
                c2.sendKeyboardInput(key, com.limelight.nvstream.input.KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }, GameMenu.KEY_UP_DELAY);
    }

    private void showArtemisMenu() {
        if (mMenu == null) mMenu = new ArtemisMenu(this, this);
        mMenu.showMenu();
    }

    // Mouse mode is GameInputController state (shared input stack, not Game's UI) --
    // trivially portable. Mirrors Game.selectMouseMode()'s applyMouseMode + remember.
    @Override public void onApplyMouseMode(int index) {
        if (mInputController == null) return;
        mInputController.applyMouseMode(index);
        if (mPrefs != null && mPrefs.rememberMouseMode) {
            com.limelight.profiles.ProfilesManager.getInstance().getOverlayingSharedPreferences(this)
                    .edit().putString("mouse_mode_list", String.valueOf(index)).apply();
        }
    }

    // HUD stats overlay: enablePerfOverlay is read live by the decoder each stats
    // interval (see MediaCodecDecoderRenderer), so flipping it needs no reconnect --
    // only the perf listener itself has to be wired before connect() (done in
    // showOverlay()).
    @Override public void onToggleHud() {
        mConfig.showHud = !mConfig.showHud;
        mConfig.save();
        if (mPrefs != null) mPrefs.enablePerfOverlay = mConfig.showHud;
        if (mOverlay != null) mOverlay.setPerfStatsVisible(mConfig.showHud);
    }

    @Override public boolean isHudEnabled() { return mConfig.showHud; }

    @Override public void onPerfUpdate(String text) {
        new android.os.Handler(getMainLooper()).post(() -> {
            if (mOverlay != null) mOverlay.setPerfStatsText(text);
        });
    }

    // --- ArtemisGestureRecognizer.Host: map recognized gestures to comrade actions.
    // Back/home/anchor-drag. Tune the exact host bindings (VK codes / i3 keybinds) here.
    private com.limelight.nvstream.NvConnection conn() {
        return mStream != null ? mStream.getConnection() : null;
    }

    @Override public void onEdgeBack(boolean fromLeft) {
        com.limelight.nvstream.NvConnection c = conn();
        if (c == null) return;
        // Left edge -> mouse "back" (X1); right edge -> "forward" (X2).
        byte btn = fromLeft
                ? com.limelight.nvstream.input.MouseButtonPacket.BUTTON_X1
                : com.limelight.nvstream.input.MouseButtonPacket.BUTTON_X2;
        c.sendMouseButtonDown(btn);
        c.sendMouseButtonUp(btn);
    }

    @Override public void onBottomHome() {
        com.limelight.nvstream.NvConnection c = conn();
        if (c == null) return;
        // TODO tune: Super+Tab (i3 workspace/window cycle). VK_TAB=0x09, Meta held.
        final short VK_TAB = 0x09;
        c.sendKeyboardInput(VK_TAB, com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN,
                com.limelight.nvstream.input.KeyboardPacket.MODIFIER_META, (byte) 0);
        c.sendKeyboardInput(VK_TAB, com.limelight.nvstream.input.KeyboardPacket.KEY_UP,
                com.limelight.nvstream.input.KeyboardPacket.MODIFIER_META, (byte) 0);
    }

    @Override public void onAnchorDragStart() {
        com.limelight.nvstream.NvConnection c = conn();
        if (c == null) return;
        // i3's floating_modifier drag is $mod + LEFT-BUTTON-HELD drag, not just cursor
        // movement under $mod -- holding Super alone (previous code) let the modifier arm
        // but never actually grabbed the window, so drags were silent no-ops. Press the
        // button AFTER the modifier is down so i3 sees Mod-then-click, matching a real drag.
        final short VK_LWIN = 0x5B;
        c.sendKeyboardInput(VK_LWIN, com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
        c.sendMouseButtonDown(com.limelight.nvstream.input.MouseButtonPacket.BUTTON_LEFT);
    }

    @Override public void onAnchorDragMove(float dx, float dy) {
        com.limelight.nvstream.NvConnection c = conn();
        if (c != null) c.sendMouseMove((short) dx, (short) dy);
    }

    @Override public void onAnchorDragEnd() {
        com.limelight.nvstream.NvConnection c = conn();
        if (c == null) return;
        final short VK_LWIN = 0x5B;
        // Release button before modifier -- releasing Super first while the button is
        // still logically down could let a stray move land as a non-mod drag on the host.
        c.sendMouseButtonUp(com.limelight.nvstream.input.MouseButtonPacket.BUTTON_LEFT);
        c.sendKeyboardInput(VK_LWIN, com.limelight.nvstream.input.KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
    }

    // Three-finger tap in the stream toggles the soft keyboard (SpectreBoard),
    // four-finger tap does the same "full" keyboard. Wired from GameInputController's
    // gesture dispatch via this Host callback.
    @Override public void toggleKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                    android.view.inputmethod.InputMethodManager.HIDE_IMPLICIT_ONLY);
        }
    }

    @Override public void toggleFullKeyboard() { toggleKeyboard(); }

    private static final String CHANNEL_ID = "artemisd_channel";
    private static final int NOTIFICATION_ID = 0xd1;

    public static final String ACTION_SHOW = "com.termux.diana.action.OVERLAY_SHOW";
    public static final String ACTION_HIDE = "com.termux.diana.action.OVERLAY_HIDE";
    public static final String ACTION_TOGGLE = "com.termux.diana.action.OVERLAY_TOGGLE";
    public static final String ACTION_STOP = "com.termux.diana.action.OVERLAY_STOP";
    public static final String ACTION_SHOW_MENU = "com.termux.diana.action.OVERLAY_SHOW_MENU";

    // Cybersyn's HID-mode gate: content "amd" makes the volume keys drive comrade's
    // gyro-pointer + click (KeyHijackController.amdMode), watched via FileObserver.
    // SpectreBoard writes this when its trackpad is up; the diana overlay is also a
    // remote-pointer surface, so drive the same gate while it's shown. (diana shares
    // UID 1000 / system_app_data_file with com.termux, so it can write here.)
    private static final java.io.File HID_MODE_FILE =
            new java.io.File("/data/data/com.termux/files/usr/tmp/cybersyn-hidmode");

    private void setHidMode(String mode) {
        try (java.io.FileWriter w = new java.io.FileWriter(HID_MODE_FILE)) {
            w.write(mode);
        } catch (Exception e) {
            Log.e("ArtemisDaemon", "Failed to set cybersyn-hidmode gate", e);
        }
    }

    // Separate from HID_MODE_FILE on purpose: that file has two independent writers
    // (this daemon + SpectreBoard's own AmdMode toggle) that already don't coordinate
    // with each other (documented edge case), so it can't double as "is the artemis
    // overlay itself actually visible right now". SpectreBoard's LatinIME reads this
    // one to gate its fullscreen IME extract view to only artemis-overlay + ime-up,
    // instead of the previous always-on-ime-up-in-landscape behavior.
    private static final java.io.File OVERLAY_VISIBLE_FILE =
            new java.io.File("/data/data/com.termux/files/usr/tmp/artemis-overlay-visible");

    private void setOverlayVisibleFlag(boolean visible) {
        try (java.io.FileWriter w = new java.io.FileWriter(OVERLAY_VISIBLE_FILE)) {
            w.write(visible ? "1" : "0");
        } catch (Exception e) {
            Log.e("ArtemisDaemon", "Failed to set artemis-overlay-visible flag", e);
        }
    }

    private ArtemisOverlayWindow mOverlay;
    private StreamController mStream;
    private ArtemisConfig mConfig;
    private PreferenceConfiguration mPrefs;
    private int mStreamWidth, mStreamHeight;
    private GameInputController mInputController;
    private ArtemisGestureRecognizer mGestureRecognizer;
    private boolean mVisible;

    // Resolves the resolution to REQUEST from the host: real (portrait) display size
    // normally, or that swapped into a landscape shape when mConfig.landscape is set.
    // The overlay WINDOW always stays at the real display size -- only the requested
    // stream resolution (and the SurfaceView's buffer, which gets letterboxed into the
    // window) changes. See ArtemisConfig.landscape.
    private int[] resolveStreamResolution(int realWidth, int realHeight) {
        if (mConfig.landscape) {
            return new int[]{Math.max(realWidth, realHeight), Math.min(realWidth, realHeight)};
        }
        return new int[]{realWidth, realHeight};
    }

    /** GameMenu's "rotate screen" option. Flips the landscape request and, if a stream
     *  is already up, reconnects at the new resolution in place (same StreamController,
     *  same PreferenceConfiguration instance, same render target Surface -- just a fresh
     *  negotiate). The overlay window itself never moves. */
    // KNOWN GAP (untested): GameInputController's absolute-position math uses the
    // reference view's full (portrait) width/height as the protocol's scaling frame
    // (conn.sendMousePosition(x, y, referenceWidth, referenceHeight)), not the
    // letterboxed video rect. With landscape mode on, the video only fills a centered
    // band of the portrait window, so touch/click position will be off wherever the
    // video doesn't cover the full window 1:1 -- needs on-device verification, and
    // likely a letterbox-rect-aware coordinate remap if it's actually wrong.
    public void toggleLandscape() {
        mConfig.landscape = !mConfig.landscape;
        mConfig.save();
        if (mStream == null || mOverlay == null) return;

        android.graphics.Point real = new android.graphics.Point();
        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay().getRealSize(real);
        int[] res = resolveStreamResolution(real.x, real.y);
        mStreamWidth = res[0];
        mStreamHeight = res[1];
        mPrefs.width = mStreamWidth;
        mPrefs.height = mStreamHeight;
        // See showOverlay() -- invert-resolution reverted, broke rendering live.
        mStream.setInvertResolution(false);

        mStream.disconnect();
        mStream.connect();
        mStream.setDecodePaused(!mVisible);
        mStream.setTargetFps(mVisible ? mConfig.fps : 0);
        mOverlay.setStreamBufferSize(mStreamWidth, mStreamHeight);

        // Same glitch class as the vanilla app's own resolution-change handling
        // (pre-dates artemisd -- owning the WindowManager window ourselves didn't
        // eliminate it, confirmed live): the first reconnect after a resolution
        // change renders corrupted/stale frames and only clears up on a second
        // reconnect. Force that second reconnect automatically instead of leaving
        // it to a manual re-toggle.
        final int expectedWidth = mStreamWidth, expectedHeight = mStreamHeight;
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (mStream == null || mStreamWidth != expectedWidth || mStreamHeight != expectedHeight) {
                return; // superseded by another toggle/disconnect in the meantime
            }
            mStream.disconnect();
            mStream.connect();
            mStream.setDecodePaused(!mVisible);
            mStream.setTargetFps(mVisible ? mConfig.fps : 0);
            mOverlay.setStreamBufferSize(mStreamWidth, mStreamHeight);
        }, 1500);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // Locking the phone (screen off) always hides the overlay. This is the
    // guaranteed escape hatch: even if the overlay has grabbed focus and
    // disabled nav gestures, hitting power frees the UI. Also pauses decode.
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                hideOverlay();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        String glRenderer = GlPreferences.readPreferences(this).glRenderer;
        MediaCodecHelper.initialize(this, glRenderer);
        mConfig = ArtemisConfig.load();
        registerReceiver(mScreenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        // onDestroy() isn't guaranteed to run on a crash (confirmed live: a stale-decoder
        // race during a fast STOP->SHOW cycle killed the process before it got there),
        // which would leave this flag stuck at "1" forever and permanently break
        // SpectreBoard's IME gate for every other app. Reset it on every fresh process
        // start as a fail-safe -- a real showOverlay() sets it back to "1" immediately
        // once the overlay is actually visible.
        setOverlayVisibleFlag(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground();
        if (mOverlay == null) {
            mOverlay = new ArtemisOverlayWindow();
            mOverlay.create(this);
            // The Surface is created asynchronously after the window is added. If a
            // show was requested before it was ready, connect the moment it is.
            mOverlay.setOnSurfaceReadyListener(() -> { if (mVisible) connectStreamToSurface(); });
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (action != null) handleAction(action);
        return START_STICKY;
    }

    private void handleAction(String action) {
        switch (action) {
            case ACTION_SHOW:  showOverlay(); break;
            case ACTION_HIDE:  hideOverlay(); break;
            case ACTION_TOGGLE:
                if (mVisible) hideOverlay(); else showOverlay();
                break;
            case ACTION_SHOW_MENU:
                // The menu needs a live connection to act on (sendKeys/toggle/disconnect
                // all go through it) -- bring the daemon up first if it isn't already.
                if (mStream == null) showOverlay();
                showArtemisMenu();
                break;
        }
    }

    private void showOverlay() {
        if (mVisible) return;
        mVisible = true;

        if (mStream == null) {
            Log.e("ArtemisDaemon", "creating StreamController...");
            mPrefs = PreferenceConfiguration.readPreferences(this);

            // Resolve stream resolution from the REAL display size (incl. system-bar
            // regions). getResources().getDisplayMetrics() returns the non-decor area
            // (e.g. 2360 instead of 2410), which would size the stream smaller than the
            // full-screen overlay window and misalign it (gap at bottom / overlap top).
            int displayWidth = 1080, displayHeight = 2410;
            try {
                android.view.WindowManager wm =
                        (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                android.graphics.Point real = new android.graphics.Point();
                wm.getDefaultDisplay().getRealSize(real);
                displayWidth = real.x;
                displayHeight = real.y;
            } catch (Exception ignored) {}

            // The overlay WINDOW stays at the real (portrait) size; only the requested
            // stream resolution flips when landscape mode is on (see ArtemisConfig.landscape
            // / resolveStreamResolution) -- the landscape buffer gets letterboxed into the
            // portrait window via SurfaceHolder.setFixedSize, no window resize needed.
            int[] res = resolveStreamResolution(displayWidth, displayHeight);
            mStreamWidth = res[0];
            mStreamHeight = res[1];

            mPrefs.width = mStreamWidth;
            mPrefs.height = mStreamHeight;
            mPrefs.fps = mConfig.fps;
            // Config bitrate is in Mbps (user-friendly); StreamConfiguration wants
            // Kbps. Passing it raw (e.g. 40) meant 40 Kbps -> whole stream macroblocked.
            mPrefs.bitrate = mConfig.bitrate * 1000;
            mPrefs.playHostAudio = mConfig.audioEnabled;
            mPrefs.enablePerfOverlay = mConfig.showHud;
            Log.e("ArtemisDaemon", "stream resolution: " + mStreamWidth + "x" + mStreamHeight
                    + (mConfig.landscape ? " (landscape)" : ""));

            ComputerDetails.AddressTuple host =
                    new ComputerDetails.AddressTuple(mConfig.host, mConfig.port);

            // Read uniqueId from Diana's files (written by IdentityManager)
            String uniqueId = "0123456789ABCDEF";
            try {
                java.io.File f = new java.io.File(getFilesDir(), "uniqueid");
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                uniqueId = r.readLine().trim();
                r.close();
            } catch (Exception e) {
                Log.e("ArtemisDaemon", "Failed to read uniqueid", e);
            }

            // Build server cert from config or existing DB
            X509Certificate serverCert = getServerCert();

            mStream = new StreamController(this, mPrefs, host,
                    mConfig.httpsPort, uniqueId, serverCert,
                    mConfig.appId, mConfig.appName, mConfig.appUuid);
            mStream.setPerfListener(this);
            // REVERTED (live-tested, broke rendering): this host's Sunshine/vkms capture
            // does NOT auto-rotate to match a swapped negotiated resolution the way the
            // real app's autoInvertVideoResolution assumes -- it just crams the real
            // (portrait) content into the landscape-shaped frame unrotated, rendering as
            // a thin strip. The fps gain theory (portrait decode ~60fps vs landscape
            // ~113fps) may still be real, but StreamController.setInvertResolution() is
            // NOT safe to use for the always-on portrait path on this setup. Left wired
            // but permanently off pending a real fix (e.g. a host-side capture rotate).
            mStream.setInvertResolution(false);
            mOverlay.setPerfStatsVisible(mConfig.showHud);
        }

        // Connect now if the Surface is already up; otherwise the onSurfaceReady
        // listener will connect once it is. We only make the overlay visible/
        // focusable inside connectStreamToSurface(), so it never grabs focus (and
        // toggles the keyboard) while empty.
        if (mOverlay.isSurfaceReady()) {
            connectStreamToSurface();
        }
    }

    private void connectStreamToSurface() {
        if (!mVisible || mStream == null || !mOverlay.isSurfaceReady()) return;
        // Buffer size must be set before connect() so the very first frame already
        // lands in a correctly-shaped (letterboxed if landscape) SurfaceView buffer.
        mOverlay.setStreamBufferSize(mStreamWidth, mStreamHeight);
        mStream.setRenderTarget(mOverlay.getSurfaceView().getHolder().getSurface());
        mStream.connect();
        mStream.setTargetFps(mConfig.fps);
        // Resume decoding (arms an IDR request if we were previously hidden)
        mStream.setDecodePaused(false);

        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        android.graphics.Point real = new android.graphics.Point();
        wm.getDefaultDisplay().getRealSize(real);

        if (mConfig.stripMode) {
            // View-only top slice: no input stack, no gyro/click hijack, not an IME
            // kiosk target -- NOT_TOUCHABLE/NOT_FOCUSABLE (set inside show()) let
            // touches fall through to whatever's underneath.
            mOverlay.show(true, real.x, real.y, mConfig.stripFraction);
            setOverlayVisibleFlag(false);
        } else {
            // Wire input through the REAL Artemis input stack (GameInputController),
            // reusing the tuned touch/trackpad/multitouch/keyboard/mouse handling.
            // Built once and reused; the connection (and thus this) lives across hides.
            if (mInputController == null) {
                android.view.View ref = mOverlay.getRootView();
                PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
                mInputController = new GameInputController(
                        this, mStream.getConnection(), prefs, ref,
                        new com.limelight.binding.input.capture.OverlayPointerCaptureProvider(ref),
                        this);
                mInputController.initMouseMode();
            }
            mInputController.setReferenceView(mOverlay.getRootView());
            mInputController.setGrabbedInput(true);
            mOverlay.setInputController(mInputController);
            // Gesture recognizer (edge-back / bottom-home / anchor-drag) runs as a
            // touch pre-filter. Built once; sized to the real display.
            if (mGestureRecognizer == null) {
                float density = getResources().getDisplayMetrics().density;
                mGestureRecognizer = new ArtemisGestureRecognizer(
                        this, mOverlay.getRootView(), density, real.x, real.y);
            }
            mOverlay.setGestureRecognizer(mGestureRecognizer);
            // Grab focus + show only now that video is actually coming up.
            mOverlay.show(false, real.x, real.y, mConfig.stripFraction);
            // Overlay is a remote-pointer surface: route vol keys to gyro/click on comrade.
            setHidMode("amd");
            setOverlayVisibleFlag(true);
        }
    }

    private void hideOverlay() {
        if (!mVisible) return;
        mVisible = false;
        mOverlay.hide();
        if (mStream != null) {
            // Stop decoding entirely while hidden (connection stays up).
            mStream.setDecodePaused(true);
            // CLEAR the surface frame-rate hint (0 = no preference) — do NOT force it
            // to 1. Surface.setFrameRate applies a PER-UID frame-rate override, and diana
            // shares UID 1000 with com.termux.shadereditor (the live wallpaper). Forcing
            // 1fps here throttled the wallpaper to a crawl the instant the overlay was
            // hidden. Decode is already paused, so there's no power cost to clearing it.
            mStream.setTargetFps(0);
        }
        // Overlay no longer taking input: release the vol-key gyro/click override.
        setHidMode("android");
        setOverlayVisibleFlag(false);
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(mScreenReceiver); } catch (Exception ignored) {}
        setHidMode("android");
        setOverlayVisibleFlag(false);
        if (mInputController != null) { mInputController.destroy(); mInputController = null; }
        if (mStream != null) { mStream.disconnect(); mStream = null; }
        if (mOverlay != null) { mOverlay.destroy(); mOverlay = null; }
        stopForeground(true);
        super.onDestroy();
    }

    private void startForeground() {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Artemis Daemon")
                .setContentText("Streaming service running")
                .setSmallIcon(com.limelight.R.drawable.ic_computer)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Artemis Streaming", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Persistent notification for artemisd daemon");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private X509Certificate getServerCert() {
        if (mConfig.serverCertBase64 != null) {
            try {
                byte[] der = android.util.Base64.decode(mConfig.serverCertBase64, android.util.Base64.DEFAULT);
                return (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der));
            } catch (Exception e) {
                Log.e("ArtemisDaemon", "Failed to decode config cert", e);
            }
        }
        try {
            ComputerDatabaseManager db = new ComputerDatabaseManager(this);
            for (ComputerDetails c : db.getAllComputers()) {
                if (c.serverCert != null) return c.serverCert;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
