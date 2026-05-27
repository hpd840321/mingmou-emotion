package com.school.emotion.service;

import com.school.emotion.model.dto.SchoolOverviewDTO;
import com.school.emotion.repository.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardService {

    private final EmotionAggregationRepository aggregationRepository;
    private final AlertLogRepository alertLogRepository;

    public DashboardService(EmotionAggregationRepository aggregationRepository,
                            AlertLogRepository alertLogRepository) {
        this.aggregationRepository = aggregationRepository;
        this.alertLogRepository = alertLogRepository;
    }

    public SchoolOverviewDTO getSchoolOverview(Long gradeId, String period) {
        SchoolOverviewDTO dto = new SchoolOverviewDTO();
        var aggs = aggregationRepository.findAll();
        double avgHealth = aggs.stream().mapToDouble(a -> a.getPositiveRatio() * 100).average().orElse(0);
        double avgEngagement = aggs.stream().mapToDouble(a -> a.getEngagementScore()).average().orElse(0);
        double avgNegative = aggs.stream().mapToDouble(a -> a.getNegativeRatio() * 100).average().orElse(0);
        long alertCount = alertLogRepository.countByAcknowledgedFalse();

        dto.setKpis(List.of(
            createKpi("情绪健康度", Math.round(avgHealth), "%", avgHealth > 60 ? "good" : "warning"),
            createKpi("课堂参与度", Math.round(avgEngagement), "%", avgEngagement > 60 ? "good" : "warning"),
            createKpi("异常情绪率", Math.round(avgNegative), "%", avgNegative < 20 ? "good" : "danger"),
            createKpi("重点关注", alertCount, "人", alertCount > 0 ? "danger" : "good")
        ));
        dto.setGradeComparison(new ArrayList<>());
        dto.setAlertRanking(new ArrayList<>());
        dto.setCrossClassAlerts(new ArrayList<>());
        return dto;
    }

    private SchoolOverviewDTO.KpiItem createKpi(String label, long value, String unit, String status) {
        var item = new SchoolOverviewDTO.KpiItem();
        item.setLabel(label);
        item.setValue(value);
        item.setUnit(unit);
        item.setChange(null);
        item.setChangeDirection("flat");
        item.setStatus(status);
        return item;
    }

}
