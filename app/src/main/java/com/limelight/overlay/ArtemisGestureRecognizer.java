package com.limelight.overlay;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * In-overlay gesture recognizer. The overlay already receives every touch (system
 * gestures are suppressed on it), so instead of hooking SystemUI/Launcher we run the
 * recognition ourselves — the geometric core distilled from AOSP's
 * {@code EdgeBackGestureHandler} (back) and Quickstep swipe-up (home): edge-zone gate →
 * drift past slop/commit threshold, single-finger for the edge gestures. We deliberately
 * skip the parts we don't need (InputMonitor pilfering, the TFLite false-positive
 * classifier, the edge-panel animation) and fire the real system haptic via
 * {@link View#performHapticFeedback}, so it feels native without any elevated tooling.
 *
 * The recognizer is a PRE-FILTER: {@link #onTouch} returns true when it has taken the
 * gesture, so the caller must NOT forward those events to the stream (no double-fire).
 * Edge strips (left/right/bottom) are reserved for gestures; the rest of the screen is
 * normal cursor/trackpad input.
 *
 * This is a table — add rows in {@link Host} + the state machine. Current rows:
 *   edge-swipe (L/R)  -> Host.onEdgeBack
 *   bottom-swipe up   -> Host.onBottomHome
 *   1-finger anchor + 2nd-finger drag -> Host.onAnchorDrag{Start,Move,End}
 * (3/4/5-finger taps already live in GameInputController — not duplicated here.)
 */
public class ArtemisGestureRecognizer {

    /** Host actions. Wire these to comrade (NvConnection) in the daemon. */
    public interface Host {
        /** Edge back-swipe committed. fromLeft=left edge (X1/back) vs right edge (X2/forward). */
        void onEdgeBack(boolean fromLeft);
        /** Bottom-up (home) swipe committed. */
        void onBottomHome();
        /** Anchor finger held + second finger started dragging: hold the modifier (e.g. Super). */
        void onAnchorDragStart();
        /** Second finger moved by (dx,dy) px while the anchor is held: relative mouse move. */
        void onAnchorDragMove(float dx, float dy);
        /** Anchor-drag ended: release the modifier. */
        void onAnchorDragEnd();
    }

    private enum Phase { NONE, WATCH_BACK, WATCH_HOME, ANCHOR_DRAG }

    private final Host host;
    private final View hapticView;
    private final int displayW, displayH;

    // Zone/threshold tunables (dp-scaled). Edge width ~ AOSP default sensitivity.
    private final float edgeWidthPx;    // L/R back zone
    private final float bottomHeightPx; // bottom home zone
    private final float commitPx;       // travel needed to commit
    private final float anchorSlopPx;   // max anchor movement to still count as "held"

    private Phase phase = Phase.NONE;
    private float downX, downY;
    private boolean fromLeft;
    private boolean committed;

    private int anchorPid = -1, dragPid = -1;
    private float anchorDownX, anchorDownY, lastDragX, lastDragY;

    public ArtemisGestureRecognizer(Host host, View hapticView, float density,
                                    int displayW, int displayH) {
        this.host = host;
        this.hapticView = hapticView;
        this.displayW = displayW;
        this.displayH = displayH;
        this.edgeWidthPx = 20f * density;
        this.bottomHeightPx = 24f * density;
        this.commitPx = 32f * density;
        this.anchorSlopPx = 12f * density;
    }

    /** @return true if consumed as a gesture (do NOT forward this event to the stream). */
    public boolean onTouch(MotionEvent ev) {
        if (host == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = ev.getX();
                downY = ev.getY();
                committed = false;
                anchorPid = ev.getPointerId(0);
                anchorDownX = downX;
                anchorDownY = downY;
                dragPid = -1;
                if (downX <= edgeWidthPx) { phase = Phase.WATCH_BACK; fromLeft = true; return true; }
                if (downX >= displayW - edgeWidthPx) { phase = Phase.WATCH_BACK; fromLeft = false; return true; }
                if (downY >= displayH - bottomHeightPx) { phase = Phase.WATCH_HOME; return true; }
                phase = Phase.NONE; // normal touch -> let the stream have it
                return false;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // Second finger while the first is still near its down point => anchor-drag.
                int ai = ev.findPointerIndex(anchorPid);
                boolean anchorHeld = ai >= 0
                        && Math.hypot(ev.getX(ai) - anchorDownX, ev.getY(ai) - anchorDownY) < anchorSlopPx;
                if (phase == Phase.NONE && anchorHeld) {
                    int idx = ev.getActionIndex();
                    dragPid = ev.getPointerId(idx);
                    lastDragX = ev.getX(idx);
                    lastDragY = ev.getY(idx);
                    phase = Phase.ANCHOR_DRAG;
                    host.onAnchorDragStart();
                    haptic();
                    return true;
                }
                // Edge back/home are single-finger; a second finger cancels them.
                if (phase == Phase.WATCH_BACK || phase == Phase.WATCH_HOME) phase = Phase.NONE;
                return phase == Phase.ANCHOR_DRAG;
            }

            case MotionEvent.ACTION_MOVE: {
                if (phase == Phase.WATCH_BACK && !committed) {
                    float dx = ev.getX() - downX;
                    if (Math.abs(dx) > commitPx && Math.abs(dx) > Math.abs(ev.getY() - downY)) {
                        committed = true;
                        host.onEdgeBack(fromLeft);
                        haptic();
                    }
                    return true;
                }
                if (phase == Phase.WATCH_HOME && !committed) {
                    float dyUp = downY - ev.getY();
                    if (dyUp > commitPx && dyUp > Math.abs(ev.getX() - downX)) {
                        committed = true;
                        host.onBottomHome();
                        haptic();
                    }
                    return true;
                }
                if (phase == Phase.ANCHOR_DRAG) {
                    int di = ev.findPointerIndex(dragPid);
                    if (di >= 0) {
                        float x = ev.getX(di), y = ev.getY(di);
                        host.onAnchorDragMove(x - lastDragX, y - lastDragY);
                        lastDragX = x;
                        lastDragY = y;
                    }
                    return true;
                }
                return phase != Phase.NONE;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (phase == Phase.ANCHOR_DRAG && ev.getPointerId(ev.getActionIndex()) == dragPid) {
                    host.onAnchorDragEnd();
                    phase = Phase.NONE;
                }
                return phase != Phase.NONE;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean consumed = phase != Phase.NONE;
                if (phase == Phase.ANCHOR_DRAG) host.onAnchorDragEnd();
                phase = Phase.NONE;
                return consumed;
            }
        }
        return phase != Phase.NONE;
    }

    private void haptic() {
        if (hapticView != null) {
            hapticView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
        }
    }
}
