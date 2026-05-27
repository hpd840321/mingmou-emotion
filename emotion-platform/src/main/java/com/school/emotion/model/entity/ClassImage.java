package com.school.emotion.model.entity;

import com.school.emotion.model.enums.ImageStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "class_image")
public class ClassImage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id", nullable = false)
    private SchoolClass clazz;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @Column(name = "capture_time", nullable = false)
    private OffsetDateTime captureTime;

    @Column(name = "period_label", length = 20)
    private String periodLabel;

    @Column(length = 50)
    private String source = "third_party";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImageStatus status = ImageStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public SchoolClass getClazz() { return clazz; }
    public void setClazz(SchoolClass clazz) { this.clazz = clazz; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public OffsetDateTime getCaptureTime() { return captureTime; }
    public void setCaptureTime(OffsetDateTime captureTime) { this.captureTime = captureTime; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public ImageStatus getStatus() { return status; }
    public void setStatus(ImageStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
