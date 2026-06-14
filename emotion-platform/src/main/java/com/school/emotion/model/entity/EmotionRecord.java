package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "emotion_record")
public class EmotionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "face_record_id", nullable = false, unique = true)
    private FaceRecord faceRecord;

    @Column(name = "emotion_happy")
    private Float emotionHappy;

    @Column(name = "emotion_sad")
    private Float emotionSad;

    @Column(name = "emotion_angry")
    private Float emotionAngry;

    @Column(name = "emotion_surprise")
    private Float emotionSurprise;

    @Column(name = "emotion_fear")
    private Float emotionFear;

    @Column(name = "emotion_disgust")
    private Float emotionDisgust;

    @Column(name = "emotion_neutral")
    private Float emotionNeutral;

    @Column(name = "dominant_emotion", nullable = false, length = 20)
    private String dominantEmotion;

    @Column(name = "dominant_confidence", nullable = false)
    private Float dominantConfidence;

    @Column(name = "dominant_state", length = 20)
    private String dominantState;

    @Column(name = "emotional_cohesion")
    private Float emotionalCohesion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public FaceRecord getFaceRecord() { return faceRecord; }
    public void setFaceRecord(FaceRecord faceRecord) { this.faceRecord = faceRecord; }
    public Float getEmotionHappy() { return emotionHappy; }
    public void setEmotionHappy(Float emotionHappy) { this.emotionHappy = emotionHappy; }
    public Float getEmotionSad() { return emotionSad; }
    public void setEmotionSad(Float emotionSad) { this.emotionSad = emotionSad; }
    public Float getEmotionAngry() { return emotionAngry; }
    public void setEmotionAngry(Float emotionAngry) { this.emotionAngry = emotionAngry; }
    public Float getEmotionSurprise() { return emotionSurprise; }
    public void setEmotionSurprise(Float emotionSurprise) { this.emotionSurprise = emotionSurprise; }
    public Float getEmotionFear() { return emotionFear; }
    public void setEmotionFear(Float emotionFear) { this.emotionFear = emotionFear; }
    public Float getEmotionDisgust() { return emotionDisgust; }
    public void setEmotionDisgust(Float emotionDisgust) { this.emotionDisgust = emotionDisgust; }
    public Float getEmotionNeutral() { return emotionNeutral; }
    public void setEmotionNeutral(Float emotionNeutral) { this.emotionNeutral = emotionNeutral; }
    public String getDominantEmotion() { return dominantEmotion; }
    public void setDominantEmotion(String dominantEmotion) { this.dominantEmotion = dominantEmotion; }
    public Float getDominantConfidence() { return dominantConfidence; }
    public void setDominantConfidence(Float dominantConfidence) { this.dominantConfidence = dominantConfidence; }
    public String getDominantState() { return dominantState; }
    public void setDominantState(String dominantState) { this.dominantState = dominantState; }
    public Float getEmotionalCohesion() { return emotionalCohesion; }
    public void setEmotionalCohesion(Float emotionalCohesion) { this.emotionalCohesion = emotionalCohesion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
