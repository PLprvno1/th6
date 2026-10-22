package com.example.th6.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class DrawingOverlayView extends View {
    public interface StrokeListener {
        void onStrokeStarted();
        void onStrokeFinished();
    }

    public static class StrokeData {
        public final float[] points;
        public final int color;
        public final float normalizedWidth;
        public final boolean erase;
        public StrokeData(float[] points, int color, float normalizedWidth, boolean erase) {
            this.points = points.clone(); this.color = color; this.normalizedWidth = normalizedWidth;
            this.erase = erase;
        }
        public StrokeData copy() { return new StrokeData(points, color, normalizedWidth, erase); }
    }

    private static class PathItem {
        final Path path; final int color; final float width; final boolean erase;
        PathItem(Path path, int color, float width, boolean erase) { this.path = path; this.color = color; this.width = width; this.erase = erase; }
    }

    private final List<PathItem> visiblePaths = new ArrayList<>();
    private final List<StrokeData> strokes = new ArrayList<>();
    private final RectF imageBounds = new RectF();
    private final List<Float> currentPoints = new ArrayList<>();
    private Path currentPath;
    private int currentColor = Color.RED;
    private boolean eraser;
    private float currentWidth = 10f;
    private StrokeListener strokeListener;

    public DrawingOverlayView(Context context) { super(context); }
    public DrawingOverlayView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }

    public void setStrokeListener(@Nullable StrokeListener listener) { strokeListener = listener; }

    public void setImageBounds(RectF bounds) {
        if (bounds != null && !imageBounds.equals(bounds)) {
            imageBounds.set(bounds);
            rebuildVisiblePaths();
        }
    }

    public void setPenColor(int color) { currentColor = color; }
    public void setEraser(boolean enabled) { eraser = enabled; invalidate(); }
    public void setPenWidth(float width) { currentWidth = Math.max(1f, width); }
    public boolean hasPaths() { return !strokes.isEmpty() || currentPath != null; }

    public List<StrokeData> getStrokes() {
        List<StrokeData> result = new ArrayList<>();
        for (StrokeData stroke : strokes) result.add(stroke.copy());
        return result;
    }

    public void setStrokes(List<StrokeData> values) {
        strokes.clear();
        if (values != null) for (StrokeData stroke : values) strokes.add(stroke.copy());
        rebuildVisiblePaths();
    }

    public void undo() {
        if (!strokes.isEmpty()) { strokes.remove(strokes.size() - 1); rebuildVisiblePaths(); }
    }

    public void clear() {
        strokes.clear(); visiblePaths.clear(); currentPath = null; currentPoints.clear(); invalidate();
    }

    public void renderOntoBitmap(Bitmap target) {
        if (target == null || strokes.isEmpty()) return;
        Canvas canvas = new Canvas(target);
        for (StrokeData stroke : strokes) {
            Path path = createPath(stroke.points, target.getWidth(), target.getHeight());
            Paint paint = createPaint(stroke.color, stroke.normalizedWidth * target.getWidth());
            if (stroke.erase) paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
            canvas.drawPath(path, paint);
        }
    }

    private void rebuildVisiblePaths() {
        visiblePaths.clear();
        if (imageBounds.isEmpty()) { invalidate(); return; }
        for (StrokeData stroke : strokes) {
            Path path = createPath(stroke.points, imageBounds.width(), imageBounds.height());
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setScale(imageBounds.width(), imageBounds.height());
            matrix.postTranslate(imageBounds.left, imageBounds.top);
            path.transform(matrix);
            visiblePaths.add(new PathItem(path, stroke.color, stroke.normalizedWidth * imageBounds.width(), stroke.erase));
        }
        invalidate();
    }

    private Path createPath(float[] points, float width, float height) {
        Path path = new Path();
        if (points.length < 2) return path;
        path.moveTo(points[0] * width, points[1] * height);
        for (int i = 2; i + 1 < points.length; i += 2) path.lineTo(points[i] * width, points[i + 1] * height);
        return path;
    }

    private Paint createPaint(int color, float width) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        return paint;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (PathItem item : visiblePaths) {
            Paint paint = createPaint(item.color, item.width);
            if (item.erase) paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
            canvas.drawPath(item.path, paint);
        }
        if (currentPath != null) {
            Paint paint = createPaint(currentColor, currentWidth);
            if (eraser) paint.setColor(Color.GRAY);
            canvas.drawPath(currentPath, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            currentPath = null;
            currentPoints.clear();
            invalidate();
            return true;
        }
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!imageBounds.isEmpty() && !imageBounds.contains(x, y)) return false;
                if (strokeListener != null) strokeListener.onStrokeStarted();
                currentPath = new Path(); currentPath.moveTo(x, y); currentPoints.clear(); addNormalizedPoint(x, y);
                invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                if (currentPath != null) {
                    currentPath.lineTo(x, y); addNormalizedPoint(x, y); invalidate(); return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
                if (currentPath != null) {
                    currentPath.lineTo(x, y); addNormalizedPoint(x, y);
                    float[] points = new float[currentPoints.size()];
                    for (int i = 0; i < currentPoints.size(); i++) points[i] = currentPoints.get(i);
                    float normalizedWidth = imageBounds.isEmpty() ? 0.005f : currentWidth / imageBounds.width();
                    strokes.add(new StrokeData(points, currentColor, normalizedWidth, eraser));
                    currentPath = null; currentPoints.clear(); rebuildVisiblePaths();
                    if (strokeListener != null) strokeListener.onStrokeFinished();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                currentPath = null; currentPoints.clear(); invalidate(); return true;
            default: return true;
        }
    }

    private void addNormalizedPoint(float x, float y) {
        if (imageBounds.isEmpty()) return;
        currentPoints.add(Math.max(0f, Math.min(1f, (x - imageBounds.left) / imageBounds.width())));
        currentPoints.add(Math.max(0f, Math.min(1f, (y - imageBounds.top) / imageBounds.height())));
    }
}
