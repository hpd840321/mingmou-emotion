package com.school.emotion.model.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class FaceClusterVO {
    private Long id;
    private Long classId;
    private String className;
    private Integer sampleCount;
    private OffsetDateTime firstSeenAt;
    private OffsetDateTime lastSeenAt;
    private List<String> periodLabels;
    private List<String> sampleImages;

    private Long studentId;
    private String studentName;
    private String studentNo;
    private Boolean autoAnnotated;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(OffsetDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public List<String> getPeriodLabels() { return periodLabels; }
    public void setPeriodLabels(List<String> periodLabels) { this.periodLabels = periodLabels; }
    public List<String> getSampleImages() { return sampleImages; }
    public void setSampleImages(List<String> sampleImages) { this.sampleImages = sampleImages; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public Boolean getAutoAnnotated() { return autoAnnotated; }
    public void setAutoAnnotated(Boolean autoAnnotated) { this.autoAnnotated = autoAnnotated; }
}
