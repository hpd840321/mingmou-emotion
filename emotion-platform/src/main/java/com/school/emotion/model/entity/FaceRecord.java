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

    @Column(columnDefinition = "TEXT")
    private String bbox;

    @Column(name = "face_encoding", columnDefinition = "TEXT")
    private String faceEncoding;

    private Float confidence;

    @Column(name = "quality")
    private Float quality;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FaceStatus status = FaceStatus.DETECTED;

    @Column(name = "cropped_image_url", columnDefinition = "TEXT")
    private String croppedImageUrl;

    @Column(name = "is_registered_to_lib")
    private Boolean isRegisteredToLib = false;

    @Column(name = "registered_at")
    private OffsetDateTime registeredAt;

    @Column(name = "lib_face_id", length = 64)
    private String libFaceId;

    @Column(name = "lib_register_status", length = 20)
    private String libRegisterStatus = "pending";

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
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
    public Float getQuality() { return quality; }
    public void setQuality(Float quality) { this.quality = quality; }
    public FaceStatus getStatus() { return status; }
    public void setStatus(FaceStatus status) { this.status = status; }
    public String getCroppedImageUrl() { return croppedImageUrl; }
    public void setCroppedImageUrl(String croppedImageUrl) { this.croppedImageUrl = croppedImageUrl; }
    public Boolean getIsRegisteredToLib() { return isRegisteredToLib; }
    public void setIsRegisteredToLib(Boolean isRegisteredToLib) { this.isRegisteredToLib = isRegisteredToLib; }
    public OffsetDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(OffsetDateTime registeredAt) { this.registeredAt = registeredAt; }
    public String getLibFaceId() { return libFaceId; }
    public void setLibFaceId(String libFaceId) { this.libFaceId = libFaceId; }
    public String getLibRegisterStatus() { return libRegisterStatus; }
    public void setLibRegisterStatus(String libRegisterStatus) { this.libRegisterStatus = libRegisterStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
