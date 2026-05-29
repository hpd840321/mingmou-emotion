package com.school.emotion.service;

import com.school.emotion.model.entity.FaceCluster;
import com.school.emotion.repository.FaceClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FaceClusteringServiceV2 {

    private static final Logger log = LoggerFactory.getLogger(FaceClusteringServiceV2.class);

    private final RestTemplate restTemplate;
    private final FaceClusterRepository clusterRepository;
    private final String qdrantUrl;
    private final float similarityThreshold;
    private final int minClusterSize;

    public FaceClusteringServiceV2(
            RestTemplate restTemplate,
            FaceClusterRepository clusterRepository,
            @Value("${app.clustering.qdrant-url:http://localhost:6333}") String qdrantUrl,
            @Value("${app.clustering.similarity-threshold:0.7}") float similarityThreshold,
            @Value("${app.clustering.min-cluster-size:3}") int minClusterSize) {
        this.restTemplate = restTemplate;
        this.clusterRepository = clusterRepository;
        this.qdrantUrl = qdrantUrl;
        this.similarityThreshold = similarityThreshold;
        this.minClusterSize = minClusterSize;
    }

    @Scheduled(fixedDelay = 3600000)
    public void scheduledClustering() {
        runClustering();
    }

    @SuppressWarnings("unchecked")
    public ClusteringReport runClustering() {
        long start = System.currentTimeMillis();
        log.info("Clustering: threshold={}, minCluster={}", similarityThreshold, minClusterSize);

        List<PointData> allPoints = scrollAllPoints();
        if (allPoints.isEmpty()) {
            log.warn("No points in Qdrant");
            return new ClusteringReport(0, 0, 0, 0);
        }
        log.info("Loaded {} face vectors from Qdrant", allPoints.size());

        int n = allPoints.size();
        List<Set<Integer>> graph = new ArrayList<>(n);
        for (int i = 0; i < n; i++) graph.add(new HashSet<>());

        int comparisons = 0;
        for (int i = 0; i < n; i++) {
            float[] vi = allPoints.get(i).vector;
            for (int j = i + 1; j < n; j++) {
                float[] vj = allPoints.get(j).vector;
                float sim = cosineSimilarity(vi, vj);
                comparisons++;
                if (sim >= similarityThreshold) {
                    graph.get(i).add(j);
                    graph.get(j).add(i);
                }
            }
        }
        log.info("Similarity graph: {} comparisons", comparisons);

        boolean[] visited = new boolean[n];
        List<List<Integer>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!visited[i]) {
                List<Integer> component = new ArrayList<>();
                Queue<Integer> queue = new LinkedList<>();
                queue.add(i);
                visited[i] = true;
                while (!queue.isEmpty()) {
                    int node = queue.poll();
                    component.add(node);
                    for (int neighbor : graph.get(node)) {
                        if (!visited[neighbor]) {
                            visited[neighbor] = true;
                            queue.add(neighbor);
                        }
                    }
                }
                if (component.size() >= minClusterSize) {
                    clusters.add(component);
                }
            }
        }

        int outliers = n - clusters.stream().mapToInt(List::size).sum();
        log.info("Found {} clusters, {} outliers", clusters.size(), outliers);

        int saved = 0;
        for (List<Integer> cluster : clusters) {
            String clusterKey = "qcluster_" + UUID.randomUUID().toString().substring(0, 8);
            List<String> faceIds = cluster.stream()
                    .map(idx -> allPoints.get(idx).id)
                    .collect(Collectors.toList());

            FaceCluster fc = new FaceCluster();
            fc.setClusterKey(clusterKey);
            fc.setClassId(0L);
            fc.setFaceTokens(toJsonArray(faceIds));
            fc.setSampleCount(faceIds.size());
            fc.setFirstSeenAt(OffsetDateTime.now());
            fc.setLastSeenAt(OffsetDateTime.now());
            fc.setStatus("pending");
            clusterRepository.save(fc);
            saved++;
        }

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        log.info("Clustering done: {} faces, {} clusters, {}s", allPoints.size(), saved, elapsed);
        return new ClusteringReport(allPoints.size(), saved, outliers, comparisons);
    }

    @SuppressWarnings("unchecked")
    private List<PointData> scrollAllPoints() {
        List<PointData> result = new ArrayList<>();
        Object nextId = null;
        try {
            while (true) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("limit", 1000);
                body.put("with_vector", true);
                body.put("with_payload", false);
                if (nextId != null) body.put("offset", nextId);

                var response = restTemplate.postForEntity(
                        qdrantUrl + "/collections/face_features/points/scroll",
                        body, Map.class);
                Map<String, Object> respBody = response.getBody();
                if (respBody == null) break;

                Map<String, Object> resultMap = (Map<String, Object>) respBody.get("result");
                if (resultMap == null) break;

                List<Map<String, Object>> points = (List<Map<String, Object>>) resultMap.get("points");
                if (points == null || points.isEmpty()) break;

                for (Map<String, Object> p : points) {
                    PointData pd = new PointData();
                    pd.id = p.get("id") != null ? p.get("id").toString() : "unknown";
                    Object vec = p.get("vector");
                    if (vec instanceof List) {
                        List<Number> vecList = (List<Number>) vec;
                        pd.vector = new float[vecList.size()];
                        for (int i = 0; i < vecList.size(); i++) {
                            pd.vector[i] = vecList.get(i).floatValue();
                        }
                    }
                    result.add(pd);
                }
                if (points.size() < 1000) break;
                nextId = points.get(points.size() - 1).get("id");
            }
        } catch (Exception e) {
            log.error("Qdrant scroll failed: {}", e.getMessage());
        }
        return result;
    }

    private static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom < 1e-10 ? 0 : (float) (dot / denom);
    }

    private static String toJsonArray(List<String> items) {
        return "[\"" + String.join("\",\"", items) + "\"]";
    }

    static class PointData {
        String id;
        float[] vector;
    }

    public record ClusteringReport(int totalFaces, int clusters, int outliers, int comparisons) {}
}
