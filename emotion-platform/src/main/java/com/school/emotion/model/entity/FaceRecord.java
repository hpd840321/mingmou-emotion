package com.school.emotion.model.entity;

import com.school.emotion.model.enums.FaceStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "face_record")
public class FaceRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_image_id", nullable = false)
    private ClassImage classImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(columnDefinition = "JSONB")
    private String bbox;

    @Column(name = "face_encoding", columnDefinition = "JSONB")
    private String faceEncoding;

    private Float confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FaceStatus status = FaceStatus.DETECTED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ClassImage getClassImage() { return classImage; }
    public void setClassImage(ClassImage classImage) { this.classImage = classImage; }
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public String getBbox() { return bbox; }
    public void setBbox(String bbox) { this.bbox = bbox; }
    public String getFaceEncoding() { return faceEncoding; }
    public void setFaceEncoding(String faceEncoding) { this.faceEncoding = faceEncoding; }
    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }
    public FaceStatus getStatus() { return status; }
    public void setStatus(FaceStatus status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
