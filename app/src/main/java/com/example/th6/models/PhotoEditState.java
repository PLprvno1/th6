package com.example.th6.models;

import android.graphics.RectF;

import com.example.th6.utils.BitmapEditor;

import java.util.ArrayList;
import java.util.List;

/** Lightweight edit recipe. It stores parameters and operations, never bitmap snapshots. */
public final class PhotoEditState {
    public static final int ROTATE = 0;
    public static final int FLIP = 1;
    public static final int CROP = 2;
    public static final int STROKE = 3;

    public static final class Operation {
        public final int type;
        public final float value;
        public final boolean horizontal;
        public final boolean vertical;
        public final RectF crop;
        public final float[] points;
        public final int color;
        public final float strokeWidth;
        public final boolean erase;

        private Operation(int type, float value, boolean horizontal, boolean vertical,
                          RectF crop, float[] points, int color, float strokeWidth, boolean erase) {
            this.type = type;
            this.value = value;
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.crop = crop == null ? null : new RectF(crop);
            this.points = points == null ? null : points.clone();
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.erase = erase;
        }

        public static Operation rotate(float degrees) { return new Operation(ROTATE, degrees, false, false, null, null, 0, 0, false); }
        public static Operation flip(boolean horizontal, boolean vertical) { return new Operation(FLIP, 0, horizontal, vertical, null, null, 0, 0, false); }
        public static Operation crop(RectF normalized) { return new Operation(CROP, 0, false, false, normalized, null, 0, 0, false); }
        public static Operation stroke(float[] points, int color, float width) { return stroke(points, color, width, false); }
        public static Operation stroke(float[] points, int color, float width, boolean erase) { return new Operation(STROKE, 0, false, false, null, points, color, width, erase); }
        public Operation copy() { return new Operation(type, value, horizontal, vertical, crop, points, color, strokeWidth, erase); }
    }

    public AdjustmentSettings adjustments = new AdjustmentSettings();
    public int filter = BitmapEditor.FILTER_ORIGINAL;
    public float filterIntensity = 1f;
    public final List<Operation> operations = new ArrayList<>();

    public PhotoEditState copy() {
        PhotoEditState result = new PhotoEditState();
        result.adjustments = adjustments.copy();
        result.filter = filter;
        result.filterIntensity = filterIntensity;
        for (Operation operation : operations) result.operations.add(operation.copy());
        return result;
    }

    public boolean isNeutral() {
        return operations.isEmpty() && adjustments.isNeutral()
                && filter == BitmapEditor.FILTER_ORIGINAL;
    }
}
