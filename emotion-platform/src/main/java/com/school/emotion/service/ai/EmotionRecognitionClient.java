package com.school.emotion.service.ai;

import com.school.emotion.client.VisionMindClient;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import org.springframework.stereotype.Service;

@Service
public class EmotionRecognitionClient implements EmotionRecognitionService {

    private final VisionMindClient visionMind;

    public EmotionRecognitionClient(VisionMindClient visionMind) {
        this.visionMind = visionMind;
    }

    @Override
    public EmotionAnalysisResult analyzeEmotion(byte[] faceCrop) {
        return visionMind.analyzeAttribute(faceCrop);
    }
}
