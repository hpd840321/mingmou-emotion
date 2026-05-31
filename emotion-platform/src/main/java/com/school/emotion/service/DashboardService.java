package com.school.emotion.service;

import com.school.emotion.model.dto.SchoolOverviewDTO;
import com.school.emotion.model.entity.EmotionAggregation;
import com.school.emotion.model.entity.Grade;
import com.school.emotion.model.entity.SchoolClass;
import com.school.emotion.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardService {

    private final EmotionAggregationRepository aggregationRepository;
    private final AlertLogRepository alertLogRepository;
    private final GradeRepository gradeRepository;
    private final SchoolClassRepository schoolClassRepository;

    public DashboardService(EmotionAggregationRepository aggregationRepository,
                            AlertLogRepository alertLogRepository,
                            GradeRepository gradeRepository,
                            SchoolClassRepository schoolClassRepository) {
        this.aggregationRepository = aggregationRepository;
        this.alertLogRepository = alertLogRepository;
        this.gradeRepository = gradeRepository;
        this.schoolClassRepository = schoolClassRepository;
    }

    public SchoolOverviewDTO getSchoolOverview(Long gradeId, String period) {
        SchoolOverviewDTO dto = new SchoolOverviewDTO();
        List<EmotionAggregation> aggs = aggregationRepository.findAll();

        double avgHealth = aggs.stream().mapToDouble(a ->
                a.getPositiveRatio() != null ? a.getPositiveRatio() * 100 : 0).average().orElse(0);
        double avgEngagement = aggs.stream().mapToDouble(a ->
                a.getEngagementScore() != null ? a.getEngagementScore() : 0).average().orElse(0);
        double avgNegative = aggs.stream().mapToDouble(a ->
                a.getNegativeRatio() != null ? a.getNegativeRatio() * 100 : 0).average().orElse(0);
        long alertCount = alertLogRepository.countByAcknowledgedFalse();

        dto.setKpis(List.of(
            createKpi("情绪健康度", Math.round(avgHealth), "%", avgHealth > 60 ? "good" : "warning"),
            createKpi("课堂参与度", Math.round(avgEngagement), "%", avgEngagement > 60 ? "good" : "warning"),
            createKpi("异常情绪率", Math.round(avgNegative), "%", avgNegative < 20 ? "good" : "danger"),
            createKpi("重点关注", alertCount, "人", alertCount > 0 ? "danger" : "good")
        ));

        // Grade comparison - aggregate positive ratio per grade
        List<Grade> grades = gradeRepository.findAll();
        List<SchoolOverviewDTO.GradeComparison> gradeComp = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Grade g : grades) {
            List<SchoolClass> classes = schoolClassRepository.findByGrade_Id(g.getId());
            double totalPos = 0;
            int classCount = 0;
            for (SchoolClass c : classes) {
                var classAggs = aggregationRepository.findByClassIdAndDate(c.getId(), today);
                if (!classAggs.isEmpty()) {
                    totalPos += classAggs.stream().mapToDouble(a ->
                            a.getPositiveRatio() != null ? a.getPositiveRatio() : 0).average().orElse(0);
                    classCount++;
                }
            }
            if (classCount > 0) {
                var item = new SchoolOverviewDTO.GradeComparison();
                item.setName(g.getName());
                item.setValue(Math.round(totalPos / classCount * 100));
                gradeComp.add(item);
            }
        }
        dto.setGradeComparison(gradeComp);

        // Alert ranking - negative ratio per class, sorted descending
        List<SchoolOverviewDTO.AlertRanking> rankings = new ArrayList<>();
        for (Grade g : grades) {
            List<SchoolClass> classes = schoolClassRepository.findByGrade_Id(g.getId());
            for (SchoolClass c : classes) {
                var classAggs = aggregationRepository.findByClassIdAndDate(c.getId(), today);
                if (!classAggs.isEmpty()) {
                    double negRate = classAggs.stream().mapToDouble(a ->
                            a.getNegativeRatio() != null ? a.getNegativeRatio() : 0).average().orElse(0);
                    var item = new SchoolOverviewDTO.AlertRanking();
                    item.setClassName(c.getName());
                    item.setRate(Math.round(negRate * 100) / 100.0);
                    rankings.add(item);
                }
            }
        }
        rankings.sort((a, b) -> Double.compare(b.getRate(), a.getRate()));
        if (rankings.size() > 5) rankings = rankings.subList(0, 5);
        dto.setAlertRanking(rankings);

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
