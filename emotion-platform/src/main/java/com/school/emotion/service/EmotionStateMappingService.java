package com.school.emotion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmotionStateMappingService {

    private static final Logger log = LoggerFactory.getLogger(EmotionStateMappingService.class);

    public enum EmotionState {
        ENGAGED,
        CONFUSED,
        WITHDRAWN,
        UNKNOWN
    }

    private static final Map<String, EmotionState> EMOTION_MAP = buildEmotionMap();

    private static final String[] EMOTION_KEYS = {
        "neutral", "happy", "sad", "surprise", "fear", "disgust", "angry"
    };

    private static final float UNKNOWN_THRESHOLD = 0.5f;

    private static Map<String, EmotionState> buildEmotionMap() {
        Map<String, EmotionState> map = new HashMap<>();
        map.put("neutral", EmotionState.ENGAGED);
        map.put("happy", EmotionState.ENGAGED);
        map.put("surprise", EmotionState.ENGAGED);
        map.put("disgust", EmotionState.CONFUSED);
        map.put("angry", EmotionState.CONFUSED);
        map.put("sad", EmotionState.WITHDRAWN);
        map.put("fear", EmotionState.WITHDRAWN);
        return Collections.unmodifiableMap(map);
    }

    public EmotionStateMappingService() {
    }

    public EmotionState mapFromProbabilities(Map<String, Float> probabilities) {
        if (probabilities == null || probabilities.isEmpty()) {
            log.debug("Empty probabilities, returning UNKNOWN");
            return EmotionState.UNKNOWN;
        }

        String bestKey = null;
        float maxProb = Float.MIN_VALUE;

        for (int i = 0; i < EMOTION_KEYS.length; i++) {
            String key = EMOTION_KEYS[i];
            Float value = probabilities.get(key);
            if (value != null && value > maxProb) {
                maxProb = value;
                bestKey = key;
            }
        }

        if (bestKey == null) {
            log.debug("No valid probability found, returning UNKNOWN");
            return EmotionState.UNKNOWN;
        }

        if (maxProb < UNKNOWN_THRESHOLD) {
            log.debug("Max probability {} < 0.5, returning UNKNOWN", maxProb);
            return EmotionState.UNKNOWN;
        }

        EmotionState state = EMOTION_MAP.get(bestKey);
        if (state == null) {
            log.warn("Unknown emotion key: {}, returning UNKNOWN", bestKey);
            return EmotionState.UNKNOWN;
        }

        log.debug("Mapped emotion {} (prob={}) to {}", bestKey, maxProb, state);
        return state;
    }

    public double calcCohesion(Map<EmotionState, Double> distribution) {
        if (distribution == null || distribution.isEmpty()) {
            return 0.0;
        }

        double total = 0.0;
        for (double count : distribution.values()) {
            total += count;
        }

        if (total == 0.0) {
            return 0.0;
        }

        double entropy = 0.0;
        for (double count : distribution.values()) {
            double p = count / total;
            if (p > 0.0) {
                entropy -= p * Math.log(p);
            }
        }

        double maxEntropy = Math.log(3);
        double cohesion = 1.0 - (entropy / maxEntropy);

        if (cohesion < 0.0) {
            cohesion = 0.0;
        }
        if (cohesion > 1.0) {
            cohesion = 1.0;
        }

        return cohesion;
    }
}
