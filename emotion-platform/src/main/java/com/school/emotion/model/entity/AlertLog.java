package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "alert_log")
public class AlertLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_rule_id", nullable = false)
    private Long alertRuleId;

    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "trigger_value")
    private Float triggerValue;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false)
    private Boolean acknowledged = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAlertRuleId() { return alertRuleId; }
    public void setAlertRuleId(Long alertRuleId) { this.alertRuleId = alertRuleId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public Float getTriggerValue() { return triggerValue; }
    public void setTriggerValue(Float triggerValue) { this.triggerValue = triggerValue; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Boolean getAcknowledged() { return acknowledged; }
    public void setAcknowledged(Boolean acknowledged) { this.acknowledged = acknowledged; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
