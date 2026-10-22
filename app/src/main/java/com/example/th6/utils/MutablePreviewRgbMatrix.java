package com.example.th6.utils;

import android.opengl.Matrix;

import androidx.media3.effect.RgbMatrix;
import androidx.media3.common.util.UnstableApi;

/** Mutable color transform sampled by Media3 for each rendered frame. */
@UnstableApi
public final class MutablePreviewRgbMatrix implements RgbMatrix {
    private volatile float[] matrix = identity();

    public void update(int brightness, int contrast, int saturation, int exposure,
                       int highlights, int shadows, int temperature, int filter) {
        float[] result = identity();
        float b = brightness / 100f;
        if (Math.abs(b) > .005f) {
            float[] m = identity();
            m[12] = b; m[13] = b; m[14] = b;
            result = after(result, m);
        }

        float contrastFactor = clamp(.5f + contrast / 100f, .5f, 2f);
        if (Math.abs(contrastFactor - 1f) > .005f) {
            result = after(result, affine(contrastFactor, (1f - contrastFactor) * .5f));
        }

        int filterSaturation = filter == 1 ? 25 : filter == 5 ? 10 : 0;
        float sat = clamp((saturation / 100f - 1f) * 100f + filterSaturation, -100f, 100f) / 100f;
        if (filter == 4) sat = -1f;
        if (Math.abs(sat) > .0001f) result = after(result, saturation(sat));

        float ev = exposure / 10f;
        float scale = (float) Math.pow(2d, ev);
        float temp = temperature / 100f;
        float red = scale * (1f + Math.max(0f, temp) * .25f);
        float blue = scale * (1f + Math.max(0f, -temp) * .25f);
        if (Math.abs(ev) > .005f || Math.abs(temp) > .005f) {
            result = after(result, scales(red, scale, blue));
        }
        if (filter == 2) result = after(result, scales(1.08f, 1.01f, .94f));
        if (filter == 3) result = after(result, scales(.94f, 1.01f, 1.08f));
        if (filter == 5) result = after(result, contrast(.35f));

        // Highlights/shadows use a nonlinear LUT during export. A tangent approximation around
        // mid-gray keeps preview responsive without rebuilding that LUT for every slider change.
        if (highlights != 0 || shadows != 0) {
            float h = highlights / 100f * .30f;
            float sh = shadows / 100f * .30f;
            float gain = 1f + .3f * h - .3f * sh;
            float offset = -.075f * h + .225f * sh;
            result = after(result, affine(gain, offset));
        }
        matrix = result;
    }

    @Override public float[] getMatrix(long presentationTimeUs, boolean useHdr) {
        return matrix;
    }

    @Override public boolean isNoOp(int inputWidth, int inputHeight) {
        float[] value = matrix;
        float[] identity = identity();
        for (int i = 0; i < 16; i++) if (Math.abs(value[i] - identity[i]) > .00001f) return false;
        return true;
    }

    private static float[] after(float[] current, float[] next) {
        float[] combined = new float[16];
        Matrix.multiplyMM(combined, 0, next, 0, current, 0);
        return combined;
    }

    private static float[] contrast(float value) {
        float factor = (1f + value) / (1.0001f - value);
        return affine(factor, (1f - factor) * .5f);
    }

    private static float[] affine(float scale, float offset) {
        float[] m = identity();
        m[0] = scale; m[5] = scale; m[10] = scale;
        m[12] = offset; m[13] = offset; m[14] = offset;
        return m;
    }

    private static float[] scales(float red, float green, float blue) {
        float[] m = identity();
        m[0] = red; m[5] = green; m[10] = blue;
        return m;
    }

    private static float[] saturation(float adjustment) {
        // The editor stores saturation as an adjustment around zero (-1..1), matching
        // HslAdjustment.adjustSaturation. Convert it to the actual multiplier (0..2):
        // -1 is grayscale, 0 keeps original color, +0.25 is 125% saturation.
        float amount = clamp(1f + adjustment, 0f, 2f);
        float inverse = 1f - amount;
        float lr = .2126f, lg = .7152f, lb = .0722f;
        float[] m = identity();
        m[0] = lr * inverse + amount; m[4] = lg * inverse;          m[8] = lb * inverse;
        m[1] = lr * inverse;          m[5] = lg * inverse + amount; m[9] = lb * inverse;
        m[2] = lr * inverse;          m[6] = lg * inverse;          m[10] = lb * inverse + amount;
        return m;
    }

    private static float[] identity() {
        float[] value = new float[16];
        Matrix.setIdentityM(value, 0);
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
