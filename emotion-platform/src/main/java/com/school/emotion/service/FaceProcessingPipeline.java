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
        for (int i = 0; i < pending.size(); i++) {
            // Check if stop was requested
            if (progressService.isStopRequested()) {
                log.warn("Pipeline stop requested, terminating after {} images", i);
                break;
            }
            ClassImage ci = pending.get(i);
            try {
                ProcessResult result = processImage(ci);
                if (result.faceDetected) detected++;
                else noFace++;
            } catch (Exception e) {
                log.error("Failed to process image {}: {}", ci.getId(), e.getMessage());
                markFailed(ci, e.getMessage());
                errors++;
            }
            if (i > 0 && i % 50 == 0) {
                log.info("  Progress: {}/{} (detected={}, noFace={}, errors={})", i, pending.size(), detected, noFace, errors);
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

        // Face detection via REST API (VisionMind)
        FaceDetectionResult faceResult;
        try {
            faceResult = visionMindClient.detectFaces(imageBytes);
        } catch (Exception e) {
            markFailed(ci, "Detection error: " + e.getMessage());
            return new ProcessResult(false, false);
        }

        List<FaceDetectionResult.Face> faces = faceResult.getFaces();
        if (faces == null || faces.isEmpty()) {
            markCompleted(ci);
            return new ProcessResult(false, false);
        }

        // Filter by confidence threshold and pick best face
        FaceDetectionResult.Face bestFace = faces.stream()
                .filter(f -> f.getConfidence() != null && f.getConfidence() >= confidenceThreshold)
                .max(java.util.Comparator.comparing(FaceDetectionResult.Face::getConfidence))
                .orElse(null);

        if (bestFace == null) {
            markCompleted(ci);
            return new ProcessResult(false, false);
        }

        // Create face_record (student association happens via FaceLibraryService annotation)
        FaceDetectionResult.BBox bbox = bestFace.getBbox();
        FaceRecord fr = new FaceRecord();
        fr.setClassImage(ci);
        fr.setStudent(null);
        fr.setBbox(bbox != null ? String.format("{\"x\":%.1f,\"y\":%.1f,\"width\":%.1f,\"height\":%.1f}",
                bbox.getX(), bbox.getY(), bbox.getWidth(), bbox.getHeight()) : null);
        fr.setConfidence(bestFace.getConfidence());
        fr.setStatus(FaceStatus.DETECTED);
        fr = faceRecordRepository.save(fr);

        // Crop face
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
            }
        } catch (Exception e) {
            log.warn("Face cropping/registration failed for record {}: {}", fr.getId(), e.getMessage());
        }

        // Emotion analysis via REST /v1/face/attribute (covers all faces in full image)
        try {
            EmotionAnalysisResult emotionResult = visionMindClient.analyzeAttribute(imageBytes);
            if (emotionResult != null && emotionResult.getDominantEmotion() != null) {
                EmotionRecord er = new EmotionRecord();
                er.setFaceRecord(fr);
                er.setDominantEmotion(emotionResult.getDominantEmotion());
                er.setDominantConfidence(emotionResult.getDominantConfidence());

                Map<String, Float> probs = emotionResult.getEmotions();
                if (probs != null) {
                    er.setEmotionHappy(probs.get("happy"));
                    er.setEmotionSad(probs.get("sad"));
                    er.setEmotionAngry(probs.get("angry"));
                    er.setEmotionSurprise(probs.get("surprise"));
                    er.setEmotionFear(probs.get("fear"));
                    er.setEmotionDisgust(probs.get("disgust"));
                    er.setEmotionNeutral(probs.get("neutral"));
                }

                emotionRecordRepository.save(er);
                fr.setStatus(FaceStatus.IDENTIFIED);
                log.info("Emotion record {} created for face {}: {}", er.getId(), fr.getId(), er.getDominantEmotion());
            }
        } catch (Exception e) {
            log.warn("Emotion analysis failed for image {}: {}", ci.getId(), e.getMessage());
        }

        faceRecordRepository.save(fr);

        markCompleted(ci);
        return new ProcessResult(true, true);
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
