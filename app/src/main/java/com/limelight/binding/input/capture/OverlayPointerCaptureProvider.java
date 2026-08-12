package com.limelight.binding.input.capture;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/**
 * Capture provider for the WindowManager-overlay daemon.
 *
 * <p>The overlay root view already manages pointer capture itself (it calls
 * {@link View#requestPointerCapture()} on focus and installs a captured-pointer listener), so this
 * provider does not drive capture. It only reports the relative-axis state of captured pointer
 * events so that the shared {@code GameInputController} mouse path works without an Activity.</p>
 */
public class OverlayPointerCaptureProvider extends InputCaptureProvider {
    private final View targetView;

    public OverlayPointerCaptureProvider(View targetView) {
        this.targetView = targetView;
    }

    @Override
    public boolean isCapturingActive() {
        // The overlay is the sole input surface while it is shown; the window hides itself by
        // moving off-screen and becoming non-touchable, so input stops arriving before it matters.
        return true;
    }

    @Override
    public boolean eventHasRelativeMouseAxes(MotionEvent event) {
        // SOURCE_MOUSE_RELATIVE is how SOURCE_MOUSE appears when our view has pointer capture.
        // SOURCE_TOUCHPAD will have relative axes populated iff our view has pointer capture.
        int eventSource = event.getSource();
        return (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) ||
                (eventSource == InputDevice.SOURCE_TOUCHPAD && targetView.hasPointerCapture());
    }

    @Override
    public float getRelativeAxisX(MotionEvent event, int pointerIndex) {
        int axis = (event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE) ?
                MotionEvent.AXIS_X : MotionEvent.AXIS_RELATIVE_X;
        float x = event.getAxisValue(axis, pointerIndex);
        for (int i = 0; i < event.getHistorySize(); i++) {
            x += event.getHistoricalAxisValue(axis, pointerIndex, i);
        }
        return x;
    }

    @Override
    public float getRelativeAxisY(MotionEvent event, int pointerIndex) {
        int axis = (event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE) ?
                MotionEvent.AXIS_Y : MotionEvent.AXIS_RELATIVE_Y;
        float y = event.getAxisValue(axis, pointerIndex);
        for (int i = 0; i < event.getHistorySize(); i++) {
            y += event.getHistoricalAxisValue(axis, pointerIndex, i);
        }
        return y;
    }
}
