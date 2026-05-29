package com.school.emotion.service;

import com.school.emotion.model.enums.ImageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PipelineProgressService {

    private static final Logger log = LoggerFactory.getLogger(PipelineProgressService.class);

    private final SimpMessagingTemplate messagingTemplate;

    // In-memory pipeline status counters
    private final Map<String, AtomicInteger> statusCounters = new ConcurrentHashMap<>();

    public PipelineProgressService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        resetCounters();
    }

    public synchronized void resetCounters() {
        statusCounters.put("PENDING", new AtomicInteger(0));
        statusCounters.put("PROCESSING", new AtomicInteger(0));
        statusCounters.put("COMPLETED", new AtomicInteger(0));
        statusCounters.put("FAILED", new AtomicInteger(0));
    }

    public void onStatusChange(Long imageId, ImageStatus oldStatus, ImageStatus newStatus, String fileName, String errorMessage) {
        // Update counters
        if (oldStatus != null) {
            AtomicInteger oldCounter = statusCounters.get(oldStatus.name());
            if (oldCounter != null) oldCounter.decrementAndGet();
        }
        AtomicInteger newCounter = statusCounters.get(newStatus.name());
        if (newCounter != null) newCounter.incrementAndGet();

        // Build progress event
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("imageId", imageId);
        event.put("fileName", fileName);
        event.put("oldStatus", oldStatus != null ? oldStatus.name() : null);
        event.put("newStatus", newStatus.name());
        event.put("errorMessage", errorMessage);
        event.put("timestamp", java.time.Instant.now().toString());
        event.put("counts", Map.of(
                "PENDING", statusCounters.get("PENDING").get(),
                "PROCESSING", statusCounters.get("PROCESSING").get(),
                "COMPLETED", statusCounters.get("COMPLETED").get(),
                "FAILED", statusCounters.get("FAILED").get()
        ));

        // Push via STOMP
        try {
            messagingTemplate.convertAndSend("/topic/pipeline-progress", event);
        } catch (Exception e) {
            log.warn("Failed to push pipeline progress: {}", e.getMessage());
        }
    }

    public Map<String, Integer> getStatusCounts() {
        return Map.of(
                "PENDING", statusCounters.get("PENDING").get(),
                "PROCESSING", statusCounters.get("PROCESSING").get(),
                "COMPLETED", statusCounters.get("COMPLETED").get(),
                "FAILED", statusCounters.get("FAILED").get()
        );
    }
}
