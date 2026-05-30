package com.school.emotion.client;

public class ExternalEmotionPushRecord {
    private Long Id;
    private String CameraCode;
    private String student_code;
    private String SmallPic;
    private String CaptureTime;
    private String ImageUrl;
    private String Confidence;
    private Integer score;
    private String color;
    private String Emotion;
    private String GazeDirection;
    private String created_at;

    public Long getId() { return Id; }
    public void setId(Long id) { Id = id; }
    public String getCameraCode() { return CameraCode; }
    public void setCameraCode(String cameraCode) { CameraCode = cameraCode; }
    public String getStudent_code() { return student_code; }
    public void setStudent_code(String student_code) { this.student_code = student_code; }
    public String getSmallPic() { return SmallPic; }
    public void setSmallPic(String smallPic) { SmallPic = smallPic; }
    public String getCaptureTime() { return CaptureTime; }
    public void setCaptureTime(String captureTime) { CaptureTime = captureTime; }
    public String getImageUrl() { return ImageUrl; }
    public void setImageUrl(String imageUrl) { ImageUrl = imageUrl; }
    public String getConfidence() { return Confidence; }
    public void setConfidence(String confidence) { Confidence = confidence; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getEmotion() { return Emotion; }
    public void setEmotion(String emotion) { Emotion = emotion; }
    public String getGazeDirection() { return GazeDirection; }
    public void setGazeDirection(String gazeDirection) { GazeDirection = gazeDirection; }
    public String getCreated_at() { return created_at; }
    public void setCreated_at(String created_at) { this.created_at = created_at; }
}
