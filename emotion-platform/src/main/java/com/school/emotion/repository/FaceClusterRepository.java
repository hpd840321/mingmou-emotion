package com.school.emotion.repository;

import com.school.emotion.model.entity.FaceCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FaceClusterRepository extends JpaRepository<FaceCluster, Long> {
    List<FaceCluster> findByClassIdAndStatusOrderBySampleCountDesc(Long classId, String status);
    List<FaceCluster> findByClassIdAndStatusInOrderBySampleCountDesc(Long classId, java.util.Collection<String> statuses);
    List<FaceCluster> findByStatus(String status);
    long countByClassIdAndStatus(Long classId, String status);
}
