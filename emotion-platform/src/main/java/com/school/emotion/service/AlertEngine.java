package com.school.emotion.service;

import com.school.emotion.event.AlertTriggeredEvent;
import com.school.emotion.model.entity.AlertLog;
import com.school.emotion.model.entity.AlertRule;
import com.school.emotion.model.entity.EmotionAggregation;
import com.school.emotion.repository.AlertLogRepository;
import com.school.emotion.repository.AlertRuleRepository;
import com.school.emotion.repository.EmotionAggregationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BiPredicate;

@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final AlertRuleRepository ruleRepository;
    private final AlertLogRepository logRepository;
    private final EmotionAggregationRepository aggregationRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AlertEngine(AlertRuleRepository ruleRepository,
                       AlertLogRepository logRepository,
                       EmotionAggregationRepository aggregationRepository,
                       ApplicationEventPublisher eventPublisher) {
        this.ruleRepository = ruleRepository;
        this.logRepository = logRepository;
        this.aggregationRepository = aggregationRepository;
        this.eventPublisher = eventPublisher;
    }

    public void evaluateForStudent(Long studentId) {
        List<AlertRule> rules = ruleRepository.findByEnabledTrue();
        for (AlertRule rule : rules) {
            evaluateRule(rule, studentId);
        }
    }

    @Scheduled(fixedRate = 300000)
    public void scheduledEvaluation() {
        log.debug("Running scheduled alert evaluation");
        List<AlertRule> rules = ruleRepository.findByEnabledTrue();
        for (AlertRule rule : rules) {
            if ("global".equals(rule.getScope())) {
                evaluateGlobalRule(rule);
            }
        }
    }

    private void evaluateRule(AlertRule rule, Long studentId) {
        var aggregations = aggregationRepository.findByStudentIdAndDateBetween(
                studentId, LocalDate.now().minusDays(1), LocalDate.now());
        if (aggregations.isEmpty()) return;

        double value = extractMetric(aggregations, rule.getMetric());
        boolean triggered = compare(value, rule.getOperator(), rule.getThreshold());

        if (triggered && !logRepository.existsByAlertRuleIdAndStudentIdAndAcknowledgedFalse(
                rule.getId(), studentId)) {
            createAlert(rule, studentId, value);
        }
    }

    private double extractMetric(List<EmotionAggregation> aggs, String metric) {
        return switch (metric) {
            case "negative_ratio" -> aggs.stream().mapToDouble(EmotionAggregation::getNegativeRatio).average().orElse(0);
            case "positive_ratio" -> aggs.stream().mapToDouble(EmotionAggregation::getPositiveRatio).average().orElse(0);
            case "engagement_score" -> aggs.stream().mapToDouble(EmotionAggregation::getEngagementScore).average().orElse(0);
            default -> 0;
        };
    }

    private boolean compare(double value, String operator, double threshold) {
        BiPredicate<Double, Double> predicate = switch (operator) {
            case ">" -> (v, t) -> v > t;
            case ">=" -> (v, t) -> v >= t;
            case "<" -> (v, t) -> v < t;
            case "<=" -> (v, t) -> v <= t;
            case "==" -> (v, t) -> Math.abs(v - t) < 0.001;
            default -> (v, t) -> false;
        };
        return predicate.test(value, threshold);
    }

    private void createAlert(AlertRule rule, Long studentId, double value) {
        AlertLog alertLog = new AlertLog();
        alertLog.setAlertRuleId(rule.getId());
        alertLog.setStudentId(studentId);
        alertLog.setTriggerValue((float) value);
        alertLog.setMessage(String.format("规则[%s]触发: %s=%.2f, 阈值=%.2f",
                rule.getName(), rule.getMetric(), value, rule.getThreshold()));
        alertLog.setAcknowledged(false);
        alertLog = logRepository.save(alertLog);

        eventPublisher.publishEvent(new AlertTriggeredEvent(
                alertLog.getId(), studentId, "", "",
                alertLog.getMessage(), "medium"));
        log.warn("Alert triggered: studentId={}, rule={}", studentId, rule.getName());
    }

    private void evaluateGlobalRule(AlertRule rule) {
    }
}
