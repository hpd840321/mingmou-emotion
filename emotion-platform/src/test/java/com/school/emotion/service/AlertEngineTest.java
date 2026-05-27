package com.school.emotion.service;

import com.school.emotion.event.AlertTriggeredEvent;
import com.school.emotion.model.entity.AlertLog;
import com.school.emotion.model.entity.AlertRule;
import com.school.emotion.model.entity.EmotionAggregation;
import com.school.emotion.repository.AlertLogRepository;
import com.school.emotion.repository.AlertRuleRepository;
import com.school.emotion.repository.EmotionAggregationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertEngineTest {

    @Mock private AlertRuleRepository ruleRepository;
    @Mock private AlertLogRepository logRepository;
    @Mock private EmotionAggregationRepository aggregationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AlertEngine alertEngine;

    private AlertRule createRule(String metric, String operator, float threshold) {
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("测试规则");
        rule.setMetric(metric);
        rule.setOperator(operator);
        rule.setThreshold(threshold);
        rule.setScope("global");
        rule.setEnabled(true);
        return rule;
    }

    private EmotionAggregation createAggregation(float negativeRatio, float engagement) {
        EmotionAggregation agg = new EmotionAggregation();
        agg.setNegativeRatio(negativeRatio);
        agg.setEngagementScore(engagement);
        agg.setDate(LocalDate.now());
        return agg;
    }

    @Test
    void evaluateForStudent_shouldTriggerAlertWhenThresholdExceeded() {
        AlertRule rule = createRule("negative_ratio", ">", 0.5f);
        EmotionAggregation agg = createAggregation(0.8f, 40f);

        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
        when(aggregationRepository.findByStudentIdAndDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(agg));
        when(logRepository.existsByAlertRuleIdAndStudentIdAndAcknowledgedFalse(anyLong(), anyLong()))
                .thenReturn(false);
        when(logRepository.save(any())).thenAnswer(inv -> {
            AlertLog al = inv.getArgument(0);
            al.setId(1L);
            return al;
        });

        alertEngine.evaluateForStudent(1L);

        ArgumentCaptor<AlertLog> captor = ArgumentCaptor.forClass(AlertLog.class);
        verify(logRepository).save(captor.capture());

        AlertLog saved = captor.getValue();
        assertEquals(1L, saved.getAlertRuleId());
        assertEquals(1L, saved.getStudentId());
        assertEquals(0.8f, saved.getTriggerValue(), 0.01);
        assertFalse(saved.getAcknowledged());

        verify(eventPublisher).publishEvent(any(AlertTriggeredEvent.class));
    }

    @Test
    void evaluateForStudent_shouldNotTriggerWhenBelowThreshold() {
        AlertRule rule = createRule("negative_ratio", ">", 0.5f);
        EmotionAggregation agg = createAggregation(0.3f, 70f);

        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
        when(aggregationRepository.findByStudentIdAndDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(agg));

        alertEngine.evaluateForStudent(1L);

        verify(logRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void evaluateForStudent_shouldNotDuplicateAlerts() {
        AlertRule rule = createRule("negative_ratio", ">", 0.5f);
        EmotionAggregation agg = createAggregation(0.9f, 20f);

        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
        when(aggregationRepository.findByStudentIdAndDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(agg));
        when(logRepository.existsByAlertRuleIdAndStudentIdAndAcknowledgedFalse(1L, 1L))
                .thenReturn(true); // Already has unacknowledged alert

        alertEngine.evaluateForStudent(1L);

        verify(logRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void evaluateForStudent_shouldSupportMultipleOperators() {
        // Test >= operator
        AlertRule rule = createRule("engagement_score", "<=", 30f);
        EmotionAggregation agg = createAggregation(0.6f, 25f);

        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
        when(aggregationRepository.findByStudentIdAndDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(agg));
        when(logRepository.existsByAlertRuleIdAndStudentIdAndAcknowledgedFalse(anyLong(), anyLong()))
                .thenReturn(false);
        when(logRepository.save(any())).thenAnswer(inv -> {
            AlertLog al = inv.getArgument(0);
            al.setId(1L);
            return al;
        });

        alertEngine.evaluateForStudent(1L);
    
        verify(logRepository).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(AlertTriggeredEvent.class));
    }
}
