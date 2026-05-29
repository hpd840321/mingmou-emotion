package com.school.emotion.service;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ImageIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImageIngestConsumer.class);

    private static final String STREAM_KEY = "image:ingest";
    private static final String CONSUMER_GROUP = "image-processors";

    private final ClassImageRepository classImageRepository;
    private final FaceProcessingPipeline pipeline;
    private final StringRedisTemplate redisTemplate;

    private volatile boolean running = true;

    public ImageIngestConsumer(
            ClassImageRepository classImageRepository,
            FaceProcessingPipeline pipeline,
            StringRedisTemplate redisTemplate) {
        this.classImageRepository = classImageRepository;
        this.pipeline = pipeline;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, CONSUMER_GROUP);
            log.info("Created consumer group '{}' for stream '{}'", CONSUMER_GROUP, STREAM_KEY);
        } catch (Exception e) {
            log.debug("Consumer group setup: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void pollStream() {
        if (!running) return;
        try {
            @SuppressWarnings("unchecked")
            List<MapRecord<String, String, String>> messages = (List) redisTemplate.opsForStream().read(
                    Consumer.from(CONSUMER_GROUP, "processor-1"),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
            if (messages == null || messages.isEmpty()) return;

            for (MapRecord<String, String, String> msg : messages) {
                String imageId = msg.getValue().get("imageId");
                if (imageId == null) continue;
                classImageRepository.findById(Long.parseLong(imageId))
                        .filter(ci -> ci.getStatus() == ImageStatus.PENDING)
                        .ifPresent(ci -> {
                            try {
                                pipeline.processSingleImage(ci);
                            } catch (Exception e) {
                                log.error("Failed to process image {}: {}", imageId, e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.debug("Redis stream poll failed (normal if stream is empty): {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
    }
}
