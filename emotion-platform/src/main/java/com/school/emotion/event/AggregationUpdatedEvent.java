package com.school.emotion.event;

import java.time.LocalDate;

public class AggregationUpdatedEvent {
    private final Long studentId;
    private final LocalDate date;
    private final Long periodId;

    public AggregationUpdatedEvent(Long studentId, LocalDate date, Long periodId) {
        this.studentId = studentId;
        this.date = date;
        this.periodId = periodId;
    }

    public Long getStudentId() { return studentId; }
    public LocalDate getDate() { return date; }
    public Long getPeriodId() { return periodId; }
}
