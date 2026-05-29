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
        Map<String, Object> emotionData = (Map<String, Object>) vmData.get("emotion");
        if (emotionData == null && vmData.containsKey("attributes")) {
            var attrs = (java.util.List<Map<String, Object>>) vmData.get("attributes");
            if (attrs != null && !attrs.isEmpty()) {
                Object expr = attrs.get(0).get("expression");
                if (expr instanceof Map) {
                    emotionData = (Map<String, Object>) expr;
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
            Object probs = emotionData.get("probabilities");
            if (probs instanceof Map) {
                Map<String, Object> rawProbs = (Map<String, Object>) probs;
                java.util.HashMap<String, Float> parsed = new java.util.HashMap<>();
                for (var entry : rawProbs.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        parsed.put(entry.getKey(), ((Number) entry.getValue()).floatValue());
                    }
                }
                if (!parsed.isEmpty()) {
                    result.setEmotions(parsed);
                }
            }
        }
        return result;
    }
}
