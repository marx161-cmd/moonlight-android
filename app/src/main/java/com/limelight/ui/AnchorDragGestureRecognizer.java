package com.limelight.ui;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * 1-finger-anchor + drag gesture, with the action chosen by where the anchor started and
 * how many fingers do the dragging:
 *  - Anchor down inside the top-left corner zone -> WINDOW_MOVE (hold-Super + relative
 *    mouse move -> i3 Mod+drag), regardless of drag finger count. This is the original
 *    single-purpose behavior, now gated to a specific corner instead of anywhere on
 *    screen (2026-08-24).
 *  - Anchor down anywhere else + 1 drag finger -> LEFT_CLICK_DRAG (click-and-hold-drag,
 *    mirrors how a normal touch-and-hold works on a real mouse).
 *  - A 2nd drag finger joining mid-gesture upgrades LEFT_CLICK_DRAG to RIGHT_CLICK_DRAG
 *    (release LEFT, press RIGHT), mirroring how the existing 2-finger tap already means
 *    right-click elsewhere in this app -- anchor + two fingers is the "hold" version of
 *    that same mapping.
 *
 * Native touchscreen passthrough mode ({@link Host#isNativeTouchscreenModeActive()}) is a
 * hard exception to the "anywhere else" click-drag case (2026-08-26): a real pinch (or
 * two-finger scroll) starts exactly like anchor-drag's own trigger -- finger 1 down, brief
 * pause, finger 2 down nearby -- so without this check every pinch got hijacked into a
 * click-drag and never reached the host as a real touch event. Corner-anchored WINDOW_MOVE
 * still works in this mode too, since a deliberate 120dp-corner grab isn't something a
 * normal pinch/scroll gesture would ever land on.
 *
 * Ported from the abandoned artemisd overlay daemon's ArtemisGestureRecognizer (commit
 * 7f4797ec, moonlight-noir branch) -- only the anchor-drag idea, since edge-back/
 * bottom-home already work natively here (stock Diana is a normal Activity, doesn't
 * suppress system gesture nav the way that overlay had to).
 *
 * Pre-filter: {@link #onTouch} returns true when it has taken the gesture, so the
 * caller must NOT also forward those events to the stream (no double-fire). A plain
 * single-finger touch is untouched -- returns false immediately, same as before this
 * existed.
 *
 * Requires the anchor finger to have been down for {@link #MIN_ANCHOR_HOLD_MS} before a
 * second finger touching down counts as anchor-drag (2026-08-24 fix). Without this, every
 * normal two-finger gesture (pinch-zoom, two-finger scroll) also starts with a
 * near-stationary first finger at the instant the second finger lands -- indistinguishable
 * from anchor-drag's own trigger condition -- so the recognizer was stealing ALL two-finger
 * input, not just the deliberate hold-then-add-second-finger case. Confirmed live: "blocks
 * half the normal gestures."
 */
public class AnchorDragGestureRecognizer {

    public enum Action { WINDOW_MOVE, LEFT_CLICK_DRAG, RIGHT_CLICK_DRAG }

    public interface Host {
        /** Anchor-drag started: begin holding the modifier/button for this action. */
        void onAnchorDragStart(Action action);
        /** Drag finger moved by (dx,dy) px while the anchor is held: relative mouse move. */
        void onAnchorDragMove(Action action, float dx, float dy);
        /** Anchor-drag ended (or upgraded to a different action): release the modifier/button. */
        void onAnchorDragEnd(Action action);
        /** True when touch events are being relayed to the host as real multi-touch (native
         * touchscreen mode) rather than translated to mouse input -- see the class doc. */
        boolean isNativeTouchscreenModeActive();
    }

    /** Minimum time the anchor finger must be down before a second finger touching down
     * counts as anchor-drag, so a normal fast two-finger gesture (pinch/scroll) -- where
     * both fingers land close together in time -- passes through untouched instead. */
    private static final long MIN_ANCHOR_HOLD_MS = 200;

    /** Anchor must land within this many px of the view's top-left (0,0) to trigger
     * WINDOW_MOVE instead of a click-drag. */
    private static final float CORNER_ZONE_DP = 120f;

    private final Host host;
    private final View hapticView;
    private final float anchorSlopPx; // max anchor movement to still count as "held"
    private final float cornerZonePx;

    private boolean dragging;
    private boolean anchorInCorner;
    private Action currentAction;
    private int anchorPid = -1, dragPid = -1, dragPid2 = -1;
    private float anchorDownX, anchorDownY, lastDragX, lastDragY;

    public AnchorDragGestureRecognizer(Host host, View hapticView, float density) {
        this.host = host;
        this.hapticView = hapticView;
        this.anchorSlopPx = 12f * density;
        this.cornerZonePx = CORNER_ZONE_DP * density;
    }

    /** @return true if consumed as a gesture (do NOT forward this event to the stream). */
    public boolean onTouch(MotionEvent ev) {
        if (host == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = false;
                currentAction = null;
                anchorPid = ev.getPointerId(0);
                anchorDownX = ev.getX();
                anchorDownY = ev.getY();
                anchorInCorner = anchorDownX <= cornerZonePx && anchorDownY <= cornerZonePx;
                dragPid = -1;
                dragPid2 = -1;
                return false; // normal touch -> let the stream have it, same as always
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // Second finger while the first is still near its down point => anchor-drag.
                int ai = ev.findPointerIndex(anchorPid);
                boolean anchorHeld = ai >= 0
                        && Math.hypot(ev.getX(ai) - anchorDownX, ev.getY(ai) - anchorDownY) < anchorSlopPx;
                boolean anchorHeldLongEnough =
                        (ev.getEventTime() - ev.getDownTime()) >= MIN_ANCHOR_HOLD_MS;
                if (!dragging && anchorHeld && anchorHeldLongEnough) {
                    if (!anchorInCorner && host.isNativeTouchscreenModeActive()) {
                        // Native touchscreen mode: only the corner-anchored WINDOW_MOVE case is
                        // ours to claim. A real pinch/two-finger-scroll starts with this same
                        // "finger 1 held, finger 2 lands nearby" shape, so leave it alone and let
                        // it fall through to trySendTouchEvent() as real multi-touch.
                        return false;
                    }
                    int idx = ev.getActionIndex();
                    dragPid = ev.getPointerId(idx);
                    lastDragX = ev.getX(idx);
                    lastDragY = ev.getY(idx);
                    dragging = true;
                    currentAction = anchorInCorner ? Action.WINDOW_MOVE : Action.LEFT_CLICK_DRAG;
                    host.onAnchorDragStart(currentAction);
                    haptic();
                    return true;
                }
                if (dragging && currentAction == Action.LEFT_CLICK_DRAG && dragPid2 == -1) {
                    // A second drag finger joined mid-gesture -> upgrade to right-click-hold.
                    dragPid2 = ev.getPointerId(ev.getActionIndex());
                    host.onAnchorDragEnd(currentAction);
                    currentAction = Action.RIGHT_CLICK_DRAG;
                    host.onAnchorDragStart(currentAction);
                    haptic();
                    return true;
                }
                return dragging;
            }

            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    int di = ev.findPointerIndex(dragPid);
                    if (di >= 0) {
                        float x = ev.getX(di), y = ev.getY(di);
                        host.onAnchorDragMove(currentAction, x - lastDragX, y - lastDragY);
                        lastDragX = x;
                        lastDragY = y;
                    }
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (dragging) {
                    int upPid = ev.getPointerId(ev.getActionIndex());
                    if (upPid == dragPid || upPid == dragPid2) {
                        host.onAnchorDragEnd(currentAction);
                        dragging = false;
                        currentAction = null;
                        dragPid = -1;
                        dragPid2 = -1;
                    }
                }
                return dragging;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean consumed = dragging;
                if (dragging) host.onAnchorDragEnd(currentAction);
                dragging = false;
                currentAction = null;
                dragPid = -1;
                dragPid2 = -1;
                return consumed;
            }
        }
        return dragging;
    }

    private void haptic() {
        if (hapticView != null) {
            hapticView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
        }
    }
}
