package com.school.emotion.service.ai;

import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class EmotionRecognitionClient implements EmotionRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(EmotionRecognitionClient.class);
    private final RestClient restClient;

    public EmotionRecognitionClient(@Value("${ai.emotion-recognition.url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @Retry(name = "emotionRecognition")
    @CircuitBreaker(name = "emotionRecognition")
    public EmotionAnalysisResult analyzeEmotion(byte[] faceCrop) {
        try {
            return restClient.post()
                    .uri("/analyze")
                    .body(faceCrop)
                    .retrieve()
                    .body(EmotionAnalysisResult.class);
        } catch (Exception e) {
            log.error("Emotion recognition API call failed", e);
            throw new AiServiceException("Emotion recognition failed", e);
        }
    }
}
