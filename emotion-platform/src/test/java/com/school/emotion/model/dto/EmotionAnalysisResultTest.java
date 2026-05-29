package com.school.emotion.model.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmotionAnalysisResultTest {

    @Test
    void fromVmResponse_shouldParseExpressionSuccessfully() {
        Map<String, Object> vmData = Map.of(
            "attributes", List.of(Map.of(
                "expression", Map.of("label", "happy", "probability", 0.87)
            ))
        );

        EmotionAnalysisResult result = EmotionAnalysisResult.fromVmResponse(vmData);

        assertEquals("happy", result.getDominantEmotion());
        assertEquals(0.87f, result.getDominantConfidence(), 0.01);
    }

    @Test
    void fromVmResponse_shouldReturnNullDominantWhenExpressionMissing() {
        Map<String, Object> vmData = Map.of(
            "attributes", List.of(Map.of(
                "gender", 1
            ))
        );

        EmotionAnalysisResult result = EmotionAnalysisResult.fromVmResponse(vmData);

        assertNull(result.getDominantEmotion());
    }

    @Test
    void fromVmResponse_shouldReturnNullWhenAttributesEmpty() {
        Map<String, Object> vmData = Map.of("attributes", List.of());

        EmotionAnalysisResult result = EmotionAnalysisResult.fromVmResponse(vmData);

        assertNull(result.getDominantEmotion());
    }

    @Test
    void fromVmResponse_shouldParseEmotionTopLevel() {
        Map<String, Object> vmData = Map.of(
            "emotion", Map.of("label", "sad", "probability", 0.72)
        );

        EmotionAnalysisResult result = EmotionAnalysisResult.fromVmResponse(vmData);

        assertEquals("sad", result.getDominantEmotion());
        assertEquals(0.72f, result.getDominantConfidence(), 0.01);
    }
}
