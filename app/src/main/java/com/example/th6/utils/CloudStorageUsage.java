package com.example.th6.utils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Formats the Backup Center's conventional 1 GiB display quota. */
public final class CloudStorageUsage {
    public static final double TOTAL_STORAGE_MB = 1024.0d;
    private static final double BYTES_PER_MB = 1024.0d * 1024.0d;

    public final double usedMb;
    public final double usedPercent;
    public final double remainingMb;
    public final int progress;
    public final String usedAmount;
    public final String percentText;
    public final String remainingAmount;

    private CloudStorageUsage(double usedMb, double usedPercent, double remainingMb,
                              int progress, String usedAmount, String percentText,
                              String remainingAmount) {
        this.usedMb = usedMb;
        this.usedPercent = usedPercent;
        this.remainingMb = remainingMb;
        this.progress = progress;
        this.usedAmount = usedAmount;
        this.percentText = percentText;
        this.remainingAmount = remainingAmount;
    }

    public static CloudStorageUsage fromBytes(long bytes) {
        if (bytes < 0L) throw new IllegalArgumentException("Storage bytes cannot be negative");

        double usedMb = bytes / BYTES_PER_MB;
        double usedPercent = usedMb / TOTAL_STORAGE_MB * 100.0d;
        double remainingMb = Math.max(0.0d, TOTAL_STORAGE_MB - usedMb);
        int progress = (int) Math.round(Math.min(100.0d, Math.max(0.0d, usedPercent)) * 100.0d);
        String usedLabel = usedMb >= TOTAL_STORAGE_MB
                ? format(usedMb / TOTAL_STORAGE_MB) + " GB"
                : format(usedMb) + " MB";
        String percentLabel = usedPercent == 0.0d ? "0" : format(usedPercent);

        return new CloudStorageUsage(usedMb, usedPercent, remainingMb, progress,
                usedLabel, percentLabel + "%", format(remainingMb) + " MB");
    }

    private static String format(double value) {
        DecimalFormat format = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault()));
        format.setGroupingUsed(false);
        return format.format(value);
    }

}
