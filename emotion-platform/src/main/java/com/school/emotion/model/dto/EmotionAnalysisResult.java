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
        if (emotionData != null) {
            result.setDominantEmotion((String) emotionData.get("label"));
            result.setDominantConfidence(((Number) emotionData.get("probability")).floatValue());
        }
        return result;
    }
}
