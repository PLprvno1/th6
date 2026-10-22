package com.example.th6.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.PorterDuff;

import com.example.th6.models.AdjustmentSettings;

/** Bitmap operations used by both the editor preview and final export. */
public final class BitmapEditor {
    public static final int FILTER_ORIGINAL = 0;
    public static final int FILTER_VIVID = 1;
    public static final int FILTER_WARM = 2;
    public static final int FILTER_COOL = 3;
    public static final int FILTER_MONO = 4;
    public static final int FILTER_DRAMATIC = 5;
    public static final int FILTER_FADE = 6;
    public static final int FILTER_SEPIA = 7; // Kept for old callers; no longer shown as a preset.

    private BitmapEditor() { }

    public static Bitmap rotate(Bitmap src, float degrees) {
        if (src == null) return null;
        Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    public static Bitmap flip(Bitmap src, boolean horizontal, boolean vertical) {
        if (src == null) return null;
        Matrix matrix = new Matrix();
        matrix.setScale(horizontal ? -1f : 1f, vertical ? -1f : 1f, src.getWidth() / 2f, src.getHeight() / 2f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    public static Bitmap cropNormalized(Bitmap src, RectF normalized) {
        if (src == null || normalized == null) return src;
        int left = clamp(Math.round(normalized.left * src.getWidth()), 0, src.getWidth() - 1);
        int top = clamp(Math.round(normalized.top * src.getHeight()), 0, src.getHeight() - 1);
        int right = clamp(Math.round(normalized.right * src.getWidth()), left + 1, src.getWidth());
        int bottom = clamp(Math.round(normalized.bottom * src.getHeight()), top + 1, src.getHeight());
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top);
    }

    public static Bitmap applyAdjustments(Bitmap src, AdjustmentSettings settings) {
        return renderColor(src, settings, FILTER_ORIGINAL, 0f);
    }

    public static Bitmap applyFilter(Bitmap src, int filterType) {
        return renderColor(src, new AdjustmentSettings(), filterType, 1f);
    }

    public static Bitmap renderColor(Bitmap src, AdjustmentSettings settings, int filter, float filterIntensity) {
        if (src == null) return null;
        AdjustmentSettings values = settings == null ? new AdjustmentSettings() : settings;
        float intensity = clamp(filterIntensity, 0f, 1f);
        if (values.isNeutral() && (filter == FILTER_ORIGINAL || intensity == 0f)) return src;
        Bitmap output = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        int[] row = new int[src.getWidth()];
        float exposure = (float) Math.pow(2d, clamp(values.getExposure(), -3f, 3f));
        float brightness = values.getBrightness() / 255f;
        float contrast = values.getContrast();
        float saturation = values.getSaturation();
        float temp = values.getTemperature() / 100f;
        float tint = values.getTint() / 100f;
        float filterContrast = filter == FILTER_DRAMATIC ? 1.35f : filter == FILTER_FADE ? 0.82f : 1f;
        float filterSaturation = filter == FILTER_VIVID ? 1.55f : filter == FILTER_DRAMATIC ? 1.18f
                : (filter == FILTER_MONO || filter == FILTER_SEPIA) ? 0f : 1f;

        for (int y = 0; y < src.getHeight(); y++) {
            if (Thread.currentThread().isInterrupted()) {
                output.recycle();
                return null;
            }
            src.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                int color = row[x];
                float r = Color.red(color) / 255f;
                float g = Color.green(color) / 255f;
                float b = Color.blue(color) / 255f;
                float luma = clamp(0.2126f * r + 0.7152f * g + 0.0722f * b, 0f, 1f);
                float shadowWeight = (1f - luma) * (1f - luma);
                float highlightWeight = luma * luma;
                r = r * exposure + brightness + values.getShadows() / 100f * 0.24f * shadowWeight
                        + values.getHighlights() / 100f * 0.20f * highlightWeight;
                g = g * exposure + brightness + values.getShadows() / 100f * 0.24f * shadowWeight
                        + values.getHighlights() / 100f * 0.20f * highlightWeight;
                b = b * exposure + brightness + values.getShadows() / 100f * 0.24f * shadowWeight
                        + values.getHighlights() / 100f * 0.20f * highlightWeight;
                r = (r - 0.5f) * contrast + 0.5f + temp * 0.10f + tint * 0.055f;
                g = (g - 0.5f) * contrast + 0.5f - tint * 0.07f;
                b = (b - 0.5f) * contrast + 0.5f - temp * 0.10f + tint * 0.035f;

                float adjustedLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = adjustedLuma + (r - adjustedLuma) * saturation;
                g = adjustedLuma + (g - adjustedLuma) * saturation;
                b = adjustedLuma + (b - adjustedLuma) * saturation;

                float fr = r, fg = g, fb = b;
                switch (filter) {
                    case FILTER_VIVID:
                    case FILTER_DRAMATIC:
                    case FILTER_FADE:
                        fr = (fr - 0.5f) * filterContrast + 0.5f;
                        fg = (fg - 0.5f) * filterContrast + 0.5f;
                        fb = (fb - 0.5f) * filterContrast + 0.5f;
                        float fl = 0.2126f * fr + 0.7152f * fg + 0.0722f * fb;
                        fr = fl + (fr - fl) * filterSaturation;
                        fg = fl + (fg - fl) * filterSaturation;
                        fb = fl + (fb - fl) * filterSaturation;
                        if (filter == FILTER_FADE) {
                            fr = fr * 0.94f + 0.045f;
                            fg = fg * 0.92f + 0.045f;
                            fb = fb * 0.90f + 0.05f;
                        }
                        break;
                    case FILTER_WARM:
                        fr += 0.10f; fg += 0.025f; fb -= 0.09f;
                        break;
                    case FILTER_COOL:
                        fr -= 0.06f; fg += 0.005f; fb += 0.09f;
                        break;
                    case FILTER_MONO:
                        fr = fg = fb = 0.2126f * fr + 0.7152f * fg + 0.0722f * fb;
                        break;
                    case FILTER_SEPIA:
                        float sr = 0.393f * r + 0.769f * g + 0.189f * b;
                        float sg = 0.349f * r + 0.686f * g + 0.168f * b;
                        float sb = 0.272f * r + 0.534f * g + 0.131f * b;
                        fr = sr; fg = sg; fb = sb;
                        break;
                    default:
                        break;
                }
                r += (fr - r) * intensity;
                g += (fg - g) * intensity;
                b += (fb - b) * intensity;
                row[x] = Color.argb(Color.alpha(color), toByte(r), toByte(g), toByte(b));
            }
            output.setPixels(row, 0, row.length, 0, y, row.length, 1);
        }
        return output;
    }

    public static void drawNormalizedStroke(Bitmap target, float[] points, int color, float normalizedWidth) {
        drawNormalizedStroke(target, points, color, normalizedWidth, false);
    }

    public static void drawNormalizedStroke(Bitmap target, float[] points, int color, float normalizedWidth, boolean erase) {
        if (target == null || points == null || points.length < 4) return;
        Path path = new Path();
        path.moveTo(points[0] * target.getWidth(), points[1] * target.getHeight());
        for (int i = 2; i + 1 < points.length; i += 2) {
            path.lineTo(points[i] * target.getWidth(), points[i + 1] * target.getHeight());
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        if (erase) paint.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(1f, normalizedWidth * target.getWidth()));
        new Canvas(target).drawPath(path, paint);
    }

    private static int toByte(float value) { return Math.round(clamp(value, 0f, 1f) * 255f); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
