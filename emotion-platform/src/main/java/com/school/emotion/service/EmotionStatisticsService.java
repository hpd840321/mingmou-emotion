package com.school.emotion.service;

import com.school.emotion.model.entity.EmotionAggregation;
import com.school.emotion.model.entity.EmotionRecord;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.repository.EmotionAggregationRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.service.analysis.EngagementCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmotionStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(EmotionStatisticsService.class);

    private final EmotionRecordRepository emotionRecordRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionAggregationRepository aggregationRepository;
    private final EngagementCalculator engagementCalculator;

    public EmotionStatisticsService(
            EmotionRecordRepository emotionRecordRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionAggregationRepository aggregationRepository,
            EngagementCalculator engagementCalculator) {
        this.emotionRecordRepository = emotionRecordRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.aggregationRepository = aggregationRepository;
        this.engagementCalculator = engagementCalculator;
    }

    @Scheduled(fixedDelay = 600000)
    public void scheduledAggregation() {
        log.info("Running scheduled emotion aggregation");
        aggregateByClassAndDate();
    }

    @Transactional
    public AggregationReport aggregateByClassAndDate() {
        List<EmotionRecord> allRecords = emotionRecordRepository.findAll();
        if (allRecords.isEmpty()) {
            return new AggregationReport(0, 0);
        }

        Map<AggKey, AggBucket> buckets = new HashMap<>();

        for (EmotionRecord er : allRecords) {
            FaceRecord fr = er.getFaceRecord();
            if (fr == null || fr.getClassImage() == null) continue;

            Long classId = fr.getClassImage().getClazz().getId();
            LocalDate date = fr.getClassImage().getCaptureTime().toLocalDate();
            AggKey key = new AggKey(classId, date, 0L);

            buckets.computeIfAbsent(key, k -> new AggBucket()).add(er);
        }

        int updated = 0;
        for (Map.Entry<AggKey, AggBucket> entry : buckets.entrySet()) {
            AggKey key = entry.getKey();
            AggBucket bucket = entry.getValue();

            float total = bucket.total;
            float positiveRatio = (bucket.happy + bucket.surprise) / total;
            float negativeRatio = (bucket.sad + bucket.angry + bucket.fear + bucket.disgust) / total;
            float engagement = (float) engagementCalculator.calculate(positiveRatio, negativeRatio, 0);

            EmotionAggregation agg = aggregationRepository
                    .findByClassIdAndDate(key.classId, key.date)
                    .stream().findFirst().orElse(new EmotionAggregation());

            agg.setStudentId(0L);
            agg.setClassId(key.classId);
            agg.setDate(key.date);
            agg.setSampleCount((int) total);
            agg.setRatioHappy(bucket.happy / total);
            agg.setRatioSad(bucket.sad / total);
            agg.setRatioAngry(bucket.angry / total);
            agg.setRatioSurprise(bucket.surprise / total);
            agg.setRatioFear(bucket.fear / total);
            agg.setRatioDisgust(bucket.disgust / total);
            agg.setRatioNeutral(bucket.neutral / total);
            agg.setPositiveRatio(positiveRatio);
            agg.setNegativeRatio(negativeRatio);
            agg.setEngagementScore(engagement);
            aggregationRepository.save(agg);
            updated++;
        }

        log.info("Aggregation: {} records -> {} buckets", allRecords.size(), updated);
        return new AggregationReport(allRecords.size(), updated);
    }

    record AggKey(Long classId, LocalDate date, Long periodId) {}
    record AggReport(int totalRecords, int bucketsCreated) {}

    static class AggBucket {
        int total = 0, happy = 0, sad = 0, angry = 0;
        int surprise = 0, fear = 0, disgust = 0, neutral = 0;

        void add(EmotionRecord er) {
            total++;
            String de = er.getDominantEmotion();
            if ("happy".equals(de) || "开心".equals(de)) happy++;
            else if ("sad".equals(de) || "伤心".equals(de)) sad++;
            else if ("angry".equals(de) || "愤怒".equals(de)) angry++;
            else if ("surprise".equals(de) || "惊讶".equals(de)) surprise++;
            else if ("fear".equals(de) || "恐惧".equals(de)) fear++;
            else if ("disgust".equals(de) || "厌恶".equals(de)) disgust++;
            else neutral++;
        }
    }

    public record AggregationReport(int totalRecords, int bucketsCreated) {}
}
