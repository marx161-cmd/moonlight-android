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
 *
 * Spread-corner tap (2026-09-01): one finger down in the top-right zone, a second in the
 * bottom-left zone (either order), both released quickly with no drag -> {@link
 * Host#onSpreadTap()}. Diagonally-opposite corners, not just "any two corners," so it can't
 * be hit by an accidental single-hand grip near one edge. Deliberately a TAP, not a drag --
 * unlike WINDOW_MOVE/click-drag it never calls onAnchorDragStart, so nothing is forwarded to
 * the host mid-gesture; it either fires once on a clean release or fires nothing at all.
 */
public class AnchorDragGestureRecognizer {

    public enum Action { WINDOW_MOVE, LEFT_CLICK_DRAG, RIGHT_CLICK_DRAG }
    private enum Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT }

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
        /** Fired once on a clean top-right + bottom-left spread tap (see class doc). */
        void onSpreadTap();
    }

    /** Minimum time the anchor finger must be down before a second finger touching down
     * counts as anchor-drag, so a normal fast two-finger gesture (pinch/scroll) -- where
     * both fingers land close together in time -- passes through untouched instead. */
    private static final long MIN_ANCHOR_HOLD_MS = 200;

    /** Anchor must land within this many px of a corner to trigger WINDOW_MOVE (top-left)
     * or count toward a spread tap (top-right/bottom-left) instead of a click-drag. */
    private static final float CORNER_ZONE_DP = 120f;

    /** Max total gesture duration (first finger down to last finger up) for a spread tap
     * to fire, mirroring Game.java's existing N-finger-tap thresholds. */
    private static final long SPREAD_TAP_THRESHOLD_MS = 300;

    private final Host host;
    private final View hapticView;
    private final float anchorSlopPx; // max finger movement to still count as "held"/"tap"
    private final float cornerZonePx;

    private boolean dragging;
    private boolean anchorInCorner;
    private Corner anchorCorner = Corner.NONE;
    private Action currentAction;
    private int anchorPid = -1, dragPid = -1, dragPid2 = -1;
    private float anchorDownX, anchorDownY, lastDragX, lastDragY;

    private boolean spreadTapPending;
    private int spreadSecondPid = -1;
    private float spreadSecondDownX, spreadSecondDownY;

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
                spreadTapPending = false;
                spreadSecondPid = -1;
                anchorPid = ev.getPointerId(0);
                anchorDownX = ev.getX();
                anchorDownY = ev.getY();
                anchorCorner = cornerAt(anchorDownX, anchorDownY);
                anchorInCorner = anchorCorner == Corner.TOP_LEFT;
                dragPid = -1;
                dragPid2 = -1;
                return false; // normal touch -> let the stream have it, same as always
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // Second finger while the first is still near its down point => anchor-drag
                // (or a spread tap, checked first below).
                int ai = ev.findPointerIndex(anchorPid);
                boolean anchorHeld = ai >= 0
                        && Math.hypot(ev.getX(ai) - anchorDownX, ev.getY(ai) - anchorDownY) < anchorSlopPx;

                // Spread-corner tap check runs BEFORE the anchor-drag hold-timer gate below,
                // and deliberately ignores it: a real two-finger tap lands both fingers
                // within a few tens of ms of each other, nowhere near MIN_ANCHOR_HOLD_MS
                // (200ms, tuned for the *staggered* hold-then-add-second-finger drag
                // gesture). Gating the spread tap on that timer would make it unreachable
                // for an actual simultaneous tap.
                if (!dragging && !spreadTapPending && anchorHeld) {
                    int idx = ev.getActionIndex();
                    float secondX = ev.getX(idx), secondY = ev.getY(idx);
                    Corner secondCorner = cornerAt(secondX, secondY);
                    boolean isSpread = (anchorCorner == Corner.TOP_RIGHT && secondCorner == Corner.BOTTOM_LEFT)
                            || (anchorCorner == Corner.BOTTOM_LEFT && secondCorner == Corner.TOP_RIGHT);
                    if (isSpread) {
                        // Tap candidate, not a drag: claim the gesture but don't start any
                        // mouse/keyboard action yet -- only onSpreadTap() on a clean release.
                        spreadTapPending = true;
                        spreadSecondPid = ev.getPointerId(idx);
                        spreadSecondDownX = secondX;
                        spreadSecondDownY = secondY;
                        return true;
                    }
                }

                boolean anchorHeldLongEnough =
                        (ev.getEventTime() - ev.getDownTime()) >= MIN_ANCHOR_HOLD_MS;
                if (!dragging && anchorHeld && anchorHeldLongEnough) {
                    int idx = ev.getActionIndex();
                    float secondX = ev.getX(idx), secondY = ev.getY(idx);
                    if (!anchorInCorner && host.isNativeTouchscreenModeActive()) {
                        // Native touchscreen mode: only the corner-anchored WINDOW_MOVE case is
                        // ours to claim. A real pinch/two-finger-scroll starts with this same
                        // "finger 1 held, finger 2 lands nearby" shape, so leave it alone and let
                        // it fall through to trySendTouchEvent() as real multi-touch.
                        return false;
                    }
                    dragPid = ev.getPointerId(idx);
                    lastDragX = secondX;
                    lastDragY = secondY;
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
                if (spreadTapPending) {
                    // Either finger drifting too far means this was a drag, not a tap --
                    // stop tracking it as a spread-tap candidate, but keep consuming the
                    // stream (already claimed at ACTION_POINTER_DOWN) rather than letting a
                    // partial event stream leak through mid-gesture.
                    int ai = ev.findPointerIndex(anchorPid);
                    int si = ev.findPointerIndex(spreadSecondPid);
                    boolean anchorMoved = ai >= 0
                            && Math.hypot(ev.getX(ai) - anchorDownX, ev.getY(ai) - anchorDownY) >= anchorSlopPx;
                    boolean secondMoved = si >= 0
                            && Math.hypot(ev.getX(si) - spreadSecondDownX, ev.getY(si) - spreadSecondDownY) >= anchorSlopPx;
                    if (anchorMoved || secondMoved) {
                        spreadTapPending = false;
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
                if (spreadTapPending) {
                    int upPid = ev.getPointerId(ev.getActionIndex());
                    if (upPid == anchorPid || upPid == spreadSecondPid) {
                        if ((ev.getEventTime() - ev.getDownTime()) <= SPREAD_TAP_THRESHOLD_MS) {
                            host.onSpreadTap();
                            haptic();
                        }
                        spreadTapPending = false;
                        spreadSecondPid = -1;
                    }
                }
                return dragging || spreadTapPending;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean consumed = dragging || spreadTapPending;
                if (dragging) host.onAnchorDragEnd(currentAction);
                dragging = false;
                currentAction = null;
                dragPid = -1;
                dragPid2 = -1;
                spreadTapPending = false;
                spreadSecondPid = -1;
                return consumed;
            }
        }
        return dragging;
    }

    private Corner cornerAt(float x, float y) {
        int w = hapticView != null ? hapticView.getWidth() : 0;
        int h = hapticView != null ? hapticView.getHeight() : 0;
        if (x <= cornerZonePx && y <= cornerZonePx) return Corner.TOP_LEFT;
        if (x >= w - cornerZonePx && y <= cornerZonePx) return Corner.TOP_RIGHT;
        if (x <= cornerZonePx && y >= h - cornerZonePx) return Corner.BOTTOM_LEFT;
        return Corner.NONE;
    }

    private void haptic() {
        if (hapticView != null) {
            hapticView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
        }
    }
}
