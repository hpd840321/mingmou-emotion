package com.school.emotion.model.dto;

import java.util.List;

public class SchoolOverviewDTO {
    private List<KpiItem> kpis;
    private List<GradeComparison> gradeComparison;
    private List<AlertRanking> alertRanking;
    private List<java.util.Map<String, Object>> trendData;
    private List<AlertItem> crossClassAlerts;

    public List<KpiItem> getKpis() { return kpis; }
    public void setKpis(List<KpiItem> kpis) { this.kpis = kpis; }
    public List<GradeComparison> getGradeComparison() { return gradeComparison; }
    public void setGradeComparison(List<GradeComparison> gradeComparison) { this.gradeComparison = gradeComparison; }
    public List<AlertRanking> getAlertRanking() { return alertRanking; }
    public void setAlertRanking(List<AlertRanking> alertRanking) { this.alertRanking = alertRanking; }
    public List<java.util.Map<String, Object>> getTrendData() { return trendData; }
    public void setTrendData(List<java.util.Map<String, Object>> trendData) { this.trendData = trendData; }
    public List<AlertItem> getCrossClassAlerts() { return crossClassAlerts; }
    public void setCrossClassAlerts(List<AlertItem> crossClassAlerts) { this.crossClassAlerts = crossClassAlerts; }

    public static class KpiItem {
        private String label; private double value; private String unit;
        private Double change; private String changeDirection; private String status;
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public Double getChange() { return change; }
        public void setChange(Double change) { this.change = change; }
        public String getChangeDirection() { return changeDirection; }
        public void setChangeDirection(String changeDirection) { this.changeDirection = changeDirection; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class GradeComparison {
        private String name; private double value;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
    }

    public static class AlertRanking {
        private String className; private double rate;
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public double getRate() { return rate; }
        public void setRate(double rate) { this.rate = rate; }
    }

    public static class AlertItem {
        private Long id; private String studentName; private String className;
        private String message; private String severity; private String timestamp; private boolean acknowledged;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getStudentName() { return studentName; }
        public void setStudentName(String studentName) { this.studentName = studentName; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public boolean isAcknowledged() { return acknowledged; }
        public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }
    }
}
