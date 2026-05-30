package com.school.emotion.model.dto;

import java.util.Map;

public class EmotionAnalysisResult {
    private Map<String, Float> emotions;
    private String dominantEmotion;
    private Float dominantConfidence;

    public Map<String, Float> getEmotions() { return emotions; }
    public void setEmotions(Map<String, Float> emotions) { this.emotions = emotions; }
    public String getDominantEmotion() { return dominantEmotion; }
    public void setDominantEmotion(String dominantEmotion) { this.dominantEmotion = dominantEmotion; }
    public Float getDominantConfidence() { return dominantConfidence; }
    public void setDominantConfidence(Float dominantConfidence) { this.dominantConfidence = dominantConfidence; }

    @SuppressWarnings("unchecked")
    public static EmotionAnalysisResult fromVmResponse(Map<String, Object> vmData) {
        EmotionAnalysisResult result = new EmotionAnalysisResult();
        Map<String, Object> emotionData = null;

        // Path 1: /v1/face/emotion direct endpoint → flat {emotion, label, probabilities}
        if (vmData.containsKey("label") && vmData.containsKey("emotion")) {
            emotionData = vmData;
        }
        // Path 2: /v1/face/attribute endpoint → nested {attributes: [{emotion: {label, probability}}]}
        if (emotionData == null && vmData.containsKey("attributes")) {
            var attrs = (java.util.List<Map<String, Object>>) vmData.get("attributes");
            if (attrs != null && !attrs.isEmpty()) {
                Object emo = attrs.get(0).get("emotion");
                if (emo instanceof Map) {
                    emotionData = (Map<String, Object>) emo;
                }
            }
        }
        if (emotionData != null) {
            result.setDominantEmotion((String) emotionData.get("label"));
            if (emotionData.get("probability") != null) {
                result.setDominantConfidence(((Number) emotionData.get("probability")).floatValue());
            }
            if (result.getDominantEmotion() == null) {
                result.setDominantConfidence(null);
            }

            // Parse per-dimension probabilities if present
            // Path A: /v1/face/attribute → probabilities as Map<String, Number>
            // Path B: /v1/face/emotion → probabilities as List<Number> (8-class array)
            Object probs = emotionData.get("probabilities");
            java.util.Map<String, Float> parsed = null;
            if (probs instanceof Map) {
                Map<String, Object> rawProbs = (Map<String, Object>) probs;
                parsed = new java.util.LinkedHashMap<>();
                for (var entry : rawProbs.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        parsed.put(entry.getKey(), ((Number) entry.getValue()).floatValue());
                    }
                }
            } else if (probs instanceof java.util.List) {
                java.util.List<Object> rawList = (java.util.List<Object>) probs;
                String[] EMOTION_KEYS = {"neutral", "happy", "sad", "surprise", "fear", "disgust", "angry", "contempt"};
                parsed = new java.util.LinkedHashMap<>();
                for (int i = 0; i < rawList.size() && i < EMOTION_KEYS.length; i++) {
                    if (rawList.get(i) instanceof Number) {
                        parsed.put(EMOTION_KEYS[i], ((Number) rawList.get(i)).floatValue());
                    }
                }
            }
            if (parsed != null && !parsed.isEmpty()) {
                result.setEmotions(parsed);
            }
        }
        return result;
    }
}
