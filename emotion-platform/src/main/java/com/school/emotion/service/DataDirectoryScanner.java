package com.school.emotion.service;

import com.school.emotion.model.entity.*;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class DataDirectoryScanner {

    private static final Logger log = LoggerFactory.getLogger(DataDirectoryScanner.class);

    private static final Pattern FILENAME_PATTERN =
            Pattern.compile(".*(\\d{4})(\\d{2})(\\d{2})_?(\\d{2})(\\d{2})(\\d{2}).*\\.jpg$");

    private static final Pattern DATE_DIR_PATTERN =
            Pattern.compile("(\\d{4})-(\\d{2})-?(\\d{2})");

    private static final Map<String, String> PERIOD_MAP = new HashMap<>();
    static {
        PERIOD_MAP.put("早读-到校", "arrival");
        PERIOD_MAP.put("第1节", "period_1");  PERIOD_MAP.put("第2节", "period_2");
        PERIOD_MAP.put("第3节", "period_3");  PERIOD_MAP.put("第4节", "period_4");
        PERIOD_MAP.put("第5节", "period_5");  PERIOD_MAP.put("第6节", "period_6");
        PERIOD_MAP.put("第7节", "period_7");  PERIOD_MAP.put("第8节", "period_8");
        PERIOD_MAP.put("课间操", "recess");
        PERIOD_MAP.put("午餐-午休", "lunch");
        PERIOD_MAP.put("课外活动-放学", "afterclass");
    }

    private final GradeRepository gradeRepository;
    private final SchoolClassRepository classRepository;
    private final StudentRepository studentRepository;
    private final ClassImageRepository classImageRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final Path dataRoot;
    private volatile boolean scanning = false;

    public DataDirectoryScanner(
            GradeRepository gradeRepository,
            SchoolClassRepository classRepository,
            StudentRepository studentRepository,
            ClassImageRepository classImageRepository,
            RedisTemplate<String, String> redisTemplate,
            @Value("${app.data.dir:./data}") String dataRoot) {
        this.gradeRepository = gradeRepository;
        this.classRepository = classRepository;
        this.studentRepository = studentRepository;
        this.classImageRepository = classImageRepository;
        this.redisTemplate = redisTemplate;
        this.dataRoot = Path.of(dataRoot);
    }

    @Scheduled(fixedDelayString = "${app.scan.interval-ms:300000}", initialDelay = 60000)
    public void scheduledSync() {
        if (scanning) {
            log.debug("Scan already in progress, skipping scheduled sync");
            return;
        }
        scanning = true;
        try {
            ScanReport report = scanAll();
            if (report.imported() > 0) {
                log.info("Scheduled sync: imported {} new images (total={})", report.imported(), report.total());
            }
        } catch (Exception e) {
            log.error("Scheduled sync failed: {}", e.getMessage());
        } finally {
            scanning = false;
        }
    }

    /**
     * 全量扫描 data/ 目录并导入所有图片。
     * 目录结构: data/{school}/{class}/{YYYY-MMDD}/{period}/*.jpg
     */
    public ScanReport scanAll() {
        if (!Files.isDirectory(dataRoot)) {
            return new ScanReport(0, 0, "Data directory not found: " + dataRoot);
        }
        int total = 0, imported = 0;
        List<String> errors = new ArrayList<>();

        try (Stream<Path> schools = Files.list(dataRoot)) {
            List<Path> schoolDirs = schools.filter(Files::isDirectory).toList();

            for (Path schoolDir : schoolDirs) {
                String schoolName = schoolDir.getFileName().toString();
                log.info("Scanning school: {}", schoolName);

                // 1. Create/get grade record for this school
                Grade grade = getOrCreateGrade(schoolName);

                try (Stream<Path> classes = Files.list(schoolDir)) {
                    List<Path> classDirs = classes.filter(Files::isDirectory).toList();

                    for (Path classDir : classDirs) {
                        String className = classDir.getFileName().toString();

                        // 2. Create/get class record
                        SchoolClass schoolClass = getOrCreateClass(grade, className);

                        // 3. Process date directories
                        ScanResult result = processClassDir(classDir, schoolClass);
                        total += result.total;
                        imported += result.imported;
                        errors.addAll(result.errors);
                    }
                }
            }
        } catch (IOException e) {
            return new ScanReport(total, imported, "IO error: " + e.getMessage());
        }

        return new ScanReport(total, imported, errors.isEmpty() ? null : String.join("; ", errors));
    }

    @Transactional
    protected ScanResult processClassDir(Path classDir, SchoolClass schoolClass) {
        int total = 0, imported = 0;
        List<String> errors = new ArrayList<>();

        try (Stream<Path> dateDirs = Files.list(classDir)) {
            List<Path> dateDirList = dateDirs.filter(Files::isDirectory).toList();

            for (Path dateDir : dateDirList) {
                String dirName = dateDir.getFileName().toString();
                var matcher = DATE_DIR_PATTERN.matcher(dirName);

                if (!matcher.matches()) {
                    log.warn("Skipping non-date directory: {}", dirName);
                    continue;
                }

                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                LocalDate date = LocalDate.of(year, month, day);

                try (Stream<Path> periodDirs = Files.list(dateDir)) {
                    List<Path> periodDirList = periodDirs.filter(Files::isDirectory).toList();

                    for (Path periodDir : periodDirList) {
                        String periodName = periodDir.getFileName().toString();
                        String periodKey = PERIOD_MAP.getOrDefault(periodName, "other");

                        try (Stream<Path> images = Files.list(periodDir)) {
                            List<Path> imageFiles = images.filter(p -> p.toString().endsWith(".jpg")).toList();

                            for (Path img : imageFiles) {
                                total++;
                                try {
                                    if (importImage(img, schoolClass, date, periodKey)) {
                                        imported++;
                                    }
                                } catch (Exception e) {
                                    errors.add(img.getFileName() + ": " + e.getMessage());
                                    log.error("Import failed: {}", img, e);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            errors.add("IO error in " + classDir + ": " + e.getMessage());
        }

        return new ScanResult(total, imported, errors);
    }

    private boolean importImage(Path imgPath, SchoolClass schoolClass, LocalDate date, String periodKey) {
        String filename = imgPath.getFileName().toString();
        String imageUrl = imgPath.toAbsolutePath().toString();

        if (classImageRepository.existsByImageUrl(imageUrl)) {
            return false;
        }

        var matcher = FILENAME_PATTERN.matcher(filename);

        OffsetDateTime captureTime;
        if (matcher.matches()) {
            int h = Integer.parseInt(matcher.group(4));
            int min = Integer.parseInt(matcher.group(5));
            captureTime = OffsetDateTime.of(date, LocalTime.of(h, min, 0), ZoneOffset.ofHours(8));
        } else {
            // Fallback: use file last modified time
            try {
                captureTime = OffsetDateTime.of(date, LocalTime.NOON, ZoneOffset.ofHours(8));
            } catch (Exception e) {
                captureTime = OffsetDateTime.now();
            }
        }

        ClassImage ci = new ClassImage();
        ci.setClazz(schoolClass);
        ci.setImageUrl(imgPath.toAbsolutePath().toString());
        ci.setCaptureTime(captureTime);
        ci.setPeriodLabel(periodKey);
        ci.setStatus(ImageStatus.PENDING);
        ci.setSource("auto_scan");
        ci = classImageRepository.save(ci);

        // Push to Redis Stream for processing
        redisTemplate.opsForStream().add("image:ingest", Map.of("imageId", ci.getId().toString()));

        log.debug("Imported image: {} → class={}, period={}, time={}",
                filename, schoolClass.getName(), periodKey, captureTime);
        return true;
    }

    private Grade getOrCreateGrade(String name) {
        // Use sort_order as a simple heuristic: existing grades get higher order
        var grades = gradeRepository.findAll();
        for (Grade g : grades) {
            if (g.getName().equals(name)) return g;
        }
        Grade grade = new Grade();
        grade.setName(name);
        grade.setSortOrder(grades.size() + 1);
        return gradeRepository.save(grade);
    }

    private SchoolClass getOrCreateClass(Grade grade, String className) {
        var classes = classRepository.findByGrade_Id(grade.getId());
        for (SchoolClass c : classes) {
            if (c.getName().equals(className)) return c;
        }
        SchoolClass sc = new SchoolClass();
        sc.setGrade(grade);
        sc.setName(className);
        sc.setSortOrder(classes.size() + 1);
        return classRepository.save(sc);
    }

    public record ScanReport(int total, int imported, String error) {}
    private record ScanResult(int total, int imported, List<String> errors) {}
}
