package com.example.th6.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class SmartCleanupImageAnalyzerTest {
    @Test public void exactPerceptualMatchRequiresStrongStructureAgreement() {
        assertTrue(SmartCleanupImageAnalyzer.isDuplicate(0L, 0L, 0.99));
        assertFalse(SmartCleanupImageAnalyzer.isDuplicate(0L, 0L, 0.80));
    }

    @Test public void similarClassificationIsSeparateFromDuplicateClassification() {
        long base = 0L;
        long near = (1L << 5) | (1L << 8) | (1L << 13) | (1L << 24) | (1L << 40);
        assertTrue(SmartCleanupImageAnalyzer.isSimilar(base, near, 0.90));
        assertFalse(SmartCleanupImageAnalyzer.isDuplicate(base, near, 0.99));
        assertFalse(SmartCleanupImageAnalyzer.isSimilar(base, near, 0.70));
    }

    @Test public void identicalByteHashRemainsZeroDistance() {
        assertEquals(0, SmartCleanupImageAnalyzer.hammingDistance(0x12345678L, 0x12345678L));
        assertEquals(3, SmartCleanupImageAnalyzer.hammingDistance(0L, 0b1011L));
    }

    @Test public void perceptualHashAndStructureAgreeForIdenticalDownsampledInput() {
        byte[] pixels = new byte[32 * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) pixels[y * 32 + x] = (byte) ((x * 3 + y * 5) & 255);
        long first = SmartCleanupImageAnalyzer.perceptualHash(pixels);
        assertEquals(first, SmartCleanupImageAnalyzer.perceptualHash(pixels.clone()));
        assertEquals(1.0, SmartCleanupImageAnalyzer.luminanceSsim(pixels, pixels.clone()), 0.0001);
    }

    @Test public void similarFrequencyPatternWithOppositeStructureIsRejectedBySsim() {
        byte[] firstPixels = new byte[32 * 32];
        byte[] inversePixels = new byte[32 * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int value = ((x / 4 + y / 4) % 2) * 255;
            firstPixels[y * 32 + x] = (byte) value;
            inversePixels[y * 32 + x] = (byte) (255 - value);
        }
        double ssim = SmartCleanupImageAnalyzer.luminanceSsim(firstPixels, inversePixels);
        assertTrue(ssim < SmartCleanupImageAnalyzer.SIMILAR_MIN_SSIM);
        assertFalse(SmartCleanupImageAnalyzer.isDuplicate(
                SmartCleanupImageAnalyzer.perceptualHash(firstPixels),
                SmartCleanupImageAnalyzer.perceptualHash(inversePixels), ssim));
    }
}
