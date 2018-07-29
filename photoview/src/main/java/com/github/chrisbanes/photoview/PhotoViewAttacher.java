/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.github.chrisbanes.photoview;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.OverScroller;

/**
 * The component of {@link PhotoView} which does the work allowing for zooming, scaling, panning, etc.
 * It is made public in case you need to subclass something other than AppCompatImageView and still
 * gain the functionality that {@link PhotoView} offers
 */
public class PhotoViewAttacher implements View.OnTouchListener,
        View.OnLayoutChangeListener {

    private static float DEFAULT_MAX_SCALE = 3.0f;
    private static float DEFAULT_MID_SCALE = 1.75f;
    private static float DEFAULT_MIN_SCALE = 1.0f;
    private static int DEFAULT_ZOOM_DURATION = 200;

    private static final int EDGE_NONE = -1;//图片两边都不在边缘内
    private static final int EDGE_LEFT = 0;//图片左边显示在View的左边缘内
    private static final int EDGE_RIGHT = 1;//图片右边显示在View的右边缘内
    private static final int EDGE_BOTH = 2;//图片两边都在边缘内
    private static int SINGLE_TOUCH = 1;//单指

    //插值方式
    private Interpolator mInterpolator = new AccelerateDecelerateInterpolator();
    private int mZoomDuration = DEFAULT_ZOOM_DURATION;
    private float mMinScale = DEFAULT_MIN_SCALE;
    private float mMidScale = DEFAULT_MID_SCALE;
    private float mMaxScale = DEFAULT_MAX_SCALE;

    //允许父组件拦截
    private boolean mAllowParentInterceptOnEdge = true;
    private boolean mBlockParentIntercept = false;

    private ImageView mImageView;

    // Gesture Detectors
    private GestureDetector mGestureDetector;//单击，长按，Fling
    private CustomGestureDetector mScaleDragDetector;//缩放和拖拽

    // These are set so we don't keep allocating them on the heap
    //设置好，不用再分配内存
    private final Matrix mBaseMatrix = new Matrix();//基础矩阵,用来保存初始的显示矩阵
    private final Matrix mDrawMatrix = new Matrix();//绘画矩阵，用来计算最后显示区域的矩阵，是在mBaseMatrix和mSuppMatrix的基础上计算出来的
    private final Matrix mSuppMatrix = new Matrix();
    private final RectF mDisplayRect = new RectF();//显示矩形
    //ImageView的变换矩阵
    private final float[] mMatrixValues = new float[9];

    // Listeners
    private OnMatrixChangedListener mMatrixChangeListener;
    private OnPhotoTapListener mPhotoTapListener;
    private OnOutsidePhotoTapListener mOutsidePhotoTapListener;
    private OnViewTapListener mViewTapListener;
    private View.OnClickListener mOnClickListener;
    private OnLongClickListener mLongClickListener;
    private OnScaleChangedListener mScaleChangeListener;
    private OnSingleFlingListener mSingleFlingListener;
    private OnViewDragListener mOnViewDragListener;

    private FlingRunnable mCurrentFlingRunnable;
    private int mScrollEdge = EDGE_BOTH;//两边边缘
    private float mBaseRotation;//基础旋转角度

    private boolean mZoomEnabled = true;//是否可以缩放
    private ScaleType mScaleType = ScaleType.FIT_CENTER;//默认缩放类型

    //拖动，多点触控缩放
    private OnGestureListener onGestureListener = new OnGestureListener() {
        @Override
        public void onDrag(float dx, float dy) {
            //判断是否正在缩放
            if (mScaleDragDetector.isScaling()) {
                return; // Do not drag if we are already scaling
            }

            if (mOnViewDragListener != null) {
                mOnViewDragListener.onDrag(dx, dy);
            }
            //平移
            mSuppMatrix.postTranslate(dx, dy);
            checkAndDisplayMatrix();

            /*
             * Here we decide whether to let the ImageView's parent to start taking
             * over the touch event.
             *
             * First we check whether this function is enabled. We never want the
             * parent to take over if we're scaling. We then check the edge we're
             * on, and the direction of the scroll (i.e. if we're pulling against
             * the edge, aka 'overscrolling', let the parent take over).
             */
            ViewParent parent = mImageView.getParent();
            if (mAllowParentInterceptOnEdge && !mScaleDragDetector.isScaling() && !mBlockParentIntercept) {
                if (mScrollEdge == EDGE_BOTH
                        || (mScrollEdge == EDGE_LEFT && dx >= 1f)
                        || (mScrollEdge == EDGE_RIGHT && dx <= -1f)) {
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
            } else {
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
            }
        }

        @Override
        public void onFling(float startX, float startY, float velocityX, float velocityY) {
            mCurrentFlingRunnable = new FlingRunnable(mImageView.getContext());
            mCurrentFlingRunnable.fling(getImageViewWidth(mImageView),
                    getImageViewHeight(mImageView), (int) velocityX, (int) velocityY);
            mImageView.post(mCurrentFlingRunnable);
        }

        @Override
        public void onScale(float scaleFactor, float focusX, float focusY) {
            if ((getScale() < mMaxScale || scaleFactor < 1f) && (getScale() > mMinScale || scaleFactor > 1f)) {
                if (mScaleChangeListener != null) {
                    mScaleChangeListener.onScaleChange(scaleFactor, focusX, focusY);
                }
                mSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
                checkAndDisplayMatrix();
            }
        }
    };

    public PhotoViewAttacher(ImageView imageView) {
        mImageView = imageView;
        imageView.setOnTouchListener(this);
        imageView.addOnLayoutChangeListener(this);

        if (imageView.isInEditMode()) {
            return;
        }

        mBaseRotation = 0.0f;

        // Create Gesture Detectors...
        mScaleDragDetector = new CustomGestureDetector(imageView.getContext(), onGestureListener);

        mGestureDetector = new GestureDetector(imageView.getContext(), new GestureDetector.SimpleOnGestureListener() {

            // forward long click listener
            @Override
            public void onLongPress(MotionEvent e) {
                if (mLongClickListener != null) {
                    mLongClickListener.onLongClick(mImageView);
                }
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (mSingleFlingListener != null) {
                    if (getScale() > DEFAULT_MIN_SCALE) {
                        return false;
                    }

                    if (e1.getPointerCount() > SINGLE_TOUCH
                            || e1.getPointerCount() > SINGLE_TOUCH) {
                        return false;
                    }

                    return mSingleFlingListener.onFling(e1, e2, velocityX, velocityY);
                }
                return false;
            }
        });

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            //不同于OnGestureListener.onSingleTapUp(MotionEvent),这个回调方法只在确信用户不会发生第二次敲击时调用
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                //点击事件
                if (mOnClickListener != null) {
                    mOnClickListener.onClick(mImageView);
                }
                final RectF displayRect = getDisplayRect();

                final float x = e.getX(), y = e.getY();

                //ImageView内单击调用
                if (mViewTapListener != null) {
                    mViewTapListener.onViewTap(mImageView, x, y);
                }

                if (displayRect != null) {

                    // Check to see if the user tapped on the photo
                    if (displayRect.contains(x, y)) {//判断是不是敲击在显示矩阵内,即图片内

                        //如果是的，就计算敲击百分比
                        float xResult = (x - displayRect.left)
                                / displayRect.width();
                        float yResult = (y - displayRect.top)
                                / displayRect.height();

                        if (mPhotoTapListener != null) {//敲击图片内回调
                            mPhotoTapListener.onPhotoTap(mImageView, xResult, yResult);
                        }
                        return true;
                    } else {
                        if (mOutsidePhotoTapListener != null) {//如果敲击在图片外回调
                            mOutsidePhotoTapListener.onOutsidePhotoTap(mImageView);
                        }
                    }
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent ev) {
                try {
                    float scale = getScale();//获取当前缩放比
                    float x = ev.getX();//获取敲击的坐标
                    float y = ev.getY();

                    if (scale < getMediumScale()) {
                        //如果之前的缩放小于中等值，现在就缩放到中等值，缩放锚点就是当前的敲击事件坐标，true表示需要动画缩放
                        setScale(getMediumScale(), x, y, true);
                    } else if (scale >= getMediumScale() && scale < getMaximumScale()) {
                        setScale(getMaximumScale(), x, y, true);
                    } else {
                        setScale(getMinimumScale(), x, y, true);
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    // Can sometimes happen when getX() and getY() is called
                }

                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                // Wait for the confirmed onDoubleTap() instead
                //由于不需要处理两次敲击间的其他事件，故这里不做处理
                return false;
            }
        });
    }

    public void setOnDoubleTapListener(GestureDetector.OnDoubleTapListener newOnDoubleTapListener) {
        this.mGestureDetector.setOnDoubleTapListener(newOnDoubleTapListener);
    }

    public void setOnScaleChangeListener(OnScaleChangedListener onScaleChangeListener) {
        this.mScaleChangeListener = onScaleChangeListener;
    }

    public void setOnSingleFlingListener(OnSingleFlingListener onSingleFlingListener) {
        this.mSingleFlingListener = onSingleFlingListener;
    }

    @Deprecated
    public boolean isZoomEnabled() {
        return mZoomEnabled;
    }

    public RectF getDisplayRect() {
        checkMatrixBounds();
        return getDisplayRect(getDrawMatrix());
    }

    public boolean setDisplayMatrix(Matrix finalMatrix) {
        if (finalMatrix == null) {
            throw new IllegalArgumentException("Matrix cannot be null");
        }

        if (mImageView.getDrawable() == null) {
            return false;
        }

        mSuppMatrix.set(finalMatrix);
        checkAndDisplayMatrix();

        return true;
    }

    public void setBaseRotation(final float degrees) {
        mBaseRotation = degrees % 360;
        update();
        setRotationBy(mBaseRotation);
        checkAndDisplayMatrix();
    }

    public void setRotationTo(float degrees) {
        mSuppMatrix.setRotate(degrees % 360);
        checkAndDisplayMatrix();
    }

    /**
     * 旋转角度
     * @param degrees
     */
    public void setRotationBy(float degrees) {
        mSuppMatrix.postRotate(degrees % 360);//后乘旋转角度
        checkAndDisplayMatrix();
    }

    public float getMinimumScale() {
        return mMinScale;
    }

    public float getMediumScale() {
        return mMidScale;
    }

    public float getMaximumScale() {
        return mMaxScale;
    }

    public float getScale() {
        return (float) Math.sqrt((float) Math.pow(getValue(mSuppMatrix, Matrix.MSCALE_X), 2) + (float) Math.pow(getValue(mSuppMatrix, Matrix.MSKEW_Y), 2));
    }

    public ScaleType getScaleType() {
        return mScaleType;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        // Update our base matrix, as the bounds have changed
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            updateBaseMatrix(mImageView.getDrawable());
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        boolean handled = false;

        //可以缩放且有图片时才能处理手势监听
        if (mZoomEnabled && Util.hasDrawable((ImageView) v)) {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ViewParent parent = v.getParent();
                    // First, disable the Parent from intercepting the touch event
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }

                    // If we're flinging, and the user presses down, cancel
                    //如果正在快速滑动，取消Fling事件
                    cancelFling();
                    break;

                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    //如果小于最小值，
                    if (getScale() < mMinScale) {
                        RectF rect = getDisplayRect();
                        if (rect != null) {
                            //恢复到最小
                            v.post(new AnimatedZoomRunnable(getScale(), mMinScale,
                                    rect.centerX(), rect.centerY()));
                            handled = true;
                        }
                    } else if (getScale() > mMaxScale) {//大于最大值
                        RectF rect = getDisplayRect();
                        if (rect != null) {
                            //恢复到最大值
                            v.post(new AnimatedZoomRunnable(getScale(), mMaxScale,
                                    rect.centerX(), rect.centerY()));
                            handled = true;
                        }
                    }
                    break;
            }

            // Try the Scale/Drag detector
            //如果mScaleDragDetector（缩放、拖拽）不为空，让它处理事件
            if (mScaleDragDetector != null) {
                boolean wasScaling = mScaleDragDetector.isScaling();
                boolean wasDragging = mScaleDragDetector.isDragging();

                handled = mScaleDragDetector.onTouchEvent(ev);

                boolean didntScale = !wasScaling && !mScaleDragDetector.isScaling();
                boolean didntDrag = !wasDragging && !mScaleDragDetector.isDragging();

                mBlockParentIntercept = didntScale && didntDrag;
            }

            // Check to see if the user double tapped
            //如果mGestureDetector（双击，长按）不为空，交给它处理事件
            if (mGestureDetector != null && mGestureDetector.onTouchEvent(ev)) {
                handled = true;
            }

        }

        return handled;
    }

    public void setAllowParentInterceptOnEdge(boolean allow) {
        mAllowParentInterceptOnEdge = allow;
    }

    public void setMinimumScale(float minimumScale) {
        Util.checkZoomLevels(minimumScale, mMidScale, mMaxScale);
        mMinScale = minimumScale;
    }

    public void setMediumScale(float mediumScale) {
        Util.checkZoomLevels(mMinScale, mediumScale, mMaxScale);
        mMidScale = mediumScale;
    }

    public void setMaximumScale(float maximumScale) {
        Util.checkZoomLevels(mMinScale, mMidScale, maximumScale);
        mMaxScale = maximumScale;
    }

    public void setScaleLevels(float minimumScale, float mediumScale, float maximumScale) {
        Util.checkZoomLevels(minimumScale, mediumScale, maximumScale);
        mMinScale = minimumScale;
        mMidScale = mediumScale;
        mMaxScale = maximumScale;
    }

    public void setOnLongClickListener(OnLongClickListener listener) {
        mLongClickListener = listener;
    }

    public void setOnClickListener(View.OnClickListener listener) {
        mOnClickListener = listener;
    }

    public void setOnMatrixChangeListener(OnMatrixChangedListener listener) {
        mMatrixChangeListener = listener;
    }

    public void setOnPhotoTapListener(OnPhotoTapListener listener) {
        mPhotoTapListener = listener;
    }

    public void setOnOutsidePhotoTapListener(OnOutsidePhotoTapListener mOutsidePhotoTapListener) {
        this.mOutsidePhotoTapListener = mOutsidePhotoTapListener;
    }

    public void setOnViewTapListener(OnViewTapListener listener) {
        mViewTapListener = listener;
    }

    public void setOnViewDragListener(OnViewDragListener listener) {
        mOnViewDragListener = listener;
    }

    public void setScale(float scale) {
        setScale(scale, false);
    }

    public void setScale(float scale, boolean animate) {
        setScale(scale,
                (mImageView.getRight()) / 2,
                (mImageView.getBottom()) / 2,
                animate);
    }

    public void setScale(float scale, float focalX, float focalY,
                         boolean animate) {
        // Check to see if the scale is within bounds
        if (scale < mMinScale || scale > mMaxScale) {
            throw new IllegalArgumentException("Scale must be within the range of minScale and maxScale");
        }

        //是否需要动画
        if (animate) {
            mImageView.post(new AnimatedZoomRunnable(getScale(), scale,
                    focalX, focalY));
        } else {
            //设置给mSuppMatrix矩阵
            mSuppMatrix.setScale(scale, scale, focalX, focalY);
            checkAndDisplayMatrix();
        }
    }

    /**
     * Set the zoom interpolator
     *
     * @param interpolator the zoom interpolator
     */
    public void setZoomInterpolator(Interpolator interpolator) {
        mInterpolator = interpolator;
    }

    /**
     * ImageView已被强制设置ScaleType为Matrix
     * 如果我们仍然需要ScaleType的显示效果使用此方法来模拟相关效果
     * 在photoVeiw中被重写
     * @param scaleType
     */
    public void setScaleType(ScaleType scaleType) {
        if (Util.isSupportedScaleType(scaleType) && scaleType != mScaleType) {
            mScaleType = scaleType;
            update();
        }
    }

    public boolean isZoomable() {
        return mZoomEnabled;
    }

    public void setZoomable(boolean zoomable) {
        mZoomEnabled = zoomable;
        update();
    }

    public void update() {
        if (mZoomEnabled) {
            // Update the base matrix using the current drawable
            //更新基础矩阵mBaseMatrix
            updateBaseMatrix(mImageView.getDrawable());
        } else {
            // Reset the Matrix...
            //重置矩阵
            resetMatrix();
        }
    }

    /**
     * Get the display matrix
     *
     * @param matrix target matrix to copy to
     */
    public void getDisplayMatrix(Matrix matrix) {
        matrix.set(getDrawMatrix());
    }

    /**
     * Get the current support matrix
     */
    public void getSuppMatrix(Matrix matrix) {
        matrix.set(mSuppMatrix);
    }

    private Matrix getDrawMatrix() {
        mDrawMatrix.set(mBaseMatrix);
        mDrawMatrix.postConcat(mSuppMatrix);
        return mDrawMatrix;
    }

    public Matrix getImageMatrix() {
        return mDrawMatrix;
    }

    public void setZoomTransitionDuration(int milliseconds) {
        this.mZoomDuration = milliseconds;
    }

    /**
     * Helper method that 'unpacks' a Matrix and returns the required value
     *
     * @param matrix     Matrix to unpack
     * @param whichValue Which value from Matrix.M* to return
     * @return returned value
     */
    private float getValue(Matrix matrix, int whichValue) {
        matrix.getValues(mMatrixValues);
        return mMatrixValues[whichValue];
    }

    /**
     * Resets the Matrix back to FIT_CENTER, and then displays its contents
     */
    private void resetMatrix() {
        mSuppMatrix.reset();
        setRotationBy(mBaseRotation);//设置初始的旋转角度
        setImageViewMatrix(getDrawMatrix());//把mDrawMatrix设置给ImageView，以对图片进行变化
        checkMatrixBounds();//检查Matrix边界
    }

    private void setImageViewMatrix(Matrix matrix) {
        mImageView.setImageMatrix(matrix);//应用矩阵

        // Call MatrixChangedListener if needed
        //回调监听
        if (mMatrixChangeListener != null) {
            RectF displayRect = getDisplayRect(matrix);
            if (displayRect != null) {
                mMatrixChangeListener.onMatrixChanged(displayRect);
            }
        }
    }

    /**
     * Helper method that simply checks the Matrix, and then displays the result
     */
    private void checkAndDisplayMatrix() {
        if (checkMatrixBounds()) {
            setImageViewMatrix(getDrawMatrix());
        }
    }

    /**
     * Helper method that maps the supplied Matrix to the current Drawable
     *
     * @param matrix - Matrix to map Drawable against
     * @return RectF - Displayed Rectangle
     */
    private RectF getDisplayRect(Matrix matrix) {
        Drawable d = mImageView.getDrawable();
        if (d != null) {
            mDisplayRect.set(0, 0, d.getIntrinsicWidth(),
                    d.getIntrinsicHeight());
            matrix.mapRect(mDisplayRect);
            return mDisplayRect;
        }
        return null;
    }

    /**
     * 每次更换图片时需update()刷新，在update()中被调用
     * Calculate Matrix for FIT_CENTER
     *
     * @param drawable - Drawable being displayed
     */
    private void updateBaseMatrix(Drawable drawable) {
        if (drawable == null) {
            return;
        }

        final float viewWidth = getImageViewWidth(mImageView);
        final float viewHeight = getImageViewHeight(mImageView);
        //获取Drawable的固有的宽高
        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();

        //单位矩阵
        mBaseMatrix.reset();

        //刚载入图片，初始化获取宽的缩放比
        final float widthScale = viewWidth / drawableWidth;
        final float heightScale = viewHeight / drawableHeight;

        if (mScaleType == ScaleType.CENTER) {
            //基础矩阵就平移两者的宽度差一半，以保持居中
            mBaseMatrix.postTranslate((viewWidth - drawableWidth) / 2F,
                    (viewHeight - drawableHeight) / 2F);

        } else if (mScaleType == ScaleType.CENTER_CROP) {
            //取最大值
            float scale = Math.max(widthScale, heightScale);
            //使最小的那一边也缩放到View的尺寸
            mBaseMatrix.postScale(scale, scale);
            //平移到中间
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                    (viewHeight - drawableHeight * scale) / 2F);

        } else if (mScaleType == ScaleType.CENTER_INSIDE) {
            //取较小的缩放值
            float scale = Math.min(1.0f, Math.min(widthScale, heightScale));
            //当图片宽高超出View宽高时调用,否则缩放还是1
            mBaseMatrix.postScale(scale, scale);
            //平移到中间
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                    (viewHeight - drawableHeight * scale) / 2F);

        } else {
            //如果是FIT_XX相关的缩放类型
            RectF mTempSrc = new RectF(0, 0, drawableWidth, drawableHeight);
            RectF mTempDst = new RectF(0, 0, viewWidth, viewHeight);

            if ((int) mBaseRotation % 180 != 0) {
                mTempSrc = new RectF(0, 0, drawableHeight, drawableWidth);
            }

            //直接根据Matrix提供的setRectToRect来设置
            switch (mScaleType) {
                case FIT_CENTER:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.CENTER);
                    break;

                case FIT_START:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.START);
                    break;

                case FIT_END:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.END);
                    break;

                case FIT_XY:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.FILL);
                    break;

                default:
                    break;
            }
        }

        //初始化完成，重置矩阵
        resetMatrix();
    }

    private boolean checkMatrixBounds() {

        final RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return false;
        }

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;//计算调整边界时要平移的距离

        final int viewHeight = getImageViewHeight(mImageView);
        //如果图片的高小于等于View，说明图片的垂直方向可以完全显示在View里面
        if (height <= viewHeight) {
            //于是根据缩放类型进行边界调整
            switch (mScaleType) {
                case FIT_START:
                    deltaY = -rect.top;
                    break;
                case FIT_END:
                    deltaY = viewHeight - height - rect.top;
                    break;
                default:
                    deltaY = (viewHeight - height) / 2 - rect.top;
                    break;
            }
        } else if (rect.top > 0) {//如果图片高度超出来View的高，但是rect.top > 0说明ImageView上边还有空余的区域
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        final int viewWidth = getImageViewWidth(mImageView);
        //如果宽度小于View的宽，进行相应调整
        if (width <= viewWidth) {
            switch (mScaleType) {
                case FIT_START:
                    deltaX = -rect.left;
                    break;
                case FIT_END:
                    deltaX = viewWidth - width - rect.left;
                    break;
                default:
                    deltaX = (viewWidth - width) / 2 - rect.left;
                    break;
            }
            mScrollEdge = EDGE_BOTH;//图片宽度小于View的宽度，说明两边显示在边缘内
        } else if (rect.left > 0) {
            mScrollEdge = EDGE_LEFT;//rect.left > 0表示显示在左边边缘内
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
            mScrollEdge = EDGE_RIGHT;
        } else {
            mScrollEdge = EDGE_NONE;
        }

        // Finally actually translate the matrix
        //最后，将平移给mSuppMatrix
        mSuppMatrix.postTranslate(deltaX, deltaY);
        return true;
    }

    /**
     * 获取控件宽度
     * @param imageView
     * @return
     */
    private int getImageViewWidth(ImageView imageView) {
        return imageView.getWidth() - imageView.getPaddingLeft() - imageView.getPaddingRight();
    }

    /**
     * 获取控件高度
     * @param imageView
     * @return
     */
    private int getImageViewHeight(ImageView imageView) {
        return imageView.getHeight() - imageView.getPaddingTop() - imageView.getPaddingBottom();
    }

    private void cancelFling() {
        if (mCurrentFlingRunnable != null) {
            mCurrentFlingRunnable.cancelFling();
            mCurrentFlingRunnable = null;
        }
    }

    private class AnimatedZoomRunnable implements Runnable {

        private final float mFocalX, mFocalY;//焦点
        private final long mStartTime;//开始时间
        private final float mZoomStart, mZoomEnd;

        public AnimatedZoomRunnable(final float currentZoom, final float targetZoom,
                                    final float focalX, final float focalY) {
            mFocalX = focalX;
            mFocalY = focalY;
            mStartTime = System.currentTimeMillis();
            mZoomStart = currentZoom;//开始时缩放倍数
            mZoomEnd = targetZoom;//目标缩放倍数
        }

        @Override
        public void run() {

            float t = interpolate();//获取当前的时间插值
            float scale = mZoomStart + t * (mZoomEnd - mZoomStart);//根据插值，获取当前时间的缩放值
            float deltaScale = scale / getScale();//获取缩放比，大于1表示在放大，小于1在缩小。deltaScale * getScale() = scale

            onGestureListener.onScale(deltaScale, mFocalX, mFocalY);//回调出去,deltaScale表示相对上次要缩放的比例

            // We haven't hit our target scale yet, so post ourselves again
            //插值小于1表示没有缩放完成，通过不停post进行执行动画
            if (t < 1f) {
                //Compat根据版本做了兼容处理，小于4.2用了   view.postDelayed，大于等于4.2用了view.postOnAnimation
                Compat.postOnAnimation(mImageView, this);
            }
        }

        /**
         * 计算当前时间的插值
         * @return
         */
        private float interpolate() {
            float t = 1f * (System.currentTimeMillis() - mStartTime) / mZoomDuration;
            t = Math.min(1f, t);
            t = mInterpolator.getInterpolation(t);
            return t;
        }
    }

    private class FlingRunnable implements Runnable {

        private final OverScroller mScroller;
        private int mCurrentX, mCurrentY;//拖动的目标值

        public FlingRunnable(Context context) {
            mScroller = new OverScroller(context);
        }

        public void cancelFling() {
            mScroller.forceFinished(true);//停止
        }

        public void fling(int viewWidth, int viewHeight, int velocityX,
                          int velocityY) {
            final RectF rect = getDisplayRect();
            if (rect == null) {
                return;
            }

            final int startX = Math.round(-rect.left);//四舍五入，左边的x坐标
            final int minX, maxX, minY, maxY;//Fling的边界值

            //如果图片的宽度大于View宽时就计算X的边界。
            if (viewWidth < rect.width()) {
                minX = 0;
                maxX = Math.round(rect.width() - viewWidth);
            } else {
                minX = maxX = startX;//如果图片宽小于View宽，就将三者设为一样
            }

            final int startY = Math.round(-rect.top);//竖直方向上
            //如果显示矩形高大于View的高,就计算边界
            if (viewHeight < rect.height()) {
                minY = 0;
                maxY = Math.round(rect.height() - viewHeight);
            } else {
                minY = maxY = startY;
            }

            mCurrentX = startX;
            mCurrentY = startY;

            // If we actually can move, fling the scroller
            //fling()函数会根据velocityX, velocityY的值大小计算滑动距离
            if (startX != maxX || startY != maxY) {
                mScroller.fling(startX, startY, velocityX, velocityY, minX,
                        maxX, minY, maxY, 0, 0);
            }
        }

        @Override
        public void run() {
            if (mScroller.isFinished()) {
                return; // remaining post that should not be handled
            }

            if (mScroller.computeScrollOffset()) {
                //获取当前的位置
                final int newX = mScroller.getCurrX();
                final int newY = mScroller.getCurrY();
                //将平移差值应用到mSuppMatrix
                mSuppMatrix.postTranslate(mCurrentX - newX, mCurrentY - newY);
                checkAndDisplayMatrix();

                mCurrentX = newX;
                mCurrentY = newY;

                // Post On animation
                //不停执行Runable来实现Fling惯性滚动效果
                Compat.postOnAnimation(mImageView, this);
            }
        }
    }
}
