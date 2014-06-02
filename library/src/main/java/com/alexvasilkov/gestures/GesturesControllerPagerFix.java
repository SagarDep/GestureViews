package com.alexvasilkov.gestures;

import android.content.Context;
import android.graphics.Rect;
import android.support.v4.view.ViewPager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Allows cross movement between view controlled by this {@link GesturesController}
 * and it's parent {@link android.support.v4.view.ViewPager} by splitting scroll movement
 * between view and view pager
 */
public class GesturesControllerPagerFix extends GesturesController {

    private static boolean sIsGlobalMotionDetected;

    private final int mPagerSlop;

    private ViewPager mViewPager;

    private MotionEvent mTmpEvent;
    private boolean mIsScrollingViewPager;
    private boolean mIsSkipViewPager;
    private float mAccumulateScrollX, mAccumulateScrollY;
    private int mViewPagerX;

    private boolean mIsLocalMotionDetected;
    private boolean mSkipNonPrimaryPointers;
    private boolean mSkipViewPagerDrag;

    public GesturesControllerPagerFix(Context context, OnStateChangedListener listener) {
        super(context, listener);

        mPagerSlop = 2 * ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void fixViewPagerScroll(ViewPager pager) {
        mViewPager = pager;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (sIsGlobalMotionDetected && !mIsLocalMotionDetected) return false; // Not our event, skip it

        if (mViewPager == null) {
            return super.onTouch(view, event);
        } else {
            MotionEvent fixedEvent = handleTouch(event);
            return fixedEvent == null || super.onTouch(view, fixedEvent);
        }
    }

    @Override
    public boolean onDown(MotionEvent e) {
        mAccumulateScrollX = mAccumulateScrollY = 0f;
        mIsScrollingViewPager = false;
        mIsSkipViewPager = false;
        return super.onDown(e);
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (mViewPager == null) {
            return super.onScroll(e1, e2, distanceX, distanceY);
        } else {
            float fixedDistanceX = -scrollBy(-distanceX, -distanceY);
            // Skipping vertical movement if view pager is dragged
            float fixedDistanceY = mViewPagerX == 0 ? distanceY : 0f;

            return super.onScroll(e1, e2, fixedDistanceX, fixedDistanceY);
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        // Ignoring fling if view pager was dragged
        return mViewPagerX == 0 && super.onFling(e1, e2, velocityX, velocityY);
    }

    /**
     * Handles touch event and returns altered event to pass further
     */
    private MotionEvent handleTouch(MotionEvent event) {
        recycleTmpEvent();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sIsGlobalMotionDetected = mIsLocalMotionDetected = true;

                // Initializing view pager fake drag
                mViewPager.requestDisallowInterceptTouchEvent(true);
                mViewPager.beginFakeDrag();
                mViewPagerX = 0;

                return event;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                sIsGlobalMotionDetected = mIsLocalMotionDetected = false;

                // Ending view pager fake drag
                mViewPager.endFakeDrag();

                return event;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) { // on first non-primary pointer
                    // Skipping non-primary pointers if we're already dragging view pager
                    // to skip scale/rotation gestures
                    mSkipNonPrimaryPointers = mViewPagerX != 0;
                    // Skipping view pager fake dragging if we're not started dragging yet
                    // to allow scale/rotation gestures
                    mSkipViewPagerDrag = mViewPagerX == 0;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() == 2) { // only primary pointer left
                    mSkipNonPrimaryPointers = false;
                    mSkipViewPagerDrag = false;
                }
                break;
        }

        if (mSkipViewPagerDrag) return event; // No event adjusting is needed

        boolean isNonPrimaryPointerEvent = event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_UP;

        if (mSkipNonPrimaryPointers && isNonPrimaryPointerEvent) return null; // No event should be passed further

        mTmpEvent = mSkipNonPrimaryPointers ? obtainOnePointerEvent(event) : MotionEvent.obtain(event);
        // Applying offset to the returned event, offset will be calculated in scrollBy method below
        mTmpEvent.offsetLocation(mViewPagerX, 0f);
        return mTmpEvent;
    }

    private MotionEvent obtainOnePointerEvent(MotionEvent e) {
        return MotionEvent.obtain(e.getDownTime(), e.getEventTime(), e.getAction(),
                e.getX(), e.getY(), e.getMetaState());
    }

    private void recycleTmpEvent() {
        if (mTmpEvent != null) {
            mTmpEvent.recycle();
            mTmpEvent = null;
        }
    }

    /**
     * Scrolls view pager when view reached it's bounds. Returns distance (<= dX) at which view can be scrolled.
     * Here we will split given distance (dX) into movement of ViewPager and movement of view itself.
     */
    private float scrollBy(float dX, float dY) {
        if (mSkipViewPagerDrag) return dX;

        float dViewX, dPagerX;

        final State state = getState();
        final Rect viewMovingBounds = getStateController().getMovingBounds(state);

        if (getSettings().isEnabled()) {
            final float dir = Math.signum(dX);
            final float movementX = Math.abs(dX); // always >= 0, no direction info

            // available movement distances (always >= 0, no direction info)
            final float availableViewX = dir < 0 ? state.x - viewMovingBounds.left : viewMovingBounds.right - state.x;
            final float availablePagerX = dir * mViewPagerX < 0 ? Math.abs(mViewPagerX) : 0;

            if (availablePagerX >= movementX) {
                // Only view pager is moved
                dViewX = 0;
                dPagerX = movementX;
            } else {
                if (availableViewX + availablePagerX >= movementX) {
                    // Moving pager for full available distance and moving view for remaining distance
                    dViewX = movementX - availablePagerX;
                    dPagerX = availablePagerX;
                } else {
                    // Moving view for full available distance and moving pager for remaining distance
                    dViewX = availableViewX;
                    dPagerX = movementX - availableViewX;
                }
            }

            // Applying direction
            dViewX *= dir;
            dPagerX *= dir;
        } else {
            dPagerX = dX;
            dViewX = 0f;
        }

        // Checking vertical and horizontal thresholds
        if (!mIsScrollingViewPager && !mIsSkipViewPager) {
            if (viewMovingBounds.width() < mPagerSlop) {
                // View have small horizontal movement area, checking if thresholds are passed
                mAccumulateScrollX += dPagerX;
                mAccumulateScrollY += dY;

                if (Math.abs(mAccumulateScrollX) > mPagerSlop) {
                    // Scrolling view pager if movement in X axis passed threshold
                    mIsScrollingViewPager = true;
                    // Adjusting pager movement for smoother scrolling
                    dPagerX = Math.signum(mAccumulateScrollX) * (Math.abs(mAccumulateScrollX) - mPagerSlop);
                } else if (Math.abs(mAccumulateScrollY) > mPagerSlop) {
                    // Skipping view pager if movement in Y axis passed threshold
                    mIsSkipViewPager = true;
                }
            } else {
                // View have wide horizontal movement area - allow view pager to scroll
                mIsScrollingViewPager = true;
            }
        }

        if (mIsScrollingViewPager) {
            mViewPagerX += scrollPagerBy((int) dPagerX);
        }

        return dViewX;
    }

    /**
     * Manually scrolls view pager and returns actual distance at which pager was scrolled
     */
    private int scrollPagerBy(int dX) {
        int scrollBegin = mViewPager.getScrollX();
        mViewPager.fakeDragBy(dX);
        return scrollBegin - mViewPager.getScrollX();
    }

}