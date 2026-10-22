package com.example.th6.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Locale;

public class CloudStorageUsageTest {
    private static final long BYTES_PER_MB = 1024L * 1024L;

    @Test public void zeroUsageShowsZeroPercentAndFullRemaining() {
        CloudStorageUsage usage = CloudStorageUsage.fromBytes(0L);

        assertEquals(0.0d, usage.usedPercent, 0.0d);
        assertEquals("0%", usage.percentText);
        assertEquals("1024 MB", usage.remainingAmount);
        assertEquals(0, usage.progress);
    }

    @Test public void formatsNinePointFourFourMb() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            CloudStorageUsage usage = CloudStorageUsage.fromBytes(Math.round(9.44d * BYTES_PER_MB));
            assertEquals("9.44 MB", usage.usedAmount);
            assertEquals("0.92%", usage.percentText);
            assertEquals("1014.56 MB", usage.remainingAmount);
            assertEquals(92, usage.progress);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void halfQuotaShowsFiftyPercent() {
        CloudStorageUsage usage = CloudStorageUsage.fromBytes(512L * BYTES_PER_MB);

        assertEquals("512 MB", usage.usedAmount);
        assertEquals("50%", usage.percentText);
        assertEquals("512 MB", usage.remainingAmount);
        assertEquals(5000, usage.progress);
    }

    @Test public void fullQuotaShowsOneHundredPercent() {
        CloudStorageUsage usage = CloudStorageUsage.fromBytes(1024L * BYTES_PER_MB);

        assertEquals(100.0d, usage.usedPercent, 0.0d);
        assertEquals("100%", usage.percentText);
        assertEquals("0 MB", usage.remainingAmount);
        assertEquals(10000, usage.progress);
    }

    @Test public void overQuotaKeepsPercentageButCapsProgressAndRemaining() {
        CloudStorageUsage usage = CloudStorageUsage.fromBytes(2L * 1024L * BYTES_PER_MB);

        assertEquals(200.0d, usage.usedPercent, 0.0d);
        assertEquals("200%", usage.percentText);
        assertEquals("0 MB", usage.remainingAmount);
        assertEquals(10000, usage.progress);
    }

    @Test public void negativeByteCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CloudStorageUsage.fromBytes(-1L));
    }

}
