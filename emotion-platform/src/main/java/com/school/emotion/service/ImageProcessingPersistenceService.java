package com.school.emotion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将 @Transactional 操作抽取到独立Bean中，确保AOP代理生效。
 * ImageProcessingOrchestrator 内部直接调用会导致代理失效。
 */
@Service
public class ImageProcessingPersistenceService {

    private final ClassImageRepository classImageRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final ObjectMapper objectMapper;

    public ImageProcessingPersistenceService(
            ClassImageRepository classImageRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionRecordRepository emotionRecordRepository,
            ObjectMapper objectMapper) {
        this.classImageRepository = classImageRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void markProcessing(Long imageId) {
        classImageRepository.findById(imageId).ifPresent(ci -> {
            ci.setStatus(ImageStatus.PROCESSING);
            classImageRepository.save(ci);
        });
    }

    @Transactional
    public void markCompleted(Long imageId) {
        classImageRepository.findById(imageId).ifPresent(ci -> {
            ci.setStatus(ImageStatus.COMPLETED);
            classImageRepository.save(ci);
        });
    }

    @Transactional
    public void markFailed(Long imageId, String errorMsg) {
        classImageRepository.findById(imageId).ifPresent(ci -> {
            ci.setStatus(ImageStatus.FAILED);
            ci.setErrorMessage(errorMsg);
            classImageRepository.save(ci);
        });
    }

    @Transactional
    public void saveResults(Long imageId, FaceDetectionResult.Face face,
                             EmotionAnalysisResult emotionResult, int totalFaces) {
        ClassImage classImage = classImageRepository.findById(imageId).orElse(null);
        if (classImage == null) return;

        FaceRecord faceRecord = new FaceRecord();
        faceRecord.setClassImage(classImage);
        faceRecord.setStudent(null);
        if (face.getBbox() != null) {
            try {
                faceRecord.setBbox(objectMapper.writeValueAsString(face.getBbox()));
            } catch (Exception e) {
                faceRecord.setBbox(null);
            }
        }
        faceRecord.setConfidence(face.getConfidence());
        faceRecord.setQuality(face.getQuality());
        faceRecord.setStatus(FaceStatus.DETECTED);
        faceRecord = faceRecordRepository.save(faceRecord);

        if (emotionResult != null && emotionResult.getDominantEmotion() != null) {
            EmotionRecord emotionRecord = new EmotionRecord();
            emotionRecord.setFaceRecord(faceRecord);
            emotionRecord.setDominantEmotion(emotionResult.getDominantEmotion());
            emotionRecord.setDominantConfidence(emotionResult.getDominantConfidence());

            java.util.Map<String, Float> probs = emotionResult.getEmotions();
            if (probs != null) {
                emotionRecord.setEmotionHappy(probs.get("happy"));
                emotionRecord.setEmotionSad(probs.get("sad"));
                emotionRecord.setEmotionAngry(probs.get("angry"));
                emotionRecord.setEmotionSurprise(probs.get("surprise"));
                emotionRecord.setEmotionFear(probs.get("fear"));
                emotionRecord.setEmotionDisgust(probs.get("disgust"));
                emotionRecord.setEmotionNeutral(probs.get("neutral"));
            }

            emotionRecordRepository.save(emotionRecord);

            faceRecord.setStatus(FaceStatus.IDENTIFIED);
            faceRecordRepository.save(faceRecord);
        } else {
            faceRecord.setStatus(FaceStatus.UNIDENTIFIED);
            faceRecordRepository.save(faceRecord);
        }

        classImage.setStatus(ImageStatus.COMPLETED);
        classImageRepository.save(classImage);
    }
}
