package com.limelight.overlay;

import android.view.SurfaceHolder;

public interface SurfaceCallback {
    void surfaceCreated(SurfaceHolder holder);
    void surfaceChanged(SurfaceHolder holder, int format, int width, int height);
    void surfaceDestroyed(SurfaceHolder holder);
}
