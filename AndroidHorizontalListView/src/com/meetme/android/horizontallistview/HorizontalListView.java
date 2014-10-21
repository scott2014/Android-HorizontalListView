
package com.meetme.android.horizontallistview;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

// @formatter:off
/*
 * <li>Does not support keyboard navigation</li>
 * <li>Does not support scroll bars<li>
 * <li>Does not support header or footer views<li>
 * <li>Does not support disabled items<li>
 */
// @formatter:on
public class HorizontalListView extends AdapterView<ListAdapter> {
    /** Defines where to insert items into the ViewGroup, as defined in {@code ViewGroup #addViewInLayout(View, int, LayoutParams, boolean)} */
    //定义插入到ViewGroup位置为列尾
	private static final int INSERT_AT_END_OF_LIST = -1;
	//插入到列头
    private static final int INSERT_AT_START_OF_LIST = 0;

    //默认吸收速率
    private static final float FLING_DEFAULT_ABSORB_VELOCITY = 30f;

    //用于跟踪的摩擦量
    private static final float FLING_FRICTION = 0.009f;
    
    //用于跟踪状态数据用于讲ListView恢复到当前状态
    private static final String BUNDLE_ID_CURRENT_X = "BUNDLE_ID_CURRENT_X";

    //上一次状态的Bundle_Id,用于恢复到上一次状态
    private static final String BUNDLE_ID_PARENT_STATE = "BUNDLE_ID_PARENT_STATE";

    //跟踪滚动状态
    protected Scroller mFlingTracker = new Scroller(getContext());

   //当手势被检测到时用于回调
    private final GestureListener mGestureListener = new GestureListener();

    //用于检测以便他们能够回调
    private GestureDetector mGestureDetector;

    //跟踪最左侧视图起始布局位置
    private int mDisplayOffset;
    
    //持有一个引用绑定数据到这个视图
    protected ListAdapter mAdapter;
    
    //回收视图缓存
    private List<Queue<View>> mRemovedViewsCache = new ArrayList<Queue<View>>();

    //ListView视图数据改变
    private boolean mDataChanged = false;

    //临时用于测量的矩形
    private Rect mRect = new Rect();

    //跟踪当前接触的视图,用于代表正在碰触的视图
    private View mViewBeingTouched = null;
    
    //分割线的宽度
    private int mDividerWidth = 0;

    //divider drawable
    private Drawable mDivider = null;
    
    //当前渲染视图的x坐标
    protected int mCurrentX;
    
    //下一个渲染视图的x坐标
    protected int mNextX;

    //用于持有滚动位置用于恢复到之前状态
    private Integer mRestoreX = null;
    
    //用于跟踪最大可能的x坐标位置
    private int mMaxX = Integer.MAX_VALUE;

    //最左侧当前可见的adapter索引
    private int mLeftViewAdapterIndex;
    
    //最右侧可见的adapter索引
    private int mRightViewAdapterIndex;

    //当前选中的adapter索引
    private int mCurrentlySelectedAdapterIndex;

    //回调监听器用于通知用户已经滚动这个视图到了最低的那个点
    private RunningOutOfDataListener mRunningOutOfDataListener = null;

    //用光了数据阀值
    private int mRunningOutOfDataThreshold = 0;

    //跟踪是否我们已经告诉监听器我们数据过低,我们只告诉他们一次
    private boolean mHasNotifiedRunningLowOnData = false;
    
    //滚动状态改变的时候调用接口
    private OnScrollStateChangedListener mOnScrollStateChangedListener = null;

    //呈现当前接口的滚动状态
    private OnScrollStateChangedListener.ScrollState mCurrentScrollState = OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE;

    //跟踪左侧边缘发光状态
    private EdgeEffectCompat mEdgeGlowLeft;
    
    //跟踪右侧边缘发光状态
    private EdgeEffectCompat mEdgeGlowRight;

    //当前视图的测量高度,用于子视图布局
    private int mHeightMeasureSpec;

    //用于跟踪是否触摸动作应该被阻止
    private boolean mBlockTouchAction = false;

    //用于跟踪是否父垂直滚动视图已经被告知不允许拦截touch事件
    private boolean mIsParentVerticiallyScrollableViewDisallowingInterceptTouchEvent = false;

    //点击监听器
    private OnClickListener mOnClickListener;

    public HorizontalListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mEdgeGlowLeft = new EdgeEffectCompat(context);
        mEdgeGlowRight = new EdgeEffectCompat(context);
        mGestureDetector = new GestureDetector(context, mGestureListener);
        bindGestureDetector();
        initView();
        retrieveXmlConfiguration(context, attrs);
        //如果需要重绘,必须将此标记设置为false
        setWillNotDraw(false);
        
        //跟踪操作系统摩擦量
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            HoneycombPlus.setFriction(mFlingTracker, FLING_FRICTION);
        }
    }

   //注册手势监听器用于获取手势通知
    private void bindGestureDetector() {
        //通用的触摸监听器,可以用于任意视图的手势监听
    	final View.OnTouchListener gestureListenerHandler = new View.OnTouchListener() {
            @Override
            public boolean onTouch(final View v, final MotionEvent event) {
                //代理触摸事件到我们的手势检测类
            	return mGestureDetector.onTouchEvent(event);
            }
        };

        setOnTouchListener(gestureListenerHandler);
    }

    /**
     * @param disallowIntercept If true the parent will be prevented from intercepting child touch events
     */
    
    //如果HorizontalListView内嵌到垂直的ScrollView的时候,必须禁止父视图与用户的任何交互
    private void requestParentListViewToNotInterceptTouchEvents(Boolean disallowIntercept) {
        // Prevent calling this more than once needlessly
        if (mIsParentVerticiallyScrollableViewDisallowingInterceptTouchEvent != disallowIntercept) {
            View view = this;

            while (view.getParent() instanceof View) {
                // If the parent is a ListView or ScrollView then disallow intercepting of touch events
                if (view.getParent() instanceof ListView || view.getParent() instanceof ScrollView) {
                    view.getParent().requestDisallowInterceptTouchEvent(disallowIntercept);
                    mIsParentVerticiallyScrollableViewDisallowingInterceptTouchEvent = disallowIntercept;
                    return;
                }

                view = (View) view.getParent();
            }
        }
    }

    //提取xml配置设置ListView的相关属性
    private void retrieveXmlConfiguration(Context context, AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.HorizontalListView);

            // Get the provided drawable from the XML
            final Drawable d = a.getDrawable(R.styleable.HorizontalListView_android_divider);
            if (d != null) {
                // If a drawable is provided to use as the divider then use its intrinsic width for the divider width
                setDivider(d);
            }

            // If a width is explicitly specified then use that width
            final int dividerWidth = a.getDimensionPixelSize(R.styleable.HorizontalListView_dividerWidth, 0);
            if (dividerWidth != 0) {
                setDividerWidth(dividerWidth);
            }

            a.recycle();
        }
    }
    
    
    //============================================
    //用于状态恢复
    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();

        // Add the parent state to the bundle
        bundle.putParcelable(BUNDLE_ID_PARENT_STATE, super.onSaveInstanceState());

        // Add our state to the bundle
        bundle.putInt(BUNDLE_ID_CURRENT_X, mCurrentX);

        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle bundle = (Bundle) state;

            // Restore our state from the bundle
            mRestoreX = Integer.valueOf((bundle.getInt(BUNDLE_ID_CURRENT_X)));

            // Restore out parent's state from the bundle
            super.onRestoreInstanceState(bundle.getParcelable(BUNDLE_ID_PARENT_STATE));
        }
    }
    //============================================================
    
    
    //间隔
    //getInstrinsicWidth  获取单位为dp的组件宽度
    public void setDivider(Drawable divider) {
        mDivider = divider;

        if (divider != null) {
            setDividerWidth(divider.getIntrinsicWidth());
        } else {
            setDividerWidth(0);
        }
    }

   //设置宽度
    public void setDividerWidth(int width) {
        mDividerWidth = width;
        
        /*requestLayout：当view确定自身已经不再适合现有的区域时，该view本身调用这个方法要求parent view重新调用他的onMeasure onLayout来对重新设置自己位置。

        特别的当view的layoutparameter发生改变，并且它的值还没能应用到view上，这时候适合调用这个方法*/
        //强制视图渲染自身
        requestLayout();
        invalidate();
    }

    private void initView() {
        mLeftViewAdapterIndex = -1;
        mRightViewAdapterIndex = -1;
        mDisplayOffset = 0;
        mCurrentX = 0;
        mNextX = 0;
        mMaxX = Integer.MAX_VALUE;
        setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
    }

    //重新初始化HorizontalListView 移除所有的子视图,重置到初始配置
    private void reset() {
        initView();
        removeAllViewsInLayout();
        requestLayout();
    }

    //用于捕获adapter data改变事件
    private DataSetObserver mAdapterDataObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            mDataChanged = true;

            // Clear so we can notify again as we run out of data
            mHasNotifiedRunningLowOnData = false;

            unpressTouchedChild();

            // Invalidate and request layout to force this view to completely redraw itself
            invalidate();
            requestLayout();
        }

        @Override
        public void onInvalidated() {
            // Clear so we can notify again as we run out of data
            mHasNotifiedRunningLowOnData = false;

            unpressTouchedChild();
            reset();

            // Invalidate and request layout to force this view to completely redraw itself
            invalidate();
            requestLayout();
        }
    };
    
    //设置当前选中的item
    @Override
    public void setSelection(int position) {
        mCurrentlySelectedAdapterIndex = position;
    }
    
    //获取选中的视图
    @Override
    public View getSelectedView() {
        return getChild(mCurrentlySelectedAdapterIndex);
    }
    
    
    //设置Adapter
    @Override
    public void setAdapter(ListAdapter adapter) {
        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(mAdapterDataObserver);
        }

        if (adapter != null) {
            // Clear so we can notify again as we run out of data
            mHasNotifiedRunningLowOnData = false;

            mAdapter = adapter;
            mAdapter.registerDataSetObserver(mAdapterDataObserver);
        }

        initializeRecycledViewCache(mAdapter.getViewTypeCount());
        reset();
    }

    @Override
    public ListAdapter getAdapter() {
        return mAdapter;
    }

    //初始化回收视图缓存
    private void initializeRecycledViewCache(int viewTypeCount) {
        // The cache is created such that the response from mAdapter.getItemViewType is the array index to the correct cache for that item.
        mRemovedViewsCache.clear();
        for (int i = 0; i < viewTypeCount; i++) {
            mRemovedViewsCache.add(new LinkedList<View>());
        }
    }

    //从缓存中返回一个能被使用的回收视图,如果没有一个可以使用就返回null
    private View getRecycledView(int adapterIndex) {
        int itemViewType = mAdapter.getItemViewType(adapterIndex);

        if (isItemViewTypeValid(itemViewType)) {
            return mRemovedViewsCache.get(itemViewType).poll();
        }

        return null;
    }

    //增加视图到回收缓存
    private void recycleView(int adapterIndex, View view) {
        // There is one Queue of views for each different type of view.
        // Just add the view to the pile of other views of the same type.
        // The order they are added and removed does not matter.
        int itemViewType = mAdapter.getItemViewType(adapterIndex);
        if (isItemViewTypeValid(itemViewType)) {
            mRemovedViewsCache.get(itemViewType).offer(view);
        }
    }
    
    //视图类型是否已经失效
    private boolean isItemViewTypeValid(int itemViewType) {
        return itemViewType < mRemovedViewsCache.size();
    }

    //增加一个子视图到ViewGroup并检测它是否已经渲染到了正确的尺寸
    private void addAndMeasureChild(final View child, int viewPos) {
        LayoutParams params = getLayoutParams(child);
        addViewInLayout(child, viewPos, params, true);
        measureChild(child);
    }
    
    //检测提供的子类
    private void measureChild(View child) {
        ViewGroup.LayoutParams childLayoutParams = getLayoutParams(child);
        int childHeightSpec = ViewGroup.getChildMeasureSpec(mHeightMeasureSpec, getPaddingTop() + getPaddingBottom(), childLayoutParams.height);

        int childWidthSpec;
        if (childLayoutParams.width > 0) {
            childWidthSpec = MeasureSpec.makeMeasureSpec(childLayoutParams.width, MeasureSpec.EXACTLY);
        } else {
            childWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        }

        child.measure(childWidthSpec, childHeightSpec);
    }
    
    //得到子视图的布局参数
    private ViewGroup.LayoutParams getLayoutParams(View child) {
        ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
        if (layoutParams == null) {
            // Since this is a horizontal list view default to matching the parents height, and wrapping the width
            layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        return layoutParams;
    }

    //布局改变回调
    @SuppressLint("WrongCall")
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (mAdapter == null) {
            return;
        }

        // Force the OS to redraw this view
        invalidate();

        // If the data changed then reset everything and render from scratch at the same offset as last time
        if (mDataChanged) {
            int oldCurrentX = mCurrentX;
            initView();
            removeAllViewsInLayout();
            mNextX = oldCurrentX;
            mDataChanged = false;
        }

        // If restoring from a rotation
        if (mRestoreX != null) {
            mNextX = mRestoreX;
            mRestoreX = null;
        }

        // If in a fling
        if (mFlingTracker.computeScrollOffset()) {
            // Compute the next position
            mNextX = mFlingTracker.getCurrX();
        }

        // Prevent scrolling past 0 so you can't scroll past the end of the list to the left
        if (mNextX < 0) {
            mNextX = 0;

            // Show an edge effect absorbing the current velocity
            if (mEdgeGlowLeft.isFinished()) {
                mEdgeGlowLeft.onAbsorb((int) determineFlingAbsorbVelocity());
            }

            mFlingTracker.forceFinished(true);
            setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
        } else if (mNextX > mMaxX) {
            // Clip the maximum scroll position at mMaxX so you can't scroll past the end of the list to the right
            mNextX = mMaxX;

            // Show an edge effect absorbing the current velocity
            if (mEdgeGlowRight.isFinished()) {
                mEdgeGlowRight.onAbsorb((int) determineFlingAbsorbVelocity());
            }

            mFlingTracker.forceFinished(true);
            setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
        }

        // Calculate our delta from the last time the view was drawn
        int dx = mCurrentX - mNextX;
        removeNonVisibleChildren(dx);
        fillList(dx);
        positionChildren(dx);

        // Since the view has now been drawn, update our current position
        mCurrentX = mNextX;

        // If we have scrolled enough to lay out all views, then determine the maximum scroll position now
        if (determineMaxX()) {
            // Redo the layout pass since we now know the maximum scroll position
            onLayout(changed, left, top, right, bottom);
            return;
        }

        // If the fling has finished
        if (mFlingTracker.isFinished()) {
            // If the fling just ended
            if (mCurrentScrollState == OnScrollStateChangedListener.ScrollState.SCROLL_STATE_FLING) {
                setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
            }
        } else {
            // Still in a fling so schedule the next frame
            ViewCompat.postOnAnimation(this, mDelayedLayout);
        }
    }
    
    //得到左侧边缘强度
    @Override
    protected float getLeftFadingEdgeStrength() {
        int horizontalFadingEdgeLength = getHorizontalFadingEdgeLength();

        // If completely at the edge then disable the fading edge
        if (mCurrentX == 0) {
            return 0;
        } else if (mCurrentX < horizontalFadingEdgeLength) {
            // We are very close to the edge, so enable the fading edge proportional to the distance from the edge, and the width of the edge effect
            return (float) mCurrentX / horizontalFadingEdgeLength;
        } else {
            // The current x position is more then the width of the fading edge so enable it fully.
            return 1;
        }
    }

    //得到右侧边缘强度
    @Override
    protected float getRightFadingEdgeStrength() {
        int horizontalFadingEdgeLength = getHorizontalFadingEdgeLength();

        // If completely at the edge then disable the fading edge
        if (mCurrentX == mMaxX) {
            return 0;
        } else if ((mMaxX - mCurrentX) < horizontalFadingEdgeLength) {
            // We are very close to the edge, so enable the fading edge proportional to the distance from the ednge, and the width of the edge effect
            return (float) (mMaxX - mCurrentX) / horizontalFadingEdgeLength;
        } else {
            // The distance from the maximum x position is more then the width of the fading edge so enable it fully.
            return 1;
        }
    }

    //决定当前吸收速率
    private float determineFlingAbsorbVelocity() {
        // If the OS version is high enough get the real velocity */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            return IceCreamSandwichPlus.getCurrVelocity(mFlingTracker);
        } else {
            // Unable to get the velocity so just return a default.
            // In actuality this is never used since EdgeEffectCompat does not draw anything unless the device is ICS+.
            // Less then ICS EdgeEffectCompat essentially performs a NOP.
            return FLING_DEFAULT_ABSORB_VELOCITY;
        }
    }
    
    //用于计划请求布局通过runnable对象
    private Runnable mDelayedLayout = new Runnable() {
        @Override
        public void run() {
            requestLayout();
        }
    };

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Cache off the measure spec
        mHeightMeasureSpec = heightMeasureSpec;
    };

    /**
     * Determine the Max X position. This is the farthest that the user can scroll the screen. Until the last adapter item has been
     * laid out it is impossible to calculate; once that has occurred this will perform the calculation, and if necessary force a
     * redraw and relayout of this view.
     *
     * @return true if the maxx position was just determined
     */
    
    //决定最大的X位置,这是一个最远用于可以滚动的屏幕,直到Adapter item已经布局到可能计算的地方
    private boolean determineMaxX() {
        // If the last view has been laid out, then we can determine the maximum x position
        if (isLastItemInAdapter(mRightViewAdapterIndex)) {
            View rightView = getRightmostChild();

            if (rightView != null) {
                int oldMaxX = mMaxX;

                // Determine the maximum x position
                mMaxX = mCurrentX + (rightView.getRight() - getPaddingLeft()) - getRenderWidth();

                // Handle the case where the views do not fill at least 1 screen
                if (mMaxX < 0) {
                    mMaxX = 0;
                }

                if (mMaxX != oldMaxX) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Adds children views to the left and right of the current views until the screen is full */
    //增加子视图到当前视图的左右,直到屏幕填满
    private void fillList(final int dx) {
        // Get the rightmost child and determine its right edge
        int edge = 0;
        View child = getRightmostChild();
        if (child != null) {
            edge = child.getRight();
        }

        // Add new children views to the right, until past the edge of the screen
        fillListRight(edge, dx);

        // Get the leftmost child and determine its left edge
        edge = 0;
        child = getLeftmostChild();
        if (child != null) {
            edge = child.getLeft();
        }

        // Add new children views to the left, until past the edge of the screen
        fillListLeft(edge, dx);
    }

    //移除不可见的子视图
    private void removeNonVisibleChildren(final int dx) {
        View child = getLeftmostChild();

        // Loop removing the leftmost child, until that child is on the screen
        while (child != null && child.getRight() + dx <= 0) {
            // The child is being completely removed so remove its width from the display offset and its divider if it has one.
            // To remove add the size of the child and its divider (if it has one) to the offset.
            // You need to add since its being removed from the left side, i.e. shifting the offset to the right.
            mDisplayOffset += isLastItemInAdapter(mLeftViewAdapterIndex) ? child.getMeasuredWidth() : mDividerWidth + child.getMeasuredWidth();

            // Add the removed view to the cache
            recycleView(mLeftViewAdapterIndex, child);

            // Actually remove the view
            removeViewInLayout(child);

            // Keep track of the adapter index of the left most child
            mLeftViewAdapterIndex++;

            // Get the new leftmost child
            child = getLeftmostChild();
        }

        child = getRightmostChild();

        // Loop removing the rightmost child, until that child is on the screen
        while (child != null && child.getLeft() + dx >= getWidth()) {
            recycleView(mRightViewAdapterIndex, child);
            removeViewInLayout(child);
            mRightViewAdapterIndex--;
            child = getRightmostChild();
        }
    }
    
    private void fillListRight(int rightEdge, final int dx) {
        // Loop adding views to the right until the screen is filled
        while (rightEdge + dx + mDividerWidth < getWidth() && mRightViewAdapterIndex + 1 < mAdapter.getCount()) {
            mRightViewAdapterIndex++;

            // If mLeftViewAdapterIndex < 0 then this is the first time a view is being added, and left == right
            if (mLeftViewAdapterIndex < 0) {
                mLeftViewAdapterIndex = mRightViewAdapterIndex;
            }

            // Get the view from the adapter, utilizing a cached view if one is available
            View child = mAdapter.getView(mRightViewAdapterIndex, getRecycledView(mRightViewAdapterIndex), this);
            addAndMeasureChild(child, INSERT_AT_END_OF_LIST);

            // If first view, then no divider to the left of it, otherwise add the space for the divider width
            rightEdge += (mRightViewAdapterIndex == 0 ? 0 : mDividerWidth) + child.getMeasuredWidth();

            // Check if we are running low on data so we can tell listeners to go get more
            determineIfLowOnData();
        }
    }

    private void fillListLeft(int leftEdge, final int dx) {
        // Loop adding views to the left until the screen is filled
        while (leftEdge + dx - mDividerWidth > 0 && mLeftViewAdapterIndex >= 1) {
            mLeftViewAdapterIndex--;
            View child = mAdapter.getView(mLeftViewAdapterIndex, getRecycledView(mLeftViewAdapterIndex), this);
            addAndMeasureChild(child, INSERT_AT_START_OF_LIST);

            // If first view, then no divider to the left of it
            leftEdge -= mLeftViewAdapterIndex == 0 ? child.getMeasuredWidth() : mDividerWidth + child.getMeasuredWidth();

            // If on a clean edge then just remove the child, otherwise remove the divider as well
            mDisplayOffset -= leftEdge + dx == 0 ? child.getMeasuredWidth() : mDividerWidth + child.getMeasuredWidth();
        }
    }

    /** Loops through each child and positions them onto the screen */
    private void positionChildren(final int dx) {
        int childCount = getChildCount();

        if (childCount > 0) {
            mDisplayOffset += dx;
            int leftOffset = mDisplayOffset;

            // Loop each child view
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                int left = leftOffset + getPaddingLeft();
                int top = getPaddingTop();
                int right = left + child.getMeasuredWidth();
                int bottom = top + child.getMeasuredHeight();

                // Layout the child
                child.layout(left, top, right, bottom);

                // Increment our offset by added child's size and divider width
                leftOffset += child.getMeasuredWidth() + mDividerWidth;
            }
        }
    }

    //得到当前屏幕最左边的子视图
    private View getLeftmostChild() {
        return getChildAt(0);
    }
    
    //得到当前屏幕最右边的子视图
    private View getRightmostChild() {
        return getChildAt(getChildCount() - 1);
    }

    //得到一个包含在当前视图里面的子视图,并获取该子视图的索引
    private View getChild(int adapterIndex) {
        if (adapterIndex >= mLeftViewAdapterIndex && adapterIndex <= mRightViewAdapterIndex) {
            return getChildAt(adapterIndex - mLeftViewAdapterIndex);
        }

        return null;
    }

    //返回指定坐标子视图的索引
    private int getChildIndex(final int x, final int y) {
        int childCount = getChildCount();

        for (int index = 0; index < childCount; index++) {
            getChildAt(index).getHitRect(mRect);
            if (mRect.contains(x, y)) {
                return index;
            }
        }

        return -1;
    }
    
    //决定是否当前索引是最后一个索引
    private boolean isLastItemInAdapter(int index) {
        return index == mAdapter.getCount() - 1;
    }
    
    //获取将被渲染的视图高度(px)
    private int getRenderHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    /** Gets the width in px this view will be rendered. (padding removed) */
    private int getRenderWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }
    
    //滚动到提供的offset值
    public void scrollTo(int x) {
        mFlingTracker.startScroll(mNextX, 0, x - mNextX, 0);
        setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_FLING);
        requestLayout();
    }
    
    //获取第一个可见的位置
    @Override
    public int getFirstVisiblePosition() {
        return mLeftViewAdapterIndex;
    }

    @Override
    public int getLastVisiblePosition() {
        return mRightViewAdapterIndex;
    }

    //绘画超出部分的光晕效果
    /** Draws the overscroll edge glow effect on the left and right sides of the horizontal list */
    private void drawEdgeGlow(Canvas canvas) {
        if (mEdgeGlowLeft != null && !mEdgeGlowLeft.isFinished() && isEdgeGlowEnabled()) {
            // The Edge glow is meant to come from the top of the screen, so rotate it to draw on the left side.
            final int restoreCount = canvas.save();
            final int height = getHeight();

            canvas.rotate(-90, 0, 0);
            canvas.translate(-height + getPaddingBottom(), 0);

            mEdgeGlowLeft.setSize(getRenderHeight(), getRenderWidth());
            if (mEdgeGlowLeft.draw(canvas)) {
                invalidate();
            }

            canvas.restoreToCount(restoreCount);
        } else if (mEdgeGlowRight != null && !mEdgeGlowRight.isFinished() && isEdgeGlowEnabled()) {
            // The Edge glow is meant to come from the top of the screen, so rotate it to draw on the right side.
            final int restoreCount = canvas.save();
            final int width = getWidth();

            canvas.rotate(90, 0, 0);
            canvas.translate(getPaddingTop(), -width);
            mEdgeGlowRight.setSize(getRenderHeight(), getRenderWidth());
            if (mEdgeGlowRight.draw(canvas)) {
                invalidate();
            }

            canvas.restoreToCount(restoreCount);
        }
    }
    
    //画间隔
    /** Draws the dividers that go in between the horizontal list view items */
    private void drawDividers(Canvas canvas) {
        final int count = getChildCount();

        // Only modify the left and right in the loop, we set the top and bottom here since they are always the same
        final Rect bounds = mRect;
        mRect.top = getPaddingTop();
        mRect.bottom = mRect.top + getRenderHeight();

        // Draw the list dividers
        for (int i = 0; i < count; i++) {
            // Don't draw a divider to the right of the last item in the adapter
            if (!(i == count - 1 && isLastItemInAdapter(mRightViewAdapterIndex))) {
                View child = getChildAt(i);

                bounds.left = child.getRight();
                bounds.right = child.getRight() + mDividerWidth;

                // Clip at the left edge of the screen
                if (bounds.left < getPaddingLeft()) {
                    bounds.left = getPaddingLeft();
                }

                // Clip at the right edge of the screen
                if (bounds.right > getWidth() - getPaddingRight()) {
                    bounds.right = getWidth() - getPaddingRight();
                }

                // Draw a divider to the right of the child
                drawDivider(canvas, bounds);

                // If the first view, determine if a divider should be shown to the left of it.
                // A divider should be shown if the left side of this view does not fill to the left edge of the screen.
                if (i == 0 && child.getLeft() > getPaddingLeft()) {
                    bounds.left = getPaddingLeft();
                    bounds.right = child.getLeft();
                    drawDivider(canvas, bounds);
                }
            }
        }
    }

    /**
     * Draws a divider in the given bounds.
     *
     * @param canvas The canvas to draw to.
     * @param bounds The bounds of the divider.
     */
    private void drawDivider(Canvas canvas, Rect bounds) {
        if (mDivider != null) {
            mDivider.setBounds(bounds);
            mDivider.draw(canvas);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawDividers(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawEdgeGlow(canvas);
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        // Don't dispatch setPressed to our children. We call setPressed on ourselves to
        // get the selector in the right state, but we don't want to press each child.
    }

    protected boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        mFlingTracker.fling(mNextX, 0, (int) -velocityX, 0, 0, mMaxX, 0, 0);
        setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_FLING);
        requestLayout();
        return true;
    }

    protected boolean onDown(MotionEvent e) {
        // If the user just caught a fling, then disable all touch actions until they release their finger
        mBlockTouchAction = !mFlingTracker.isFinished();

        // Allow a finger down event to catch a fling
        mFlingTracker.forceFinished(true);
        setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);

        unpressTouchedChild();

        if (!mBlockTouchAction) {
            // Find the child that was pressed
            final int index = getChildIndex((int) e.getX(), (int) e.getY());
            if (index >= 0) {
                // Save off view being touched so it can later be released
                mViewBeingTouched = getChildAt(index);

                if (mViewBeingTouched != null) {
                    // Set the view as pressed
                    mViewBeingTouched.setPressed(true);
                    refreshDrawableState();
                }
            }
        }

        return true;
    }

    /** If a view is currently pressed then unpress it */
    private void unpressTouchedChild() {
        if (mViewBeingTouched != null) {
            // Set the view as not pressed
            mViewBeingTouched.setPressed(false);
            refreshDrawableState();

            // Null out the view so we don't leak it
            mViewBeingTouched = null;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return HorizontalListView.this.onDown(e);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            return HorizontalListView.this.onFling(e1, e2, velocityX, velocityY);
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            // Lock the user into interacting just with this view
            requestParentListViewToNotInterceptTouchEvents(true);

            setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_TOUCH_SCROLL);
            unpressTouchedChild();
            mNextX += (int) distanceX;
            updateOverscrollAnimation(Math.round(distanceX));
            requestLayout();

            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            unpressTouchedChild();
            OnItemClickListener onItemClickListener = getOnItemClickListener();

            final int index = getChildIndex((int) e.getX(), (int) e.getY());

            // If the tap is inside one of the child views, and we are not blocking touches
            if (index >= 0 && !mBlockTouchAction) {
                View child = getChildAt(index);
                int adapterIndex = mLeftViewAdapterIndex + index;

                if (onItemClickListener != null) {
                    onItemClickListener.onItemClick(HorizontalListView.this, child, adapterIndex, mAdapter.getItemId(adapterIndex));
                    return true;
                }
            }

            if (mOnClickListener != null && !mBlockTouchAction) {
                mOnClickListener.onClick(HorizontalListView.this);
            }

            return false;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            unpressTouchedChild();

            final int index = getChildIndex((int) e.getX(), (int) e.getY());
            if (index >= 0 && !mBlockTouchAction) {
                View child = getChildAt(index);
                OnItemLongClickListener onItemLongClickListener = getOnItemLongClickListener();
                if (onItemLongClickListener != null) {
                    int adapterIndex = mLeftViewAdapterIndex + index;
                    boolean handled = onItemLongClickListener.onItemLongClick(HorizontalListView.this, child, adapterIndex, mAdapter
                            .getItemId(adapterIndex));

                    if (handled) {
                        // BZZZTT!!1!
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                }
            }
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Detect when the user lifts their finger off the screen after a touch
        if (event.getAction() == MotionEvent.ACTION_UP) {
            // If not flinging then we are idle now. The user just finished a finger scroll.
            if (mFlingTracker == null || mFlingTracker.isFinished()) {
                setCurrentScrollState(OnScrollStateChangedListener.ScrollState.SCROLL_STATE_IDLE);
            }

            // Allow the user to interact with parent views
            requestParentListViewToNotInterceptTouchEvents(false);

            releaseEdgeGlow();
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            unpressTouchedChild();
            releaseEdgeGlow();

            // Allow the user to interact with parent views
            requestParentListViewToNotInterceptTouchEvents(false);
        }

        return super.onTouchEvent(event);
    }

    /** Release the EdgeGlow so it animates */
    private void releaseEdgeGlow() {
        if (mEdgeGlowLeft != null) {
            mEdgeGlowLeft.onRelease();
        }

        if (mEdgeGlowRight != null) {
            mEdgeGlowRight.onRelease();
        }
    }

    /**
     * Sets a listener to be called when the HorizontalListView has been scrolled to a point where it is
     * running low on data. An example use case is wanting to auto download more data when the user
     * has scrolled to the point where only 10 items are left to be rendered off the right of the
     * screen. To get called back at that point just register with this function with a
     * numberOfItemsLeftConsideredLow value of 10. <br>
     * <br>
     * This will only be called once to notify that the HorizontalListView is running low on data.
     * Calling notifyDataSetChanged on the adapter will allow this to be called again once low on data.
     *
     * @param listener The listener to be notified when the number of array adapters items left to
     * be shown is running low.
     *
     * @param numberOfItemsLeftConsideredLow The number of array adapter items that have not yet
     * been displayed that is considered too low.
     */
    public void setRunningOutOfDataListener(RunningOutOfDataListener listener, int numberOfItemsLeftConsideredLow) {
        mRunningOutOfDataListener = listener;
        mRunningOutOfDataThreshold = numberOfItemsLeftConsideredLow;
    }

    /**
     * This listener is used to allow notification when the HorizontalListView is running low on data to display.
     */
    public static interface RunningOutOfDataListener {
        /** Called when the HorizontalListView is running out of data and has reached at least the provided threshold. */
        void onRunningOutOfData();
    }

    /**
     * Determines if we are low on data and if so will call to notify the listener, if there is one,
     * that we are running low on data.
     */
    private void determineIfLowOnData() {
        // Check if the threshold has been reached and a listener is registered
        if (mRunningOutOfDataListener != null && mAdapter != null &&
                mAdapter.getCount() - (mRightViewAdapterIndex + 1) < mRunningOutOfDataThreshold) {

            // Prevent notification more than once
            if (!mHasNotifiedRunningLowOnData) {
                mHasNotifiedRunningLowOnData = true;
                mRunningOutOfDataListener.onRunningOutOfData();
            }
        }
    }

    /**
     * Register a callback to be invoked when the HorizontalListView has been clicked.
     *
     * @param listener The callback that will be invoked.
     */
    @Override
    public void setOnClickListener(OnClickListener listener) {
        mOnClickListener = listener;
    }

    /**
     * Interface definition for a callback to be invoked when the view scroll state has changed.
     */
    public interface OnScrollStateChangedListener {
        public enum ScrollState {
            /**
             * The view is not scrolling. Note navigating the list using the trackball counts as being
             * in the idle state since these transitions are not animated.
             */
            SCROLL_STATE_IDLE,

            /**
             * The user is scrolling using touch, and their finger is still on the screen
             */
            SCROLL_STATE_TOUCH_SCROLL,

            /**
             * The user had previously been scrolling using touch and had performed a fling. The
             * animation is now coasting to a stop
             */
            SCROLL_STATE_FLING
        }

        /**
         * Callback method to be invoked when the scroll state changes.
         *
         * @param scrollState The current scroll state.
         */
        public void onScrollStateChanged(ScrollState scrollState);
    }

    /**
     * Sets a listener to be invoked when the scroll state has changed.
     *
     * @param listener The listener to be invoked.
     */
    public void setOnScrollStateChangedListener(OnScrollStateChangedListener listener) {
        mOnScrollStateChangedListener = listener;
    }

    /**
     * Call to set the new scroll state.
     * If it has changed and a listener is registered then it will be notified.
     */
    private void setCurrentScrollState(OnScrollStateChangedListener.ScrollState newScrollState) {
        // If the state actually changed then notify listener if there is one
        if (mCurrentScrollState != newScrollState && mOnScrollStateChangedListener != null) {
            mOnScrollStateChangedListener.onScrollStateChanged(newScrollState);
        }

        mCurrentScrollState = newScrollState;
    }

    /**
     * Updates the over scroll animation based on the scrolled offset.
     *
     * @param scrolledOffset The scroll offset
     */
    private void updateOverscrollAnimation(final int scrolledOffset) {
        if (mEdgeGlowLeft == null || mEdgeGlowRight == null) return;

        // Calculate where the next scroll position would be
        int nextScrollPosition = mCurrentX + scrolledOffset;

        // If not currently in a fling (Don't want to allow fling offset updates to cause over scroll animation)
        if (mFlingTracker == null || mFlingTracker.isFinished()) {
            // If currently scrolled off the left side of the list and the adapter is not empty
            if (nextScrollPosition < 0) {

                // Calculate the amount we have scrolled since last frame
                int overscroll = Math.abs(scrolledOffset);

                // Tell the edge glow to redraw itself at the new offset
                mEdgeGlowLeft.onPull((float) overscroll / getRenderWidth());

                // Cancel animating right glow
                if (!mEdgeGlowRight.isFinished()) {
                    mEdgeGlowRight.onRelease();
                }
            } else if (nextScrollPosition > mMaxX) {
                // Scrolled off the right of the list

                // Calculate the amount we have scrolled since last frame
                int overscroll = Math.abs(scrolledOffset);

                // Tell the edge glow to redraw itself at the new offset
                mEdgeGlowRight.onPull((float) overscroll / getRenderWidth());

                // Cancel animating left glow
                if (!mEdgeGlowLeft.isFinished()) {
                    mEdgeGlowLeft.onRelease();
                }
            }
        }
    }

    /**
     * Checks if the edge glow should be used enabled.
     * The glow is not enabled unless there are more views than can fit on the screen at one time.
     */
    private boolean isEdgeGlowEnabled() {
        if (mAdapter == null || mAdapter.isEmpty()) return false;

        // If the maxx is more then zero then the user can scroll, so the edge effects should be shown
        return mMaxX > 0;
    }

    @TargetApi(11)
    /** Wrapper class to protect access to API version 11 and above features */
    private static final class HoneycombPlus {
        static {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                throw new RuntimeException("Should not get to HoneycombPlus class unless sdk is >= 11!");
            }
        }

        /** Sets the friction for the provided scroller */
        public static void setFriction(Scroller scroller, float friction) {
            if (scroller != null) {
                scroller.setFriction(friction);
            }
        }
    }

    @TargetApi(14)
    /** Wrapper class to protect access to API version 14 and above features */
    private static final class IceCreamSandwichPlus {
        static {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                throw new RuntimeException("Should not get to IceCreamSandwichPlus class unless sdk is >= 14!");
            }
        }

        /** Gets the velocity for the provided scroller */
        public static float getCurrVelocity(Scroller scroller) {
            return scroller.getCurrVelocity();
        }
    }
}
