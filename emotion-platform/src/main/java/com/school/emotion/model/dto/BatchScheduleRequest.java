package com.school.emotion.model.dto;

import com.school.emotion.model.entity.CourseSchedule;
import java.util.List;

public class BatchScheduleRequest {
    private String week;
    private List<CourseSchedule> entries;

    public String getWeek() { return week; }
    public void setWeek(String week) { this.week = week; }
    public List<CourseSchedule> getEntries() { return entries; }
    public void setEntries(List<CourseSchedule> entries) { this.entries = entries; }
}
