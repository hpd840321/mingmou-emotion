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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExternalEmotionPushService {

    private static final Logger log = LoggerFactory.getLogger(ExternalEmotionPushService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // AddEmotion 单批 1 条（含 base64 大图 ~1.8MB/条）
    private static final int EMOTION_BATCH_SIZE = 1;
    // 原图缩放到最长边 640px
    private static final int ORIGINAL_MAX_DIM = 640;
    // 裁剪图 margin（与 FaceCroppingService 保持一致）
    private static final float CROP_MARGIN = 0.30f;

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

    // ================================================================
    //  Base64 编码工具
    // ================================================================

    /**
     * 读取图片文件，可选缩放后返回 base64 字符串。
     */
    private String imageToBase64(String path, int maxDim) {
        if (path == null || path.isEmpty()) return "";
        try {
            byte[] raw = Files.readAllBytes(Path.of(path));
            if (maxDim > 0) {
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(raw));
                if (img != null) {
                    int w = img.getWidth();
                    int h = img.getHeight();
                    if (Math.max(w, h) > maxDim) {
                        double ratio = (double) maxDim / Math.max(w, h);
                        int nw = (int) (w * ratio);
                        int nh = (int) (h * ratio);
                        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                        java.awt.Graphics2D g = scaled.createGraphics();
                        g.drawImage(img, 0, 0, nw, nh, null);
                        g.dispose();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ImageIO.write(scaled, "JPEG", bos);
                        raw = bos.toByteArray();
                    }
                }
            }
            return Base64.getEncoder().encodeToString(raw);
        } catch (IOException e) {
            log.warn("  Cannot read image {}: {}", path, e.getMessage());
            return "";
        }
    }

    /**
     * 从原图动态裁剪人脸并返回 base64。
     */
    private String cropFaceToBase64(String originalPath, String bboxJson) {
        if (originalPath == null || bboxJson == null) return "";
        try {
            var bbox = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(bboxJson, Map.class);
            int x = ((Number) bbox.get("x")).intValue();
            int y = ((Number) bbox.get("y")).intValue();
            int w = ((Number) bbox.get("width")).intValue();
            int h = ((Number) bbox.get("height")).intValue();

            BufferedImage img = ImageIO.read(Path.of(originalPath).toFile());
            if (img == null) return "";

            int mx = Math.max(1, (int) (w * CROP_MARGIN));
            int my = Math.max(1, (int) (h * CROP_MARGIN));
            int x1 = Math.max(0, x - mx);
            int y1 = Math.max(0, y - my);
            int x2 = Math.min(img.getWidth() - 1, x + w + mx);
            int y2 = Math.min(img.getHeight() - 1, y + h + my);

            if (x2 <= x1 || y2 <= y1) return "";

            BufferedImage crop = img.getSubimage(x1, y1, x2 - x1, y2 - y1);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(crop, "JPEG", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            log.warn("  Crop failed for {}: {}", Path.of(originalPath).getFileName(), e.getMessage());
            return "";
        }
    }

    // ================================================================
    //  Student 推送
    // ================================================================

    public void pushStudent(Student student) {
        if (!pushClient.isEnabled()) return;
        List<String> imageB64s = faceRecordRepository.findByStudentId(student.getId()).stream()
                .filter(fr -> fr.getCroppedImageUrl() != null || fr.getBbox() != null)
                .map(fr -> {
                    if (fr.getCroppedImageUrl() != null) {
                        String b64 = imageToBase64(fr.getCroppedImageUrl(), 0);
                        if (!b64.isEmpty()) return b64;
                    }
                    if (fr.getClassImage() != null) {
                        return cropFaceToBase64(fr.getClassImage().getImageUrl(), fr.getBbox());
                    }
                    return "";
                })
                .filter(s -> !s.isEmpty())
                .limit(5)
                .collect(Collectors.toList());

        if (imageB64s.isEmpty()) {
            log.warn("  Student {} has no readable face images", student.getStudentNo());
            return;
        }
        var result = pushClient.updateStudent(student.getStudentNo(), student.getName(), imageB64s);
        if (!result.isSuccess()) {
            log.warn("  Failed to push student {}: {}", student.getStudentNo(), result.getMessage());
        }
    }

    public void pushAllStudents() {
        if (!pushClient.isEnabled()) return;
        List<Student> students = studentRepository.findAll();
        int pushed = 0;
        for (Student s : students) {
            List<FaceRecord> frs = faceRecordRepository.findByStudentId(s.getId());
            if (frs.isEmpty()) continue;
            pushStudent(s);
            pushed++;
        }
        log.info("Push all students: {} attempted", pushed);
    }

    // ================================================================
    //  Emotion 推送
    // ================================================================

    public void pushAllEmotions() {
        if (!pushClient.isEnabled()) return;
        List<EmotionRecord> records = emotionRecordRepository.findPushedRecords();
        if (records.isEmpty()) {
            log.info("No emotion records with student linkage to push");
            return;
        }
        log.info("Pushing {} emotion records (with student linkage)...", records.size());
        pushEmotionRecords(records);
    }

    public void pushStudentEmotions(Long studentId) {
        if (!pushClient.isEnabled()) return;
        List<EmotionRecord> records = emotionRecordRepository.findByStudentId(studentId);
        if (records.isEmpty()) return;
        log.info("Pushing {} emotion records for student {}", records.size(), studentId);
        pushEmotionRecords(records);
    }

    public void pushEmotionRecords(List<EmotionRecord> records) {
        if (!pushClient.isEnabled()) return;
        List<ExternalEmotionPushRecord> pushRecords = records.stream()
                .map(this::toPushRecord)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (pushRecords.isEmpty()) return;

        int total = pushRecords.size();
        int pushed = 0;
        int errors = 0;

        for (int i = 0; i < total; i += EMOTION_BATCH_SIZE) {
            List<ExternalEmotionPushRecord> batch = pushRecords.subList(i,
                    Math.min(i + EMOTION_BATCH_SIZE, total));
            var result = pushClient.addEmotions(batch);
            if (result.isSuccess()) {
                pushed += batch.size();
            } else {
                errors += batch.size();
                log.warn("Failed to push emotion batch {}-{}: {}", i, i + batch.size(), result.getMessage());
            }
            if ((i + 1) % 1000 == 0 || i + EMOTION_BATCH_SIZE >= total) {
                log.info("  Emotion push: {}/{} ({} errors)", i + batch.size(), total, errors);
            }
        }
        log.info("Emotion push complete: {}/{} pushed, {} errors", pushed, total, errors);
    }

    // ================================================================
    //  记录转换
    // ================================================================

    private ExternalEmotionPushRecord toPushRecord(EmotionRecord er) {
        try {
            FaceRecord fr = er.getFaceRecord();
            if (fr == null) return null;

            Student student = fr.getStudent();
            if (student == null) return null;

            ClassImage ci = fr.getClassImage();
            if (ci == null) return null;

            // SmallPic: 裁剪人脸图 base64
            String smallPicB64 = "";
            if (fr.getCroppedImageUrl() != null) {
                smallPicB64 = imageToBase64(fr.getCroppedImageUrl(), 0);
            }
            if (smallPicB64.isEmpty() && ci.getImageUrl() != null && fr.getBbox() != null) {
                smallPicB64 = cropFaceToBase64(ci.getImageUrl(), fr.getBbox());
            }
            if (smallPicB64.isEmpty()) {
                log.debug("  Emotion #{}: no face image available", er.getId());
                return null;
            }

            // ImageUrl: 原图缩放到 640px 后 base64
            String origImgB64 = imageToBase64(ci.getImageUrl(), ORIGINAL_MAX_DIM);

            ExternalEmotionPushRecord record = new ExternalEmotionPushRecord();
            record.setId(er.getId());
            record.setCameraCode(pushClient.getCameraCode());
            record.setStudent_code(student.getStudentNo());
            record.setSmallPic(smallPicB64);
            record.setCaptureTime(ci.getCaptureTime() != null
                    ? ci.getCaptureTime().format(DTF) : "");
            record.setImageUrl(origImgB64);
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

    // ================================================================
    //  情绪映射
    // ================================================================

    private String mapEmotion(String dbLabel) {
        if (dbLabel == null) return "calm";
        String label = switch (dbLabel) {
            case "中性" -> "neutral";
            case "开心" -> "happy";
            case "伤心" -> "sad";
            case "愤怒" -> "angry";
            case "惊讶" -> "surprise";
            case "恐惧" -> "fear";
            case "厌恶" -> "disgust";
            case "蔑视" -> "contempt";
            default -> dbLabel;
        };
        return switch (label) {
            case "happy" -> "happy";
            case "sad" -> "sad";
            case "angry", "disgust" -> "angry";
            case "surprise" -> "surprised";
            case "fear" -> "fearful";
            case "neutral", "contempt" -> "calm";
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

    // ================================================================
    //  全量推送
    // ================================================================

    public PushSummary pushAll() {
        if (!pushClient.isEnabled()) return new PushSummary(0, 0);
        pushAllStudents();
        pushAllEmotions();
        return new PushSummary(
                (int) studentRepository.count(),
                (int) emotionRecordRepository.count());
    }

    public record PushSummary(int students, int emotions) {}
}
