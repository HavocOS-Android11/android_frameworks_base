/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.pip.phone;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.PIP_STASHING;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.phone.PipMenuActivityController.MENU_STATE_CLOSE;
import static com.android.wm.shell.pip.phone.PipMenuActivityController.MENU_STATE_FULL;
import static com.android.wm.shell.pip.phone.PipMenuActivityController.MENU_STATE_NONE;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.Size;
import android.view.IPinnedStackController;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipBoundsHandler;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;

import java.io.PrintWriter;

/**
 * Manages all the touch handling for PIP on the Phone, including moving, dismissing and expanding
 * the PIP.
 */
public class PipTouchHandler {
    private static final String TAG = "PipTouchHandler";

    /** Duration of the dismiss scrim fading in/out. */
    private static final int DISMISS_TRANSITION_DURATION_MS = 200;

    /* The multiplier to apply scale the target size by when applying the magnetic field radius */
    private static final float MAGNETIC_FIELD_RADIUS_MULTIPLIER = 1.25f;

    // Allow PIP to resize to a slightly bigger state upon touch
    private final boolean mEnableResize;
    private final Context mContext;
    private final PipBoundsHandler mPipBoundsHandler;
    private final @NonNull PipBoundsState mPipBoundsState;
    private final PipUiEventLogger mPipUiEventLogger;
    private final PipDismissTargetHandler mPipDismissTargetHandler;

    private PipResizeGestureHandler mPipResizeGestureHandler;
    private IPinnedStackController mPinnedStackController;

    private final PipMenuActivityController mMenuController;
    private final AccessibilityManager mAccessibilityManager;
    private boolean mShowPipMenuOnAnimationEnd = false;

    /**
     * Whether PIP stash is enabled or not. When enabled, if at the time of fling-release the
     * PIP bounds is outside the left/right edge of the screen, it will be shown in "stashed" mode,
     * where PIP will only show partially.
     */
    private boolean mEnableStash = false;

    // The current movement bounds
    private Rect mMovementBounds = new Rect();

    // The reference inset bounds, used to determine the dismiss fraction
    private Rect mInsetBounds = new Rect();
    // The reference bounds used to calculate the normal/expanded target bounds
    private Rect mNormalBounds = new Rect();
    @VisibleForTesting public Rect mNormalMovementBounds = new Rect();
    private Rect mExpandedBounds = new Rect();
    @VisibleForTesting public Rect mExpandedMovementBounds = new Rect();
    private int mExpandedShortestEdgeSize;

    // Used to workaround an issue where the WM rotation happens before we are notified, allowing
    // us to send stale bounds
    private int mDeferResizeToNormalBoundsUntilRotation = -1;
    private int mDisplayRotation;

    private Handler mHandler = new Handler();

    // Behaviour states
    private int mMenuState = MENU_STATE_NONE;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private int mImeOffset;
    private boolean mIsShelfShowing;
    private int mShelfHeight;
    private int mMovementBoundsExtraOffsets;
    private int mBottomOffsetBufferPx;
    private float mSavedSnapFraction = -1f;
    private boolean mSendingHoverAccessibilityEvents;
    private boolean mMovementWithinDismiss;
    private PipAccessibilityInteractionConnection mConnection;

    // Touch state
    private final PipTouchState mTouchState;
    private final FloatingContentCoordinator mFloatingContentCoordinator;
    private PipMotionHelper mMotionHelper;
    private PipTouchGesture mGesture;

    // Temp vars
    private final Rect mTmpBounds = new Rect();

    /**
     * A listener for the PIP menu activity.
     */
    private class PipMenuListener implements PipMenuActivityController.Listener {
        @Override
        public void onPipMenuStateChanged(int menuState, boolean resize, Runnable callback) {
            setMenuState(menuState, resize, callback);
        }

        @Override
        public void onPipExpand() {
            mMotionHelper.expandLeavePip();
        }

        @Override
        public void onPipDismiss() {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_TAP_TO_REMOVE);
            mTouchState.removeDoubleTapTimeoutCallback();
            mMotionHelper.dismissPip();
        }

        @Override
        public void onPipShowMenu() {
            mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                    true /* allowMenuTimeout */, willResizeMenu(), shouldShowResizeHandle());
        }
    }

    @SuppressLint("InflateParams")
    public PipTouchHandler(Context context,
            PipMenuActivityController menuController,
            PipBoundsHandler pipBoundsHandler,
            @NonNull PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger) {
        // Initialize the Pip input consumer
        mContext = context;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mPipBoundsHandler = pipBoundsHandler;
        mPipBoundsState = pipBoundsState;
        mMenuController = menuController;
        mMenuController.addListener(new PipMenuListener());
        mGesture = new DefaultPipTouchGesture();
        mMotionHelper = new PipMotionHelper(mContext, pipBoundsState, pipTaskOrganizer,
                mMenuController, mPipBoundsHandler.getSnapAlgorithm(), floatingContentCoordinator);
        mPipResizeGestureHandler =
                new PipResizeGestureHandler(context, pipBoundsHandler, mMotionHelper,
                        pipTaskOrganizer, this::getMovementBounds,
                        this::updateMovementBounds, pipUiEventLogger, menuController);
        mPipDismissTargetHandler = new PipDismissTargetHandler(context, pipUiEventLogger,
                mMotionHelper, mHandler);
        mTouchState = new PipTouchState(ViewConfiguration.get(context), mHandler,
                () -> mMenuController.showMenuWithDelay(MENU_STATE_FULL, mMotionHelper.getBounds(),
                        true /* allowMenuTimeout */, willResizeMenu(), shouldShowResizeHandle()),
                menuController::hideMenu);

        Resources res = context.getResources();
        mEnableResize = res.getBoolean(R.bool.config_pipEnableResizeForMenu);
        reloadResources();

        mFloatingContentCoordinator = floatingContentCoordinator;
        mConnection = new PipAccessibilityInteractionConnection(mContext, pipBoundsState,
                mMotionHelper, pipTaskOrganizer, mPipBoundsHandler.getSnapAlgorithm(),
                this::onAccessibilityShowMenu, this::updateMovementBounds, mHandler);

        mPipUiEventLogger = pipUiEventLogger;

        mEnableStash = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                PIP_STASHING,
                /* defaultValue = */ false);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                context.getMainExecutor(),
                properties -> {
                    if (properties.getKeyset().contains(PIP_STASHING)) {
                        mEnableStash = properties.getBoolean(
                                PIP_STASHING, /* defaultValue = */ false);
                    }
                });
    }

    private void reloadResources() {
        final Resources res = mContext.getResources();
        mBottomOffsetBufferPx = res.getDimensionPixelSize(R.dimen.pip_bottom_offset_buffer);
        mExpandedShortestEdgeSize = res.getDimensionPixelSize(
                R.dimen.pip_expanded_shortest_edge_size);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);
        mPipDismissTargetHandler.updateMagneticTargetSize();
    }

    private boolean shouldShowResizeHandle() {
        return false;
    }

    public void setTouchGesture(PipTouchGesture gesture) {
        mGesture = gesture;
    }

    public void setTouchEnabled(boolean enabled) {
        mTouchState.setAllowTouches(enabled);
    }

    public void showPictureInPictureMenu() {
        // Only show the menu if the user isn't currently interacting with the PiP
        if (!mTouchState.isUserInteracting()) {
            mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                    false /* allowMenuTimeout */, willResizeMenu(),
                    shouldShowResizeHandle());
        }
    }

    public void onActivityPinned() {
        mPipDismissTargetHandler.createOrUpdateDismissTarget();

        mShowPipMenuOnAnimationEnd = true;
        mPipResizeGestureHandler.onActivityPinned();
        mFloatingContentCoordinator.onContentAdded(mMotionHelper);
    }

    public void onActivityUnpinned(ComponentName topPipActivity) {
        if (topPipActivity == null) {
            // Clean up state after the last PiP activity is removed
            mPipDismissTargetHandler.cleanUpDismissTarget();

            mFloatingContentCoordinator.onContentRemoved(mMotionHelper);
        }
        mPipResizeGestureHandler.onActivityUnpinned();
    }

    public void onPinnedStackAnimationEnded(
            @PipAnimationController.TransitionDirection int direction) {
        // Always synchronize the motion helper bounds once PiP animations finish
        mMotionHelper.synchronizePinnedStackBounds();
        updateMovementBounds();
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            // Set the initial bounds as the user resize bounds.
            mPipResizeGestureHandler.setUserResizeBounds(mMotionHelper.getBounds());
        }

        if (mShowPipMenuOnAnimationEnd) {
            mMenuController.showMenu(MENU_STATE_CLOSE, mMotionHelper.getBounds(),
                    true /* allowMenuTimeout */, false /* willResizeMenu */,
                    shouldShowResizeHandle());
            mShowPipMenuOnAnimationEnd = false;
        }
    }

    public void onConfigurationChanged() {
        mPipResizeGestureHandler.onConfigurationChanged();
        mMotionHelper.synchronizePinnedStackBounds();
        reloadResources();

        // Recreate the dismiss target for the new orientation.
        mPipDismissTargetHandler.createOrUpdateDismissTarget();
    }

    public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        mIsImeShowing = imeVisible;
        mImeHeight = imeHeight;
    }

    public void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight) {
        mIsShelfShowing = shelfVisible;
        mShelfHeight = shelfHeight;
    }

    /**
     * Called when SysUI state changed.
     *
     * @param isSysUiStateValid Is SysUI valid or not.
     */
    public void onSystemUiStateChanged(boolean isSysUiStateValid) {
        mPipResizeGestureHandler.onSystemUiStateChanged(isSysUiStateValid);
    }

    public void adjustBoundsForRotation(Rect outBounds, Rect curBounds, Rect insetBounds) {
        final Rect toMovementBounds = new Rect();
        mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(outBounds, insetBounds,
                toMovementBounds, 0);
        final int prevBottom = mMovementBounds.bottom - mMovementBoundsExtraOffsets;
        if ((prevBottom - mBottomOffsetBufferPx) <= curBounds.top) {
            outBounds.offsetTo(outBounds.left, toMovementBounds.bottom);
        }
    }

    /**
     * Responds to IPinnedStackListener on resetting aspect ratio for the pinned window.
     */
    public void onAspectRatioChanged() {
        mPipResizeGestureHandler.invalidateUserResizeBounds();
    }

    public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds, Rect curBounds,
            boolean fromImeAdjustment, boolean fromShelfAdjustment, int displayRotation) {
        // Set the user resized bounds equal to the new normal bounds in case they were
        // invalidated (e.g. by an aspect ratio change).
        if (mPipResizeGestureHandler.getUserResizeBounds().isEmpty()) {
            mPipResizeGestureHandler.setUserResizeBounds(normalBounds);
        }

        final int bottomOffset = mIsImeShowing ? mImeHeight : 0;
        final boolean fromDisplayRotationChanged = (mDisplayRotation != displayRotation);
        if (fromDisplayRotationChanged) {
            mTouchState.reset();
        }

        // Re-calculate the expanded bounds
        mNormalBounds.set(normalBounds);
        Rect normalMovementBounds = new Rect();
        mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(mNormalBounds, insetBounds,
                normalMovementBounds, bottomOffset);

        if (mMovementBounds.isEmpty()) {
            // mMovementBounds is not initialized yet and a clean movement bounds without
            // bottom offset shall be used later in this function.
            mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(curBounds, insetBounds,
                    mMovementBounds, 0 /* bottomOffset */);
        }

        // Calculate the expanded size
        float aspectRatio = (float) normalBounds.width() / normalBounds.height();
        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        Size expandedSize = mPipBoundsHandler.getSnapAlgorithm().getSizeForAspectRatio(aspectRatio,
                mExpandedShortestEdgeSize, displaySize.x, displaySize.y);
        mExpandedBounds.set(0, 0, expandedSize.getWidth(), expandedSize.getHeight());
        Rect expandedMovementBounds = new Rect();
        mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(mExpandedBounds, insetBounds,
                expandedMovementBounds, bottomOffset);

        mPipResizeGestureHandler.updateMinSize(mNormalBounds.width(), mNormalBounds.height());
        mPipResizeGestureHandler.updateMaxSize(mExpandedBounds.width(), mExpandedBounds.height());

        // The extra offset does not really affect the movement bounds, but are applied based on the
        // current state (ime showing, or shelf offset) when we need to actually shift
        int extraOffset = Math.max(
                mIsImeShowing ? mImeOffset : 0,
                !mIsImeShowing && mIsShelfShowing ? mShelfHeight : 0);

        // If this is from an IME or shelf adjustment, then we should move the PiP so that it is not
        // occluded by the IME or shelf.
        if (fromImeAdjustment || fromShelfAdjustment) {
            if (mTouchState.isUserInteracting()) {
                // Defer the update of the current movement bounds until after the user finishes
                // touching the screen
            } else {
                final boolean isExpanded = mMenuState == MENU_STATE_FULL && willResizeMenu();
                final Rect toMovementBounds = new Rect();
                mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(curBounds, insetBounds,
                        toMovementBounds, mIsImeShowing ? mImeHeight : 0);
                final int prevBottom = mMovementBounds.bottom - mMovementBoundsExtraOffsets;
                // This is to handle landscape fullscreen IMEs, don't apply the extra offset in this
                // case
                final int toBottom = toMovementBounds.bottom < toMovementBounds.top
                        ? toMovementBounds.bottom
                        : toMovementBounds.bottom - extraOffset;

                if (isExpanded) {
                    curBounds.set(mExpandedBounds);
                    mPipBoundsHandler.getSnapAlgorithm().applySnapFraction(curBounds,
                            toMovementBounds, mSavedSnapFraction);
                }

                if (prevBottom < toBottom) {
                    // The movement bounds are expanding
                    if (curBounds.top > prevBottom - mBottomOffsetBufferPx) {
                        mMotionHelper.animateToOffset(curBounds, toBottom - curBounds.top);
                    }
                } else if (prevBottom > toBottom) {
                    // The movement bounds are shrinking
                    if (curBounds.top > toBottom - mBottomOffsetBufferPx) {
                        mMotionHelper.animateToOffset(curBounds, toBottom - curBounds.top);
                    }
                }
            }
        }

        // Update the movement bounds after doing the calculations based on the old movement bounds
        // above
        mNormalMovementBounds.set(normalMovementBounds);
        mExpandedMovementBounds.set(expandedMovementBounds);
        mDisplayRotation = displayRotation;
        mInsetBounds.set(insetBounds);
        updateMovementBounds();
        mMovementBoundsExtraOffsets = extraOffset;
        mConnection.onMovementBoundsChanged(mNormalBounds, mExpandedBounds, mNormalMovementBounds,
                mExpandedMovementBounds);

        // If we have a deferred resize, apply it now
        if (mDeferResizeToNormalBoundsUntilRotation == displayRotation) {
            mMotionHelper.animateToUnexpandedState(normalBounds, mSavedSnapFraction,
                    mNormalMovementBounds, mMovementBounds, true /* immediate */);
            mSavedSnapFraction = -1f;
            mDeferResizeToNormalBoundsUntilRotation = -1;
        }
    }

    /**
     * TODO Add appropriate description
     */
    public void onRegistrationChanged(boolean isRegistered) {
        mAccessibilityManager.setPictureInPictureActionReplacingConnection(isRegistered
                ? mConnection : null);
        if (!isRegistered && mTouchState.isUserInteracting()) {
            // If the input consumer is unregistered while the user is interacting, then we may not
            // get the final TOUCH_UP event, so clean up the dismiss target as well
            mPipDismissTargetHandler.cleanUpDismissTarget();
        }
    }

    private void onAccessibilityShowMenu() {
        mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                true /* allowMenuTimeout */, willResizeMenu(),
                shouldShowResizeHandle());
    }

    /**
     * TODO Add appropriate description
     */
    public boolean handleTouchEvent(InputEvent inputEvent) {
        // Skip any non motion events
        if (!(inputEvent instanceof MotionEvent)) {
            return true;
        }
        // Skip touch handling until we are bound to the controller
        if (mPinnedStackController == null) {
            return true;
        }

        MotionEvent ev = (MotionEvent) inputEvent;
        if (mPipResizeGestureHandler.willStartResizeGesture(ev)) {
            // Initialize the touch state for the gesture, but immediately reset to invalidate the
            // gesture
            mTouchState.onTouchEvent(ev);
            mTouchState.reset();
            return true;
        }

        if ((ev.getAction() == MotionEvent.ACTION_DOWN || mTouchState.isUserInteracting())
                && mPipDismissTargetHandler.maybeConsumeMotionEvent(ev)) {
            // If the first touch event occurs within the magnetic field, pass the ACTION_DOWN event
            // to the touch state. Touch state needs a DOWN event in order to later process MOVE
            // events it'll receive if the object is dragged out of the magnetic field.
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                mTouchState.onTouchEvent(ev);
            }

            // Continue tracking velocity when the object is in the magnetic field, since we want to
            // respect touch input velocity if the object is dragged out and then flung.
            mTouchState.addMovementToVelocityTracker(ev);

            return true;
        }

        // Update the touch state
        mTouchState.onTouchEvent(ev);

        boolean shouldDeliverToMenu = mMenuState != MENU_STATE_NONE;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                mGesture.onDown(mTouchState);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mGesture.onMove(mTouchState)) {
                    break;
                }

                shouldDeliverToMenu = !mTouchState.isDragging();
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Update the movement bounds again if the state has changed since the user started
                // dragging (ie. when the IME shows)
                updateMovementBounds();

                if (mGesture.onUp(mTouchState)) {
                    break;
                }

                // Fall through to clean up
            }
            case MotionEvent.ACTION_CANCEL: {
                shouldDeliverToMenu = !mTouchState.startedDragging() && !mTouchState.isDragging();
                mTouchState.reset();
                break;
            }
            case MotionEvent.ACTION_HOVER_ENTER:
                // If Touch Exploration is enabled, some a11y services (e.g. Talkback) is probably
                // on and changing MotionEvents into HoverEvents.
                // Let's not enable menu show/hide for a11y services.
                if (!mAccessibilityManager.isTouchExplorationEnabled()) {
                    mTouchState.removeHoverExitTimeoutCallback();
                    mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                            false /* allowMenuTimeout */, false /* willResizeMenu */,
                            shouldShowResizeHandle());
                }
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (!shouldDeliverToMenu && !mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
                    mSendingHoverAccessibilityEvents = true;
                }
                break;
            }
            case MotionEvent.ACTION_HOVER_EXIT: {
                // If Touch Exploration is enabled, some a11y services (e.g. Talkback) is probably
                // on and changing MotionEvents into HoverEvents.
                // Let's not enable menu show/hide for a11y services.
                if (!mAccessibilityManager.isTouchExplorationEnabled()) {
                    mTouchState.scheduleHoverExitTimeoutCallback();
                }
                if (!shouldDeliverToMenu && mSendingHoverAccessibilityEvents) {
                    sendAccessibilityHoverEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    mSendingHoverAccessibilityEvents = false;
                }
                break;
            }
        }

        // Deliver the event to PipMenuActivity to handle button click if the menu has shown.
        if (shouldDeliverToMenu) {
            final MotionEvent cloneEvent = MotionEvent.obtain(ev);
            // Send the cancel event and cancel menu timeout if it starts to drag.
            if (mTouchState.startedDragging()) {
                cloneEvent.setAction(MotionEvent.ACTION_CANCEL);
                mMenuController.pokeMenu();
            }

            mMenuController.handlePointerEvent(cloneEvent);
        }

        return true;
    }

    private void sendAccessibilityHoverEvent(int type) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }

        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setImportantForAccessibility(true);
        event.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID);
        event.setWindowId(
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Sets the controller to update the system of changes from user interaction.
     */
    void setPinnedStackController(IPinnedStackController controller) {
        mPinnedStackController = controller;
    }

    /**
     * Sets the menu visibility.
     */
    private void setMenuState(int menuState, boolean resize, Runnable callback) {
        if (mMenuState == menuState && !resize) {
            return;
        }

        if (menuState == MENU_STATE_FULL && mMenuState != MENU_STATE_FULL) {
            // Save the current snap fraction and if we do not drag or move the PiP, then
            // we store back to this snap fraction.  Otherwise, we'll reset the snap
            // fraction and snap to the closest edge.
            if (resize) {
                animateToExpandedState(callback);
            }
        } else if (menuState == MENU_STATE_NONE && mMenuState == MENU_STATE_FULL) {
            // Try and restore the PiP to the closest edge, using the saved snap fraction
            // if possible
            if (resize) {
                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    // This is a very special case: when the menu is expanded and visible,
                    // navigating to another activity can trigger auto-enter PiP, and if the
                    // revealed activity has a forced rotation set, then the controller will get
                    // updated with the new rotation of the display. However, at the same time,
                    // SystemUI will try to hide the menu by creating an animation to the normal
                    // bounds which are now stale.  In such a case we defer the animation to the
                    // normal bounds until after the next onMovementBoundsChanged() call to get the
                    // bounds in the new orientation
                    try {
                        int displayRotation = mPinnedStackController.getDisplayRotation();
                        if (mDisplayRotation != displayRotation) {
                            mDeferResizeToNormalBoundsUntilRotation = displayRotation;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Could not get display rotation from controller");
                    }
                }

                if (mDeferResizeToNormalBoundsUntilRotation == -1) {
                    animateToUnexpandedState(getUserResizeBounds());
                }
            } else {
                mSavedSnapFraction = -1f;
            }
        }
        mMenuState = menuState;
        updateMovementBounds();
        // If pip menu has dismissed, we should register the A11y ActionReplacingConnection for pip
        // as well, or it can't handle a11y focus and pip menu can't perform any action.
        onRegistrationChanged(menuState == MENU_STATE_NONE);
        if (menuState == MENU_STATE_NONE) {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_HIDE_MENU);
        } else if (menuState == MENU_STATE_FULL) {
            mPipUiEventLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_SHOW_MENU);
        }
    }

    private void animateToExpandedState(Runnable callback) {
        Rect expandedBounds = new Rect(mExpandedBounds);
        mSavedSnapFraction = mMotionHelper.animateToExpandedState(expandedBounds,
                mMovementBounds, mExpandedMovementBounds, callback);
    }

    private void animateToUnexpandedState(Rect restoreBounds) {
        Rect restoredMovementBounds = new Rect();
        mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(restoreBounds,
                mInsetBounds, restoredMovementBounds, mIsImeShowing ? mImeHeight : 0);
        mMotionHelper.animateToUnexpandedState(restoreBounds, mSavedSnapFraction,
                restoredMovementBounds, mMovementBounds, false /* immediate */);
        mSavedSnapFraction = -1f;
    }

    /**
     * @return the motion helper.
     */
    public PipMotionHelper getMotionHelper() {
        return mMotionHelper;
    }

    @VisibleForTesting
    public PipResizeGestureHandler getPipResizeGestureHandler() {
        return mPipResizeGestureHandler;
    }

    @VisibleForTesting
    public void setPipResizeGestureHandler(PipResizeGestureHandler pipResizeGestureHandler) {
        mPipResizeGestureHandler = pipResizeGestureHandler;
    }

    @VisibleForTesting
    public void setPipMotionHelper(PipMotionHelper pipMotionHelper) {
        mMotionHelper = pipMotionHelper;
    }

    /**
     * @return the unexpanded bounds.
     */
    public Rect getNormalBounds() {
        return mNormalBounds;
    }

    Rect getUserResizeBounds() {
        return mPipResizeGestureHandler.getUserResizeBounds();
    }

    /**
     * Gesture controlling normal movement of the PIP.
     */
    private class DefaultPipTouchGesture extends PipTouchGesture {
        private final Point mStartPosition = new Point();
        private final PointF mDelta = new PointF();
        private boolean mShouldHideMenuAfterFling;

        @Override
        public void onDown(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return;
            }

            Rect bounds = mMotionHelper.getPossiblyAnimatingBounds();
            mDelta.set(0f, 0f);
            mStartPosition.set(bounds.left, bounds.top);
            mMovementWithinDismiss = touchState.getDownTouchPosition().y >= mMovementBounds.bottom;
            mMotionHelper.setSpringingToTouch(false);

            // If the menu is still visible then just poke the menu
            // so that it will timeout after the user stops touching it
            if (mMenuState != MENU_STATE_NONE) {
                mMenuController.pokeMenu();
            }
        }

        @Override
        public boolean onMove(PipTouchState touchState) {
            if (!touchState.isUserInteracting()) {
                return false;
            }

            if (touchState.startedDragging()) {
                mSavedSnapFraction = -1f;
                mPipDismissTargetHandler.showDismissTargetMaybe();
            }

            if (touchState.isDragging()) {
                // Move the pinned stack freely
                final PointF lastDelta = touchState.getLastTouchDelta();
                float lastX = mStartPosition.x + mDelta.x;
                float lastY = mStartPosition.y + mDelta.y;
                float left = lastX + lastDelta.x;
                float top = lastY + lastDelta.y;

                // Add to the cumulative delta after bounding the position
                mDelta.x += left - lastX;
                mDelta.y += top - lastY;

                mTmpBounds.set(mMotionHelper.getPossiblyAnimatingBounds());
                mTmpBounds.offsetTo((int) left, (int) top);
                mMotionHelper.movePip(mTmpBounds, true /* isDragging */);

                final PointF curPos = touchState.getLastTouchPosition();
                if (mMovementWithinDismiss) {
                    // Track if movement remains near the bottom edge to identify swipe to dismiss
                    mMovementWithinDismiss = curPos.y >= mMovementBounds.bottom;
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onUp(PipTouchState touchState) {
            mPipDismissTargetHandler.hideDismissTargetMaybe();

            if (!touchState.isUserInteracting()) {
                return false;
            }

            final PointF vel = touchState.getVelocity();

            if (touchState.isDragging()) {
                if (mMenuState != MENU_STATE_NONE) {
                    // If the menu is still visible, then just poke the menu so that
                    // it will timeout after the user stops touching it
                    mMenuController.showMenu(mMenuState, mMotionHelper.getBounds(),
                            true /* allowMenuTimeout */, willResizeMenu(),
                            shouldShowResizeHandle());
                }
                mShouldHideMenuAfterFling = mMenuState == MENU_STATE_NONE;

                // Reset the touch state on up before the fling settles
                mTouchState.reset();
                final Rect animatingBounds = mMotionHelper.getPossiblyAnimatingBounds();
                // If User releases the PIP window while it's out of the display bounds, put
                // PIP into stashed mode.
                if (mEnableStash
                        && (animatingBounds.right > mPipBoundsState.getDisplayBounds().right
                        || animatingBounds.left < mPipBoundsState.getDisplayBounds().left)) {
                    mMotionHelper.stashToEdge(vel.x, vel.y, this::flingEndAction /* endAction */);
                } else {
                    mMotionHelper.flingToSnapTarget(vel.x, vel.y,
                            this::flingEndAction /* endAction */);
                }
            } else if (mTouchState.isDoubleTap()) {
                // If using pinch to zoom, double-tap functions as resizing between max/min size
                if (mPipResizeGestureHandler.isUsingPinchToZoom()) {
                    final boolean toExpand =
                            mMotionHelper.getBounds().width() < mExpandedBounds.width()
                            && mMotionHelper.getBounds().height() < mExpandedBounds.height();
                    mPipResizeGestureHandler.setUserResizeBounds(toExpand ? mExpandedBounds
                            : mNormalBounds);
                    if (toExpand) {
                        animateToExpandedState(null);
                    } else {
                        animateToUnexpandedState(mNormalBounds);
                    }
                } else {
                    // Expand to fullscreen if this is a double tap
                    // the PiP should be frozen until the transition ends
                    setTouchEnabled(false);
                    mMotionHelper.expandLeavePip();
                }
            } else if (mMenuState != MENU_STATE_FULL) {
                if (!mTouchState.isWaitingForDoubleTap()) {
                    // User has stalled long enough for this not to be a drag or a double tap, just
                    // expand the menu
                    mMenuController.showMenu(MENU_STATE_FULL, mMotionHelper.getBounds(),
                            true /* allowMenuTimeout */, willResizeMenu(),
                            shouldShowResizeHandle());
                } else {
                    // Next touch event _may_ be the second tap for the double-tap, schedule a
                    // fallback runnable to trigger the menu if no touch event occurs before the
                    // next tap
                    mTouchState.scheduleDoubleTapTimeoutCallback();
                }
            }
            return true;
        }

        private void flingEndAction() {
            if (mShouldHideMenuAfterFling) {
                // If the menu is not visible, then we can still be showing the activity for the
                // dismiss overlay, so just finish it after the animation completes
                mMenuController.hideMenu();
            }
        }
    }

    /**
     * Updates the current movement bounds based on whether the menu is currently visible and
     * resized.
     */
    private void updateMovementBounds() {
        mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(mMotionHelper.getBounds(),
                mInsetBounds, mMovementBounds, mIsImeShowing ? mImeHeight : 0);
        mMotionHelper.setCurrentMovementBounds(mMovementBounds);

        boolean isMenuExpanded = mMenuState == MENU_STATE_FULL;
        mPipBoundsHandler.setMinEdgeSize(
                isMenuExpanded && willResizeMenu() ? mExpandedShortestEdgeSize : 0);
    }

    private Rect getMovementBounds(Rect curBounds) {
        Rect movementBounds = new Rect();
        mPipBoundsHandler.getSnapAlgorithm().getMovementBounds(curBounds, mInsetBounds,
                movementBounds, mIsImeShowing ? mImeHeight : 0);
        return movementBounds;
    }

    /**
     * @return whether the menu will resize as a part of showing the full menu.
     */
    private boolean willResizeMenu() {
        if (!mEnableResize) {
            return false;
        }
        return mExpandedBounds.width() != mNormalBounds.width()
                || mExpandedBounds.height() != mNormalBounds.height();
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMovementBounds=" + mMovementBounds);
        pw.println(innerPrefix + "mNormalBounds=" + mNormalBounds);
        pw.println(innerPrefix + "mNormalMovementBounds=" + mNormalMovementBounds);
        pw.println(innerPrefix + "mExpandedBounds=" + mExpandedBounds);
        pw.println(innerPrefix + "mExpandedMovementBounds=" + mExpandedMovementBounds);
        pw.println(innerPrefix + "mMenuState=" + mMenuState);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mIsShelfShowing=" + mIsShelfShowing);
        pw.println(innerPrefix + "mShelfHeight=" + mShelfHeight);
        pw.println(innerPrefix + "mSavedSnapFraction=" + mSavedSnapFraction);
        pw.println(innerPrefix + "mMovementBoundsExtraOffsets=" + mMovementBoundsExtraOffsets);
        mPipBoundsHandler.dump(pw, innerPrefix);
        mTouchState.dump(pw, innerPrefix);
        mMotionHelper.dump(pw, innerPrefix);
        if (mPipResizeGestureHandler != null) {
            mPipResizeGestureHandler.dump(pw, innerPrefix);
        }
    }

}
