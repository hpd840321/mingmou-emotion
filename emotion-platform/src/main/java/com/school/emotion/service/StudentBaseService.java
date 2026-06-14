package com.school.emotion.service;

import com.school.emotion.model.entity.Grade;
import com.school.emotion.model.entity.SchoolClass;
import com.school.emotion.model.entity.Student;
import com.school.emotion.model.entity.StudentFace;
import com.school.emotion.repository.GradeRepository;
import com.school.emotion.repository.SchoolClassRepository;
import com.school.emotion.repository.StudentFaceRepository;
import com.school.emotion.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StudentBaseService {

    private static final Logger log = LoggerFactory.getLogger(StudentBaseService.class);

    private final StudentRepository studentRepository;
    private final StudentFaceRepository studentFaceRepository;
    private final GradeRepository gradeRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final RestTemplate restTemplate;

    @Value("${student.face.upload-dir:./data/student-faces}")
    private String uploadDir;

    @Value("${visionmind.api.base-url:http://localhost:8080}")
    private String vmApiBase;

    public StudentBaseService(StudentRepository studentRepository,
                              StudentFaceRepository studentFaceRepository,
                              GradeRepository gradeRepository,
                              SchoolClassRepository schoolClassRepository,
                              RestTemplate restTemplate) {
        this.studentRepository = studentRepository;
        this.studentFaceRepository = studentFaceRepository;
        this.gradeRepository = gradeRepository;
        this.schoolClassRepository = schoolClassRepository;
        this.restTemplate = restTemplate;
    }

    @Transactional(readOnly = true)
    public Page<Student> listStudents(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return studentRepository.findAll(pageable);
    }

    @Transactional
    public List<String> importStudents(MultipartFile file) {
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                errors.add("Empty file");
                return errors;
            }
            String[] headers = headerLine.split(",");
            int gradeIdx = -1, classIdx = -1, studentNoIdx = -1, nameIdx = -1;
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim().toLowerCase();
                if (h.contains("grade") || h.contains("年级")) gradeIdx = i;
                else if (h.contains("class") || h.contains("班级")) classIdx = i;
                else if (h.contains("student_no") || h.contains("学号")) studentNoIdx = i;
                else if (h.contains("name") || h.contains("姓名")) nameIdx = i;
            }
            if (studentNoIdx < 0 || nameIdx < 0) {
                errors.add("Missing required columns: student_no/学号, name/姓名");
                return errors;
            }
            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String[] cols = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                if (cols.length <= Math.max(studentNoIdx, nameIdx)) {
                    errors.add("Line " + lineNum + ": insufficient columns");
                    continue;
                }
                String studentNo = cols[studentNoIdx].trim().replaceAll("^\"|\"$", "");
                String name = cols[nameIdx].trim().replaceAll("^\"|\"$", "");
                if (studentNo.isEmpty() || name.isEmpty()) {
                    errors.add("Line " + lineNum + ": empty student_no or name");
                    continue;
                }
                if (studentRepository.findByStudentNo(studentNo).isPresent()) {
                    continue;
                }
                Student student = new Student();
                student.setStudentNo(studentNo);
                student.setName(name);
                if (gradeIdx >= 0 && classIdx >= 0 && gradeIdx < cols.length && classIdx < cols.length) {
                    String gradeName = cols[gradeIdx].trim().replaceAll("^\"|\"$", "");
                    String className = cols[classIdx].trim().replaceAll("^\"|\"$", "");
                    SchoolClass schoolClass = findOrCreateClass(gradeName, className);
                    student.setClazz(schoolClass);
                }
                studentRepository.save(student);
            }
        } catch (Exception e) {
            log.error("Failed to import students", e);
            errors.add("Import failed: " + e.getMessage());
        }
        return errors;
    }

    private SchoolClass findOrCreateClass(String gradeName, String className) {
        List<Grade> grades = gradeRepository.findAll();
        Grade grade = grades.stream()
                .filter(g -> g.getName().equals(gradeName))
                .findFirst()
                .orElse(null);
        if (grade == null) {
            grade = new Grade();
            grade.setName(gradeName);
            grade.setSortOrder(0);
            grade = gradeRepository.save(grade);
        }
        final Grade finalGrade = grade;
        List<SchoolClass> classes = schoolClassRepository.findByGrade_Id(finalGrade.getId());
        return classes.stream()
                .filter(c -> c.getName().equals(className))
                .findFirst()
                .orElseGet(() -> {
                    SchoolClass newClass = new SchoolClass();
                    newClass.setName(className);
                    newClass.setGrade(finalGrade);
                    return schoolClassRepository.save(newClass);
                });
    }

    @Transactional
    public StudentFace uploadFace(Long studentId, MultipartFile image) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Student not found: " + studentId));
        try {
            Path dir = Path.of(uploadDir);
            Files.createDirectories(dir);
            String filename = UUID.randomUUID() + "_" + image.getOriginalFilename();
            Path target = dir.resolve(filename);
            Files.copy(image.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            StudentFace studentFace = new StudentFace();
            studentFace.setStudent(student);
            studentFace.setImageUrl(target.toAbsolutePath().toString());
            studentFace.setIsPrimary(studentFaceRepository.count() == 0);
            studentFaceRepository.save(studentFace);

            extractFeatureAndRegister(studentFace, target);

            return studentFace;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload face image", e);
        }
    }

    private void extractFeatureAndRegister(StudentFace studentFace, Path imagePath) {
        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String faceId = "stuface_" + studentFace.getId();
            Map<String, Object> body = Map.of(
                    "id", faceId,
                    "name", faceId,
                    "image", "data:image/jpeg;base64," + base64
            );
            restTemplate.postForEntity(
                    vmApiBase + "/v1/facedb/register",
                    new HttpEntity<>(body, headers),
                    Map.class);
        } catch (Exception e) {
            log.warn("Face feature extraction failed for faceId={}: {}", studentFace.getId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<StudentFace> getStudentFaces(Long studentId) {
        return studentFaceRepository.findAll().stream()
                .filter(sf -> sf.getStudent() != null && sf.getStudent().getId().equals(studentId))
                .toList();
    }

    @Transactional
    public void deleteFace(Long faceId) {
        StudentFace studentFace = studentFaceRepository.findById(faceId)
                .orElseThrow(() -> new RuntimeException("StudentFace not found: " + faceId));
        try {
            if (studentFace.getImageUrl() != null) {
                Files.deleteIfExists(Path.of(studentFace.getImageUrl()));
            }
        } catch (Exception e) {
            log.warn("Failed to delete face image file: {}", e.getMessage());
        }
        studentFaceRepository.delete(studentFace);
    }
}
