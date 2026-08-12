package com.limelight.overlay;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.binding.input.KeyboardTranslator;

public class InputHandler {

    private final NvConnection mConn;
    private final KeyboardTranslator mTranslator;
    private final PreferenceConfiguration mPrefConfig;

    private byte mModifierFlags;

    public InputHandler(NvConnection conn, KeyboardTranslator translator, PreferenceConfiguration prefConfig) {
        mConn = conn;
        mTranslator = translator;
        mPrefConfig = prefConfig;
    }

    public void setConn(NvConnection conn) {
        // no-op placeholder kept for symmetry; connection is passed via constructor
    }

    public boolean handleKeyDown(KeyEvent event) {
        if (mConn == null) return false;

        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) return false;

        int deviceId = event.getDeviceId();
        if (mPrefConfig.ignoreSynthEvents && deviceId <= 0) return false;

        // Synthetic back = right-click (mouse source)
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (!mPrefConfig.mouseNavButtons) {
                mConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }
            return true;
        }

        short translated = mTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
        if (translated == 0) {
            if (mPrefConfig.backAsMeta && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                translated = 0x5b;
            } else {
                int unicodeChar = event.getUnicodeChar();
                if ((unicodeChar & 0x80000000) == 0 && Character.isDefined(unicodeChar)) {
                    mConn.sendUtf8Text("" + (char) unicodeChar);
                    return true;
                }
                return false;
            }
        }

        if (event.getRepeatCount() > 0) return true;

        byte modifier = getModifierState(event);
        mConn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, modifier,
                mTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId)
                        ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        return true;
    }

    public boolean handleKeyUp(KeyEvent event) {
        if (mConn == null) return false;

        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) return false;

        int deviceId = event.getDeviceId();
        if (mPrefConfig.ignoreSynthEvents && deviceId <= 0) return false;

        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (!mPrefConfig.mouseNavButtons) {
                mConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }
            return true;
        }

        short translated = mTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
        if (translated == 0) {
            if (mPrefConfig.backAsMeta && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                translated = 0x5b;
            } else {
                int unicodeChar = event.getUnicodeChar();
                return (unicodeChar & 0x80000000) == 0 && Character.isDefined(unicodeChar);
            }
        }

        mConn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event),
                mTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId)
                        ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        return true;
    }

    public boolean handleCapturedPointer(View view, MotionEvent event) {
        if (mConn == null) return false;

        float dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);

        if (dx != 0 || dy != 0) {
            mConn.sendMouseMove((short) dx, (short) dy);
        }

        // Handle relative-mouse button presses (button state in AXIS_BUTTON_STATE via ACTION_BUTTON_*)
        if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
            int button = event.getActionButton();
            if (button == MotionEvent.BUTTON_PRIMARY) mConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            else if (button == MotionEvent.BUTTON_SECONDARY) mConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            else if (button == MotionEvent.BUTTON_TERTIARY) mConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
        } else if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
            int button = event.getActionButton();
            if (button == MotionEvent.BUTTON_PRIMARY) mConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            else if (button == MotionEvent.BUTTON_SECONDARY) mConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            else if (button == MotionEvent.BUTTON_TERTIARY) mConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
        }

        return true;
    }

    public boolean handleTouch(View view, MotionEvent event) {
        if (mConn == null) return false;

        byte eventType = getTouchType(event);
        if (eventType < 0) return false;

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                sendTouchForPointer(view, event, eventType, i);
            }
            return true;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            mConn.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0, MoonBridge.LI_ROT_UNKNOWN);
            return true;
        } else {
            return sendTouchForPointer(view, event, eventType, event.getActionIndex());
        }
    }

    private boolean sendTouchForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] coords = normalizeXY(view, event, pointerIndex);
        return mConn.sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                coords[0], coords[1], 1.0f,
                0, 0, MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private float[] normalizeXY(View view, MotionEvent event, int pointerIndex) {
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float width = view != null ? view.getWidth() : 1;
        float height = view != null ? view.getHeight() : 1;
        if (width <= 0) width = 1;
        if (height <= 0) height = 1;
        return new float[] { x / width, y / height };
    }

    private byte getTouchType(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                return MoonBridge.LI_TOUCH_EVENT_UP;
            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;
            case MotionEvent.ACTION_HOVER_ENTER:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;
            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;
            case MotionEvent.ACTION_CANCEL:
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;
            default:
                return -1;
        }
    }

    private byte getModifierState(KeyEvent event) {
        byte modifier = mModifierFlags;
        if (event.isShiftPressed()) modifier |= KeyboardPacket.MODIFIER_SHIFT;
        if (event.isCtrlPressed()) modifier |= KeyboardPacket.MODIFIER_CTRL;
        if (event.isAltPressed()) modifier |= KeyboardPacket.MODIFIER_ALT;
        if (event.isMetaPressed()) modifier |= KeyboardPacket.MODIFIER_META;
        return modifier;
    }
}
