package com.school.emotion.event;

public class AlertTriggeredEvent {
    private final Long alertLogId;
    private final Long studentId;
    private final String studentName;
    private final String className;
    private final String message;
    private final String severity;

    public AlertTriggeredEvent(Long alertLogId, Long studentId, String studentName,
                                String className, String message, String severity) {
        this.alertLogId = alertLogId;
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.message = message;
        this.severity = severity;
    }

    public Long getAlertLogId() { return alertLogId; }
    public Long getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public String getMessage() { return message; }
    public String getSeverity() { return severity; }
}
