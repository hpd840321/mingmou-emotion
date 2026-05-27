package com.school.emotion.service;

import com.school.emotion.model.entity.*;
import com.school.emotion.repository.EmotionAggregationRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.service.analysis.EngagementCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.school.emotion.service.analysis.EngagementCalculator;
import org.junit.jupiter.api.BeforeEach;

@ExtendWith(MockitoExtension.class)
class EmotionAggregationServiceTest {

    @Mock private EmotionAggregationRepository aggregationRepository;
    @Mock private EmotionRecordRepository emotionRecordRepository;
    @Mock private FaceRecordRepository faceRecordRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private EmotionAggregationService service;
    private final EngagementCalculator calculator = new EngagementCalculator();

    @BeforeEach
    void setUp() {
        service = new EmotionAggregationService(aggregationRepository, emotionRecordRepository,
                faceRecordRepository, calculator, eventPublisher);
    }

    private FaceRecord createFaceRecord(Long id, Long studentId) {
        SchoolClass clazz = new SchoolClass();
        clazz.setId(1L);
        ClassImage image = new ClassImage();
        image.setId(1L);
        image.setClazz(clazz);
        FaceRecord fr = new FaceRecord();
        fr.setId(id);
        fr.setClassImage(image);
        return fr;
    }

    @Test
    void aggregate_shouldCalculateRatios() {
        FaceRecord fr1 = createFaceRecord(1L, 1L);
        FaceRecord fr2 = createFaceRecord(2L, 1L);
        EmotionRecord er1 = new EmotionRecord();
        er1.setDominantEmotion("happy");
        er1.setDominantConfidence(0.9f);
        EmotionRecord er2 = new EmotionRecord();
        er2.setDominantEmotion("sad");
        er2.setDominantConfidence(0.7f);

        when(faceRecordRepository.findByStudentId(1L)).thenReturn(List.of(fr1, fr2));
        when(emotionRecordRepository.findByFaceRecordId(1L)).thenReturn(er1);
        when(emotionRecordRepository.findByFaceRecordId(2L)).thenReturn(er2);
        when(aggregationRepository.findByStudentIdAndDateAndPeriodId(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.aggregate(1L, LocalDate.of(2026, 5, 27), 1L);

        ArgumentCaptor<EmotionAggregation> captor = ArgumentCaptor.forClass(EmotionAggregation.class);
        verify(aggregationRepository).save(captor.capture());

        EmotionAggregation saved = captor.getValue();
        assertEquals(2, saved.getSampleCount());
        assertEquals(0.5f, saved.getRatioHappy(), 0.01);
        assertEquals(0.5f, saved.getRatioSad(), 0.01);
    }

    @Test
    void aggregate_shouldSkipWhenNoRecords() {
        when(faceRecordRepository.findByStudentId(99L)).thenReturn(List.of());
        service.aggregate(99L, LocalDate.now(), 1L);
        verify(aggregationRepository, never()).save(any());
    }

    @Test
    void aggregate_shouldPublishEvent() {
        FaceRecord fr = createFaceRecord(1L, 1L);
        EmotionRecord er = new EmotionRecord();
        er.setDominantEmotion("happy");
        er.setDominantConfidence(0.95f);

        when(faceRecordRepository.findByStudentId(1L)).thenReturn(List.of(fr));
        when(emotionRecordRepository.findByFaceRecordId(1L)).thenReturn(er);
        when(aggregationRepository.findByStudentIdAndDateAndPeriodId(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.aggregate(1L, LocalDate.now(), 1L);
        verify(eventPublisher).publishEvent(any(com.school.emotion.event.AggregationUpdatedEvent.class));
    }

    @Test
    void aggregate_shouldUpdateExistingInsteadOfCreating() {
        FaceRecord fr = createFaceRecord(1L, 1L);
        EmotionRecord er = new EmotionRecord();
        er.setDominantEmotion("neutral");
        er.setDominantConfidence(0.8f);

        EmotionAggregation existing = new EmotionAggregation();
        existing.setSampleCount(5);
        existing.setRatioHappy(0.8f);

        when(faceRecordRepository.findByStudentId(1L)).thenReturn(List.of(fr));
        when(emotionRecordRepository.findByFaceRecordId(1L)).thenReturn(er);
        when(aggregationRepository.findByStudentIdAndDateAndPeriodId(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        service.aggregate(1L, LocalDate.now(), 1L);

        ArgumentCaptor<EmotionAggregation> captor = ArgumentCaptor.forClass(EmotionAggregation.class);
        verify(aggregationRepository).save(captor.capture());

        EmotionAggregation saved = captor.getValue();
        assertEquals(1, saved.getSampleCount());
        assertEquals(1.0f, saved.getRatioNeutral(), 0.01);
    }
}
