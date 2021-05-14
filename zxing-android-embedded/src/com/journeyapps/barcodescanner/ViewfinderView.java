/*
 * Copyright (C) 2008 ZXing authors
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

package com.journeyapps.barcodescanner;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.R;

import java.util.ArrayList;
import java.util.List;

/**
 * This view is overlaid on top of the camera preview. It adds the viewfinder rectangle and partial
 * transparency outside it, as well as the laser scanner animation and result points.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public class ViewfinderView extends View {
    protected static final String TAG = ViewfinderView.class.getSimpleName();

    protected static final int[] SCANNER_ALPHA = {0, 64, 128, 192, 255, 192, 128, 64};
    protected static final long ANIMATION_DELAY = 80L;
    protected static final int CURRENT_POINT_OPACITY = 0xA0;
    protected static final int MAX_RESULT_POINTS = 20;
    protected static final int POINT_SIZE = 6;

    //border
    protected Paint mBorderPaint;
    protected int mBorderLineLength;
    private final int mDefaultBorderColor = ContextCompat.getColor(this.getContext(), android.R.color.white);
    private final int mDefaultBorderStrokeWidth = 5;
    private final int mDefaultBorderLineLength = 120;
    private final int mDefaultBorderDistance = 30;

    protected final Paint paint;
    protected Bitmap resultBitmap;
    protected int maskColor;
    protected final int resultColor;
    protected final int laserColor;
    protected final int resultPointColor;
    protected boolean laserVisibility;
    protected int scannerAlpha;
    protected List<ResultPoint> possibleResultPoints;
    protected List<ResultPoint> lastPossibleResultPoints;
    protected CameraPreview cameraPreview;

    private int endY = 0;
    private int endYGradientTop = 0;
    private int shadeDirection = 0;
    private int scanLineSpeed = 2;
    private boolean slidingDown = true;

    private Paint paintScanLine = new Paint(Paint.ANTI_ALIAS_FLAG) {
        {
            setDither(true);
            setStrokeWidth(2);
            setColor(Color.WHITE);
        }
    };

    // Cache the framingRect and previewSize, so that we can still draw it after the preview
    // stopped.
    protected Rect framingRect;
    protected Size previewSize;

    // This constructor is used when the class is built from an XML resource.
    public ViewfinderView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Initialize these once for performance rather than calling them every time in onDraw().
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Resources resources = getResources();

        // Get setted attributes on view
        TypedArray attributes = getContext().obtainStyledAttributes(attrs, R.styleable.zxing_finder);

        this.maskColor = attributes.getColor(R.styleable.zxing_finder_zxing_viewfinder_mask,
                resources.getColor(R.color.zxing_viewfinder_mask));
        this.resultColor = attributes.getColor(R.styleable.zxing_finder_zxing_result_view,
                resources.getColor(R.color.zxing_result_view));
        this.laserColor = attributes.getColor(R.styleable.zxing_finder_zxing_viewfinder_laser,
                resources.getColor(R.color.zxing_viewfinder_laser));
        this.resultPointColor = attributes.getColor(R.styleable.zxing_finder_zxing_possible_result_points,
                resources.getColor(R.color.zxing_possible_result_points));
        this.laserVisibility = attributes.getBoolean(R.styleable.zxing_finder_zxing_viewfinder_laser_visibility,
                false);

        attributes.recycle();

        scannerAlpha = 0;
        possibleResultPoints = new ArrayList<>(MAX_RESULT_POINTS);
        lastPossibleResultPoints = new ArrayList<>(MAX_RESULT_POINTS);

        //border paint
        mBorderPaint = new Paint();
        mBorderPaint.setColor(mDefaultBorderColor);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(mDefaultBorderStrokeWidth);
        mBorderPaint.setAntiAlias(true);

        mBorderLineLength = mDefaultBorderLineLength;
    }

    public void setCameraPreview(CameraPreview view) {
        this.cameraPreview = view;
        view.addStateListener(new CameraPreview.StateListener() {
            @Override
            public void previewSized() {
                refreshSizes();
                invalidate();
            }

            @Override
            public void previewStarted() {

            }

            @Override
            public void previewStopped() {

            }

            @Override
            public void cameraError(Exception error) {

            }

            @Override
            public void cameraClosed() {

            }
        });
    }

    protected void refreshSizes() {
        if (cameraPreview == null) {
            return;
        }
        Rect framingRect = cameraPreview.getFramingRect();
        Size previewSize = cameraPreview.getPreviewSize();
        if (framingRect != null && previewSize != null) {
            this.framingRect = framingRect;
            this.previewSize = previewSize;

        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        refreshSizes();
        if (framingRect == null || previewSize == null) {
            return;
        }

        final Rect frame = framingRect;
        final Size previewSize = this.previewSize;

        final int width = canvas.getWidth();
        final int height = canvas.getHeight();

        // Draw the exterior (i.e. outside the framing rect) darkened
        paint.setColor(resultBitmap != null ? resultColor : maskColor);
        canvas.drawRect(0, 0, width, frame.top, paint);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom + 1, paint);
        canvas.drawRect(frame.right + 1, frame.top, width, frame.bottom + 1, paint);
        canvas.drawRect(0, frame.bottom + 1, width, height, paint);

        if (resultBitmap != null) {
            // Draw the opaque result bitmap over the scanning rectangle
            paint.setAlpha(CURRENT_POINT_OPACITY);
            canvas.drawBitmap(resultBitmap, null, frame, paint);
        } else {

            final float scaleX = this.getWidth() / (float) previewSize.width;
            final float scaleY = this.getHeight() / (float) previewSize.height;

            // draw the last possible result points
            if (!lastPossibleResultPoints.isEmpty()) {
                paint.setAlpha(CURRENT_POINT_OPACITY / 2);
                paint.setColor(resultPointColor);
                float radius = POINT_SIZE / 2.0f;
                for (final ResultPoint point : lastPossibleResultPoints) {
                    canvas.drawCircle(
                            (int) (point.getX() * scaleX),
                            (int) (point.getY() * scaleY),
                            radius, paint
                    );
                }
                lastPossibleResultPoints.clear();
            }

            // draw current possible result points
            if (!possibleResultPoints.isEmpty()) {
                paint.setAlpha(CURRENT_POINT_OPACITY);
                paint.setColor(resultPointColor);
                for (final ResultPoint point : possibleResultPoints) {
                    canvas.drawCircle(
                            (int) (point.getX() * scaleX),
                            (int) (point.getY() * scaleY),
                            POINT_SIZE, paint
                    );
                }

                // swap and clear buffers
                final List<ResultPoint> temp = possibleResultPoints;
                possibleResultPoints = lastPossibleResultPoints;
                lastPossibleResultPoints = temp;
                possibleResultPoints.clear();
            }

            // Request another update at the animation interval, but only repaint the laser line,
            // not the entire viewfinder mask.
            /*postInvalidateDelayed(ANIMATION_DELAY,
                    frame.left - POINT_SIZE,
                    frame.top - POINT_SIZE,
                    frame.right + POINT_SIZE,
                    frame.bottom + POINT_SIZE);*/

        }

        drawViewFinderBorder(canvas);

        if (laserVisibility) {
            drawScanLine(canvas);
        }

        postInvalidate();
    }

    public void drawScanLine(Canvas canvas) {

        if (endY == 0) {
            endY = framingRect.top;
            endYGradientTop = framingRect.top;
            slidingDown = true;
        }

        if (slidingDown) {
            Shader shader = new LinearGradient(0,endYGradientTop - 40, 0, endYGradientTop + shadeDirection,Color.TRANSPARENT, Color.WHITE, Shader.TileMode.MIRROR);
            Paint paint = new Paint();
            paint.setShader(shader);
            paint.setAlpha(100);
            canvas.drawRect(new RectF(framingRect.left, endYGradientTop - 40, framingRect.right, endYGradientTop + shadeDirection), paint);
        } else {
            Shader shader = new LinearGradient(0,endYGradientTop, 0, endYGradientTop + 40,Color.WHITE, Color.TRANSPARENT, Shader.TileMode.MIRROR);
            Paint paint = new Paint();
            paint.setShader(shader);
            paint.setAlpha(100);
            canvas.drawRect(new RectF(framingRect.left, endYGradientTop, framingRect.right, endYGradientTop + 40), paint);
        }

        // you need to use speed 1 when you are close to the edges
        if ((endY > framingRect.bottom - 20) && (endY < framingRect.bottom + 20)) {
            scanLineSpeed = 1;
        } else if ((endY > framingRect.top - 20) && (endY < framingRect.top + 20)) {
            scanLineSpeed = 1;
        } else {
            scanLineSpeed = 3;
        }

        canvas.drawLine(framingRect.left, endY, framingRect.right, endY, paintScanLine);

        if (endY != framingRect.bottom && slidingDown) { // set end points
            endY = endY + scanLineSpeed;
            endYGradientTop = endYGradientTop + scanLineSpeed;
        } else if (endY == framingRect.bottom) {
            slidingDown = false;
            endY = endY - scanLineSpeed;
            endYGradientTop = endYGradientTop - scanLineSpeed;
        } else if (endY != framingRect.top && !(slidingDown)) {
            endY = endY - scanLineSpeed;
            endYGradientTop = endYGradientTop - scanLineSpeed;
        } else if (endY == framingRect.top) {
            slidingDown = true;
            endYGradientTop = endYGradientTop + scanLineSpeed;
            endY = endY + scanLineSpeed;
        }
    }

    public void drawViewFinderBorder(Canvas canvas) {

        // Top-left corner
        Path path = new Path();
        path.moveTo(framingRect.left - mDefaultBorderDistance, framingRect.top + mBorderLineLength);
        path.lineTo(framingRect.left - mDefaultBorderDistance, framingRect.top - mDefaultBorderDistance);
        path.lineTo(framingRect.left + mBorderLineLength, framingRect.top - mDefaultBorderDistance);
        canvas.drawPath(path, mBorderPaint);

        // Top-right corner
        path.moveTo(framingRect.right + mDefaultBorderDistance , framingRect.top + mBorderLineLength);
        path.lineTo(framingRect.right + mDefaultBorderDistance, framingRect.top - mDefaultBorderDistance);
        path.lineTo(framingRect.right - mBorderLineLength, framingRect.top - mDefaultBorderDistance);
        canvas.drawPath(path, mBorderPaint);

        // Bottom-right corner
        path.moveTo(framingRect.right + mDefaultBorderDistance, framingRect.bottom - mBorderLineLength);
        path.lineTo(framingRect.right + mDefaultBorderDistance, framingRect.bottom + mDefaultBorderDistance);
        path.lineTo(framingRect.right - mBorderLineLength, framingRect.bottom + mDefaultBorderDistance);
        canvas.drawPath(path, mBorderPaint);

        // Bottom-left corner
        path.moveTo(framingRect.left - mDefaultBorderDistance, framingRect.bottom - mBorderLineLength);
        path.lineTo(framingRect.left - mDefaultBorderDistance, framingRect.bottom + mDefaultBorderDistance);
        path.lineTo(framingRect.left + mBorderLineLength, framingRect.bottom + mDefaultBorderDistance);
        canvas.drawPath(path, mBorderPaint);
    }

    public void drawViewfinder() {
        Bitmap resultBitmap = this.resultBitmap;
        this.resultBitmap = null;
        if (resultBitmap != null) {
            resultBitmap.recycle();
        }
        invalidate();
    }

    /**
     * Draw a bitmap with the result points highlighted instead of the live scanning display.
     *
     * @param result An image of the result.
     */
    public void drawResultBitmap(Bitmap result) {
        resultBitmap = result;
        invalidate();
    }

    /**
     * Only call from the UI thread.
     *
     * @param point a point to draw, relative to the preview frame
     */
    public void addPossibleResultPoint(ResultPoint point) {
        if (possibleResultPoints.size() < MAX_RESULT_POINTS)
            possibleResultPoints.add(point);
    }

    public void setMaskColor(int maskColor) {
        this.maskColor = maskColor;
    }

    public void setLaserVisibility(boolean visible) {
        this.laserVisibility = visible;
    }
}
