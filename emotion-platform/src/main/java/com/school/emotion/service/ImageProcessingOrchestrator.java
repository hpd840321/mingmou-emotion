package com.school.emotion.service;

import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.service.ai.EmotionRecognitionService;
import com.school.emotion.service.ai.FaceRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.Comparator;

@Service
public class ImageProcessingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingOrchestrator.class);
    private static final int MAX_CONCURRENT = 6;
    private static final int BATCH_SIZE = 20;
    private static final int QUEUE_CAPACITY = 120;

    @Value("${app.face.confidence-threshold:0.5}")
    private float confidenceThreshold;

    private final ClassImageRepository classImageRepository;
    private final FaceRecognitionService faceService;
    private final EmotionRecognitionService emotionService;
    private final ImageProcessingPersistenceService persistenceService;
    private final ExecutorService executor;

    public ImageProcessingOrchestrator(
            ClassImageRepository classImageRepository,
            FaceRecognitionService faceService,
            EmotionRecognitionService emotionService,
            ImageProcessingPersistenceService persistenceService) {
        this.classImageRepository = classImageRepository;
        this.faceService = faceService;
        this.emotionService = emotionService;
        this.persistenceService = persistenceService;
        this.executor = new ThreadPoolExecutor(
                MAX_CONCURRENT, MAX_CONCURRENT,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Polls for pending images, waiting 5s after each batch completes.
     * Replaced by ImageIngestConsumer (Redis stream) + FaceProcessingPipeline.
     * Retained for manual/fallback invocation.
     */
    public void pollPendingImages() {
        List<ClassImage> pending = classImageRepository.findByStatus(ImageStatus.PENDING);
        if (pending.isEmpty()) return;

        int batchSize = Math.min(pending.size(), BATCH_SIZE);
        List<ClassImage> batch = pending.subList(0, batchSize);
        log.info("Processing {} images ({} total pending)", batch.size(), pending.size());

        CompletableFuture<?>[] futures = batch.stream()
                .map(ci -> CompletableFuture.runAsync(() -> {
                    try {
                        processImage(ci);
                    } catch (Exception e) {
                        log.error("Failed to process image {}", ci.getId(), e);
                        try {
                            persistenceService.markFailed(ci.getId(),
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                        } catch (Exception ex) {
                            log.error("Failed to mark image {} as failed", ci.getId(), ex);
                        }
                    }
                }, executor))
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).get(180, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Batch processing interrupted or timed out: {}", e.getMessage());
        }
    }

    /**
     * Process a single image: read file, call VisionMind APIs, save results.
     * API calls happen outside transaction to avoid holding DB connections.
     */
    public void processImage(ClassImage classImage) {
        log.info("Processing image: {}", classImage.getId());

        persistenceService.markProcessing(classImage.getId());

        // Phase 1: Read image and call VisionMind (no DB transaction)
        byte[] imageBytes;
        FaceDetectionResult faceResult;
        EmotionAnalysisResult emotionResult;

        try {
            imageBytes = Files.readAllBytes(Path.of(classImage.getImageUrl()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image file", e);
        }

        // Face detection (slow API call - outside transaction)
        faceResult = faceService.detectFaces(imageBytes);

        List<FaceDetectionResult.Face> faces = faceResult.getFaces();
        if (faces == null || faces.isEmpty()) {
            log.warn("No faces detected in image {}", classImage.getId());
            persistenceService.markCompleted(classImage.getId());
            return;
        }

        // Filter by confidence threshold and pick the highest confidence face
        FaceDetectionResult.Face bestFace = faces.stream()
                .filter(f -> f.getConfidence() != null && f.getConfidence() >= confidenceThreshold)
                .max(Comparator.comparing(FaceDetectionResult.Face::getConfidence))
                .orElse(null);

        if (bestFace == null) {
            log.warn("No face meets confidence threshold {} in image {} (detected {}, lowest={}, highest={})",
                    confidenceThreshold, classImage.getId(), faces.size(),
                    faces.stream().mapToDouble(f -> f.getConfidence() != null ? f.getConfidence() : 0).min().orElse(0),
                    faces.stream().mapToDouble(f -> f.getConfidence() != null ? f.getConfidence() : 0).max().orElse(0));
            persistenceService.markCompleted(classImage.getId());
            return;
        }

        // Emotion recognition (slow API call - outside transaction)
        try {
            emotionResult = emotionService.analyzeEmotion(imageBytes);
        } catch (AiServiceException e) {
            log.warn("Emotion analysis failed for image {}", classImage.getId(), e);
            // Still save face records even if emotion fails
            emotionResult = null;
        }

        // Phase 2: Save results to DB (via separate bean for proper @Transactional)
        persistenceService.saveResults(classImage.getId(),
                bestFace,
                emotionResult,
                1);

        log.info("Completed processing image: {} (confidence={})", classImage.getId(), bestFace.getConfidence());
    }
}
