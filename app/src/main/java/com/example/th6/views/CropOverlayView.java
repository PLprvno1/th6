package com.example.th6.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

public class CropOverlayView extends View {

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_TOP_LEFT = 1;
    private static final int HANDLE_TOP_RIGHT = 2;
    private static final int HANDLE_BOTTOM_LEFT = 3;
    private static final int HANDLE_BOTTOM_RIGHT = 4;
    private static final int HANDLE_TOP = 5;
    private static final int HANDLE_BOTTOM = 6;
    private static final int HANDLE_LEFT = 7;
    private static final int HANDLE_RIGHT = 8;
    private static final int HANDLE_CENTER = 9;

    private static final float HANDLE_RADIUS = 28f;
    private static final float TOUCH_TOLERANCE = 48f;
    private static final float MIN_CROP_SIZE = 100f;

    private final RectF cropRect = new RectF();
    private final RectF imageBounds = new RectF();

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int activeHandle = HANDLE_NONE;
    private float lastTouchX;
    private float lastTouchY;

    public CropOverlayView(Context context) {
        super(context);
        init();
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        dimPaint.setColor(Color.parseColor("#B3000000"));
        dimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);

        gridPaint.setColor(Color.parseColor("#80FFFFFF"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
    }

    public void setImageBounds(RectF bounds) {
        if (bounds == null || bounds.isEmpty()) return;
        RectF oldBounds = new RectF(imageBounds);
        RectF oldCrop = new RectF(cropRect);
        this.imageBounds.set(bounds);
        if (oldBounds.isEmpty() || oldCrop.isEmpty()) {
            setDefaultCropRect(bounds);
        } else {
            // Keep the selection attached to the image when its preview is zoomed or panned.
            float left = (oldCrop.left - oldBounds.left) / oldBounds.width();
            float top = (oldCrop.top - oldBounds.top) / oldBounds.height();
            float right = (oldCrop.right - oldBounds.left) / oldBounds.width();
            float bottom = (oldCrop.bottom - oldBounds.top) / oldBounds.height();
            cropRect.set(bounds.left + left * bounds.width(), bounds.top + top * bounds.height(),
                    bounds.left + right * bounds.width(), bounds.top + bottom * bounds.height());
            cropRect.intersect(bounds);
            if (cropRect.width() < MIN_CROP_SIZE || cropRect.height() < MIN_CROP_SIZE) setDefaultCropRect(bounds);
        }
        invalidate();
    }

    private void setDefaultCropRect(RectF bounds) {
        float insetX = bounds.width() * 0.05f;
        float insetY = bounds.height() * 0.05f;
        cropRect.set(bounds.left + insetX, bounds.top + insetY, bounds.right - insetX, bounds.bottom - insetY);
    }

    public void resetCropRect() {
        if (!imageBounds.isEmpty()) {
            setDefaultCropRect(imageBounds);
            invalidate();
        }
    }

    public RectF getCropRect() {
        return cropRect;
    }

    public Rect getCroppedBitmapArea(Bitmap bitmap, ImageView imageView) {
        if (bitmap == null || imageView == null || imageBounds.isEmpty() || cropRect.isEmpty()) {
            return null;
        }

        android.graphics.drawable.Drawable drawable = imageView.getDrawable();
        if (drawable == null || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) return null;
        android.graphics.Matrix inverse = new android.graphics.Matrix();
        if (!imageView.getImageMatrix().invert(inverse)) return null;
        float[] points = { cropRect.left, cropRect.top, cropRect.right, cropRect.top,
                cropRect.left, cropRect.bottom, cropRect.right, cropRect.bottom };
        inverse.mapPoints(points);
        float minX = Math.min(Math.min(points[0], points[2]), Math.min(points[4], points[6]));
        float maxX = Math.max(Math.max(points[0], points[2]), Math.max(points[4], points[6]));
        float minY = Math.min(Math.min(points[1], points[3]), Math.min(points[5], points[7]));
        float maxY = Math.max(Math.max(points[1], points[3]), Math.max(points[5], points[7]));
        float toBitmapX = (float) bitmap.getWidth() / drawable.getIntrinsicWidth();
        float toBitmapY = (float) bitmap.getHeight() / drawable.getIntrinsicHeight();
        int left = Math.max(0, (int) Math.floor(minX * toBitmapX));
        int top = Math.max(0, (int) Math.floor(minY * toBitmapY));
        int right = Math.min(bitmap.getWidth(), (int) Math.ceil(maxX * toBitmapX));
        int bottom = Math.min(bitmap.getHeight(), (int) Math.ceil(maxY * toBitmapY));
        if (right <= left || bottom <= top) return null;
        return new Rect(left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cropRect.isEmpty()) return;

        // 1. Draw Dim Overlay outside cropRect
        canvas.drawRect(imageBounds.left, imageBounds.top, imageBounds.right, cropRect.top, dimPaint);
        canvas.drawRect(imageBounds.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect.right, cropRect.top, imageBounds.right, cropRect.bottom, dimPaint);
        canvas.drawRect(imageBounds.left, cropRect.bottom, imageBounds.right, imageBounds.bottom, dimPaint);

        // 2. Draw Crop Border
        canvas.drawRect(cropRect, borderPaint);

        // 3. Draw 3x3 Grid
        float colWidth = cropRect.width() / 3f;
        float rowHeight = cropRect.height() / 3f;

        canvas.drawLine(cropRect.left + colWidth, cropRect.top, cropRect.left + colWidth, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left + 2 * colWidth, cropRect.top, cropRect.left + 2 * colWidth, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + rowHeight, cropRect.right, cropRect.top + rowHeight, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + 2 * rowHeight, cropRect.right, cropRect.top + 2 * rowHeight, gridPaint);

        // 4. Draw Handles (4 Corners)
        canvas.drawCircle(cropRect.left, cropRect.top, HANDLE_RADIUS, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.top, HANDLE_RADIUS, handlePaint);
        canvas.drawCircle(cropRect.left, cropRect.bottom, HANDLE_RADIUS, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.bottom, HANDLE_RADIUS, handlePaint);

        // 4 Mid-edges
        canvas.drawCircle(cropRect.centerX(), cropRect.top, HANDLE_RADIUS * 0.8f, handlePaint);
        canvas.drawCircle(cropRect.centerX(), cropRect.bottom, HANDLE_RADIUS * 0.8f, handlePaint);
        canvas.drawCircle(cropRect.left, cropRect.centerY(), HANDLE_RADIUS * 0.8f, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.centerY(), HANDLE_RADIUS * 0.8f, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            activeHandle = HANDLE_NONE;
            return true;
        }
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activeHandle = getTouchedHandle(x, y);
                lastTouchX = x;
                lastTouchY = y;
                return activeHandle != HANDLE_NONE;

            case MotionEvent.ACTION_MOVE:
                if (activeHandle != HANDLE_NONE) {
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    moveHandle(activeHandle, dx, dy);
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = HANDLE_NONE;
                break;
        }

        return super.onTouchEvent(event);
    }

    private int getTouchedHandle(float x, float y) {
        if (isNear(x, y, cropRect.left, cropRect.top)) return HANDLE_TOP_LEFT;
        if (isNear(x, y, cropRect.right, cropRect.top)) return HANDLE_TOP_RIGHT;
        if (isNear(x, y, cropRect.left, cropRect.bottom)) return HANDLE_BOTTOM_LEFT;
        if (isNear(x, y, cropRect.right, cropRect.bottom)) return HANDLE_BOTTOM_RIGHT;

        if (isNear(x, y, cropRect.centerX(), cropRect.top)) return HANDLE_TOP;
        if (isNear(x, y, cropRect.centerX(), cropRect.bottom)) return HANDLE_BOTTOM;
        if (isNear(x, y, cropRect.left, cropRect.centerY())) return HANDLE_LEFT;
        if (isNear(x, y, cropRect.right, cropRect.centerY())) return HANDLE_RIGHT;

        if (cropRect.contains(x, y)) return HANDLE_CENTER;

        return HANDLE_NONE;
    }

    private boolean isNear(float x1, float y1, float x2, float y2) {
        return Math.hypot(x1 - x2, y1 - y2) <= TOUCH_TOLERANCE;
    }

    private void moveHandle(int handle, float dx, float dy) {
        switch (handle) {
            case HANDLE_TOP_LEFT:
                cropRect.left = Math.min(cropRect.right - MIN_CROP_SIZE, Math.max(imageBounds.left, cropRect.left + dx));
                cropRect.top = Math.min(cropRect.bottom - MIN_CROP_SIZE, Math.max(imageBounds.top, cropRect.top + dy));
                break;

            case HANDLE_TOP_RIGHT:
                cropRect.right = Math.max(cropRect.left + MIN_CROP_SIZE, Math.min(imageBounds.right, cropRect.right + dx));
                cropRect.top = Math.min(cropRect.bottom - MIN_CROP_SIZE, Math.max(imageBounds.top, cropRect.top + dy));
                break;

            case HANDLE_BOTTOM_LEFT:
                cropRect.left = Math.min(cropRect.right - MIN_CROP_SIZE, Math.max(imageBounds.left, cropRect.left + dx));
                cropRect.bottom = Math.max(cropRect.top + MIN_CROP_SIZE, Math.min(imageBounds.bottom, cropRect.bottom + dy));
                break;

            case HANDLE_BOTTOM_RIGHT:
                cropRect.right = Math.max(cropRect.left + MIN_CROP_SIZE, Math.min(imageBounds.right, cropRect.right + dx));
                cropRect.bottom = Math.max(cropRect.top + MIN_CROP_SIZE, Math.min(imageBounds.bottom, cropRect.bottom + dy));
                break;

            case HANDLE_TOP:
                cropRect.top = Math.min(cropRect.bottom - MIN_CROP_SIZE, Math.max(imageBounds.top, cropRect.top + dy));
                break;

            case HANDLE_BOTTOM:
                cropRect.bottom = Math.max(cropRect.top + MIN_CROP_SIZE, Math.min(imageBounds.bottom, cropRect.bottom + dy));
                break;

            case HANDLE_LEFT:
                cropRect.left = Math.min(cropRect.right - MIN_CROP_SIZE, Math.max(imageBounds.left, cropRect.left + dx));
                break;

            case HANDLE_RIGHT:
                cropRect.right = Math.max(cropRect.left + MIN_CROP_SIZE, Math.min(imageBounds.right, cropRect.right + dx));
                break;

            case HANDLE_CENTER:
                if (cropRect.left + dx >= imageBounds.left && cropRect.right + dx <= imageBounds.right) {
                    cropRect.left += dx;
                    cropRect.right += dx;
                }
                if (cropRect.top + dy >= imageBounds.top && cropRect.bottom + dy <= imageBounds.bottom) {
                    cropRect.top += dy;
                    cropRect.bottom += dy;
                }
                break;
        }
    }
}
