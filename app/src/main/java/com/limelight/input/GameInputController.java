package com.limelight.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.SensitivityBean;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.touch.TrackpadContext;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Activity-independent input-handling subsystem, lifted verbatim out of {@code Game}.
 *
 * <p>All Activity/UI couplings are injected or routed through the {@link Host} callback so that
 * this class can be reused by the WindowManager-overlay daemon without an Activity.</p>
 */
public class GameInputController {

    public interface Host {
        default void toggleKeyboard() {}
        default void toggleFullKeyboard() {}
        default void showGameMenu(GameInputDevice device) {}
        default void handlePanZoomTouchEvent(MotionEvent event) {}
        default boolean isVirtualControllerConfiguring() { return false; }
        default void quitImmediately() {}
        default void setMetaKeyCaptureState(boolean enabled) {}
        default void updateZoomButtonAppearance() {}
        default boolean isOnExternalDisplay() { return false; }
    }

    private final Context context;
    private final NvConnection conn;
    private final PreferenceConfiguration prefConfig;
    private final InputCaptureProvider inputCaptureProvider;
    private final Host host;

    private View referenceView;

    private int lastButtonState = 0;

    // Only 2 touches are supported
    private final TouchContext[] touchContextMap = new TouchContext[2];
    private final TouchContext[] trackpadContextMap = new TouchContext[2];
    private long threeFingerDownTime = 0;
    private long fourFingerDownTime = 0;
    private long fiveFingerDownTime = 0;

    private static final int REFERENCE_HORIZ_RES = 1280;
    private static final int REFERENCE_VERT_RES = 720;

    private static final int STYLUS_DOWN_DEAD_ZONE_DELAY = 100;
    private static final int STYLUS_DOWN_DEAD_ZONE_RADIUS = 20;

    private static final int STYLUS_UP_DEAD_ZONE_DELAY = 150;
    private static final int STYLUS_UP_DEAD_ZONE_RADIUS = 50;

    private static final int THREE_FINGER_TAP_THRESHOLD = 300;
    private static final int FOUR_FINGER_TAP_THRESHOLD = 300;
    private static final int FIVE_FINGER_TAP_THRESHOLD = 300;

    private ControllerHandler controllerHandler;
    private KeyboardTranslator keyboardTranslator;

    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean cursorVisible = false;
    private boolean isPanZoomMode = false;
    private boolean synthClickPending = false;
    private boolean pointerSwiping = false;
    private boolean waitingForAllModifiersUp = false;
    private int specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long synthTouchDownTime = 0;

    private boolean pendingDrag = false;
    private boolean isDragging = false;
    private float lastTouchDownX, lastTouchDownY;

    private long lastAbsTouchUpTime = 0;
    private long lastAbsTouchDownTime = 0;
    private float lastAbsTouchUpX, lastAbsTouchUpY;
    private float lastAbsTouchDownX, lastAbsTouchDownY;

    //灵敏度保存到集合 适配多个手指
    private Map<String, SensitivityBean> sensitivityMap = new HashMap<>();

    public GameInputController(Context context,
                               NvConnection conn,
                               PreferenceConfiguration prefConfig,
                               View referenceView,
                               InputCaptureProvider inputCaptureProvider,
                               Host host) {
        this.context = context;
        this.conn = conn;
        this.prefConfig = prefConfig;
        this.referenceView = referenceView;
        this.inputCaptureProvider = inputCaptureProvider;
        this.host = host;

        this.keyboardTranslator = new KeyboardTranslator(prefConfig);

        InputManager inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(keyboardTranslator, null);

        // Initialize trackpad contexts
        for (int i = 0; i < trackpadContextMap.length; i++) {
            trackpadContextMap[i] = new TrackpadContext(conn, i, prefConfig.trackpadSwapAxis, prefConfig.trackpadSensitivityX, prefConfig.trackpadSensitivityY);
        }
    }

    public void setReferenceView(View referenceView) {
        this.referenceView = referenceView;
    }

    public void setControllerHandler(ControllerHandler controllerHandler) {
        this.controllerHandler = controllerHandler;
    }

    public ControllerHandler getControllerHandler() {
        return controllerHandler;
    }

    public KeyboardTranslator getKeyboardTranslator() {
        return keyboardTranslator;
    }

    public void setGrabbedInput(boolean grabbedInput) {
        this.grabbedInput = grabbedInput;
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
    }

    public boolean isPanZoomMode() {
        return isPanZoomMode;
    }

    public void setPanZoomMode(boolean panZoomMode) {
        this.isPanZoomMode = panZoomMode;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0;

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider.onWindowFocusChanged(hasFocus);
    }

    public void destroy() {
        if (controllerHandler != null) {
            controllerHandler.destroy();
        }
        if (keyboardTranslator != null) {
            InputManager inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(keyboardTranslator);
        }
        if (inputCaptureProvider != null) {
            inputCaptureProvider.destroy();
        }
    }

    public void setInputGrabState(boolean grab) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider.enableCapture();

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider.showCursor();
            }
        }
        else {
            inputCaptureProvider.disableCapture();
        }

        // Grab/ungrab system keyboard shortcuts
        host.setMetaKeyCaptureState(grab);

        grabbedInput = grab;
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            setInputGrabState(!grabbedInput);
        }
    };

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(int androidKeyCode, boolean down) {
        int modifierMask = 0;
        int nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_META_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_META;
        }
        else {
            nonModifierKeyCode = androidKeyCode;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true;
            }
            else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down;
            }
            else {
                // When all modifiers are up, perform the special action
                switch (specialKeyCode) {
                    // Toggle input grab
                    case KeyEvent.KEYCODE_Z:
                        Handler h = new Handler(Looper.getMainLooper());
                        if (h != null) {
                            h.postDelayed(toggleGrab, 250);
                        }
                        break;

                    // Quit
                    case KeyEvent.KEYCODE_Q:
                        host.quitImmediately();
                        break;

                    // Toggle cursor visibility
                    case KeyEvent.KEYCODE_C:
                        if (!grabbedInput) {
                            inputCaptureProvider.enableCapture();
                            grabbedInput = true;
                        }
                        cursorVisible = !cursorVisible;
                        if (cursorVisible) {
                            inputCaptureProvider.showCursor();
                        } else {
                            inputCaptureProvider.hideCursor();
                        }
                        break;

                    default:
                        break;
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                waitingForAllModifiersUp = false;
            }
        }
        // Check if Ctrl+Alt+Shift is down when a non-modifier key is pressed
        else if ((modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT) &&
                (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)) {
            switch (androidKeyCode) {
                case KeyEvent.KEYCODE_Z:
                case KeyEvent.KEYCODE_Q:
                case KeyEvent.KEYCODE_C:
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode;
                    waitingForAllModifiersUp = true;
                    return true;

                default:
                    // This isn't a special combo that we consume on the client side
                    return false;
            }
        }

        // Not a special combo
        return false;
    }

    // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    private byte getModifierState(KeyEvent event) {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        byte modifier = getModifierState();
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        if (event.isMetaPressed()) {
            modifier |= KeyboardPacket.MODIFIER_META;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    public boolean handleKeyDown(KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;

        if (controllerHandler != null && ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonDown(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.getKeyCode(), true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
            if (translated == 0) {
                if (prefConfig.backAsMeta && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    translated = 0x5b; // Meta key
                } else {
                    // Make sure it has a valid Unicode representation and it's not a dead character
                    // (which we don't support). If those are true, we can send it as UTF-8 text.
                    //
                    // NB: We need to be sure this happens before the getRepeatCount() check because
                    // UTF-8 events don't auto-repeat on the host side.
                    int unicodeChar = event.getUnicodeChar();
                    if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
                        conn.sendUtf8Text(""+(char)unicodeChar);
                        return true;
                    }

                    return false;
                }
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    public boolean handleKeyUp(KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;
        if (controllerHandler != null && ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonUp(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.getKeyCode(), false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
            if (translated == 0) {
                if (prefConfig.backAsMeta && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    translated = 0x5b; // Meta key
                } else {
                    // If we sent this event as UTF-8 on key down, also report that it was handled
                    // when we get the key up event for it.
                    int unicodeChar = event.getUnicodeChar();
                    return (unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0;
                }
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    public void handleEvdevKeyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = keyboardTranslator.translate(keyCode, 0, -1);
        if (keyMap != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode, buttonDown)) {
                return;
            }

            if (buttonDown) {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState(), (byte)0);
            }
            else {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState(), (byte)0);
            }
        }
    }

    private TouchContext getTouchContext(int actionIndex, TouchContext[] inputContextMap)
    {
        if (actionIndex < inputContextMap.length) {
            return inputContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    private byte getLiTouchTypeFromEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    return MoonBridge.LI_TOUCH_EVENT_CANCEL;
                }
                else {
                    return MoonBridge.LI_TOUCH_EVENT_UP;
                }

            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;

            case MotionEvent.ACTION_CANCEL:
                // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;

            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;

            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY;

            default:
                return -1;
        }
    }

    //修改移动的触控灵敏度（通过修改移动的距离实现） 默认使用右半边屏幕的时候开启
    private float[] getStreamViewRelativeSensitivityXY(MotionEvent event,float normalizedX,float normalizedY,int pointerIndex){
        float[] normalized=new float[2];
        normalized[0]=normalizedX;
        normalized[1]=normalizedY;

        //如果不是全局模式 并且 坐标 不在右边 则返回
        if(!prefConfig.touchSensitivityGlobal&&normalizedX<context.getResources().getDisplayMetrics().widthPixels/2){
            return normalized;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            SensitivityBean bean=sensitivityMap.get(String.valueOf(event.getPointerId(pointerIndex)));
            if(bean==null){
                bean=new SensitivityBean();
            }
            if(bean.getLastAbsoluteX() !=-1){
                float dx=normalizedX- bean.getLastAbsoluteX();
                float dy=normalizedY- bean.getLastAbsoluteY();
                dx*=0.01f*prefConfig.touchSensitivityX;//灵敏度
                dy*=0.01f*prefConfig.touchSensitivityY;
                normalizedX= bean.getLastRelativelyX() +dx;
                normalizedY= bean.getLastRelativelyY() +dy;
            }
            if(prefConfig.touchSensitivityRotationAuto){
                if(normalizedX>= getInputReferenceWidth()){
                    normalizedX= getInputReferenceWidth()/2.0f;
                }
                if(normalizedY>= getInputReferenceHeight()){
                    normalizedY= getInputReferenceHeight()/2.0f;
                }
            }
            bean.setLastAbsoluteX(event.getX(pointerIndex));
            bean.setLastAbsoluteY(event.getY(pointerIndex));
            bean.setLastRelativelyX(normalizedX);
            bean.setLastRelativelyY(normalizedY);
            sensitivityMap.put(String.valueOf(event.getPointerId(pointerIndex)),bean);
        }
        //抬起的时候，恢复初始化状态
        if (event.getActionMasked() == MotionEvent.ACTION_UP||event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            sensitivityMap.remove(String.valueOf(event.getPointerId(pointerIndex)));
        }
        normalized[0]=normalizedX;
        normalized[1]=normalizedY;
        return normalized;
    }

    private View getInputReferenceView() {
        return referenceView;
    }

    private int getInputReferenceWidth() {
        return referenceView != null ? referenceView.getWidth() : 0;
    }

    private int getInputReferenceHeight() {
        return referenceView != null ? referenceView.getHeight() : 0;
    }

    private int getAbsoluteMouseReferenceWidth() {
        if (prefConfig.absoluteMouseHostOffsetEnable && prefConfig.absoluteMouseHostReferenceWidth > 0) {
            return prefConfig.absoluteMouseHostReferenceWidth;
        }
        return getInputReferenceWidth();
    }

    private int getAbsoluteMouseReferenceHeight() {
        if (prefConfig.absoluteMouseHostOffsetEnable && prefConfig.absoluteMouseHostReferenceHeight > 0) {
            return prefConfig.absoluteMouseHostReferenceHeight;
        }
        return getInputReferenceHeight();
    }

    private int applyAbsoluteMouseOffsetX(float x) {
        int eventX = Math.round(x);
        if (prefConfig.absoluteMouseHostOffsetEnable) {
            eventX += prefConfig.absoluteMouseHostOffsetX;
        }
        int referenceWidth = getAbsoluteMouseReferenceWidth();
        return Math.min(Math.max(eventX, 0), referenceWidth);
    }

    private int applyAbsoluteMouseOffsetY(float y) {
        int eventY = Math.round(y);
        if (prefConfig.absoluteMouseHostOffsetEnable) {
            eventY += prefConfig.absoluteMouseHostOffsetY;
        }
        int referenceHeight = getAbsoluteMouseReferenceHeight();
        return Math.min(Math.max(eventY, 0), referenceHeight);
    }

    private void sendAbsoluteMousePosition(float x, float y) {
        int sendX = applyAbsoluteMouseOffsetX(x);
        int sendY = applyAbsoluteMouseOffsetY(y);
        conn.sendMousePosition((short)sendX, (short)sendY,
                (short)getAbsoluteMouseReferenceWidth(), (short)getAbsoluteMouseReferenceHeight());
    }

    private void sendAbsoluteMouseMoveAsPosition(short deltaX, short deltaY) {
        conn.sendMouseMoveAsMousePosition(
                deltaX, deltaY,
                (short)getAbsoluteMouseReferenceWidth(), (short)getAbsoluteMouseReferenceHeight()
        );
    }

    private float[] getCoordinatesRelativeToStreamContainer(View touchedView, float viewRelativeX, float viewRelativeY) {
        View sourceView = touchedView != null ? touchedView : getInputReferenceView();
        View referenceView = getInputReferenceView();
        int[] sourceLoc = new int[2];
        int[] referenceLoc = new int[2];

        sourceView.getLocationOnScreen(sourceLoc);
        referenceView.getLocationOnScreen(referenceLoc);

        return new float[] {
                viewRelativeX + sourceLoc[0] - referenceLoc[0],
                viewRelativeY + sourceLoc[1] - referenceLoc[1]
        };
    }

    private float[] getStreamViewRelativeNormalizedXY(View view, MotionEvent event, int pointerIndex) {
        float normalizedX = event.getX(pointerIndex);
        float normalizedY = event.getY(pointerIndex);
        //开启自定义修改触控灵敏度 并且 数值不为100
        if(prefConfig.enableTouchSensitivity&&(prefConfig.touchSensitivityX !=100||prefConfig.touchSensitivityY!=100)){
            float[] normalized=getStreamViewRelativeSensitivityXY(event,normalizedX,normalizedY,pointerIndex);
            normalizedX=normalized[0];
            normalizedY=normalized[1];
        }
        float[] streamRelative = getCoordinatesRelativeToStreamContainer(view, normalizedX, normalizedY);
        normalizedX = streamRelative[0];
        normalizedY = streamRelative[1];

        normalizedX = Math.max(normalizedX, 0.0f);
        normalizedY = Math.max(normalizedY, 0.0f);

        normalizedX = Math.min(normalizedX, getInputReferenceWidth());
        normalizedY = Math.min(normalizedY, getInputReferenceHeight());

        normalizedX /= getInputReferenceWidth();
        normalizedY /= getInputReferenceHeight();

        return new float[] { normalizedX, normalizedY };
    }

    private static float normalizeValueInRange(float value, InputDevice.MotionRange range) {
        return (value - range.getMin()) / range.getRange();
    }

    private static float getPressureOrDistance(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                // Hover events report distance
                if (dev != null) {
                    InputDevice.MotionRange distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource());
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange);
                    }
                }
                return 0.0f;

            default:
                // Other events report pressure
                return event.getPressure(pointerIndex);
        }
    }

    private static short getRotationDegrees(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                short rotationDegrees = (short) Math.toDegrees(event.getOrientation(pointerIndex));
                if (rotationDegrees < 0) {
                    rotationDegrees += 360;
                }
                return rotationDegrees;
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN;
    }

    private static float[] polarToCartesian(float r, float theta) {
        return new float[] { (float)(r * Math.cos(theta)), (float)(r * Math.sin(theta)) };
    }

    private static float cartesianToR(float[] point) {
        return (float)Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2));
    }

    private float[] getStreamViewNormalizedContactArea(MotionEvent event, int pointerIndex) {
        float orientation;

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event.getDevice() == null || event.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null) {
            orientation = (float)(Math.PI / 4);
        }
        else {
            orientation = event.getOrientation(pointerIndex);
        }

        float contactAreaMajor, contactAreaMinor;
        switch (event.getActionMasked()) {
            // Hover events report the tool size
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                contactAreaMajor = event.getToolMajor(pointerIndex);
                contactAreaMinor = event.getToolMinor(pointerIndex);
                break;

            // Other events report contact area
            default:
                contactAreaMajor = event.getTouchMajor(pointerIndex);
                contactAreaMinor = event.getTouchMinor(pointerIndex);
                break;
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        float[] contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation);

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        float[] contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (float)(orientation + (Math.PI / 2)));

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), getInputReferenceWidth()) / getInputReferenceWidth();
        contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), getInputReferenceWidth()) / getInputReferenceWidth();
        contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), getInputReferenceHeight()) / getInputReferenceHeight();
        contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), getInputReferenceHeight()) / getInputReferenceHeight();

        // Convert the normalized values back into polar coordinates
        return new float[] { cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian) };
    }

    private boolean sendPenEventForPointer(View view, MotionEvent event, byte eventType, byte toolType, int pointerIndex) {
        byte penButtons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_PRIMARY;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_SECONDARY;
        }

        byte tiltDegrees = MoonBridge.LI_TILT_UNKNOWN;
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = (byte)Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex));
            }
        }

        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendPenEvent(eventType, toolType, penButtons,
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private static byte convertToolTypeToStylusToolType(MotionEvent event, int pointerIndex) {
        switch (event.getToolType(pointerIndex)) {
            case MotionEvent.TOOL_TYPE_ERASER:
                return MoonBridge.LI_TOOL_TYPE_ERASER;
            case MotionEvent.TOOL_TYPE_STYLUS:
                return MoonBridge.LI_TOOL_TYPE_PEN;
            default:
                return MoonBridge.LI_TOOL_TYPE_UNKNOWN;
        }
    }

    private boolean trySendPenEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            boolean handledStylusEvent = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                byte toolType = convertToolTypeToStylusToolType(event, i);
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue;
                }
                else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true;
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false;
                }
            }
            return handledStylusEvent;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, (byte)0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            byte toolType = convertToolTypeToStylusToolType(event, event.getActionIndex());
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false;
            }
            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex());
        }
    }

    private boolean sendTouchEventForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private boolean trySendTouchEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (!sendTouchEventForPointer(view, event, eventType, i)) {
                    return false;
                }
            }
            return true;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event.getActionIndex());
        }
    }

    // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
    public boolean handleMotionEvent(View view, MotionEvent event) {
        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        int eventSource = event.getSource();
        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;
        if ((eventSource & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler != null && controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        }
        else if (controllerHandler != null && (deviceSources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        }
        else if ((eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)
        {
            boolean hasActionButton = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (event.getActionButton() != 0);
            // This case is for mice and non-finger touch devices
            if (
                    eventSource == InputDevice.SOURCE_MOUSE ||
                            ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 && hasActionButton) || // SOURCE_TOUCHPAD
                            (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                                    (event.getPointerCount() >= 1 &&
                                            (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                                    eventSource == 12290) // 12290 = Samsung DeX mode desktop mouse
            ) {
                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                // Two finger click
                if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 &&
                        event.getPointerCount() == 2 &&
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && event.getActionButton() == MotionEvent.BUTTON_PRIMARY)) {
                    if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
                        buttonState |= MotionEvent.BUTTON_SECONDARY;
                    }
                    else if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                        buttonState &= ~MotionEvent.BUTTON_SECONDARY;
                    }
                    // We may not pressing the primary button down from a previous event,
                    // so be sure to clear that bit out the button state.
                    buttonState &= ~MotionEvent.BUTTON_PRIMARY;
                    buttonState |= (lastButtonState & MotionEvent.BUTTON_PRIMARY);

                    changedButtons = buttonState ^ lastButtonState;
                }

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider.isCapturingActive()) {
                    // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true;
                }

                // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    short deltaX = (short)inputCaptureProvider.getRelativeAxisX(event);
                    short deltaY = (short)inputCaptureProvider.getRelativeAxisY(event);

                    if (deltaX != 0 || deltaY != 0) {
                        if (prefConfig.absoluteMouseMode) {
                            // NB: view may be null, but we can unconditionally use the reference view because we don't need to adjust
                            // relative axis deltas for the position of the stream view within the parent's coordinate system.
                            sendAbsoluteMouseMoveAsPosition(deltaX, deltaY);
                        }
                        else {
                            conn.sendMouseMove(deltaX, deltaY);
                        }
                    }
                }
                else if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    InputDevice device = event.getDevice();
                    if (device != null) {
                        InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource);
                        InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource);

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.getMin() == 0 && yRange.getMin() == 0) {
                            int xMax = (int)xRange.getMax();
                            int yMax = (int)yRange.getMax();

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                int rawX = (int)event.getX();
                                int rawY = (int)event.getY();
                                int sendX = rawX;
                                int sendY = rawY;
                                int refW = xMax;
                                int refH = yMax;

                                if (prefConfig.absoluteMouseHostOffsetEnable &&
                                        prefConfig.absoluteMouseHostReferenceWidth > 0 &&
                                        prefConfig.absoluteMouseHostReferenceHeight > 0) {
                                    sendX = rawX + prefConfig.absoluteMouseHostOffsetX;
                                    sendY = rawY + prefConfig.absoluteMouseHostOffsetY;
                                    refW = prefConfig.absoluteMouseHostReferenceWidth;
                                    refH = prefConfig.absoluteMouseHostReferenceHeight;
                                }

                                sendX = Math.min(Math.max(sendX, 0), refW);
                                sendY = Math.min(Math.max(sendY, 0), refH);

                                conn.sendMousePosition((short)sendX, (short)sendY,
                                        (short)refW, (short)refH);
                            }
                        }
                    }
                }
                else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true;
                }
                else if (view != null) {
                    if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                        // Handle trackpad two finger swipes when pointer is not captured by synthesizing a trackpad movement
                        // Android emulates trackpad  two finger swipes as one finger swipe on the screen
                        int eventAction = event.getActionMasked();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
                            if (!pointerSwiping) {
                                pointerSwiping = true;
                                handleTouchInput(view, event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_POINTER_DOWN, 1, 2);
                            }
                            return handleTouchInput(view, event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_MOVE, 1, 2);
                        } else if (pointerSwiping && eventAction == MotionEvent.ACTION_UP) {
                            pointerSwiping = false;
                            synthClickPending = false;
                            handleTouchInput(view, event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_POINTER_UP, 1, 2);
                            return true;
                        }

                        // Press & Hold / Double-Tap & Hold for Selection or Drag & Drop
                        double positionDelta = Math.sqrt(
                                Math.pow(event.getX() - lastTouchDownX, 2) +
                                        Math.pow(event.getY() - lastTouchDownY, 2)
                        );

                        if (synthClickPending &&
                                event.getEventTime() - synthTouchDownTime >= prefConfig.trackpadDragDropThreshold) {
                            if (positionDelta > 50) {
                                pendingDrag = false;
                            } else if (pendingDrag) {
                                pendingDrag = false;
                                isDragging = true;
                                if (prefConfig.trackpadDragDropVibration) {
                                    Vibrator vibrator = ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE));
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(20, 127));
                                    } else {
                                        vibrator.vibrate(20);
                                    }
                                }
                                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                return true;
                            }
                        }

                        switch (eventAction) {
                            case MotionEvent.ACTION_HOVER_MOVE:
                            case MotionEvent.ACTION_MOVE:
                                updateMousePosition(view, event);
                                return true;
                            case MotionEvent.ACTION_HOVER_EXIT:
                            case MotionEvent.ACTION_DOWN:
                                pendingDrag = true;
                                synthClickPending = true;
                                lastTouchDownX = event.getX();
                                lastTouchDownY = event.getY();
                                synthTouchDownTime = event.getEventTime();
                                return true;
                            case MotionEvent.ACTION_HOVER_ENTER:
                            case MotionEvent.ACTION_UP:
                                if (synthClickPending) {
                                    long timeDiff = event.getEventTime() - synthTouchDownTime;

                                    if (eventSource == 12290) {
                                        // Special handle for DeX
                                        // DeX reports button secondary when tapping with two fingers
                                        // So there's no need to distinguish left/right click by time difference
                                        if (timeDiff < 120) {
                                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                        }
                                    } else {
                                        if (timeDiff < 20) {
                                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                        } else if (timeDiff < 120) {
                                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                                        }
                                    }
                                    if (isDragging) {
                                        isDragging = false;
                                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                    }
                                    pendingDrag = false;
                                    synthClickPending = false;
                                }
                                return true;
                            case MotionEvent.ACTION_BUTTON_PRESS:
                            case MotionEvent.ACTION_BUTTON_RELEASE:
                                synthClickPending = false;
                            default:
                                break;
                        }
                    } else {
                        updateMousePosition(view, event);
                    }
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn.sendMouseHighResScroll((short)(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
                    conn.sendMouseHighResHScroll((short)(event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                if (prefConfig.mouseNavButtons) {
                    if ((changedButtons & MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                        }
                    }

                    if ((changedButtons & MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                        }
                    }
                }

                // Handle stylus presses
                if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                }

                lastButtonState = buttonState;
            }
            // This case is for fingers
            else {
                if (eventSource == InputDevice.SOURCE_TOUCHPAD) {
                    return handleTouchInput(view, event, trackpadContextMap, false);
                } else {
                    if (host.isVirtualControllerConfiguring()) {
                        // Ignore presses when the virtual controller is being configured
                        return true;
                    }

                    if (isPanZoomMode) {
                        // panning the streamView
                        host.handlePanZoomTouchEvent(event);
                        return true;
                    }

                    // If touch is disabled or not initialized, we'll try panning the streamView
                    if (touchContextMap[0] == null) {
                        return true;
                    }

                    if (prefConfig.enableMultiTouchGestures || !prefConfig.enableMultiTouchScreen) {
                        int pointerCount = event.getPointerCount();
                        if (pointerCount > 2) {
                            int eventAction = event.getActionMasked();
                            if (
                                    (
                                            eventAction == MotionEvent.ACTION_POINTER_DOWN
                                                    || eventAction == MotionEvent.ACTION_POINTER_UP
                                                    || eventAction == MotionEvent.ACTION_UP
                                    )
                                            && handleMultiTouchGesture(event, eventAction, pointerCount, view)
                            ) {
                                return true;
                            }
                        }
                    }

                    if (prefConfig.enableMultiTouchScreen && !prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
                        // If this host supports touch events and absolute touch is enabled,
                        // send it directly as a touch event.
                        return true;
                    }

                    return handleTouchInput(view, event, touchContextMap, true);
                }
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    private boolean handleTouchInput(View touchedView, MotionEvent event, TouchContext[] inputContextMap, boolean isTouchScreen) {
        // Actual invert logic is handled within the touch context
        return handleTouchInput(touchedView, event, inputContextMap, isTouchScreen, false, event.getActionMasked(), event.getActionIndex(), event.getPointerCount());
    }

    private boolean handleTouchInput(View touchedView, MotionEvent event, TouchContext[] inputContextMap, boolean isTouchScreen, boolean invertAxis, int eventAction, int actionIndex, int pointerCount) {
        TouchContext context = getTouchContext(actionIndex, inputContextMap);
        if (context == null) {
            return false;
        }

        if (isTouchScreen) {
            View referenceView = getInputReferenceView();
            for (TouchContext touchContext : inputContextMap) {
                if (touchContext instanceof AbsoluteTouchContext) {
                    ((AbsoluteTouchContext) touchContext).setTargetView(referenceView);
                }
            }
        }

        int actualActionIndex = event.getActionIndex();
        int actualPointerCount = event.getPointerCount();

        boolean shouldDuplicateMovement = actualPointerCount < pointerCount;

        if (eventAction == MotionEvent.ACTION_MOVE) {
            // ACTION_MOVE is special because it always has actionIndex == 0
            // We'll call the move handlers for all indexes manually

            // First process the historical events
            for (int i = 0; i < event.getHistorySize(); i++) {
                for (TouchContext aTouchContextMap : inputContextMap) {
                    if (aTouchContextMap.getActionIndex() < pointerCount)
                    {
                        int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                        int historicalX = (int)event.getHistoricalX(aActionIndex, i);
                        int historicalY = (int)event.getHistoricalY(aActionIndex, i);
                        if (isTouchScreen) {
                            float[] streamCoords = getCoordinatesRelativeToStreamContainer(touchedView, historicalX, historicalY);
                            historicalX = (int)streamCoords[0];
                            historicalY = (int)streamCoords[1];
                        }

                        // Invert axis again since synthetic events are not inverted
                        // Invert twice could correct the direction
                        // Blame Android for this problem
                        // some devices report inverted axis when trackpad pointer is captured
                        // but not when they're simulated as swipes on the screen
                        if (invertAxis) {
                            aTouchContextMap.touchMoveEvent(
                                    historicalY,
                                    historicalX,
                                    event.getHistoricalEventTime(i)
                            );
                        } else {
                            aTouchContextMap.touchMoveEvent(
                                    historicalX,
                                    historicalY,
                                    event.getHistoricalEventTime(i)
                            );
                        }
                    }
                }
            }

            // Now process the current values
            for (TouchContext aTouchContextMap : inputContextMap) {
                if (aTouchContextMap.getActionIndex() < pointerCount)
                {
                    int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                    int currentX = (int)event.getX(aActionIndex);
                    int currentY = (int)event.getY(aActionIndex);
                    if (isTouchScreen) {
                        float[] streamCoords = getCoordinatesRelativeToStreamContainer(touchedView, currentX, currentY);
                        currentX = (int)streamCoords[0];
                        currentY = (int)streamCoords[1];
                    }

                    // Invert axis again since synthetic events are not inverted
                    if (invertAxis) {
                        aTouchContextMap.touchMoveEvent(
                                currentY,
                                currentX,
                                event.getEventTime()
                        );
                    } else {
                        aTouchContextMap.touchMoveEvent(
                                currentX,
                                currentY,
                                event.getEventTime());
                    }
                }
            }

            return true;
        }

        int eventX = (int)event.getX(actualActionIndex);
        int eventY = (int)event.getY(actualActionIndex);

        // Handle view scaling
        if (isTouchScreen) {
            float[] streamCoords = getCoordinatesRelativeToStreamContainer(touchedView, eventX, eventY);
            eventX = (int)streamCoords[0];
            eventY = (int)streamCoords[1];
        }

        switch (eventAction)
        {
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_DOWN:
                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount);
                }
                context.touchDownEvent(eventX, eventY, event.getEventTime(), true);
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (prefConfig.touchscreenTrackpad) {
                    if (pointerCount == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                        // All fingers up
                        long currentEventTime = event.getEventTime();
                        if (currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the keyboard
                            host.toggleKeyboard();
                            return true;
                        } else if (currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                            host.toggleFullKeyboard();
                            return true;
                        } else if (currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                            if(prefConfig.enableBackMenu) {
                                host.showGameMenu(null);
                            }
                            return true;
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    context.cancelTouch();
                }
                else {
                    context.touchUpEvent(eventX, eventY, event.getEventTime());
                }

                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount - 1);
                }
                if (actionIndex == 0 && pointerCount > 1 && !context.isCancelled()) {
                    // The original secondary touch now becomes primary
                    int pointer1X = (int)event.getX(1);
                    int pointer1Y = (int)event.getY(1);
                    if (isTouchScreen) {
                        float[] streamCoords = getCoordinatesRelativeToStreamContainer(touchedView, pointer1X, pointer1Y);
                        pointer1X = (int)streamCoords[0];
                        pointer1Y = (int)streamCoords[1];
                    }
                    context.touchDownEvent(
                            pointer1X,
                            pointer1Y,
                            event.getEventTime(), false);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (TouchContext aTouchContext : inputContextMap) {
                    aTouchContext.cancelTouch();
                    aTouchContext.setPointerCount(0);
                }
                break;
            default:
                return false;
        }

        return true;
    }

    private boolean handleMultiTouchGesture(MotionEvent event, int eventAction, int pointerCount, View view) {

        if (eventAction == MotionEvent.ACTION_POINTER_DOWN) {
            if (pointerCount == 3) {
                threeFingerDownTime = event.getEventTime();
            } else if (pointerCount == 4) {
                threeFingerDownTime = 0;
                fourFingerDownTime = event.getEventTime();
            } else if (pointerCount == 5) {
                threeFingerDownTime = 0;
                fourFingerDownTime = 0;
                fiveFingerDownTime = event.getEventTime();
            }
        }

        switch (eventAction) {
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                long currentEventTime = event.getEventTime();
                if (pointerCount >= 5 && fiveFingerDownTime > 0 && currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                    if(prefConfig.enableBackMenu) {
                        host.showGameMenu(null);
                    }
                    fiveFingerDownTime = 0;
                    break;
                } else if (pointerCount == 4 && fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                    host.toggleFullKeyboard();
                    fourFingerDownTime = 0;
                    break;
                } else if (pointerCount == 3 && threeFingerDownTime > 0 && currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                    host.toggleKeyboard();
                    threeFingerDownTime = 0;
                    break;
                }
                threeFingerDownTime = 0;
                fourFingerDownTime = 0;
                fiveFingerDownTime = 0;

                cancelStaleTouchState(event, view);
                return false;
            default:
                return false;
        }

        cancelStaleTouchState(event, view);
        return true;
    }

    private void cancelStaleTouchState(MotionEvent event, View view) {
        MotionEvent cancelEvent = MotionEvent.obtain(event);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        view.dispatchTouchEvent(cancelEvent);
        cancelEvent.recycle();
        for (TouchContext aTouchContext : touchContextMap) {
            aTouchContext.cancelTouch();
            aTouchContext.setPointerCount(0);
        }
    }

    private void updateMousePosition(View touchedView, MotionEvent event) {
        float[] streamCoords = getCoordinatesRelativeToStreamContainer(touchedView, event.getX(0), event.getY(0));
        float eventX = streamCoords[0];
        float eventY = streamCoords[1];

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS))
        {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchUpX, 2) + Math.pow(eventY - lastAbsTouchUpY, 2)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                        return;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchDownX, 2) + Math.pow(eventY - lastAbsTouchDownY, 2)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                        return;
                    }
                    break;
            }
        }

        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), getInputReferenceWidth());
        eventY = Math.min(Math.max(eventY, 0), getInputReferenceHeight());

        sendAbsoluteMousePosition(eventX, eventY);
    }

    /**
     * Initializes and applies the appropriate mouse mode based on user preferences
     * and whether the app is running in secondary display mode (such as Samsung DeX).
     *
     * Behavior:
     * - If the app is in secondary display mode:
     *   - Applies the user's saved mouse mode if it's one of the supported modes:
     *     "Trackpad Natural", "Trackpad Gaming", or "Disabled".
     *   - Otherwise, defaults to applying the "Trackpad Natural" mode.
     *
     * - If the app is not in secondary display mode:
     *   - Applies the user's saved mouse mode as is.
     *
     * This ensures the correct input mode is applied depending on the environment,
     * improving compatibility with desktop-like multi-display modes.
     */
    public void initMouseMode() {
        String[] mouseModes = context.getResources().getStringArray(R.array.mouse_mode_names);

        String savedMouseModeIndexStr = ProfilesManager.getInstance()
                .getOverlayingSharedPreferences(context)
                .getString("mouse_mode_list", "0");

        int savedMouseModeIndex;
        try {
            savedMouseModeIndex = Integer.parseInt(savedMouseModeIndexStr);
        } catch (NumberFormatException e) {
            savedMouseModeIndex = 0;
        }

        String savedMouseModeString = (savedMouseModeIndex >= 0 && savedMouseModeIndex < mouseModes.length)
                ? mouseModes[savedMouseModeIndex]
                : null;

        String natural = context.getString(R.string.mouse_mode_track_pad_natural);
        String gaming = context.getString(R.string.mouse_mode_track_pad_gaming);
        String disabled = context.getString(R.string.mouse_mode_disabled);

        int naturalIndex = 2; //fallback natural mode for secondary screen
        for (int i = 0; i < mouseModes.length; i++) {
            if (mouseModes[i].equals(natural)) {
                naturalIndex = i;
                break;
            }
        }
        // We only want to temporary override the mouse mode to work with external, but not store it
        if (host.isOnExternalDisplay()) {
            if (savedMouseModeString != null &&
                    (savedMouseModeString.equals(natural) ||
                            savedMouseModeString.equals(gaming) ||
                            savedMouseModeString.equals(disabled))) {
                applyMouseMode(savedMouseModeIndex);
            } else {
                applyMouseMode(naturalIndex);
            }
        } else {
            applyMouseMode(savedMouseModeIndex);
        }
    }

    //本地鼠标光标切换
    public void toggleMouseLocalCursor(){
        if (!grabbedInput) {
            inputCaptureProvider.enableCapture();
            grabbedInput = true;
        }
        cursorVisible = !cursorVisible;
        if (cursorVisible) {
            inputCaptureProvider.showCursor();
        } else {
            inputCaptureProvider.hideCursor();
        }
    }

    public void applyMouseMode(int mode) {
        switch (mode) {
            case 0: // Multi-touch
                prefConfig.enableMultiTouchScreen = true;
                prefConfig.touchscreenTrackpad = false;
                break;
            case 1: // Normal mouse
            case 5: // Normal mouse with swapped buttons
                prefConfig.enableMultiTouchScreen = false;
                prefConfig.touchscreenTrackpad = false;
                break;
            case 2: // Trackpad (natural)
            case 3: // Trackpad (gaming)
                prefConfig.enableMultiTouchScreen = false;
                prefConfig.touchscreenTrackpad = true;
                break;
            case 4: // Touch mouse disabled
                break;
            default:
                break;
        }

        //Initialize touch contexts
        for (int i = 0; i < touchContextMap.length; i++) {
            if (touchContextMap[i] != null) touchContextMap[i].cancelTouch();
            if (mode == 4) {
                // Touch mouse disabled
                touchContextMap[i] = null;
            } else if (!prefConfig.touchscreenTrackpad) {
                touchContextMap[i] = new AbsoluteTouchContext(
                        conn, i, getInputReferenceView(), mode == 5,
                        prefConfig.absoluteMouseHostOffsetEnable,
                        prefConfig.absoluteMouseHostOffsetX,
                        prefConfig.absoluteMouseHostOffsetY,
                        getAbsoluteMouseReferenceWidth(),
                        getAbsoluteMouseReferenceHeight()
                );
            } else if (mode == 3) {
                touchContextMap[i] = new RelativeTouchContext(conn, i, REFERENCE_HORIZ_RES, REFERENCE_VERT_RES, referenceView, prefConfig);
            } else {
                touchContextMap[i] = new TrackpadContext(conn, i);
            }
        }

        // Always exit zoom mode if mouse mode has changed
        isPanZoomMode = false;
        host.updateZoomButtonAppearance();
    }
}
