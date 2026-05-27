package com.school.emotion.listener;

import com.school.emotion.event.ImageProcessedEvent;
import com.school.emotion.service.EmotionAggregationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AggregationEventListener {

    private static final Logger log = LoggerFactory.getLogger(AggregationEventListener.class);
    private final EmotionAggregationService aggregationService;

    public AggregationEventListener(EmotionAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @EventListener
    public void onImageProcessed(ImageProcessedEvent event) {
        log.debug("ImageProcessedEvent: studentId={}", event.getStudentId());
        aggregationService.aggregate(event.getStudentId(), event.getDate(), event.getPeriodId());
    }
}
