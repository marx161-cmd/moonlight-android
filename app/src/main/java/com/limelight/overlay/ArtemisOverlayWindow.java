package com.limelight.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import com.limelight.R;

import java.util.Collections;

public class ArtemisOverlayWindow {

    private static final String TAG = "ArtemisOverlayWindow";
    private static final int OFFSCREEN_X = 10000;

    private WindowManager mWindowManager;
    private View mRootView;
    private SurfaceView mSurfaceView;
    private boolean mSurfaceReady;
    private boolean mVisible;
    private InputHandler mInputHandler;
    private Runnable mOnSurfaceReady;

    public void create(Context context) {
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        LayoutInflater inflater = LayoutInflater.from(context);
        mRootView = inflater.inflate(R.layout.overlay_artemis, null);
        mSurfaceView = mRootView.findViewById(R.id.overlay_surface);
        // SurfaceView must render ON TOP of the overlay window, otherwise the
        // window's background covers the decoder output (black screen).
        mSurfaceView.setZOrderOnTop(true);
        mSurfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                mSurfaceReady = true;
                if (mOnSurfaceReady != null) mOnSurfaceReady.run();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                mSurfaceReady = false;
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 0;

        mWindowManager.addView(mRootView, params);

        // System gesture exclusion for full-screen overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int displayWidth = context.getResources().getDisplayMetrics().widthPixels;
            int displayHeight = context.getResources().getDisplayMetrics().heightPixels;
            mRootView.setSystemGestureExclusionRects(
                    Collections.singletonList(new Rect(0, 0, displayWidth, displayHeight)));
        }

        // Route input through the InputHandler (wired to the stream)
        mRootView.setFocusableInTouchMode(true);
        mRootView.setOnKeyListener((v, keyCode, event) -> {
            if (mInputHandler == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                return mInputHandler.handleKeyDown(event);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                return mInputHandler.handleKeyUp(event);
            }
            return false;
        });

        mRootView.setOnTouchListener((v, event) -> {
            if (mInputHandler == null) return false;
            return mInputHandler.handleTouch(v, event);
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mRootView.setOnCapturedPointerListener((view, event) -> {
                if (mInputHandler == null) return false;
                return mInputHandler.handleCapturedPointer(view, event);
            });
        }

        // Request pointer capture only AFTER focus is granted, and re-request on
        // every focus regain — capture is dropped whenever the window loses focus.
        mRootView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) requestCapture();
        });
    }

    public void setInputHandler(InputHandler handler) {
        mInputHandler = handler;
    }

    public void setOnSurfaceReadyListener(Runnable r) {
        mOnSurfaceReady = r;
        if (mSurfaceReady && r != null) r.run();
    }

    private void requestCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mRootView != null) {
            try { mRootView.requestPointerCapture(); } catch (Exception ignored) {}
        }
    }

    private void hideIme() {
        if (mRootView == null) return;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            mRootView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(mRootView.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    public void setVisible(boolean visible) {
        if (mRootView == null || mWindowManager == null) return;

        WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) mRootView.getLayoutParams();

        if (visible) {
            params.x = 0;
            params.y = 0;
            params.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            // Take key/motion focus for input forwarding, but keep the local IME
            // out of it — a desktop stream never wants the Android soft keyboard.
            params.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            mWindowManager.updateViewLayout(mRootView, params);
            mRootView.setAlpha(1.0f);
            mRootView.requestFocus();
            hideIme();
            // Pointer capture must be (re)requested once focus has actually landed.
            mRootView.post(this::requestCapture);
            mVisible = true;
        } else {
            // A SurfaceView with setZOrderOnTop punches through the window, so the
            // root view's alpha does NOT fade it — that's why alpha-only "hide"
            // stayed visible. Move the whole window off-screen instead: truly
            // hidden, Surface kept alive (no destroy/recreate churn), input off.
            mRootView.setAlpha(0.0f);
            params.x = -OFFSCREEN_X;
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            mWindowManager.updateViewLayout(mRootView, params);
            mRootView.releasePointerCapture();
            mVisible = false;
        }
    }

    public boolean isSurfaceReady() { return mSurfaceReady; }
    public boolean isVisible() { return mVisible; }
    public SurfaceView getSurfaceView() { return mSurfaceView; }
    public View getRootView() { return mRootView; }

    public void destroy() {
        if (mRootView != null && mWindowManager != null) {
            mWindowManager.removeView(mRootView);
            mRootView = null;
            mSurfaceView = null;
            mSurfaceReady = false;
        }
    }
}
