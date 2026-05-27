package com.school.emotion.service;

import com.school.emotion.event.AggregationUpdatedEvent;
import com.school.emotion.model.entity.EmotionAggregation;
import com.school.emotion.repository.EmotionAggregationRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.service.analysis.EngagementCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmotionAggregationService {

    private static final Logger log = LoggerFactory.getLogger(EmotionAggregationService.class);

    private final EmotionAggregationRepository aggregationRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EngagementCalculator engagementCalculator;
    private final ApplicationEventPublisher eventPublisher;

    public EmotionAggregationService(EmotionAggregationRepository aggregationRepository,
                                      EmotionRecordRepository emotionRecordRepository,
                                      FaceRecordRepository faceRecordRepository,
                                      EngagementCalculator engagementCalculator,
                                      ApplicationEventPublisher eventPublisher) {
        this.aggregationRepository = aggregationRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.engagementCalculator = engagementCalculator;
        this.eventPublisher = eventPublisher;
    }

    @Async
    @Transactional
    public void aggregate(Long studentId, LocalDate date, Long periodId) {
        var faceRecords = faceRecordRepository.findByStudentId(studentId);
        if (faceRecords.isEmpty()) return;

        int total = 0;
        Map<String, Integer> emotionCounts = new HashMap<>();
        for (var fr : faceRecords) {
            var er = emotionRecordRepository.findByFaceRecordId(fr.getId());
            if (er != null) {
                emotionCounts.merge(er.getDominantEmotion(), 1, Integer::sum);
                total++;
            }
        }
        if (total == 0) return;

        float ratioHappy = (float) emotionCounts.getOrDefault("happy", 0) / total;
        float ratioSad = (float) emotionCounts.getOrDefault("sad", 0) / total;
        float ratioAngry = (float) emotionCounts.getOrDefault("angry", 0) / total;
        float ratioSurprise = (float) emotionCounts.getOrDefault("surprise", 0) / total;
        float ratioFear = (float) emotionCounts.getOrDefault("fear", 0) / total;
        float ratioDisgust = (float) emotionCounts.getOrDefault("disgust", 0) / total;
        float ratioNeutral = (float) emotionCounts.getOrDefault("neutral", 0) / total;

        float positiveRatio = ratioHappy + ratioSurprise;
        float negativeRatio = ratioSad + ratioAngry + ratioFear + ratioDisgust;
        float engagementScore = (float) engagementCalculator.calculate(positiveRatio, negativeRatio, 0);

        EmotionAggregation agg = aggregationRepository
                .findByStudentIdAndDateAndPeriodId(studentId, date, periodId)
                .orElse(new EmotionAggregation());
        agg.setStudentId(studentId);
        agg.setClassId(faceRecords.get(0).getClassImage().getClazz().getId());
        agg.setDate(date);
        agg.setPeriodId(periodId);
        agg.setSampleCount(total);
        agg.setRatioHappy(ratioHappy);
        agg.setRatioSad(ratioSad);
        agg.setRatioAngry(ratioAngry);
        agg.setRatioSurprise(ratioSurprise);
        agg.setRatioFear(ratioFear);
        agg.setRatioDisgust(ratioDisgust);
        agg.setRatioNeutral(ratioNeutral);
        agg.setPositiveRatio(positiveRatio);
        agg.setNegativeRatio(negativeRatio);
        agg.setEngagementScore(engagementScore);
        aggregationRepository.save(agg);

        eventPublisher.publishEvent(new AggregationUpdatedEvent(studentId, date, periodId));
        log.debug("Aggregated student={} date={} period={} samples={}", studentId, date, periodId, total);
    }
}
