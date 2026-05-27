package com.school.emotion.service.analysis;

import org.springframework.stereotype.Component;

@Component
public class EngagementCalculator {
    public double calculate(double positiveRatio, double negativeRatio, double absenceRatio) {
        double emotionScore = positiveRatio * 60;
        double presenceScore = (1 - absenceRatio) * 40;
        return Math.min(100, Math.max(0, emotionScore + presenceScore));
    }
}
