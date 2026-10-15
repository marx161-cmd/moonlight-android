package com.limelight;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Gyro mouse: while started, turns phone rotation into relative mouse movement for the
 * stream. Same pointing maths as cybersyn-hid-relay.py's pointer path (game rotation
 * vector -> yaw + roll, 2500 px/rad, X inverted, Y inverted with --invert-y), but sent
 * over the stream's own mouse path instead of MQTT, so it has the stream's latency.
 * Every start() re-baselines on the first sample, like the relay's hold-start calibration.
 */
final class GyroMouse implements SensorEventListener {
    interface Sink { void move(int dx, int dy); }

    private static final float GAIN_X = 2500f;   // px per radian of yaw
    private static final float GAIN_Y = 2500f;   // px per radian of roll
    private static final int MAX_STEP = 300;

    private final Context context;
    private final Sink sink;
    private HandlerThread thread;
    private SensorManager sm;

    private boolean haveLast;
    private float lastYaw, lastRoll;
    private float remX, remY;   // sub-pixel remainder carried between samples

    GyroMouse(Context context, Sink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
    }

    synchronized void start() {
        if (thread != null) return;
        sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) { sm = null; return; }
        haveLast = false;
        remX = remY = 0f;
        thread = new HandlerThread("gyro-mouse");
        thread.start();
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, new Handler(thread.getLooper()));
    }

    synchronized void stop() {
        if (sm != null) sm.unregisterListener(this);
        if (thread != null) thread.quitSafely();
        thread = null;
        sm = null;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        float x = v[0], y = v[1], z = v[2];
        float w = v.length >= 4 ? v[3]
                : (float) Math.sqrt(Math.max(0.0, 1.0 - (x * x + y * y + z * z)));
        // Same angle extraction as the relay's get_angles().
        float yaw = (float) Math.atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z));
        float roll = (float) Math.atan2(2f * (w * x + y * z), 1f - 2f * (x * x + y * y));
        if (!haveLast) {
            lastYaw = yaw; lastRoll = roll; haveLast = true;
            return;
        }
        float dYaw = unwrap(yaw - lastYaw);
        float dRoll = unwrap(roll - lastRoll);
        lastYaw = yaw; lastRoll = roll;

        remX += -dYaw * GAIN_X;
        remY += -dRoll * GAIN_Y;
        int dx = clamp((int) remX), dy = clamp((int) remY);
        remX -= dx; remY -= dy;
        if (dx != 0 || dy != 0) sink.move(dx, dy);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static float unwrap(float d) {
        if (d > Math.PI) d -= 2 * Math.PI;
        if (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    private static int clamp(int v) { return Math.max(-MAX_STEP, Math.min(MAX_STEP, v)); }
}
