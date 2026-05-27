package com.school.emotion.model.dto;

import jakarta.validation.constraints.NotBlank;

public class AnnotateRequest {
    @NotBlank
    private String studentName;
    @NotBlank
    private String studentNo;
    private Long classId;

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
}
