package com.school.emotion.service;

import com.school.emotion.config.RedisStreamConfig;
import com.school.emotion.model.dto.ImageIngestResponse;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.SchoolClassRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class ImageIngestionService {

    private final ClassImageRepository classImageRepository;
    private final SchoolClassRepository classRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final Path storageDir;

    public ImageIngestionService(
            ClassImageRepository classImageRepository,
            SchoolClassRepository classRepository,
            RedisTemplate<String, String> redisTemplate,
            @Value("${app.image.storage-dir:./images}") String storageDir) {
        this.classImageRepository = classImageRepository;
        this.classRepository = classRepository;
        this.redisTemplate = redisTemplate;
        this.storageDir = Path.of(storageDir);
        try { Files.createDirectories(this.storageDir); } catch (IOException e) {
            throw new RuntimeException("Cannot create storage dir", e);
        }
    }

    @Transactional
    public ImageIngestResponse ingest(Long classId, byte[] imageBytes, String originalFilename,
                                       String captureTime, String periodLabel) {
        String filename = UUID.randomUUID() + "_" + (originalFilename != null ? originalFilename : "image.jpg");
        Path targetPath = storageDir.resolve(filename);
        try {
            Files.write(targetPath, imageBytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store image", e);
        }

        ClassImage classImage = new ClassImage();
        classImage.setClazz(classRepository.getReferenceById(classId));
        classImage.setImageUrl(targetPath.toString());
        classImage.setCaptureTime(OffsetDateTime.parse(captureTime));
        classImage.setPeriodLabel(periodLabel);
        classImage.setStatus(ImageStatus.PENDING);
        classImage = classImageRepository.save(classImage);

        redisTemplate.opsForStream().add(
                "image:ingest",
                Map.of("imageId", classImage.getId().toString()));

        return ImageIngestResponse.accepted(classImage.getId(), 0);
    }
}
