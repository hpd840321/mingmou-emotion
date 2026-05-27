package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "emotion_aggregation",
       uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "date", "period_id"}))
public class EmotionAggregation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount = 0;

    @Column(name = "ratio_happy")
    private Float ratioHappy;

    @Column(name = "ratio_sad")
    private Float ratioSad;

    @Column(name = "ratio_angry")
    private Float ratioAngry;

    @Column(name = "ratio_surprise")
    private Float ratioSurprise;

    @Column(name = "ratio_fear")
    private Float ratioFear;

    @Column(name = "ratio_disgust")
    private Float ratioDisgust;

    @Column(name = "ratio_neutral")
    private Float ratioNeutral;

    @Column(name = "engagement_score")
    private Float engagementScore;

    @Column(name = "positive_ratio")
    private Float positiveRatio;

    @Column(name = "negative_ratio")
    private Float negativeRatio;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public Float getRatioHappy() { return ratioHappy; }
    public void setRatioHappy(Float ratioHappy) { this.ratioHappy = ratioHappy; }
    public Float getRatioSad() { return ratioSad; }
    public void setRatioSad(Float ratioSad) { this.ratioSad = ratioSad; }
    public Float getRatioAngry() { return ratioAngry; }
    public void setRatioAngry(Float ratioAngry) { this.ratioAngry = ratioAngry; }
    public Float getRatioSurprise() { return ratioSurprise; }
    public void setRatioSurprise(Float ratioSurprise) { this.ratioSurprise = ratioSurprise; }
    public Float getRatioFear() { return ratioFear; }
    public void setRatioFear(Float ratioFear) { this.ratioFear = ratioFear; }
    public Float getRatioDisgust() { return ratioDisgust; }
    public void setRatioDisgust(Float ratioDisgust) { this.ratioDisgust = ratioDisgust; }
    public Float getRatioNeutral() { return ratioNeutral; }
    public void setRatioNeutral(Float ratioNeutral) { this.ratioNeutral = ratioNeutral; }
    public Float getEngagementScore() { return engagementScore; }
    public void setEngagementScore(Float engagementScore) { this.engagementScore = engagementScore; }
    public Float getPositiveRatio() { return positiveRatio; }
    public void setPositiveRatio(Float positiveRatio) { this.positiveRatio = positiveRatio; }
    public Float getNegativeRatio() { return negativeRatio; }
    public void setNegativeRatio(Float negativeRatio) { this.negativeRatio = negativeRatio; }
}
