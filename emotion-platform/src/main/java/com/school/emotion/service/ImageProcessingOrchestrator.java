package com.school.emotion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.entity.EmotionRecord;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.model.enums.FaceStatus;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.service.ai.EmotionRecognitionService;
import com.school.emotion.service.ai.FaceRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class ImageProcessingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingOrchestrator.class);

    private final ClassImageRepository classImageRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final FaceRecognitionService faceService;
    private final EmotionRecognitionService emotionService;
    private final ObjectMapper objectMapper;

    public ImageProcessingOrchestrator(
            ClassImageRepository classImageRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionRecordRepository emotionRecordRepository,
            FaceRecognitionService faceService,
            EmotionRecognitionService emotionService,
            ObjectMapper objectMapper) {
        this.classImageRepository = classImageRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.faceService = faceService;
        this.emotionService = emotionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Polls for pending images every 5 seconds.
     * In production, Redis Stream listener would be used.
     */
    @Scheduled(fixedRate = 5000)
    public void pollPendingImages() {
        List<ClassImage> pending = classImageRepository.findByStatus(ImageStatus.PENDING);
        for (ClassImage classImage : pending) {
            try {
                processImage(classImage);
            } catch (Exception e) {
                log.error("Failed to process image {}", classImage.getId(), e);
                classImage.setStatus(ImageStatus.FAILED);
                classImage.setErrorMessage(e.getMessage());
                classImageRepository.save(classImage);
            }
        }
    }

    @Transactional
    public void processImage(ClassImage classImage) {
        log.info("Processing image: {}", classImage.getId());
        classImage.setStatus(ImageStatus.PROCESSING);
        classImageRepository.save(classImage);

        // 1. Read image bytes
        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(Path.of(classImage.getImageUrl()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image file", e);
        }

        // 2. Face detection
        FaceDetectionResult faceResult = faceService.detectFaces(imageBytes);

        if (faceResult.getFaces() == null || faceResult.getFaces().isEmpty()) {
            log.warn("No faces detected in image {}", classImage.getId());
            classImage.setStatus(ImageStatus.COMPLETED);
            classImageRepository.save(classImage);
            return;
        }

        // 3. Process each detected face
        for (FaceDetectionResult.Face face : faceResult.getFaces()) {
            FaceRecord faceRecord = new FaceRecord();
            faceRecord.setClassImage(classImage);
            faceRecord.setStudent(null); // Unknown initially
            try {
                faceRecord.setBbox(objectMapper.writeValueAsString(face.getBbox()));
            } catch (Exception e) {
                faceRecord.setBbox(null);
            }
            faceRecord.setConfidence(face.getConfidence());
            faceRecord.setStatus(FaceStatus.DETECTED);
            faceRecord = faceRecordRepository.save(faceRecord);

            // 4. Emotion recognition (for simplicity, pass full image)
            // In production, crop face region first
            try {
                EmotionAnalysisResult emotionResult = emotionService.analyzeEmotion(imageBytes);

                EmotionRecord emotionRecord = new EmotionRecord();
                emotionRecord.setFaceRecord(faceRecord);
                emotionRecord.setDominantEmotion(emotionResult.getDominantEmotion());
                emotionRecord.setDominantConfidence(emotionResult.getDominantConfidence());
                emotionRecordRepository.save(emotionRecord);

                // Set identified status since we got emotion data
                faceRecord.setStatus(FaceStatus.IDENTIFIED);
                faceRecordRepository.save(faceRecord);

            } catch (AiServiceException e) {
                log.warn("Emotion analysis failed for face {}", faceRecord.getId(), e);
                faceRecord.setStatus(FaceStatus.UNIDENTIFIED);
                faceRecordRepository.save(faceRecord);
            }
        }

        classImage.setStatus(ImageStatus.COMPLETED);
        classImageRepository.save(classImage);
        log.info("Completed processing image: {}", classImage.getId());
    }
}
