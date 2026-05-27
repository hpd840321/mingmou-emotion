package com.school.emotion.service.analysis;

import org.springframework.stereotype.Component;

@Component
public class EmotionHealthCalculator {
    public double calculateHealth(double positiveRatio) {
        return Math.min(100, Math.max(0, positiveRatio * 100));
    }

    public boolean needsAttention(double negativeRatio, double threshold) {
        return negativeRatio > threshold;
    }
}
