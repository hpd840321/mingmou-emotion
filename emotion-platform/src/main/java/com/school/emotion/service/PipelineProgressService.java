package com.school.emotion.service;

import com.school.emotion.model.enums.ImageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PipelineProgressService {

    private static final Logger log = LoggerFactory.getLogger(PipelineProgressService.class);

    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, AtomicInteger> statusCounters = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    // Speed tracking
    private final AtomicLong processedCount = new AtomicLong(0);
    private volatile long startTime = 0;

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

    public void markRunning() {
        running.set(true);
        stopRequested.set(false);
        processedCount.set(0);
        startTime = System.currentTimeMillis();
        broadcastState();
    }

    public void markStopped() {
        running.set(false);
        stopRequested.set(false);
        broadcastState();
    }

    public void requestStop() {
        stopRequested.set(true);
        broadcastState();
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void onStatusChange(Long imageId, ImageStatus oldStatus, ImageStatus newStatus, String fileName, String errorMessage) {
        if (oldStatus != null) {
            AtomicInteger oldCounter = statusCounters.get(oldStatus.name());
            if (oldCounter != null) oldCounter.decrementAndGet();
        }
        AtomicInteger newCounter = statusCounters.get(newStatus.name());
        if (newCounter != null) {
            newCounter.incrementAndGet();
            if (newStatus == ImageStatus.COMPLETED || newStatus == ImageStatus.FAILED) {
                processedCount.incrementAndGet();
            }
        }

        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("imageId", imageId);
        event.put("fileName", fileName);
        event.put("oldStatus", oldStatus != null ? oldStatus.name() : null);
        event.put("newStatus", newStatus.name());
        event.put("errorMessage", errorMessage);
        event.put("timestamp", java.time.Instant.now().toString());
        event.put("running", running.get());
        event.put("eta", calculateEta());
        event.put("speed", getSpeed());
        event.put("counts", Map.of(
                "PENDING", statusCounters.get("PENDING").get(),
                "PROCESSING", statusCounters.get("PROCESSING").get(),
                "COMPLETED", statusCounters.get("COMPLETED").get(),
                "FAILED", statusCounters.get("FAILED").get()
        ));

        try {
            messagingTemplate.convertAndSend("/topic/pipeline-progress", event);
        } catch (Exception e) {
            log.warn("Failed to push pipeline progress: {}", e.getMessage());
        }
    }

    private void broadcastState() {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("running", running.get());
        event.put("stopRequested", stopRequested.get());
        event.put("eta", calculateEta());
        event.put("speed", getSpeed());
        event.put("timestamp", java.time.Instant.now().toString());
        event.put("counts", Map.of(
                "PENDING", statusCounters.get("PENDING").get(),
                "PROCESSING", statusCounters.get("PROCESSING").get(),
                "COMPLETED", statusCounters.get("COMPLETED").get(),
                "FAILED", statusCounters.get("FAILED").get()
        ));
        try {
            messagingTemplate.convertAndSend("/topic/pipeline-progress", event);
        } catch (Exception e) {
            log.warn("Failed to broadcast state: {}", e.getMessage());
        }
    }

    public String calculateEta() {
        if (!running.get() || processedCount.get() == 0) return null;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed < 1000) return null;
        double speed = (double) processedCount.get() / (elapsed / 1000.0);
        int remaining = statusCounters.get("PENDING").get() + statusCounters.get("PROCESSING").get();
        if (speed <= 0 || remaining <= 0) return null;
        long etaSeconds = (long) (remaining / speed);
        if (etaSeconds < 60) return etaSeconds + "秒";
        if (etaSeconds < 3600) return (etaSeconds / 60) + "分" + (etaSeconds % 60) + "秒";
        return (etaSeconds / 3600) + "时" + ((etaSeconds % 3600) / 60) + "分";
    }

    public double getSpeed() {
        if (!running.get() || processedCount.get() == 0) return 0;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed < 1000) return 0;
        return Math.round((double) processedCount.get() / (elapsed / 1000.0) * 10.0) / 10.0;
    }

    public Map<String, Object> getStatus() {
        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
        map.put("running", running.get());
        map.put("stopRequested", stopRequested.get());
        map.put("speed", getSpeed());
        map.put("eta", calculateEta());  // may be null, HashMap allows null
        map.put("processedCount", processedCount.get());
        return map;
    }
}
