package com.school.emotion.service;

import com.school.emotion.client.ExternalEmotionPushClient;
import com.school.emotion.client.ExternalEmotionPushRecord;
import com.school.emotion.model.entity.*;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExternalEmotionPushService {

    private static final Logger log = LoggerFactory.getLogger(ExternalEmotionPushService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExternalEmotionPushClient pushClient;
    private final StudentRepository studentRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;

    public ExternalEmotionPushService(
            ExternalEmotionPushClient pushClient,
            StudentRepository studentRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionRecordRepository emotionRecordRepository) {
        this.pushClient = pushClient;
        this.studentRepository = studentRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
    }

    public void pushStudent(Student student) {
        if (!pushClient.isEnabled()) return;
        List<String> imageUrls = faceRecordRepository.findByStudentId(student.getId()).stream()
                .filter(fr -> fr.getCroppedImageUrl() != null)
                .map(FaceRecord::getCroppedImageUrl)
                .limit(5)
                .collect(Collectors.toList());
        var result = pushClient.updateStudent(student.getStudentNo(), student.getName(), imageUrls);
        if (!result.isSuccess()) {
            log.warn("Failed to push student {}: {}", student.getStudentNo(), result.getMessage());
        }
    }

    public void pushAllStudents() {
        if (!pushClient.isEnabled()) return;
        List<Student> students = studentRepository.findAll();
        int pushed = 0, failed = 0;
        for (Student s : students) {
            var result = doPushStudent(s);
            if (result.isSuccess()) pushed++;
            else failed++;
        }
        log.info("Push all students: {} pushed, {} failed", pushed, failed);
    }

    private ExternalEmotionPushClient.PushResult doPushStudent(Student student) {
        List<String> imageUrls = faceRecordRepository.findByStudentId(student.getId()).stream()
                .filter(fr -> fr.getCroppedImageUrl() != null)
                .map(FaceRecord::getCroppedImageUrl)
                .limit(5)
                .collect(Collectors.toList());
        return pushClient.updateStudent(student.getStudentNo(), student.getName(), imageUrls);
    }

    public void pushAllEmotions() {
        if (!pushClient.isEnabled()) return;
        List<EmotionRecord> records = emotionRecordRepository.findAll();
        if (records.isEmpty()) {
            log.info("No emotion records to push");
            return;
        }
        pushEmotionRecords(records);
    }

    public void pushEmotionRecords(List<EmotionRecord> records) {
        if (!pushClient.isEnabled()) return;
        List<ExternalEmotionPushRecord> pushRecords = records.stream()
                .map(this::toPushRecord)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (pushRecords.isEmpty()) return;

        int batchSize = 200;
        for (int i = 0; i < pushRecords.size(); i += batchSize) {
            List<ExternalEmotionPushRecord> batch = pushRecords.subList(i,
                    Math.min(i + batchSize, pushRecords.size()));
            var result = pushClient.addEmotions(batch);
            if (!result.isSuccess()) {
                log.warn("Failed to push emotion batch {}-{}: {}", i, i + batch.size(), result.getMessage());
            }
        }
    }

    private ExternalEmotionPushRecord toPushRecord(EmotionRecord er) {
        try {
            FaceRecord fr = er.getFaceRecord();
            if (fr == null) return null;

            Student student = fr.getStudent();
            if (student == null) return null;

            ClassImage ci = fr.getClassImage();
            if (ci == null) return null;

            ExternalEmotionPushRecord record = new ExternalEmotionPushRecord();
            record.setId(er.getId());
            record.setCameraCode(pushClient.getCameraCode());
            record.setStudent_code(student.getStudentNo());
            record.setSmallPic(fr.getCroppedImageUrl());
            record.setCaptureTime(ci.getCaptureTime() != null
                    ? ci.getCaptureTime().format(DTF) : "");
            record.setImageUrl(ci.getImageUrl());
            record.setConfidence(er.getDominantConfidence() != null
                    ? String.format("%.2f", er.getDominantConfidence()) : "0.00");
            record.setScore(er.getDominantConfidence() != null
                    ? Math.round(er.getDominantConfidence() * 100) : 0);

            String externalEmotion = mapEmotion(er.getDominantEmotion());
            record.setEmotion(externalEmotion);
            record.setColor(mapColor(externalEmotion));
            record.setGazeDirection("");
            record.setCreated_at(er.getCreatedAt() != null
                    ? er.getCreatedAt().format(DTF) : "");
            return record;
        } catch (Exception e) {
            log.warn("Failed to convert EmotionRecord {}: {}", er.getId(), e.getMessage());
            return null;
        }
    }

    private String mapEmotion(String chineseLabel) {
        if (chineseLabel == null) return "calm";
        return switch (chineseLabel) {
            case "开心" -> "happy";
            case "伤心" -> "sad";
            case "愤怒" -> "angry";
            case "惊讶" -> "surprised";
            case "恐惧" -> "fearful";
            case "中性" -> "calm";
            case "蔑视" -> "calm";
            case "厌恶" -> "angry";
            default -> "calm";
        };
    }

    private String mapColor(String emotion) {
        return switch (emotion) {
            case "happy" -> "green";
            case "sad" -> "blue";
            case "angry" -> "red";
            case "calm" -> "cyan";
            case "surprised" -> "yellow";
            case "fearful" -> "purple";
            default -> "";
        };
    }

    public PushSummary pushAll() {
        pushAllStudents();
        pushAllEmotions();
        return new PushSummary(0, 0);
    }

    public record PushSummary(int pushed, int failed) {}
}
