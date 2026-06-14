package com.school.emotion.controller;

import com.school.emotion.model.entity.*;
import com.school.emotion.repository.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/school-tree")
public class SchoolTreeController {

    private final GradeRepository gradeRepository;
    private final SchoolClassRepository classRepository;
    private final StudentRepository studentRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final FaceClusterRepository faceClusterRepository;

    static String toImageUrl(String absolutePath) {
        if (absolutePath == null) return null;
        int idx = absolutePath.indexOf("/images/");
        if (idx >= 0) return absolutePath.substring(idx);
        return absolutePath;
    }

    public SchoolTreeController(GradeRepository gradeRepository,
                                SchoolClassRepository classRepository,
                                StudentRepository studentRepository,
                                FaceRecordRepository faceRecordRepository,
                                EmotionRecordRepository emotionRecordRepository,
                                FaceClusterRepository faceClusterRepository) {
        this.gradeRepository = gradeRepository;
        this.classRepository = classRepository;
        this.studentRepository = studentRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.faceClusterRepository = faceClusterRepository;
    }

    /** 获取学校树结构: grades → classes → [students|face_groups] */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getTree() {
        List<Grade> grades = gradeRepository.findAll();
        List<Map<String, Object>> tree = new ArrayList<>();

        for (Grade g : grades) {
            Map<String, Object> gradeNode = new HashMap<>();
            gradeNode.put("id", "grade-" + g.getId());
            gradeNode.put("label", g.getName());
            gradeNode.put("type", "grade");
            gradeNode.put("gradeId", g.getId());

            List<SchoolClass> classes = classRepository.findByGrade_Id(g.getId());
            List<Map<String, Object>> classNodes = new ArrayList<>();

            for (SchoolClass c : classes) {
                Map<String, Object> classNode = new HashMap<>();
                classNode.put("id", "class-" + c.getId());
                classNode.put("label", c.getName());
                classNode.put("type", "class");
                classNode.put("classId", c.getId());

                List<Student> students = studentRepository.findByClazz_Id(c.getId());
                List<Map<String, Object>> childNodes = new ArrayList<>();

                if (!students.isEmpty()) {
                    for (Student s : students) {
                        Map<String, Object> studentNode = new HashMap<>();
                        studentNode.put("id", "student-" + s.getId());
                        studentNode.put("label", s.getName());
                        studentNode.put("type", "student");
                        studentNode.put("studentId", s.getId());
                        studentNode.put("studentNo", s.getStudentNo());

                        List<FaceRecord> faces = faceRecordRepository.findByStudentId(s.getId());
                        List<String> sampleImages = faces.stream()
                                .filter(f -> f.getCroppedImageUrl() != null)
                                .limit(4)
                                .map(f -> "/img/" + f.getId())
                                .collect(Collectors.toList());
                        studentNode.put("sampleImages", sampleImages);
                        studentNode.put("faceCount", faces.size());
                        childNodes.add(studentNode);
                    }

                    // 添加未关联学生的人脸（student_id IS NULL）分组展示
                    List<FaceRecord> unlinkedFaces = faceRecordRepository.findByClassImage_Clazz_IdAndStudentIdIsNull(c.getId());
                    if (!unlinkedFaces.isEmpty()) {
                        Map<String, List<FaceRecord>> grouped = unlinkedFaces.stream()
                                .filter(fr -> fr.getClassImage() != null)
                                .collect(Collectors.groupingBy(fr -> {
                                    var ci = fr.getClassImage();
                                    String d = ci.getCaptureTime() != null ? ci.getCaptureTime().toLocalDate().toString() : "unknown";
                                    String p = ci.getPeriodLabel() != null ? ci.getPeriodLabel() : "other";
                                    return d + "_" + p;
                                }));
                        for (Map.Entry<String, List<FaceRecord>> entry : grouped.entrySet()) {
                            List<FaceRecord> groupFaces = entry.getValue();
                            Map<String, Object> groupNode = new HashMap<>();
                            groupNode.put("id", "unlinked_" + entry.getKey());
                            groupNode.put("label", entry.getKey() + "(" + groupFaces.size() + "人)");
                            groupNode.put("type", "face_group");
                            groupNode.put("faceCount", groupFaces.size());
                            List<String> samples = groupFaces.stream()
                                    .filter(fr -> fr.getCroppedImageUrl() != null)
                                    .limit(4)
                                    .map(fr -> "/img/" + fr.getId())
                                    .collect(Collectors.toList());
                            groupNode.put("sampleImages", samples);
                            childNodes.add(groupNode);
                        }
                    }
                } else {
                    // Fallback: show face groups (clusters) when no students linked
                    List<FaceCluster> clusters = faceClusterRepository
                            .findByClassIdAndStatusOrderBySampleCountDesc(c.getId(), "auto_annotated");
                    clusters.stream().limit(50).forEach(cl -> {
                        Map<String, Object> groupNode = new HashMap<>();
                        groupNode.put("id", "cluster-" + cl.getId());
                        groupNode.put("label", "人物#" + cl.getId());
                        groupNode.put("type", "face_group");
                        groupNode.put("clusterId", cl.getId());
                        groupNode.put("faceCount", cl.getSampleCount());

                        String tokens = cl.getFaceTokens();
                        if (tokens != null && !tokens.isEmpty()) {
                            java.util.regex.Matcher matcher =
                                    java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(tokens);
                            List<String> samples = new ArrayList<>();
                            while (matcher.find() && samples.size() < 4) {
                                String libFaceId = matcher.group(1);
                                faceRecordRepository.findByLibFaceId(libFaceId)
                                        .filter(fr -> fr.getCroppedImageUrl() != null)
                                        .ifPresent(fr -> samples.add("/img/" + fr.getId()));
                            }
                            groupNode.put("sampleImages", samples);
                        } else {
                            groupNode.put("sampleImages", List.of());
                        }
                        childNodes.add(groupNode);
                    });

                    // Also add ungrouped face records as individual entries
                    if (childNodes.isEmpty()) {
                        List<FaceRecord> allFaces = faceRecordRepository.findAll();
                        allFaces.stream().limit(100).forEach(fr -> {
                            Map<String, Object> faceNode = new HashMap<>();
                            faceNode.put("id", "face-" + fr.getId());
                            faceNode.put("label", "人脸#" + fr.getId());
                            faceNode.put("type", "face");
                            faceNode.put("faceRecordId", fr.getId());
                            faceNode.put("croppedImageUrl", "/img/" + fr.getId());
                            faceNode.put("confidence", fr.getConfidence());
                            if (fr.getCroppedImageUrl() != null) {
                                faceNode.put("sampleImages", List.of("/img/" + fr.getId()));
                            }
                            childNodes.add(faceNode);
                        });
                    }
                }
                classNode.put("children", childNodes);
                classNodes.add(classNode);
            }
            gradeNode.put("children", classNodes);
            tree.add(gradeNode);
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", tree));
    }

    /** 获取学生原始情绪数据 */
    @GetMapping("/student/{id}/raw-emotions")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getStudentRawEmotions(@PathVariable Long id) {
        List<FaceRecord> faceRecords = faceRecordRepository.findByStudentId(id);
        List<Map<String, Object>> records = new ArrayList<>();

        for (FaceRecord fr : faceRecords) {
            EmotionRecord er = emotionRecordRepository.findByFaceRecordId(fr.getId());
            if (er == null) continue;

            Map<String, Object> record = new HashMap<>();
            record.put("faceRecordId", fr.getId());
            record.put("croppedImageUrl", "/img/" + fr.getId());
            record.put("imageUrl", fr.getClassImage() != null ? toImageUrl(fr.getClassImage().getImageUrl()) : null);
            record.put("captureTime", fr.getClassImage() != null ?
                    fr.getClassImage().getCaptureTime().toString() : null);
            record.put("periodLabel", fr.getClassImage() != null ?
                    fr.getClassImage().getPeriodLabel() : null);
            record.put("bbox", fr.getBbox());
            record.put("confidence", fr.getConfidence());
            record.put("dominantEmotion", er.getDominantEmotion());
            record.put("dominantConfidence", er.getDominantConfidence());
            Map<String, Object> emotions = new LinkedHashMap<>();
            emotions.put("happy", er.getEmotionHappy());
            emotions.put("sad", er.getEmotionSad());
            emotions.put("angry", er.getEmotionAngry());
            emotions.put("surprise", er.getEmotionSurprise());
            emotions.put("fear", er.getEmotionFear());
            emotions.put("disgust", er.getEmotionDisgust());
            emotions.put("neutral", er.getEmotionNeutral());
            record.put("emotions", emotions);
            records.add(record);
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", records));
    }

    /** 根据 faceRecordId 获取单条情绪数据 */
    @GetMapping("/face/{faceRecordId}/emotion")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getFaceEmotion(@PathVariable Long faceRecordId) {
        FaceRecord fr = faceRecordRepository.findById(faceRecordId).orElse(null);
        if (fr == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "face not found"));
        }
        EmotionRecord er = emotionRecordRepository.findByFaceRecordId(faceRecordId);
        if (er == null) {
            Map<String, Object> emptyEmotions = new LinkedHashMap<>();
            for (String k : new String[]{"happy","sad","angry","surprise","fear","disgust","neutral"}) {
                emptyEmotions.put(k, 0);
            }
            return ResponseEntity.ok(Map.of("code", 0, "data", List.of(Map.of(
                "faceRecordId", faceRecordId,
                "croppedImageUrl", "/img/" + fr.getId(),
                "imageUrl", fr.getClassImage() != null ? toImageUrl(fr.getClassImage().getImageUrl()) : null,
                "captureTime", fr.getClassImage() != null ? fr.getClassImage().getCaptureTime().toString() : null,
                "periodLabel", fr.getClassImage() != null ? fr.getClassImage().getPeriodLabel() : null,
                "dominantEmotion", null, "dominantConfidence", null,
                "emotions", emptyEmotions
            ))));
        }

        Map<String, Object> emotions = new LinkedHashMap<>();
        emotions.put("happy", er.getEmotionHappy());
        emotions.put("sad", er.getEmotionSad());
        emotions.put("angry", er.getEmotionAngry());
        emotions.put("surprise", er.getEmotionSurprise());
        emotions.put("fear", er.getEmotionFear());
        emotions.put("disgust", er.getEmotionDisgust());
        emotions.put("neutral", er.getEmotionNeutral());

        Map<String, Object> record = new HashMap<>();
        record.put("faceRecordId", fr.getId());
        record.put("croppedImageUrl", "/img/" + fr.getId());
        record.put("imageUrl", fr.getClassImage() != null ? toImageUrl(fr.getClassImage().getImageUrl()) : null);
        record.put("captureTime", fr.getClassImage() != null ? fr.getClassImage().getCaptureTime().toString() : null);
        record.put("periodLabel", fr.getClassImage() != null ? fr.getClassImage().getPeriodLabel() : null);
        record.put("bbox", fr.getBbox());
        record.put("confidence", fr.getConfidence());
        record.put("dominantEmotion", er.getDominantEmotion());
        record.put("dominantConfidence", er.getDominantConfidence());
        record.put("emotions", emotions);
        return ResponseEntity.ok(Map.of("code", 0, "data", List.of(record)));
    }

    /** 通过 face_record_id 提供裁剪图（已认证，用于 API 调用） */
    @GetMapping("/face/{faceRecordId}/image")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getFaceImageApi(@PathVariable Long faceRecordId) {
        return serveFaceImage(faceRecordId);
    }

    private ResponseEntity<?> serveFaceImage(Long faceRecordId) {
        var optFr = faceRecordRepository.findById(faceRecordId);
        if (optFr.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String cropUrl = optFr.get().getCroppedImageUrl();
        if (cropUrl == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            java.nio.file.Path imgPath = java.nio.file.Path.of(cropUrl);
            if (!java.nio.file.Files.exists(imgPath)) {
                // 尝试从 /images/cropped/... 相对路径解析
                String relative = cropUrl.replace("/images/cropped/", "");
                imgPath = java.nio.file.Path.of(
                    System.getProperty("user.dir"), "images", "cropped", relative);
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(imgPath);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
