package com.school.emotion.service;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Service;

@Service
public class ImageIngestConsumer implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImageIngestConsumer.class);

    private static final String STREAM_KEY = "image:ingest";
    private static final String CONSUMER_GROUP = "image-processors";

    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final ClassImageRepository classImageRepository;
    private final FaceProcessingPipeline pipeline;
    private final StringRedisTemplate redisTemplate;

    public ImageIngestConsumer(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            ClassImageRepository classImageRepository,
            FaceProcessingPipeline pipeline,
            StringRedisTemplate redisTemplate) {
        this.container = container;
        this.classImageRepository = classImageRepository;
        this.pipeline = pipeline;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            // Create consumer group (ignore error if already exists)
            try {
                redisTemplate.opsForStream().createGroup(STREAM_KEY, CONSUMER_GROUP);
                log.info("Created consumer group '{}' for stream '{}'", CONSUMER_GROUP, STREAM_KEY);
            } catch (Exception e) {
                log.debug("Consumer group already exists: {}", e.getMessage());
            }

            container.receive(
                    Consumer.from(CONSUMER_GROUP, "processor-1"),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                    msg -> {
                        String imageId = msg.getValue().get("imageId");
                        if (imageId == null) return;
                        log.debug("Received image:ingest message: {}", imageId);
                        classImageRepository.findById(Long.parseLong(imageId))
                                .filter(ci -> ci.getStatus() == ImageStatus.PENDING)
                                .ifPresent(ci -> {
                                    try {
                                        pipeline.processSingleImage(ci);
                                    } catch (Exception e) {
                                        log.error("Failed to process image {} from stream: {}", imageId, e.getMessage());
                                    }
                                });
                    });
            container.start();
            log.info("ImageIngestConsumer subscribed to image:ingest stream");
        } catch (Exception e) {
            log.error("Failed to register Redis stream consumer: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        try {
            container.stop();
        } catch (Exception e) {
        }
    }
}
