package com.school.emotion.service;

import com.school.emotion.client.VisionMindClient;
import com.school.emotion.model.dto.AnnotateRequest;
import com.school.emotion.model.dto.FaceClusterVO;
import com.school.emotion.model.entity.FaceCluster;
import com.school.emotion.model.entity.SchoolClass;
import com.school.emotion.model.entity.Student;
import com.school.emotion.repository.FaceClusterRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.SchoolClassRepository;
import com.school.emotion.repository.StudentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class FaceLibraryService {

    private final VisionMindClient visionMind;
    private final FaceClusterRepository clusterRepository;
    private final StudentRepository studentRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final SchoolClassRepository classRepository;
    private final ApplicationEventPublisher eventPublisher;

    public FaceLibraryService(VisionMindClient visionMind,
                              FaceClusterRepository clusterRepository,
                              StudentRepository studentRepository,
                              FaceRecordRepository faceRecordRepository,
                              SchoolClassRepository classRepository,
                              ApplicationEventPublisher eventPublisher) {
        this.visionMind = visionMind;
        this.clusterRepository = clusterRepository;
        this.studentRepository = studentRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.classRepository = classRepository;
        this.eventPublisher = eventPublisher;
    }

    public List<FaceClusterVO> listPendingClusters(Long classId, String status) {
        List<FaceCluster> clusters = clusterRepository
                .findByClassIdAndStatusOrderBySampleCountDesc(classId, status);
        List<FaceClusterVO> result = new ArrayList<>();
        for (FaceCluster c : clusters) {
            FaceClusterVO vo = new FaceClusterVO();
            vo.setId(c.getId());
            vo.setClassId(c.getClassId());
            vo.setSampleCount(c.getSampleCount());
            vo.setFirstSeenAt(c.getFirstSeenAt());
            vo.setLastSeenAt(c.getLastSeenAt());
            result.add(vo);
        }
        return result;
    }

    @Transactional
    public void annotateCluster(Long clusterId, AnnotateRequest request) {
        FaceCluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + clusterId));

        Student student = new Student();
        student.setClazz(classRepository.getReferenceById(request.getClassId()));
        student.setStudentNo(request.getStudentNo());
        student.setName(request.getStudentName());
        student.setStatus("active");
        student = studentRepository.save(student);

        String extraJson = "{\"student_id\":" + student.getId()
                + ",\"class_id\":" + request.getClassId() + "}";
        visionMind.registerFace(request.getStudentNo(), request.getStudentName(),
                extraJson, new byte[0]);

        cluster.setStatus("annotated");
        cluster.setAnnotatedAt(OffsetDateTime.now());
        cluster.setAnnotatedBy(student.getId());
        clusterRepository.save(cluster);
    }

    @Transactional
    public void mergeCluster(Long clusterId, Long studentId) {
        FaceCluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + clusterId));
        cluster.setStatus("merged");
        clusterRepository.save(cluster);
    }
}
