package com.school.emotion.service.ai;

import com.school.emotion.model.dto.EmotionAnalysisResult;

public interface EmotionRecognitionService {
    EmotionAnalysisResult analyzeEmotion(byte[] faceCrop);
}
