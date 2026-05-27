package com.school.emotion.event;

import java.time.LocalDate;

public class ImageProcessedEvent {
    private final Long classImageId;
    private final Long classId;
    private final LocalDate date;
    private final Long periodId;
    private final Long studentId;

    public ImageProcessedEvent(Long classImageId, Long classId, LocalDate date,
                                Long periodId, Long studentId) {
        this.classImageId = classImageId;
        this.classId = classId;
        this.date = date;
        this.periodId = periodId;
        this.studentId = studentId;
    }

    public Long getClassImageId() { return classImageId; }
    public Long getClassId() { return classId; }
    public LocalDate getDate() { return date; }
    public Long getPeriodId() { return periodId; }
    public Long getStudentId() { return studentId; }
}
