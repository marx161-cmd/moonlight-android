package com.limelight.overlay;

import android.content.Context;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import java.security.cert.X509Certificate;

public class StreamController implements NvConnectionListener {

    private final Context mContext;
    private final PreferenceConfiguration mPrefConfig;
    private final ComputerDetails.AddressTuple mHost;
    private final int mHttpsPort;
    private final String mUniqueId;
    private final X509Certificate mServerCert;
    private final int mAppId;
    private final String mAppName;
    private final String mAppUuid;

    private NvConnection mConnection;
    private MediaCodecDecoderRenderer mDecoder;
    private Surface mRenderTarget;
    private boolean mConnected;
    private boolean mConnectionStarted;
    private InputHandler mInputHandler;

    public StreamController(Context context,
                            PreferenceConfiguration prefConfig,
                            ComputerDetails.AddressTuple host,
                            int httpsPort,
                            String uniqueId,
                            X509Certificate serverCert,
                            int appId,
                            String appName,
                            String appUuid) {
        mContext = context;
        mPrefConfig = prefConfig;
        mHost = host;
        mHttpsPort = httpsPort;
        mUniqueId = uniqueId;
        mServerCert = serverCert;
        mAppId = appId;
        mAppName = appName;
        mAppUuid = appUuid;
    }

    public void setRenderTarget(Surface surface) {
        mRenderTarget = surface;
        if (mDecoder != null) {
            mDecoder.setRenderTarget(surface);
        }
    }

    public void connect() {
        if (mConnected || mConnectionStarted) return;
        if (mRenderTarget == null) {
            LimeLog.warning("StreamController: no render target, deferring connect");
            return;
        }

        LimeLog.info("StreamController: connecting to " + mHost);

        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;

        mDecoder = new MediaCodecDecoderRenderer(
                mContext, mPrefConfig,
                e -> {},
                0, false, false, false, null, null);

        if (mDecoder.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
        }
        if (mDecoder.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
        }

        mDecoder.setPreferLowerDelays(false);
        mDecoder.setPreferLowerDelaysTimeoutUs(2000);

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(mPrefConfig.width, mPrefConfig.height)
                .setRefreshRate(mPrefConfig.fps)
                .setBitrate(mPrefConfig.bitrate)
                .setEnableSops(mPrefConfig.enableSops)
                .enableLocalAudioPlayback(mPrefConfig.playHostAudio)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                .setSupportedVideoFormats(supportedVideoFormats)
                .setClientRefreshRateX100((int)(mPrefConfig.fps * 100))
                .setAudioConfiguration(mPrefConfig.audioConfiguration)
                .setColorSpace(mDecoder.getPreferredColorSpace())
                .setColorRange(mDecoder.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(!mPrefConfig.multiController)
                .setApp(new com.limelight.nvstream.http.NvApp(mAppName, mAppUuid, mAppId, false))
                .build();

        mConnection = new NvConnection(mContext, mHost,
                mHttpsPort, mUniqueId, config,
                PlatformBinding.getCryptoProvider(mContext), mServerCert);

        mDecoder.setRenderTarget(mRenderTarget);

        AndroidAudioRenderer audio = new AndroidAudioRenderer(mContext, mPrefConfig.playHostAudio);
        mConnectionStarted = true;

        // Build input handler wired to the connection
        mInputHandler = new InputHandler(mConnection,
                new com.limelight.binding.input.KeyboardTranslator(mPrefConfig),
                mPrefConfig);

        mConnection.start(audio, mDecoder, this);
    }

    public void disconnect() {
        LimeLog.info("StreamController: disconnecting");
        mConnected = false;
        mConnectionStarted = false;
        if (mConnection != null) {
            mConnection.stop();
            mConnection = null;
        }
        mDecoder = null;
        mInputHandler = null;
    }

    public boolean isConnected() { return mConnected; }

    public void setTargetFps(int fps) {
        if (mDecoder != null) mDecoder.setTargetFps(fps);
    }

    public InputHandler getInputHandler() { return mInputHandler; }

    // NvConnectionListener

    @Override public void stageStarting(String stage) {}
    @Override public void stageComplete(String stage) {}
    @Override public boolean stageFailed(String stage, int portFlags, int errorCode) {
        LimeLog.severe("StreamController: stage failed: " + stage + " error=" + errorCode);
        mConnected = false;
        return true;
    }
    @Override public void connectionStarted() {
        LimeLog.info("StreamController: connection started");
        mConnected = true;
    }
    @Override public void connectionTerminated(int errorCode) {
        LimeLog.info("StreamController: connection terminated: " + errorCode);
        mConnected = false;
    }
    @Override public void connectionStatusUpdate(int connectionStatus) {}
    @Override public void displayMessage(String message) {}
    @Override public void displayTransientMessage(String message) {}
    @Override public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {}
    @Override public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {}
    @Override public void setHdrMode(boolean enabled, byte[] hdrMetadata) {}
    @Override public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {}
    @Override public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {}
}
