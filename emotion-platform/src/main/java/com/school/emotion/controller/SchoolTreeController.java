package com.school.emotion.controller;

import com.school.emotion.model.entity.*;
import com.school.emotion.repository.*;
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

                        // Add sample face images
                        List<FaceRecord> faces = faceRecordRepository.findByStudentId(s.getId());
                        List<String> sampleImages = faces.stream()
                                .filter(f -> f.getCroppedImageUrl() != null)
                                .limit(4)
                                .map(f -> toImageUrl(f.getCroppedImageUrl()))
                                .collect(Collectors.toList());
                        studentNode.put("sampleImages", sampleImages);
                        studentNode.put("faceCount", faces.size());
                        childNodes.add(studentNode);
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
                                        .ifPresent(fr -> samples.add(toImageUrl(fr.getCroppedImageUrl())));
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
                            faceNode.put("croppedImageUrl", toImageUrl(fr.getCroppedImageUrl()));
                            faceNode.put("confidence", fr.getConfidence());
                            if (fr.getCroppedImageUrl() != null) {
                                faceNode.put("sampleImages", List.of(toImageUrl(fr.getCroppedImageUrl())));
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
    public ResponseEntity<?> getStudentRawEmotions(@PathVariable Long id) {
        List<FaceRecord> faceRecords = faceRecordRepository.findByStudentId(id);
        List<Map<String, Object>> records = new ArrayList<>();

        for (FaceRecord fr : faceRecords) {
            EmotionRecord er = emotionRecordRepository.findByFaceRecordId(fr.getId());
            if (er == null) continue;

            Map<String, Object> record = new HashMap<>();
            record.put("faceRecordId", fr.getId());
            record.put("croppedImageUrl", toImageUrl(fr.getCroppedImageUrl()));
            record.put("imageUrl", fr.getClassImage() != null ? toImageUrl(fr.getClassImage().getImageUrl()) : null);
            record.put("captureTime", fr.getClassImage() != null ?
                    fr.getClassImage().getCaptureTime().toString() : null);
            record.put("periodLabel", fr.getClassImage() != null ?
                    fr.getClassImage().getPeriodLabel() : null);
            record.put("bbox", fr.getBbox());
            record.put("confidence", fr.getConfidence());
            record.put("dominantEmotion", er.getDominantEmotion());
            record.put("dominantConfidence", er.getDominantConfidence());
            record.put("emotions", Map.of(
                "happy", er.getEmotionHappy(),
                "sad", er.getEmotionSad(),
                "angry", er.getEmotionAngry(),
                "surprise", er.getEmotionSurprise(),
                "fear", er.getEmotionFear(),
                "disgust", er.getEmotionDisgust(),
                "neutral", er.getEmotionNeutral()
            ));
            records.add(record);
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", records));
    }
}
