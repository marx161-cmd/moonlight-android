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

public class ArtemisDaemonService extends Service {

    private static final String CHANNEL_ID = "artemisd_channel";
    private static final int NOTIFICATION_ID = 0xd1;

    public static final String ACTION_SHOW = "com.termux.diana.action.OVERLAY_SHOW";
    public static final String ACTION_HIDE = "com.termux.diana.action.OVERLAY_HIDE";
    public static final String ACTION_TOGGLE = "com.termux.diana.action.OVERLAY_TOGGLE";
    public static final String ACTION_STOP = "com.termux.diana.action.OVERLAY_STOP";

    private ArtemisOverlayWindow mOverlay;
    private StreamController mStream;
    private ArtemisConfig mConfig;
    private GameInputController mInputController;
    private boolean mVisible;

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
        }
    }

    private void showOverlay() {
        if (mVisible) return;
        mVisible = true;

        if (mStream == null) {
            Log.e("ArtemisDaemon", "creating StreamController...");
            PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);

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

            prefs.width = displayWidth;
            prefs.height = displayHeight;
            prefs.fps = mConfig.fps;
            prefs.bitrate = mConfig.bitrate;
            prefs.playHostAudio = mConfig.audioEnabled;
            Log.e("ArtemisDaemon", "stream resolution: " + displayWidth + "x" + displayHeight);

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

            mStream = new StreamController(this, prefs, host,
                    mConfig.httpsPort, uniqueId, serverCert,
                    mConfig.appId, mConfig.appName, mConfig.appUuid);
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
        mStream.setRenderTarget(mOverlay.getSurfaceView().getHolder().getSurface());
        mStream.connect();
        mStream.setTargetFps(mConfig.fps);
        // Resume decoding (arms an IDR request if we were previously hidden)
        mStream.setDecodePaused(false);
        // Wire input through the REAL Artemis input stack (GameInputController),
        // reusing the tuned touch/trackpad/multitouch/keyboard/mouse handling.
        // Built once and reused; the connection (and thus this) lives across hides.
        if (mInputController == null) {
            android.view.View ref = mOverlay.getRootView();
            PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
            mInputController = new GameInputController(
                    this, mStream.getConnection(), prefs, ref,
                    new com.limelight.binding.input.capture.OverlayPointerCaptureProvider(ref),
                    new GameInputController.Host() {});
            mInputController.initMouseMode();
        }
        mInputController.setReferenceView(mOverlay.getRootView());
        mInputController.setGrabbedInput(true);
        mOverlay.setInputController(mInputController);
        // Grab focus + show only now that video is actually coming up.
        mOverlay.setVisible(true);
    }

    private void hideOverlay() {
        if (!mVisible) return;
        mVisible = false;
        mOverlay.setVisible(false);
        if (mStream != null) {
            // Stop decoding entirely while hidden (connection stays up), and hint
            // the panel down to 1 Hz. This is what makes hidden nearly free.
            mStream.setDecodePaused(true);
            mStream.setTargetFps(1);
        }
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(mScreenReceiver); } catch (Exception ignored) {}
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
