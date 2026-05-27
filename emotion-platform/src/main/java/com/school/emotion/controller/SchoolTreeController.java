package com.school.emotion.controller;

import com.school.emotion.model.entity.Grade;
import com.school.emotion.model.entity.SchoolClass;
import com.school.emotion.model.entity.Student;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.model.entity.EmotionRecord;
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

    public SchoolTreeController(GradeRepository gradeRepository,
                                SchoolClassRepository classRepository,
                                StudentRepository studentRepository,
                                FaceRecordRepository faceRecordRepository,
                                EmotionRecordRepository emotionRecordRepository) {
        this.gradeRepository = gradeRepository;
        this.classRepository = classRepository;
        this.studentRepository = studentRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
    }

    /** 获取学校树结构: grades → classes → students */
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
                List<Map<String, Object>> studentNodes = new ArrayList<>();

                for (Student s : students) {
                    Map<String, Object> studentNode = new HashMap<>();
                    studentNode.put("id", "student-" + s.getId());
                    studentNode.put("label", s.getName());
                    studentNode.put("type", "student");
                    studentNode.put("studentId", s.getId());
                    studentNode.put("studentNo", s.getStudentNo());
                    studentNodes.add(studentNode);
                }
                classNode.put("children", studentNodes);
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
