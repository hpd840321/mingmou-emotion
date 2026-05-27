package com.school.emotion.service.ai;

import com.school.emotion.model.dto.FaceDetectionResult;

public interface FaceRecognitionService {
    FaceDetectionResult detectFaces(byte[] imageData);
}
