package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "face_cluster")
public class FaceCluster {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "cluster_key", nullable = false, length = 64)
    private String clusterKey;

    @Column(name = "face_tokens", columnDefinition = "JSONB", nullable = false)
    private String faceTokens;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount = 0;

    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(columnDefinition = "REAL[]")
    private Float[] centroid;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "annotated_by")
    private Long annotatedBy;

    @Column(name = "annotated_at")
    private OffsetDateTime annotatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public String getClusterKey() { return clusterKey; }
    public void setClusterKey(String clusterKey) { this.clusterKey = clusterKey; }
    public String getFaceTokens() { return faceTokens; }
    public void setFaceTokens(String faceTokens) { this.faceTokens = faceTokens; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(OffsetDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAnnotatedBy() { return annotatedBy; }
    public void setAnnotatedBy(Long annotatedBy) { this.annotatedBy = annotatedBy; }
    public OffsetDateTime getAnnotatedAt() { return annotatedAt; }
    public void setAnnotatedAt(OffsetDateTime annotatedAt) { this.annotatedAt = annotatedAt; }
}
