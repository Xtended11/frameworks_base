/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.ScreenDecorations.DisplayCutoutView.boundsFromDirection;

import static java.lang.Float.isNaN;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.EventLog;
import android.util.Pair;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.util.leak.RotationUtils;
import android.util.TypedValue;
import com.android.systemui.tuner.TunerService;
import android.provider.Settings;

import java.util.function.Consumer;
import java.util.Objects;

public class PhoneStatusBarView extends PanelBar implements Callbacks, TunerService.Tunable {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = StatusBar.DEBUG;
    private static final boolean DEBUG_GESTURES = false;
    private final CommandQueue mCommandQueue;
	
	private static final String LEFT_PADDING =
            "system:" + Settings.System.LEFT_PADDING;     
    private static final String RIGHT_PADDING =
            "system:" + Settings.System.RIGHT_PADDING;

    private int mBasePaddingBottom;
    private int mLeftPad;
    private int mRightPad;
    private int sbPaddingStartRes;
    private int sbPaddingEndRes;
    private int mBasePaddingTop;

    private ViewGroup mStatusBarContents;

    StatusBar mBar;

    boolean mIsFullyOpenedPanel = false;
    private ScrimController mScrimController;
    private float mMinFraction;
    private Runnable mHideExpandedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPanelFraction == 0.0f) {
                mBar.makeExpandedInvisible();
            }
        }
    };
    private DarkReceiver mBattery;
    private int mRotationOrientation = -1;
    private RotationButtonController mRotationButtonController;
    @Nullable
    private View mCenterIconSpace;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;
    private int mStatusBarHeight;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;
    private boolean mHeadsUpVisible;

    private int mRoundedCornerPadding = 0;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mCommandQueue = Dependency.get(CommandQueue.class);

        // Only create FRB here if there is no navbar
        if (!hasNavigationBar()) {
            final FloatingRotationButton floatingRotationButton =
                    new FloatingRotationButton(context);
            mRotationButtonController = new RotationButtonController(context,
                    R.style.RotateButtonCCWStart90, floatingRotationButton,
                    mRotationButtonListener);
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        if (mRotationButtonController != null && !hasNavigationBar()) {
            final int winRotation = getDisplay().getRotation();
            mRotationButtonController.onRotationProposal(rotation, winRotation, isValid);
        }
    }

    private final Consumer<Boolean> mRotationButtonListener = (visible) -> {};

    private boolean hasNavigationBar() {
        try {
            IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
            return windowManager.hasNavigationBar(Display.DEFAULT_DISPLAY);
        } catch (RemoteException ex) {}
        return false;
    }

    public void setBar(StatusBar bar) {
        mBar = bar;
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
    }

    public void swiftStatusBarItems(int horizontalShift, int verticalShift) {
        if (mStatusBarContents == null) {
            return;
        }

        mStatusBarContents.setPaddingRelative(mLeftPad + horizontalShift,
                                              mBasePaddingTop + verticalShift,
                                              mRightPad + horizontalShift,
                                              mBasePaddingBottom - verticalShift);
        invalidate();
    }

    @Override
    public void onFinishInflate() {
        mBattery = findViewById(R.id.battery);
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mCenterIconSpace = findViewById(R.id.centered_icon_area);
        mStatusBarContents = (ViewGroup) findViewById(R.id.status_bar_contents);

        mBasePaddingTop = mStatusBarContents.getPaddingTop();
        mBasePaddingBottom = mStatusBarContents.getPaddingBottom();
		Dependency.get(TunerService.class).addTunable(this,
                LEFT_PADDING, RIGHT_PADDING);

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Always have Battery meters in the status bar observe the dark/light modes.
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);
        if (updateOrientationAndCutout()) {
            updateLayoutForCutout();
        }

        if (mRotationButtonController != null && !hasNavigationBar()) {
            mRotationButtonController.registerListeners();
            mCommandQueue.addCallback(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mBattery);
        mDisplayCutout = null;

        if (mRotationButtonController != null) {
            mRotationButtonController.unregisterListeners();
            mCommandQueue.removeCallback(this);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateOrientationAndCutout()) {
            updateLayoutForCutout();
            requestLayout();
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateOrientationAndCutout()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateOrientationAndCutout() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        if (!Objects.equals(getRootWindowInsets().getDisplayCutout(), mDisplayCutout)) {
            changed = true;
            mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        }

        return changed;
    }

    @Override
    public boolean panelEnabled() {
        return mCommandQueue.panelsEnabled();
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        mBar.makeExpandedVisible(false);
    }

    @Override
    public void onPanelCollapsed() {
        super.onPanelCollapsed();
        // Close the status bar in the next frame so we can show the end of the animation.
        post(mHideExpandedRunnable);
        mIsFullyOpenedPanel = false;
    }

    public void removePendingHideExpandedRunnables() {
        removeCallbacks(mHideExpandedRunnable);
    }

    @Override
    public void onPanelFullyOpened() {
        super.onPanelFullyOpened();
        if (!mIsFullyOpenedPanel) {
            mPanel.getView().sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mIsFullyOpenedPanel = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean barConsumedEvent = mBar.interceptTouchEvent(event);

        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_PANELBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        barConsumedEvent ? 1 : 0);
            }
        }

        return barConsumedEvent || super.onTouchEvent(event);
    }

    @Override
    public void onTrackingStarted() {
        super.onTrackingStarted();
        mBar.onTrackingStarted();
        mScrimController.onTrackingStarted();
        removePendingHideExpandedRunnables();
    }

    @Override
    public void onClosingFinished() {
        super.onClosingFinished();
        mBar.onClosingFinished();
    }

    @Override
    public void onTrackingStopped(boolean expand) {
        super.onTrackingStopped(expand);
        mBar.onTrackingStopped(expand);
    }

    @Override
    public void onExpandingFinished() {
        super.onExpandingFinished();
        mScrimController.onExpandingFinished();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelScrimMinFractionChanged(float minFraction) {
        if (isNaN(minFraction)) {
            throw new IllegalArgumentException("minFraction cannot be NaN");
        }
        if (mMinFraction != minFraction) {
            mMinFraction = minFraction;
            updateScrimFraction();
        }
    }

    @Override
    public void panelExpansionChanged(float frac, boolean expanded) {
        super.panelExpansionChanged(frac, expanded);
        updateScrimFraction();
        if ((frac == 0 || frac == 1) && mBar.getNavigationBarView() != null) {
            mBar.getNavigationBarView().onStatusBarPanelStateChanged();
        }
    }

    private void updateScrimFraction() {
        float scrimFraction = mPanelFraction;
        if (mMinFraction < 1.0f) {
            scrimFraction = Math.max((mPanelFraction - mMinFraction) / (1.0f - mMinFraction),
                    0);
        }
        mScrimController.setPanelExpansion(scrimFraction);
    }

    public void updateResources() {
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);
        mRoundedCornerPadding = getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);

        updateStatusBarHeight();
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = getResources().getDimensionPixelSize(R.dimen.status_bar_height);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;

        float density = Resources.getSystem().getDisplayMetrics().density;
        Resources res = null;
        try {
            res = mContext.getPackageManager().getResourcesForApplication("com.android.systemui");
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }

        int statusBarPaddingTop = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_top);
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);
        int statusBarPaddingEnd = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_end);
        sbPaddingStartRes = (int) (statusBarPaddingStart / density);
        sbPaddingEndRes = (int) (statusBarPaddingEnd / density);

        View sbContents = findViewById(R.id.status_bar_contents);
        sbContents.setPaddingRelative(
                (int) mLeftPad,
                statusBarPaddingTop,
                (int) mRightPad,
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, (int) mLeftPad, 0, 0);

        setLayoutParams(layoutParams);
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation(StatusBarWindowView.cornerCutoutMargins(mDisplayCutout, getDisplay()));
        updateSafeInsets(StatusBarWindowView.statusBarCornerCutoutMargins(mDisplayCutout,
                getDisplay(), mRotationOrientation, mStatusBarHeight));
    }

    private void updateCutoutLocation(Pair<Integer, Integer> cornerCutoutMargins) {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || cornerCutoutMargins != null) {
            mCenterIconSpace.setVisibility(View.VISIBLE);
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCenterIconSpace.setVisibility(View.GONE);
        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = new Rect();
        boundsFromDirection(mDisplayCutout, Gravity.TOP, bounds);

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
    }

    private void updateSafeInsets(Pair<Integer, Integer> cornerCutoutMargins) {
        // Depending on our rotation, we may have to work around a cutout in the middle of the view,
        // or letterboxing from the right or left sides.

        Pair<Integer, Integer> padding =
                StatusBarWindowView.paddingNeededForCutoutAndRoundedCorner(
                        mDisplayCutout, cornerCutoutMargins, mRoundedCornerPadding);

        setPadding(padding.first, getPaddingTop(), padding.second, getPaddingBottom());

        // Apply negative paddings to center clock layout so that we'll actually be on the center.
        final int winRotation = getDisplay().getRotation();
        LayoutParams centerClockParams =
                (LayoutParams) findViewById(R.id.center_clock_layout).getLayoutParams();
        centerClockParams.leftMargin = winRotation == Surface.ROTATION_0 ? -padding.first : 0;
        centerClockParams.rightMargin = winRotation == Surface.ROTATION_0 ? -padding.second : 0;
    }

    public void setHeadsUpVisible(boolean headsUpVisible) {
        mHeadsUpVisible = headsUpVisible;
        updateVisibility();
    }

    @Override
    protected boolean shouldPanelBeVisible() {
        return mHeadsUpVisible || super.shouldPanelBeVisible();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (LEFT_PADDING.equals(key)) {
            int mLPadding = TunerService.parseInteger(newValue, sbPaddingStartRes);
            mLeftPad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, mLPadding,
                getResources().getDisplayMetrics()));
            updateStatusBarHeight();
        } else if (RIGHT_PADDING.equals(key)) {
            int mRPadding = TunerService.parseInteger(newValue, sbPaddingEndRes);
            mRightPad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, mRPadding,
                getResources().getDisplayMetrics()));
            updateStatusBarHeight();
        }
    }
}
