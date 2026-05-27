package com.school.emotion.model.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public class ImageIngestRequest {
    @NotNull(message = "image file is required")
    private MultipartFile image;

    @NotNull(message = "classId is required")
    private Long classId;

    @NotNull(message = "captureTime is required")
    private String captureTime;

    private String periodLabel;

    public MultipartFile getImage() { return image; }
    public void setImage(MultipartFile image) { this.image = image; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public String getCaptureTime() { return captureTime; }
    public void setCaptureTime(String captureTime) { this.captureTime = captureTime; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
}
