package com.example.th6.utils;

import android.graphics.Bitmap;

/** Small CPU-only perceptual hash and luminance SSIM helper for downsampled images. */
public final class SmartCleanupImageAnalyzer {
    public static final int DUPLICATE_MAX_DISTANCE = 4;
    public static final double DUPLICATE_MIN_SSIM = 0.93;
    public static final int SIMILAR_MAX_DISTANCE = 10;
    public static final double SIMILAR_MIN_SSIM = 0.82;
    private static final int N = 32;
    private static final int LOW = 8;
    private static final double[][] BASIS = createBasis();

    private SmartCleanupImageAnalyzer() { }

    private static double[][] createBasis() {
        double[][] basis = new double[LOW][N];
        for (int frequency = 0; frequency < LOW; frequency++) for (int coordinate = 0; coordinate < N; coordinate++)
            basis[frequency][coordinate] = Math.cos(((2 * coordinate + 1) * frequency * Math.PI) / (2 * N));
        return basis;
    }

    public static long perceptualHash(Bitmap bitmap) {
        return perceptualHash(graySignature(bitmap));
    }

    public static byte[] graySignature(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, N, N, true);
        try {
            int[] pixels = new int[N * N];
            scaled.getPixels(pixels, 0, N, 0, 0, N, N);
            byte[] gray = new byte[N * N];
            for (int i = 0; i < pixels.length; i++) gray[i] = (byte) Math.round(luminance(pixels[i]));
            return gray;
        } finally {
            if (scaled != bitmap && !scaled.isRecycled()) scaled.recycle();
        }
    }

    public static long perceptualHash(byte[] grayBytes) {
        if (grayBytes == null || grayBytes.length != N * N) throw new IllegalArgumentException("Expected 32x32 grayscale data");
        double[][] gray = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) gray[y][x] = grayBytes[y * N + x] & 255;
        double[][] coeff = new double[LOW][LOW];
        for (int v = 0; v < LOW; v++) for (int u = 0; u < LOW; u++) {
            double sum = 0;
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                sum += gray[y][x] * BASIS[u][x] * BASIS[v][y];
            }
            coeff[v][u] = sum;
        }
        double[] values = new double[LOW * LOW - 1];
        int k = 0;
        for (int v = 0; v < LOW; v++) for (int u = 0; u < LOW; u++) if (u != 0 || v != 0) values[k++] = coeff[v][u];
        java.util.Arrays.sort(values);
        double median = values[values.length / 2];
        long hash = 0;
        int bit = 0;
        for (int v = 0; v < LOW; v++) for (int u = 0; u < LOW; u++) {
            if (u == 0 && v == 0) continue;
            if (coeff[v][u] >= median) hash |= (1L << bit);
            bit++;
        }
        return hash;
    }

    public static double luminanceSsim(Bitmap first, Bitmap second) {
        return luminanceSsim(graySignature(first), graySignature(second));
    }

    public static double luminanceSsim(byte[] first, byte[] second) {
        if (first == null || second == null || first.length != N * N || second.length != N * N) return 0;
        double ma = 0, mb = 0;
        double[] ga = new double[first.length], gb = new double[second.length];
        for (int i = 0; i < first.length; i++) {
            ga[i] = first[i] & 255; gb[i] = second[i] & 255;
            ma += ga[i]; mb += gb[i];
        }
        ma /= first.length; mb /= second.length;
        double va = 0, vb = 0, cov = 0;
        for (int i = 0; i < first.length; i++) {
            double da = ga[i] - ma, db = gb[i] - mb;
            va += da * da; vb += db * db; cov += da * db;
        }
        va /= first.length - 1; vb /= second.length - 1; cov /= first.length - 1;
        double c1 = 6.5025, c2 = 58.5225;
        return ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2));
    }

    private static double luminance(int color) {
        return 0.299 * ((color >> 16) & 255) + 0.587 * ((color >> 8) & 255) + 0.114 * (color & 255);
    }

    public static int hammingDistance(long a, long b) { return Long.bitCount(a ^ b); }

    public static boolean isDuplicate(long hashA, long hashB, double ssim) {
        return hammingDistance(hashA, hashB) <= DUPLICATE_MAX_DISTANCE && ssim >= DUPLICATE_MIN_SSIM;
    }

    public static boolean isSimilar(long hashA, long hashB, double ssim) {
        int distance = hammingDistance(hashA, hashB);
        return distance > DUPLICATE_MAX_DISTANCE && distance <= SIMILAR_MAX_DISTANCE && ssim >= SIMILAR_MIN_SSIM;
    }
}
