package com.school.emotion.service;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ImageImportService {

    private static final Logger log = LoggerFactory.getLogger(ImageImportService.class);
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("IMG_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})\\d{2}_.+\\.jpg$");

    private static final Map<String, String> DIR_TO_PERIOD = new HashMap<>();
    static {
        DIR_TO_PERIOD.put("早读-到校", "arrival");
        DIR_TO_PERIOD.put("第1节", "period_1");
        DIR_TO_PERIOD.put("第2节", "period_2");
        DIR_TO_PERIOD.put("第3节", "period_3");
        DIR_TO_PERIOD.put("第4节", "period_4");
        DIR_TO_PERIOD.put("第5节", "period_5");
        DIR_TO_PERIOD.put("第6节", "period_6");
        DIR_TO_PERIOD.put("第7节", "period_7");
        DIR_TO_PERIOD.put("第8节", "period_8");
        DIR_TO_PERIOD.put("课间操", "recess");
        DIR_TO_PERIOD.put("午餐-午休", "lunch");
        DIR_TO_PERIOD.put("课外活动-放学", "afterclass");
    }

    private final ClassImageRepository classImageRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public ImageImportService(ClassImageRepository classImageRepository,
                               RedisTemplate<String, String> redisTemplate) {
        this.classImageRepository = classImageRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public ImportReport importDateDir(Path dateDir) {
        if (!Files.isDirectory(dateDir)) {
            return new ImportReport(0, 0, 0, "Directory not found: " + dateDir);
        }
        int total = 0, imported = 0, failed = 0;
        try (Stream<Path> paths = Files.walk(dateDir, 2)) {
            List<Path> images = paths.filter(p -> p.toString().endsWith(".jpg")).toList();
            total = images.size();
            for (Path imgPath : images) {
                try {
                    String dirName = imgPath.getParent().getFileName().toString();
                    String periodKey = DIR_TO_PERIOD.getOrDefault(dirName, "other");
                    String filename = imgPath.getFileName().toString();
                    var matcher = FILENAME_PATTERN.matcher(filename);
                    if (!matcher.matches()) { failed++; continue; }
                    int y = Integer.parseInt(matcher.group(1)), m = Integer.parseInt(matcher.group(2)), d = Integer.parseInt(matcher.group(3));
                    int h = Integer.parseInt(matcher.group(4)), min = Integer.parseInt(matcher.group(5));
                    OffsetDateTime captureTime = OffsetDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, min, 0), ZoneOffset.ofHours(8));
                    ClassImage ci = new ClassImage();
                    ci.setImageUrl(imgPath.toAbsolutePath().toString());
                    ci.setCaptureTime(captureTime);
                    ci.setPeriodLabel(periodKey);
                    ci.setStatus(ImageStatus.PENDING);
                    ci.setSource("historical_import");
                    ci = classImageRepository.save(ci);
                    redisTemplate.opsForStream().add("image:ingest", Map.of("imageId", ci.getId().toString()));
                    imported++;
                } catch (Exception e) {
                    log.error("Import failed: {}", imgPath, e);
                    failed++;
                }
            }
        } catch (IOException e) {
            return new ImportReport(0, 0, 0, "IO error: " + e.getMessage());
        }
        return new ImportReport(total, imported, failed, null);
    }

    public record ImportReport(int total, int imported, int failed, String error) {}
}
