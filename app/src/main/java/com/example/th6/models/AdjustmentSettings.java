package com.example.th6.models;

import java.io.Serializable;

public class AdjustmentSettings implements Serializable {
    private float exposure = 0.0f;     // -3.0 to +3.0 EV
    private float brightness = 0.0f;   // -100.0 to +100.0
    private float contrast = 1.0f;     // 0.5 to 2.0
    private float saturation = 1.0f;   // 0.0 to 2.0
    private float highlights = 0.0f;   // -100.0 to +100.0
    private float shadows = 0.0f;      // -100.0 to +100.0
    private float temperature = 0.0f;  // -100.0 (Cool) to +100.0 (Warm)
    private float tint = 0.0f;         // -100.0 (Green) to +100.0 (Magenta)

    public AdjustmentSettings() {
        reset();
    }

    public AdjustmentSettings(AdjustmentSettings source) {
        if (source == null) reset();
        else {
            exposure = source.exposure;
            brightness = source.brightness;
            contrast = source.contrast;
            saturation = source.saturation;
            highlights = source.highlights;
            shadows = source.shadows;
            temperature = source.temperature;
            tint = source.tint;
        }
    }

    public AdjustmentSettings copy() { return new AdjustmentSettings(this); }

    public void reset() {
        this.exposure = 0.0f;
        this.brightness = 0.0f;
        this.contrast = 1.0f;
        this.saturation = 1.0f;
        this.highlights = 0.0f;
        this.shadows = 0.0f;
        this.temperature = 0.0f;
        this.tint = 0.0f;
    }

    public boolean isNeutral() {
        return exposure == 0.0f &&
                brightness == 0.0f &&
                contrast == 1.0f &&
                saturation == 1.0f &&
                highlights == 0.0f &&
                shadows == 0.0f &&
                temperature == 0.0f &&
                tint == 0.0f;
    }

    public float getExposure() { return exposure; }
    public void setExposure(float exposure) { this.exposure = exposure; }

    public float getBrightness() { return brightness; }
    public void setBrightness(float brightness) { this.brightness = brightness; }

    public float getContrast() { return contrast; }
    public void setContrast(float contrast) { this.contrast = contrast; }

    public float getSaturation() { return saturation; }
    public void setSaturation(float saturation) { this.saturation = saturation; }

    public float getHighlights() { return highlights; }
    public void setHighlights(float highlights) { this.highlights = highlights; }

    public float getShadows() { return shadows; }
    public void setShadows(float shadows) { this.shadows = shadows; }

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; }

    public float getTint() { return tint; }
    public void setTint(float tint) { this.tint = tint; }
}
