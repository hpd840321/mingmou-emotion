package com.school.emotion.service.ai;

import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.FaceDetectionResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class FaceRecognitionClient implements FaceRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(FaceRecognitionClient.class);
    private final RestClient restClient;

    public FaceRecognitionClient(@Value("${ai.face-recognition.url:http://localhost:8081}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @Retry(name = "faceRecognition")
    @CircuitBreaker(name = "faceRecognition")
    public FaceDetectionResult detectFaces(byte[] imageData) {
        try {
            return restClient.post()
                    .uri("/detect")
                    .body(imageData)
                    .retrieve()
                    .body(FaceDetectionResult.class);
        } catch (Exception e) {
            log.error("Face recognition API call failed", e);
            throw new AiServiceException("Face recognition failed", e);
        }
    }
}
