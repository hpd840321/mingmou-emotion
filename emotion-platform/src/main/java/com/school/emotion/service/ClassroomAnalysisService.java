package com.school.emotion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.emotion.model.dto.ClassroomAnalysisResult;
import com.school.emotion.model.dto.ClassroomAnalysisResult.AiInsightCard;
import com.school.emotion.model.dto.ClassroomAnalysisResult.StudentParticle;
import com.school.emotion.model.dto.ClassroomAnalysisResult.TimelineEntry;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.entity.EmotionRecord;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.model.entity.SchoolClass;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.SchoolClassRepository;
import com.school.emotion.service.EmotionStateMappingService.EmotionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ClassroomAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ClassroomAnalysisService.class);

    private static final double RESONANCE_THRESHOLD = 0.75;

    private final ClassImageRepository classImageRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final EmotionStateMappingService emotionStateMappingService;
    private final ObjectMapper objectMapper;

    public ClassroomAnalysisService(ClassImageRepository classImageRepository,
                                    FaceRecordRepository faceRecordRepository,
                                    EmotionRecordRepository emotionRecordRepository,
                                    SchoolClassRepository schoolClassRepository,
                                    EmotionStateMappingService emotionStateMappingService,
                                    ObjectMapper objectMapper) {
        this.classImageRepository = classImageRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.schoolClassRepository = schoolClassRepository;
        this.emotionStateMappingService = emotionStateMappingService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ClassroomAnalysisResult analyze(Long classId, Long sessionId) {
        SchoolClass schoolClass = schoolClassRepository.findById(classId).orElse(null);
        if (schoolClass == null) {
            ClassroomAnalysisResult empty = new ClassroomAnalysisResult();
            empty.setSessionId("class_" + classId + "_unknown");
            empty.setClassInfo(buildClassInfo(null));
            empty.setTimeline(List.of());
            return empty;
        }

        List<ClassImage> images = classImageRepository.findByClazz_Id(classId);
        images.sort(Comparator.comparing(ClassImage::getCaptureTime));

        List<TimelineEntry> timeline = new ArrayList<>();
        TimelineEntry previous = null;

        for (ClassImage image : images) {
            TimelineEntry entry = buildTimelineEntry(image);
            if (entry != null) {
                entry.setAiInsightCards(generateInsightCards(entry, previous));
                timeline.add(entry);
                previous = entry;
            }
        }

        String sessionIdStr = buildSessionId(classId, images);

        ClassroomAnalysisResult result = new ClassroomAnalysisResult();
        result.setSessionId(sessionIdStr);
        result.setClassInfo(buildClassInfo(schoolClass));
        result.setTimeline(timeline);

        return result;
    }

    private Map<String, Object> buildClassInfo(SchoolClass schoolClass) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (schoolClass != null) {
            info.put("grade", schoolClass.getGrade() != null ? schoolClass.getGrade().getName() : "");
            info.put("class", schoolClass.getName());
        } else {
            info.put("grade", "");
            info.put("class", "");
        }
        info.put("subject", "");
        info.put("teacher", "");
        return info;
    }

    private String buildSessionId(Long classId, List<ClassImage> images) {
        if (images.isEmpty()) {
            return "class_" + classId + "_unknown";
        }
        OffsetDateTime first = images.get(0).getCaptureTime();
        String datePart = first.toLocalDate().toString().replace("-", "");
        String periodPart = images.get(0).getPeriodLabel() != null ? "_" + images.get(0).getPeriodLabel() : "";
        return "class_" + classId + "_" + datePart + periodPart;
    }

    private TimelineEntry buildTimelineEntry(ClassImage image) {
        List<FaceRecord> faces = faceRecordRepository.findByClassImageId(image.getId());
        if (faces.isEmpty()) return null;

        List<StudentParticle> particles = new ArrayList<>();
        Map<EmotionState, Double> stateDistribution = new HashMap<>();
        List<String> dominantStates = new ArrayList<>();

        for (FaceRecord face : faces) {
            EmotionRecord er = emotionRecordRepository.findByFaceRecordId(face.getId());
            if (er == null) continue;

            String stateStr = er.getDominantState();
            if (stateStr == null) {
                Map<String, Float> probs = buildProbabilitiesMap(er);
                stateStr = emotionStateMappingService.mapFromProbabilities(probs).name();
            }

            dominantStates.add(stateStr);
            stateDistribution.merge(parseEmotionState(stateStr), 1.0, Double::sum);

            int[] coords = parseBboxCoords(face.getBbox());

            StudentParticle particle = new StudentParticle();
            if (face.getStudent() != null) {
                particle.setStudentUid("student_" + face.getStudent().getId());
            } else {
                particle.setStudentUid("face_" + face.getId());
            }
            particle.setXCoord(coords[0]);
            particle.setYCoord(coords[1]);
            particle.setDominantState(stateStr);
            particles.add(particle);
        }

        if (particles.isEmpty()) return null;

        String ambientState = dominantStates.stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("UNKNOWN");

        double cohesion = emotionStateMappingService.calcCohesion(stateDistribution);

        TimelineEntry entry = new TimelineEntry();
        entry.setTimestamp(image.getCaptureTime().toEpochSecond());
        entry.setImageUrl(toImageUrl(image.getImageUrl()));
        entry.setClassroomAmbientState(ambientState);
        entry.setEmotionalCohesion(cohesion);
        entry.setStudentParticles(particles);
        entry.setAiInsightCards(new ArrayList<>());

        return entry;
    }

    private String toImageUrl(String absolutePath) {
        if (absolutePath == null) return null;
        int idx = absolutePath.indexOf("/images/");
        if (idx >= 0) return absolutePath.substring(idx);
        return absolutePath;
    }

    private int[] parseBboxCoords(String bboxJson) {
        int[] coords = {0, 0};
        if (bboxJson == null || bboxJson.isBlank()) return coords;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> bbox = objectMapper.readValue(bboxJson, Map.class);
            if (bbox.containsKey("x") && bbox.get("x") instanceof Number) {
                coords[0] = ((Number) bbox.get("x")).intValue();
            } else if (bbox.containsKey("x_coord") && bbox.get("x_coord") instanceof Number) {
                coords[0] = ((Number) bbox.get("x_coord")).intValue();
            }
            if (bbox.containsKey("y") && bbox.get("y") instanceof Number) {
                coords[1] = ((Number) bbox.get("y")).intValue();
            } else if (bbox.containsKey("y_coord") && bbox.get("y_coord") instanceof Number) {
                coords[1] = ((Number) bbox.get("y_coord")).intValue();
            }
        } catch (Exception e) {
            log.warn("Failed to parse bbox JSON: {}", bboxJson);
        }
        return coords;
    }

    private Map<String, Float> buildProbabilitiesMap(EmotionRecord er) {
        Map<String, Float> probs = new LinkedHashMap<>();
        probs.put("neutral", er.getEmotionNeutral());
        probs.put("happy", er.getEmotionHappy());
        probs.put("sad", er.getEmotionSad());
        probs.put("surprise", er.getEmotionSurprise());
        probs.put("fear", er.getEmotionFear());
        probs.put("disgust", er.getEmotionDisgust());
        probs.put("angry", er.getEmotionAngry());
        probs.values().removeIf(Objects::isNull);
        return probs;
    }

    private EmotionState parseEmotionState(String state) {
        try {
            return EmotionState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return EmotionState.UNKNOWN;
        }
    }

    private List<AiInsightCard> generateInsightCards(TimelineEntry entry, TimelineEntry previous) {
        List<AiInsightCard> cards = new ArrayList<>();

        if (entry.getEmotionalCohesion() > RESONANCE_THRESHOLD) {
            AiInsightCard card = new AiInsightCard();
            card.setCardType("RESONANCE_PEAK");
            card.setContent("此时是全班情绪共鸣的高峰，学生对课堂内容表现出高度一致的关注和投入。");
            card.setTriggerTimestamp(entry.getTimestamp());
            cards.add(card);
        }

        if (previous != null && !entry.getClassroomAmbientState().equals(previous.getClassroomAmbientState())) {
            AiInsightCard card = new AiInsightCard();
            card.setCardType("STATE_SHIFT");
            card.setContent("课堂情绪状态由「" + previous.getClassroomAmbientState() + "」转变为「"
                    + entry.getClassroomAmbientState() + "」，教学节奏或内容可能对学生产生了影响。");
            card.setTriggerTimestamp(entry.getTimestamp());
            cards.add(card);
        }

        return cards;
    }
}
