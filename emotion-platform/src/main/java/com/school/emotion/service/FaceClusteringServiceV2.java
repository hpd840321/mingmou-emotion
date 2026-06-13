package com.school.emotion.service;

import com.school.emotion.model.entity.FaceCluster;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.model.entity.Student;
import com.school.emotion.repository.FaceClusterRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.SchoolClassRepository;
import com.school.emotion.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FaceClusteringServiceV2 {

    private static final Logger log = LoggerFactory.getLogger(FaceClusteringServiceV2.class);

    private final RestTemplate restTemplate;
    private final FaceClusterRepository clusterRepository;
    private final StudentRepository studentRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final EmotionAggregationService emotionAggregationService;

    private final String qdrantUrl;
    private final String collectionName;
    private final float similarityThreshold;
    private final int minClusterSize;
    private final float minConfidence;
    private final int minFaceWidth;
    private final int maxSeatDist;

    @Autowired(required = false)
    private ExternalEmotionPushService externalPushService;

    public FaceClusteringServiceV2(
            RestTemplate restTemplate,
            FaceClusterRepository clusterRepository,
            StudentRepository studentRepository,
            FaceRecordRepository faceRecordRepository,
            SchoolClassRepository schoolClassRepository,
            EmotionAggregationService emotionAggregationService,
            @Value("${app.clustering.qdrant-url:http://localhost:6333}") String qdrantUrl,
            @Value("${app.clustering.similarity-threshold:0.85}") float similarityThreshold,
            @Value("${app.clustering.min-cluster-size:5}") int minClusterSize,
            @Value("${app.clustering.min-confidence:0.5}") float minConfidence,
            @Value("${app.clustering.min-face-width:50}") int minFaceWidth) {
        this.restTemplate = restTemplate;
        this.clusterRepository = clusterRepository;
        this.studentRepository = studentRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.schoolClassRepository = schoolClassRepository;
        this.emotionAggregationService = emotionAggregationService;
        this.qdrantUrl = qdrantUrl;
        this.collectionName = "face_features_512";  // 512-dim ArcFace collection
        this.similarityThreshold = similarityThreshold;
        this.minClusterSize = minClusterSize;
        this.minConfidence = minConfidence;
        this.minFaceWidth = minFaceWidth;
        this.maxSeatDist = 200;  // max bbox center distance for same student (px)
    }

    @Scheduled(fixedDelay = 3600000)
    public void scheduledClustering() {
        runClustering();
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void scheduledAutoAnnotate() {
        autoAnnotateClusters();
    }

    // ================================================================
    //  聚类主流程
    // ================================================================

    @SuppressWarnings("unchecked")
    public ClusteringReport runClustering() {
        long start = System.currentTimeMillis();
        log.info("Clustering V2: threshold={} minCluster={} minConf={} minFaceW={}",
                similarityThreshold, minClusterSize, minConfidence, minFaceWidth);

        // Phase 1: Load Qdrant points with payload
        List<PointData> rawPoints = scrollAllPoints();
        if (rawPoints.isEmpty()) {
            log.warn("No points in Qdrant");
            return new ClusteringReport(0, 0, 0, 0);
        }
        log.info("Loaded {} raw points from Qdrant", rawPoints.size());

        // Phase 2: Batch-load face_record metadata for filtering
        Map<Long, FaceMeta> faceMeta = loadFaceMeta(rawPoints);
        log.info("Face metadata loaded for {} records", faceMeta.size());

        // Phase 3: Filter by quality
        List<PointData> filtered = new ArrayList<>();
        int filteredByConf = 0, filteredBySize = 0, filteredNoMeta = 0;
        for (PointData pt : rawPoints) {
            long frId;
            try { frId = Long.parseLong(pt.id); } catch (NumberFormatException e) { continue; }
            FaceMeta meta = faceMeta.get(frId);
            if (meta == null) { filteredNoMeta++; continue; }
            if (meta.confidence != null && meta.confidence < minConfidence) { filteredByConf++; continue; }
            if (meta.faceWidth < minFaceWidth) { filteredBySize++; continue; }
            pt.classId = meta.classId;
            filtered.add(pt);
        }
        log.info("After filtering: {} remained (conf<{}: {}, w<{}: {}, noMeta: {})",
                filtered.size(), minConfidence, filteredByConf, minFaceWidth, filteredBySize, filteredNoMeta);

        if (filtered.isEmpty()) {
            log.warn("All points filtered out, aborting clustering");
            return new ClusteringReport(rawPoints.size(), 0, rawPoints.size(), 0);
        }

        // Phase 4: Build similarity graph & BFS
        int n = filtered.size();
        List<Set<Integer>> graph = new ArrayList<>(n);
        for (int i = 0; i < n; i++) graph.add(new HashSet<>());

        int comparisons = 0;
        for (int i = 0; i < n; i++) {
            float[] vi = filtered.get(i).vector;
            for (int j = i + 1; j < n; j++) {
                float sim = cosineSimilarity(vi, filtered.get(j).vector);
                comparisons++;
                if (sim >= similarityThreshold) {
                    // Spatial constraint: same student stays in same seat
                    PointData pi = filtered.get(i);
                    PointData pj = filtered.get(j);
                    Long fi = pi.faceRecordId;
                    Long fj = pj.faceRecordId;
                    if (fi != null && fj != null) {
                        FaceMeta mi = faceMeta.get(fi);
                        FaceMeta mj = faceMeta.get(fj);
                        if (!sameSeat(mi, mj)) continue;  // different seats → can't be same person
                    }
                    graph.get(i).add(j);
                    graph.get(j).add(i);
                }
            }
        }
        log.info("Similarity graph built: {} comparisons, {} edges",
                comparisons, graph.stream().mapToInt(Set::size).sum() / 2);

        // Phase 5: Core-expansion clustering (DBSCAN-like, cuts transitive chains)
        List<List<Integer>> clusters = coreExpansionClusters(graph, filtered, n, 8);

        // Phase 5b: Centroid merge
        clusters = centroidMerge(clusters, filtered, n, 0.92f);

        int outliers = n - clusters.stream().mapToInt(List::size).sum();
        log.info("Found {} clusters, {} outliers (filtered out)", clusters.size(), outliers);

        // Phase 6: Save clusters to DB
        int saved = 0;
        for (List<Integer> cluster : clusters) {
            String clusterKey = "qc_" + System.currentTimeMillis() + "_" + saved;
            List<String> faceIds = cluster.stream()
                    .map(idx -> filtered.get(idx).id)
                    .collect(Collectors.toList());

            // Derive classId from majority vote within cluster
            Long classId = deriveClassId(cluster, filtered);

            FaceCluster fc = new FaceCluster();
            fc.setClusterKey(clusterKey);
            fc.setClassId(classId);
            fc.setFaceTokens(toJsonArray(faceIds));
            fc.setSampleCount(faceIds.size());
            fc.setFirstSeenAt(OffsetDateTime.now());
            fc.setLastSeenAt(OffsetDateTime.now());
            fc.setStatus("pending");
            clusterRepository.save(fc);
            saved++;
        }

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        log.info("Clustering V2 done: {} faces → {} filtered → {} clusters, {}s",
                rawPoints.size(), filtered.size(), saved, elapsed);

        // Auto-annotate
        autoAnnotateClusters();

        return new ClusteringReport(rawPoints.size(), saved, outliers, comparisons);
    }

    /**
     * Derive classId for a cluster by majority vote across face_records' class_image.class.id.
     */
    private Long deriveClassId(List<Integer> cluster, List<PointData> points) {
        Map<Long, Integer> votes = new HashMap<>();
        for (int idx : cluster) {
            Long cid = points.get(idx).classId;
            if (cid != null && cid > 0) {
                votes.merge(cid, 1, Integer::sum);
            }
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // ================================================================
    //  自动标注
    // ================================================================

    @Transactional
    public void autoAnnotateClusters() {
        List<FaceCluster> clusters = clusterRepository.findByStatus("pending");
        if (clusters.isEmpty()) {
            log.info("No pending clusters to auto-annotate");
            return;
        }
        log.info("Auto-annotating {} clusters", clusters.size());

        for (FaceCluster cluster : clusters) {
            try {
                if (cluster.getStudentId() != null) {
                    log.debug("Cluster {} already has student {}, skipping", cluster.getId(), cluster.getStudentId());
                    continue;
                }
                Long classId = cluster.getClassId();
                if (classId == null || classId == 0L) {
                    log.warn("Cluster {} has no classId, skipping", cluster.getId());
                    continue;
                }

                long existingCount = studentRepository.countByStudentNoStartingWith("auto_" + classId + "_");
                int seq = (int) existingCount + 1;
                String studentNo = String.format("auto_%d_%d", classId, cluster.getId());
                String studentName = String.format("student%03d", seq);

                Student student = new Student();
                student.setStudentNo(studentNo);
                student.setName(studentName);
                student.setClazz(schoolClassRepository.getReferenceById(classId));
                student.setStatus("active");
                student = studentRepository.save(student);
                log.info("Created student {} ({}) for cluster {}", studentNo, studentName, cluster.getId());

                // Backfill face_record.student_id via face_tokens (face_record IDs)
                String faceTokens = cluster.getFaceTokens();
                if (faceTokens != null && !faceTokens.isEmpty()) {
                    int backfilled = 0;
                    try {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        List<String> ids = mapper.readValue(faceTokens,
                                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                        for (String idStr : ids) {
                            try {
                                Long frId = Long.parseLong(idStr);
                                var frOpt = faceRecordRepository.findById(frId);
                                if (frOpt.isPresent()) {
                                    var fr = frOpt.get();
                                    if (fr.getStudent() == null) {
                                        fr.setStudent(student);
                                        faceRecordRepository.save(fr);
                                        backfilled++;
                                    }
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    } catch (Exception e) {
                        // fallback: regex parse
                        Pattern p = Pattern.compile("\"?(\\d+)\"?");
                        java.util.regex.Matcher m = p.matcher(faceTokens);
                        int fallback = 0;
                        while (m.find()) {
                            try {
                                Long frId = Long.parseLong(m.group(1));
                                var frOpt = faceRecordRepository.findById(frId);
                                if (frOpt.isPresent()) {
                                    var fr = frOpt.get();
                                    if (fr.getStudent() == null) {
                                        fr.setStudent(student);
                                        faceRecordRepository.save(fr);
                                        fallback++;
                                    }
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                        backfilled = Math.max(backfilled, fallback);
                    }
                    log.info("Backfilled {} face_records for cluster {}", backfilled, cluster.getId());
                }

                cluster.setStudentId(student.getId());

                try {
                    emotionAggregationService.aggregate(student.getId(), LocalDate.now(), 0L);
                } catch (Exception aggEx) {
                    log.warn("Failed to aggregate emotion for student {}: {}", student.getId(), aggEx.getMessage());
                }

                cluster.setStatus("auto_annotated");
                clusterRepository.save(cluster);

                if (externalPushService != null) {
                    try {
                        externalPushService.pushStudent(student);
                        externalPushService.pushStudentEmotions(student.getId());
                    } catch (Exception pushEx) {
                        log.warn("Failed to push after auto-annotate: {}", pushEx.getMessage());
                    }
                }

            } catch (Exception e) {
                log.error("Failed to auto-annotate cluster {}: {}", cluster.getId(), e.getMessage());
            }
        }
        log.info("Auto-annotation complete");
    }

    // ================================================================
    //  Core-Expansion Clustering (DBSCAN-like)
    // ================================================================

    /**
     * Core-expansion: only core points (degree ≥ minCore) seed clusters.
     * This breaks transitive chains that cause mega-clusters in BFS.
     */
    private List<List<Integer>> coreExpansionClusters(List<Set<Integer>> graph,
                                                       List<PointData> points, int n, int minCore) {
        boolean[] isCore = new boolean[n];
        int coreCount = 0;
        for (int i = 0; i < n; i++) {
            if (graph.get(i).size() >= minCore) {
                isCore[i] = true;
                coreCount++;
            }
        }
        log.info("  Core points: {}/{} (min_neighbors={})", coreCount, n, minCore);

        if (coreCount == 0) {
            log.warn("  No core points, falling back to BFS");
            return bfsClusters(graph, n, minClusterSize);
        }

        // Build core-only graph
        Map<Integer, Integer> coreMap = new HashMap<>();
        List<Integer> coreIndices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (isCore[i]) {
                coreMap.put(i, coreIndices.size());
                coreIndices.add(i);
            }
        }
        List<Set<Integer>> coreGraph = new ArrayList<>(coreIndices.size());
        for (int i = 0; i < coreIndices.size(); i++) coreGraph.add(new HashSet<>());
        for (int ci : coreIndices) {
            int cIdx = coreMap.get(ci);
            for (int nb : graph.get(ci)) {
                if (isCore[nb] && coreMap.containsKey(nb)) {
                    coreGraph.get(cIdx).add(coreMap.get(nb));
                }
            }
        }

        // BFS core clusters
        List<List<Integer>> coreClusters = bfsClusters(coreGraph, coreIndices.size(), 1);
        log.info("  Core clusters before expansion: {}", coreClusters.size());

        // Expand: assign non-core points to nearest cluster centroid
        List<List<Integer>> result = new ArrayList<>();
        Set<Integer> assigned = new HashSet<>();
        for (List<Integer> cc : coreClusters) {
            List<Integer> cluster = new ArrayList<>();
            List<Integer> clusterCoreIdx = cc.stream().map(coreIndices::get).toList();
            cluster.addAll(clusterCoreIdx);
            assigned.addAll(clusterCoreIdx);

            // Compute centroid from core vectors
            float[] centroid = computeCentroid(clusterCoreIdx, points);

            // Collect candidate non-core neighbors
            Set<Integer> candidates = new HashSet<>();
            for (int ci : clusterCoreIdx) {
                for (int nb : graph.get(ci)) {
                    if (!assigned.contains(nb)) candidates.add(nb);
                }
            }
            for (int cand : candidates) {
                if (isCore[cand] && !assigned.contains(cand)) continue;
                if (centroid != null && points.get(cand).vector != null) {
                    float sim = cosineSimilarity(centroid, points.get(cand).vector);
                    if (sim >= 0.7f) {
                        cluster.add(cand);
                        assigned.add(cand);
                    }
                }
            }
            result.add(cluster);
        }

        // Filter by min_cluster
        result = result.stream().filter(c -> c.size() >= minClusterSize).collect(Collectors.toList());
        return result;
    }

    /**
     * Merge clusters whose centroids are highly similar (cos ≥ mergeThreshold).
     */
    private List<List<Integer>> centroidMerge(List<List<Integer>> clusters,
                                               List<PointData> points, int n, float mergeThreshold) {
        if (clusters.size() <= 1) return clusters;

        float[][] centroids = new float[clusters.size()][];
        for (int i = 0; i < clusters.size(); i++) {
            centroids[i] = computeCentroid(clusters.get(i), points);
        }

        List<Set<Integer>> mergeGraph = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) mergeGraph.add(new HashSet<>());
        int mergePairs = 0;
        for (int i = 0; i < clusters.size(); i++) {
            for (int j = i + 1; j < clusters.size(); j++) {
                if (centroids[i] != null && centroids[j] != null) {
                    float sim = cosineSimilarity(centroids[i], centroids[j]);
                    if (sim >= mergeThreshold) {
                        mergeGraph.get(i).add(j);
                        mergeGraph.get(j).add(i);
                        mergePairs++;
                    }
                }
            }
        }

        if (mergePairs == 0) {
            log.info("  No centroid merges needed");
            return clusters;
        }

        // BFS merge
        boolean[] visited = new boolean[clusters.size()];
        List<List<Integer>> merged = new ArrayList<>();
        int mergesDone = 0;
        for (int i = 0; i < clusters.size(); i++) {
            if (!visited[i]) {
                List<Integer> comp = new ArrayList<>();
                Queue<Integer> q = new LinkedList<>();
                q.add(i); visited[i] = true;
                while (!q.isEmpty()) {
                    int node = q.poll();
                    comp.add(node);
                    for (int nb : mergeGraph.get(node)) {
                        if (!visited[nb]) { visited[nb] = true; q.add(nb); }
                    }
                }
                if (comp.size() > 1) {
                    mergesDone++;
                    List<Integer> mergedCluster = new ArrayList<>();
                    for (int ci : comp) mergedCluster.addAll(clusters.get(ci));
                    merged.add(mergedCluster);
                } else {
                    merged.add(clusters.get(comp.get(0)));
                }
            }
        }
        log.info("  Centroid merge: {} merges, {} → {} clusters", mergesDone, clusters.size(), merged.size());
        return merged;
    }

    private float[] computeCentroid(List<Integer> indices, List<PointData> points) {
        if (indices.isEmpty()) return null;
        float[] sum = null;
        int count = 0;
        for (int idx : indices) {
            float[] v = points.get(idx).vector;
            if (v != null) {
                if (sum == null) sum = new float[v.length];
                for (int d = 0; d < v.length; d++) sum[d] += v[d];
                count++;
            }
        }
        if (sum == null || count == 0) return null;
        for (int d = 0; d < sum.length; d++) sum[d] /= count;
        return sum;
    }

    private List<List<Integer>> bfsClusters(List<Set<Integer>> graph, int n, int minSize) {
        boolean[] visited = new boolean[n];
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!visited[i]) {
                List<Integer> comp = new ArrayList<>();
                Queue<Integer> q = new LinkedList<>();
                q.add(i); visited[i] = true;
                while (!q.isEmpty()) {
                    int node = q.poll();
                    comp.add(node);
                    for (int nb : graph.get(node)) {
                        if (!visited[nb]) { visited[nb] = true; q.add(nb); }
                    }
                }
                if (comp.size() >= minSize) result.add(comp);
            }
        }
        return result;
    }

    // ================================================================
    //  Qdrant 读取（含 payload）
    // ================================================================

    @SuppressWarnings("unchecked")
    private List<PointData> scrollAllPoints() {
        List<PointData> result = new ArrayList<>();
        Object nextId = null;
        try {
            while (true) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("limit", 1000);
                body.put("with_vector", true);
                body.put("with_payload", true);  // 带回 face_record_id, class_image_id
                if (nextId != null) body.put("offset", nextId);

                var response = restTemplate.postForEntity(
                        qdrantUrl + "/collections/" + collectionName + "/points/scroll",
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

                    // Extract payload
                    Map<String, Object> payload = (Map<String, Object>) p.get("payload");
                    if (payload != null) {
                        Object frId = payload.get("face_record_id");
                        if (frId instanceof Number) pd.faceRecordId = ((Number) frId).longValue();
                        Object ciId = payload.get("class_image_id");
                        if (ciId instanceof Number) pd.classImageId = ((Number) ciId).longValue();
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

    // ================================================================
    //  质量元数据加载
    // ================================================================

    /**
     * Batch-load face_record confidence + bbox width + classId from MySQL.
     */
    private Map<Long, FaceMeta> loadFaceMeta(List<PointData> points) {
        Map<Long, FaceMeta> result = new HashMap<>();
        List<Long> ids = points.stream()
                .map(p -> p.faceRecordId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());

        // Load in batches of 500
        int batchSize = 500;
        for (int i = 0; i < ids.size(); i += batchSize) {
            List<Long> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
            List<FaceRecord> frs = faceRecordRepository.findAllById(batch);
            for (FaceRecord fr : frs) {
                FaceMeta meta = new FaceMeta();
                meta.confidence = fr.getConfidence();
                meta.faceWidth = parseBboxWidth(fr.getBbox());
                float[] center = parseBboxCenter(fr.getBbox());
                meta.centerX = center[0];
                meta.centerY = center[1];
                if (fr.getClassImage() != null && fr.getClassImage().getClazz() != null) {
                    meta.classId = fr.getClassImage().getClazz().getId();
                }
                result.put(fr.getId(), meta);
            }
        }
        return result;
    }

    private int parseBboxWidth(String bboxJson) {
        if (bboxJson == null) return 0;
        try {
            int idx = bboxJson.indexOf("\"width\":");
            if (idx < 0) return 0;
            int start = idx + 8;
            int end = bboxJson.indexOf(",", start);
            if (end < 0) end = bboxJson.indexOf("}", start);
            if (end < 0) return 0;
            return (int) Double.parseDouble(bboxJson.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private float[] parseBboxCenter(String bboxJson) {
        if (bboxJson == null) return new float[]{0, 0};
        try {
            var bbox = new com.fasterxml.jackson.databind.ObjectMapper().readValue(bboxJson, Map.class);
            float cx = ((Number) bbox.get("x")).floatValue() + ((Number) bbox.get("width")).floatValue() / 2;
            float cy = ((Number) bbox.get("y")).floatValue() + ((Number) bbox.get("height")).floatValue() / 2;
            return new float[]{cx, cy};
        } catch (Exception e) {
            return new float[]{0, 0};
        }
    }

    private boolean sameSeat(FaceMeta a, FaceMeta b) {
        if (a == null || b == null) return true;  // no data → don't filter
        double dx = a.centerX - b.centerX;
        double dy = a.centerY - b.centerY;
        return Math.sqrt(dx * dx + dy * dy) <= maxSeatDist;
    }

    // ================================================================
    //  工具方法
    // ================================================================

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

    // ================================================================
    //  内部类型
    // ================================================================

    static class PointData {
        String id;
        float[] vector;
        Long faceRecordId;
        Long classImageId;
        Long classId;  // Populated after filtering
    }

    static class FaceMeta {
        Float confidence;
        int faceWidth;
        Long classId;
        float centerX;
        float centerY;
    }

    public record ClusteringReport(int totalFaces, int clusters, int outliers, int comparisons) {}
}
