package com.school.emotion.service;

import com.school.emotion.client.VisionMindClient;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.*;
import com.school.emotion.model.enums.FaceStatus;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.GradeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class FaceProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(FaceProcessingPipeline.class);

    private final ClassImageRepository classImageRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final GradeRepository gradeRepository;
    private final VisionMindClient visionMindClient;
    private final FaceCroppingService croppingService;
    private final FaceRegistrationService registrationService;
    private final PipelineProgressService progressService;
    private final EmotionStateMappingService emotionStateMappingService;
    private final TaskExecutor pipelineExecutor;

    private final float confidenceThreshold;
    private final int batchSize;

    public FaceProcessingPipeline(
            ClassImageRepository classImageRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionRecordRepository emotionRecordRepository,
            GradeRepository gradeRepository,
            VisionMindClient visionMindClient,
            FaceCroppingService croppingService,
            FaceRegistrationService registrationService,
            PipelineProgressService progressService,
            EmotionStateMappingService emotionStateMappingService,
            TaskExecutor pipelineExecutor,
            @Value("${app.face.confidence-threshold:0.3}") float confidenceThreshold,
            @Value("${app.pipeline.batch-size:50}") int batchSize) {
        this.classImageRepository = classImageRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.gradeRepository = gradeRepository;
        this.visionMindClient = visionMindClient;
        this.croppingService = croppingService;
        this.registrationService = registrationService;
        this.progressService = progressService;
        this.emotionStateMappingService = emotionStateMappingService;
        this.pipelineExecutor = pipelineExecutor;
        this.confidenceThreshold = confidenceThreshold;
        this.batchSize = batchSize;
    }

    @PostConstruct
    public void resetStaleProcessingImages() {
        List<com.school.emotion.model.entity.ClassImage> stuck = classImageRepository.findByStatus(ImageStatus.PROCESSING);
        if (!stuck.isEmpty()) {
            log.warn("Resetting {} stale PROCESSING images to PENDING", stuck.size());
            for (var ci : stuck) {
                ci.setStatus(ImageStatus.PENDING);
                ci.setErrorMessage(null);
            }
            classImageRepository.saveAll(stuck);
        }
    }

    /**
     * Single image processing entry point (for Redis stream consumer).
     */
    public ProcessResult processSingleImage(ClassImage ci) {
        return processImage(ci);
    }

    public PipelineReport processAll() {
        log.info("Starting full processing pipeline (threshold={}, batch={})", confidenceThreshold, batchSize);
        long start = System.currentTimeMillis();

        // Phase 1: Detect faces on all PENDING images
        List<ClassImage> pending = classImageRepository.findByStatus(ImageStatus.PENDING);
        log.info("Phase 1: Face detection on {} pending images", pending.size());

        int detected = 0, noFace = 0, errors = 0;

        // Sequential processing to avoid overwhelming face_server gRPC
        for (ClassImage ci : pending) {
            if (progressService.isStopRequested()) {
                log.warn("Pipeline stop requested, terminating after processing images so far");
                break;
            }
            try {
                ProcessResult result = processImage(ci);
                if (result.faceDetected) detected++;
                else noFace++;
            } catch (Exception e) {
                log.error("Failed to process image {}: {}", ci.getId(), e.getMessage());
                markFailed(ci, e.getMessage());
                errors++;
            }
            int done = detected + noFace + errors;
            if (done > 0 && done % 50 == 0) {
                log.info("  Progress: {}/{} (detected={}, noFace={}, errors={})", done, pending.size(), detected, noFace, errors);
            }
        }

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        log.info("Pipeline complete: {} images in {}s (detected={}, noFace={}, errors={})",
                pending.size(), elapsed, detected, noFace, errors);
        return new PipelineReport(pending.size(), detected, noFace, errors, elapsed);
    }

    @Transactional
    public ProcessResult processImage(ClassImage ci) {
        // Re-read from DB to check if still PENDING (prevents concurrent processing)
        ClassImage fresh = classImageRepository.findById(ci.getId()).orElse(null);
        if (fresh == null || fresh.getStatus() != ImageStatus.PENDING) {
            log.debug("Image {} status is {}, skipping (already processed by another consumer)", ci.getId(),
                    fresh != null ? fresh.getStatus() : "deleted");
            return new ProcessResult(false, false);
        }
        ci = fresh;

        // Mark as PROCESSING and broadcast progress
        ImageStatus oldStatus = ci.getStatus();
        ci.setStatus(ImageStatus.PROCESSING);
        classImageRepository.save(ci);
        String fileName = Path.of(ci.getImageUrl()).getFileName().toString();
        progressService.onStatusChange(ci.getId(), oldStatus, ImageStatus.PROCESSING, fileName, null);

        Path imagePath = Path.of(ci.getImageUrl());
        byte[] imageBytes;
        try {
            imageBytes = java.nio.file.Files.readAllBytes(imagePath);
        } catch (IOException e) {
            markFailed(ci, "Cannot read file: " + e.getMessage());
            return new ProcessResult(false, false);
        }

        // Face detection via VisionMind REST API
        FaceDetectionResult detectionResult;
        try {
            detectionResult = visionMindClient.detectFaces(imageBytes);
        } catch (Exception e) {
            log.warn("REST detect failed for image {}: {}", ci.getId(), e.getMessage());
            markFailed(ci, "Detection error: " + e.getMessage());
            return new ProcessResult(false, false);
        }

        if (detectionResult == null || detectionResult.getFaces() == null || detectionResult.getFaces().isEmpty()) {
            markCompleted(ci);
            return new ProcessResult(false, false);
        }

        // Filter by confidence, sort descending
        List<FaceDetectionResult.Face> validFaces = detectionResult.getFaces().stream()
                .filter(face -> face.getConfidence() != null && face.getConfidence() >= confidenceThreshold)
                .sorted(java.util.Comparator.comparing(FaceDetectionResult.Face::getConfidence).reversed())
                .collect(java.util.stream.Collectors.toList());

        if (validFaces.isEmpty()) {
            markCompleted(ci);
            return new ProcessResult(false, false);
        }

        // Process each valid face: create FaceRecord, crop, register to library, analyze emotion
        int faceCount = 0;
        int emotionCount = 0;
        for (FaceDetectionResult.Face face : validFaces) {
            FaceDetectionResult.BBox bbox = face.getBbox();
            FaceRecord fr = new FaceRecord();
            fr.setClassImage(ci);
            fr.setStudent(null);
            fr.setBbox(bbox != null ? String.format("{\"x\":%.1f,\"y\":%.1f,\"width\":%.1f,\"height\":%.1f}",
                    bbox.getX(), bbox.getY(), bbox.getWidth(), bbox.getHeight()) : null);
            fr.setConfidence(face.getConfidence());
            fr.setQuality(face.getQuality());
            fr.setStatus(FaceStatus.DETECTED);
            fr = faceRecordRepository.save(fr);
            faceCount++;

            // Crop face + per-face emotion analysis
            try {
                var school = "官渡一中";
                var className = "初一班";
                String date = ci.getCaptureTime().toLocalDate().toString();
                String period = ci.getPeriodLabel() != null ? ci.getPeriodLabel() : "other";

                var cropResult = croppingService.cropFace(
                        imagePath,
                        bbox != null ? Math.round(bbox.getX()) : 0, bbox != null ? Math.round(bbox.getY()) : 0,
                        bbox != null ? Math.round(bbox.getWidth()) : 0, bbox != null ? Math.round(bbox.getHeight()) : 0,
                        school, className, date, period, fr.getId());

                if (cropResult.success()) {
                    fr.setCroppedImageUrl(cropResult.path());

                    // Register to VisionMind face library
                    long schoolId = gradeRepository.findAll().stream().findFirst().map(Grade::getId).orElse(1L);
                    registrationService.registerFaceToLibrary(fr, Path.of(cropResult.path()),
                            schoolId, ci.getClazz().getId());

                    // Emotion via VisionMindClient (uses configured RestTemplate with timeouts)
                    try {
                        byte[] cropBytes = java.nio.file.Files.readAllBytes(Path.of(cropResult.path()));
                        EmotionAnalysisResult emotionResult = visionMindClient.analyzeAttribute(cropBytes);
                        if (emotionResult != null && emotionResult.getDominantEmotion() != null) {
                            EmotionRecord er = new EmotionRecord();
                            er.setFaceRecord(fr);
                            er.setDominantEmotion(emotionResult.getDominantEmotion());
                            er.setDominantConfidence(emotionResult.getDominantConfidence());

                            // Save full 8-dim emotion probability vector
                            java.util.Map<String, Float> probs = emotionResult.getEmotions();
                            if (probs != null) {
                                er.setEmotionHappy(probs.get("happy"));
                                er.setEmotionSad(probs.get("sad"));
                                er.setEmotionAngry(probs.get("angry"));
                                er.setEmotionSurprise(probs.get("surprise"));
                                er.setEmotionFear(probs.get("fear"));
                                er.setEmotionDisgust(probs.get("disgust"));
                                er.setEmotionNeutral(probs.get("neutral"));
                            }

                            if (probs != null && !probs.isEmpty()) {
                                EmotionStateMappingService.EmotionState state = emotionStateMappingService
                                    .mapFromProbabilities(probs);
                                er.setDominantState(state.name());
                                er.setEmotionalCohesion(probs.getOrDefault(emotionResult.getDominantEmotion(), 0.0f));
                            }

                            emotionRecordRepository.save(er);
                            fr.setStatus(FaceStatus.IDENTIFIED);
                            emotionCount++;
                            log.debug("Emotion for face {}: {} (conf={})", fr.getId(),
                                    er.getDominantEmotion(), er.getDominantConfidence());
                        }
                    } catch (Exception e) {
                        log.debug("Emotion analysis skipped for face {}: {}", fr.getId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Face cropping/registration failed for record {}: {}", fr.getId(), e.getMessage());
            }

            faceRecordRepository.save(fr);
        }

        log.info("Image {} processed: {} faces, {} emotions", ci.getId(), faceCount, emotionCount);
        markCompleted(ci);
        return new ProcessResult(faceCount > 0, emotionCount > 0);
    }

    private void markCompleted(ClassImage ci) {
        ImageStatus oldStatus = ci.getStatus();
        ci.setStatus(ImageStatus.COMPLETED);
        classImageRepository.save(ci);
        String fileName = Path.of(ci.getImageUrl()).getFileName().toString();
        progressService.onStatusChange(ci.getId(), oldStatus, ImageStatus.COMPLETED, fileName, null);
    }

    private void markFailed(ClassImage ci, String error) {
        ImageStatus oldStatus = ci.getStatus();
        ci.setStatus(ImageStatus.FAILED);
        ci.setErrorMessage(error);
        classImageRepository.save(ci);
        String fileName = Path.of(ci.getImageUrl()).getFileName().toString();
        progressService.onStatusChange(ci.getId(), oldStatus, ImageStatus.FAILED, fileName, error);
    }

    public int resetFailedToPending() {
        List<ClassImage> failed = classImageRepository.findByStatus(ImageStatus.FAILED);
        int count = 0;
        for (ClassImage ci : failed) {
            ci.setStatus(ImageStatus.PENDING);
            ci.setErrorMessage(null);
            classImageRepository.save(ci);
            count++;
        }
        log.info("Reset {} failed images to PENDING", count);
        return count;
    }

    public record ProcessResult(boolean faceDetected, boolean registered) {}
    public record PipelineReport(int total, int detected, int noFace, int errors, long elapsedSeconds) {}
}
