package com.school.emotion.service.ai;

import com.school.emotion.client.VisionMindClient;
import com.school.emotion.model.dto.FaceDetectionResult;
import org.springframework.stereotype.Service;

@Service
public class FaceRecognitionClient implements FaceRecognitionService {

    private final VisionMindClient visionMind;

    public FaceRecognitionClient(VisionMindClient visionMind) {
        this.visionMind = visionMind;
    }

    @Override
    public FaceDetectionResult detectFaces(byte[] imageData) {
        return visionMind.detectFaces(imageData);
    }
}
