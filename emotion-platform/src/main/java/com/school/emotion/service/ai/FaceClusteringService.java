package com.school.emotion.service.ai;

import com.school.emotion.model.entity.FaceCluster;
import com.school.emotion.repository.FaceClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Service
public class FaceClusteringService {

    private static final Logger log = LoggerFactory.getLogger(FaceClusteringService.class);

    private final Queue<UnmatchedFace> pendingQueue = new ConcurrentLinkedQueue<>();
    private final FaceClusterRepository clusterRepository;

    public FaceClusteringService(FaceClusterRepository clusterRepository) {
        this.clusterRepository = clusterRepository;
    }

    public void offer(String faceToken, Long classId, OffsetDateTime captureTime) {
        pendingQueue.offer(new UnmatchedFace(faceToken, classId, captureTime));
    }

    @Scheduled(fixedRate = 30000)
    public void processPendingClusters() {
        if (pendingQueue.isEmpty()) return;

        List<UnmatchedFace> batch = new ArrayList<>();
        while (!pendingQueue.isEmpty() && batch.size() < 100) {
            batch.add(pendingQueue.poll());
        }

        Map<Long, List<UnmatchedFace>> byClass = batch.stream()
                .collect(Collectors.groupingBy(f -> f.classId));

        for (var entry : byClass.entrySet()) {
            processClassBatch(entry.getValue(), entry.getKey());
        }
    }

    private void processClassBatch(List<UnmatchedFace> faces, Long classId) {
        Map<String, List<UnmatchedFace>> groups = new HashMap<>();
        for (var face : faces) {
            String prefix = extractPrefix(face.token);
            groups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(face);
        }

        for (var entry : groups.entrySet()) {
            String prefix = entry.getKey();
            List<UnmatchedFace> group = entry.getValue();

            var existing = clusterRepository
                    .findByClassIdAndStatusOrderBySampleCountDesc(classId, "pending");

            Optional<FaceCluster> matched = existing.stream()
                    .filter(c -> c.getClusterKey().equals("prefix_" + prefix))
                    .findFirst();

            if (matched.isPresent()) {
                FaceCluster cluster = matched.get();
                updateCluster(cluster, group);
            } else {
                createCluster(classId, prefix, group);
            }
        }
    }

    private void updateCluster(FaceCluster cluster, List<UnmatchedFace> newFaces) {
        List<String> existingTokens = parseTokenList(cluster.getFaceTokens());
        for (var face : newFaces) {
            if (!existingTokens.contains(face.token)) {
                existingTokens.add(face.token);
            }
        }
        cluster.setFaceTokens(toJsonArray(existingTokens));
        cluster.setSampleCount(existingTokens.size());
        cluster.setLastSeenAt(OffsetDateTime.now());
        clusterRepository.save(cluster);
    }

    private FaceCluster createCluster(Long classId, String prefix, List<UnmatchedFace> faces) {
        FaceCluster cluster = new FaceCluster();
        cluster.setClassId(classId);
        cluster.setClusterKey("prefix_" + prefix);
        List<String> tokens = faces.stream().map(f -> f.token).toList();
        cluster.setFaceTokens(toJsonArray(tokens));
        cluster.setSampleCount(tokens.size());
        cluster.setFirstSeenAt(OffsetDateTime.now());
        cluster.setLastSeenAt(OffsetDateTime.now());
        cluster.setStatus("pending");
        return clusterRepository.save(cluster);
    }

    private String extractPrefix(String faceToken) {
        return faceToken.length() >= 8 ? faceToken.substring(0, 8) : faceToken;
    }

    private List<String> parseTokenList(String json) {
        return List.of(json.replaceAll("[\\[\\]\"]", "").split(","));
    }

    private String toJsonArray(List<String> tokens) {
        return "[\"" + String.join("\",\"", tokens) + "\"]";
    }

    public record UnmatchedFace(String token, Long classId, OffsetDateTime captureTime) {}
}
