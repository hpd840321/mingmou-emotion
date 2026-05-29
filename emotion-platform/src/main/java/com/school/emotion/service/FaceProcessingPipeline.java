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
import com.school.emotion.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class FaceProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(FaceProcessingPipeline.class);

    private final ClassImageRepository classImageRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final GradeRepository gradeRepository;
    private final StudentRepository studentRepository;
    private final VisionMindClient visionMindClient;
    private final FaceCroppingService croppingService;
    private final FaceRegistrationService registrationService;

    private final float confidenceThreshold;
    private final int batchSize;

    public FaceProcessingPipeline(
            ClassImageRepository classImageRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionRecordRepository emotionRecordRepository,
            GradeRepository gradeRepository,
            StudentRepository studentRepository,
            VisionMindClient visionMindClient,
            FaceCroppingService croppingService,
            FaceRegistrationService registrationService,
            @Value("${app.face.confidence-threshold:0.3}") float confidenceThreshold,
            @Value("${app.pipeline.batch-size:50}") int batchSize) {
        this.classImageRepository = classImageRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.gradeRepository = gradeRepository;
        this.studentRepository = studentRepository;
        this.visionMindClient = visionMindClient;
        this.croppingService = croppingService;
        this.registrationService = registrationService;
        this.confidenceThreshold = confidenceThreshold;
        this.batchSize = batchSize;
    }

    public PipelineReport processAll() {
        log.info("Starting full processing pipeline (threshold={}, batch={})", confidenceThreshold, batchSize);
        long start = System.currentTimeMillis();

        // Phase 1: Detect faces on all PENDING images
        List<ClassImage> pending = classImageRepository.findByStatus(ImageStatus.PENDING);
        log.info("Phase 1: Face detection on {} pending images", pending.size());

        int detected = 0, noFace = 0, errors = 0;
        for (int i = 0; i < pending.size(); i++) {
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

        // Create Student record (each detected face creates a student candidate)
        String studentNo = "AUTO_" + ci.getClazz().getId() + "_" + System.currentTimeMillis();
        Student student = new Student();
        student.setClazz(ci.getClazz());
        student.setStudentNo(studentNo);
        student.setName("未知_" + ci.getId());
        student.setStatus("active");
        student = studentRepository.save(student);

        // Create face_record linked to student
        FaceDetectionResult.BBox bbox = bestFace.getBbox();
        FaceRecord fr = new FaceRecord();
        fr.setClassImage(ci);
        fr.setStudent(student);
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

        // Emotion analysis via REST API (VisionMind)
        try {
            EmotionAnalysisResult emotionResult = visionMindClient.analyzeAttribute(imageBytes);
            if (emotionResult != null && emotionResult.getDominantEmotion() != null) {
                EmotionRecord er = new EmotionRecord();
                er.setFaceRecord(fr);
                er.setDominantEmotion(emotionResult.getDominantEmotion());
                er.setDominantConfidence(emotionResult.getDominantConfidence());
                emotionRecordRepository.save(er);
                fr.setStatus(FaceStatus.IDENTIFIED);
                faceRecordRepository.save(fr);
            }
        } catch (Exception e) {
            log.warn("Emotion analysis failed for image {}: {}", ci.getId(), e.getMessage());
        }

        fr.setIsRegisteredToLib("registered".equals(fr.getLibRegisterStatus()));
        fr.setCroppedImageUrl(fr.getCroppedImageUrl());
        faceRecordRepository.save(fr);

        markCompleted(ci);
        return new ProcessResult(true, true);
    }

    private void markCompleted(ClassImage ci) {
        ci.setStatus(ImageStatus.COMPLETED);
        classImageRepository.save(ci);
    }

    private void markFailed(ClassImage ci, String error) {
        ci.setStatus(ImageStatus.FAILED);
        ci.setErrorMessage(error);
        classImageRepository.save(ci);
    }

    public record ProcessResult(boolean faceDetected, boolean registered) {}
    public record PipelineReport(int total, int detected, int noFace, int errors, long elapsedSeconds) {}
}
