package com.school.emotion.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class VisionMindClient {

    private static final Logger log = LoggerFactory.getLogger(VisionMindClient.class);
    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String detectPath;
    private final String attributePath;
    private final String searchPath;
    private final String registerPath;

    public VisionMindClient(
            RestTemplateBuilder builder,
            ObjectMapper objectMapper,
            @Value("${visionmind.api.base-url:http://localhost:8080}") String baseUrl,
            @Value("${visionmind.face.detect.path:/v1/face/detect}") String detectPath,
            @Value("${visionmind.face.attribute.path:/v1/face/attribute}") String attributePath,
            @Value("${visionmind.face.search.path:/v1/face/search}") String searchPath,
            @Value("${visionmind.facedb.register-path:/v1/facedb/register}") String registerPath) {
        this.restTemplate = builder.build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.detectPath = detectPath;
        this.attributePath = attributePath;
        this.searchPath = searchPath;
        this.registerPath = registerPath;
    }

    // Used in tests to inject mock RestTemplate
    void setRestTemplate(RestTemplate rt) {
        this.restTemplate = rt;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 人脸检测: POST /v1/face/detect
     */
    @Retry(name = "visionmind")
    @CircuitBreaker(name = "visionmind")
    public FaceDetectionResult detectFaces(byte[] imageData) {
        String base64 = Base64.getEncoder().encodeToString(imageData);
        Map<String, Object> body = new HashMap<>();
        body.put("image_base64", base64);

        var response = restTemplate.exchange(
                baseUrl + detectPath, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<ExternalApiResponse<Map<String, Object>>>() {});

        ExternalApiResponse<Map<String, Object>> apiResp = response.getBody();
        if (apiResp == null || apiResp.getCode() != 0) {
            throw new AiServiceException("Face detection failed: "
                    + (apiResp != null ? apiResp.getMessage() : "no response"));
        }
        return FaceDetectionResult.fromVmResponse(apiResp.getData());
    }

    /**
     * 人脸属性分析(含表情): POST /v1/face/attribute
     */
    @Retry(name = "visionmind")
    @CircuitBreaker(name = "visionmind")
    public EmotionAnalysisResult analyzeAttribute(byte[] imageData) {
        String base64 = Base64.getEncoder().encodeToString(imageData);
        Map<String, Object> body = new HashMap<>();
        body.put("image_base64", base64);
        body.put("include", List.of("age", "gender", "expression", "quality", "liveness"));

        var response = restTemplate.exchange(
                baseUrl + attributePath, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<ExternalApiResponse<Map<String, Object>>>() {});

        ExternalApiResponse<Map<String, Object>> apiResp = response.getBody();
        if (apiResp == null || apiResp.getCode() != 0) {
            throw new AiServiceException("Attribute analysis failed: "
                    + (apiResp != null ? apiResp.getMessage() : "no response"));
        }
        return EmotionAnalysisResult.fromVmResponse(apiResp.getData());
    }

    /**
     * 1:N 人脸搜索: POST /v1/face/search
     */
    @Retry(name = "visionmind")
    @CircuitBreaker(name = "visionmind")
    public List<FaceSearchMatch> searchFaces(byte[] imageData, Integer topK, Double threshold) {
        String base64 = Base64.getEncoder().encodeToString(imageData);
        Map<String, Object> body = new HashMap<>();
        body.put("image", base64);
        body.put("top_k", topK != null ? topK : 5);
        body.put("threshold", threshold != null ? threshold : 0.5);

        var response = restTemplate.exchange(
                baseUrl + searchPath, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<ExternalApiResponse<Map<String, Object>>>() {});

        ExternalApiResponse<Map<String, Object>> apiResp = response.getBody();
        if (apiResp == null || apiResp.getCode() != 0) {
            return List.of();
        }
        return FaceSearchMatch.fromVmResponse(apiResp.getData());
    }

    /**
     * 人脸注册: POST /v1/facedb/register
     */
    @Retry(name = "visionmind")
    public void registerFace(String id, String name, String extraJson, byte[] imageData) {
        String base64 = Base64.getEncoder().encodeToString(imageData);
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("extra", extraJson);
        body.put("image", base64);

        restTemplate.exchange(
                baseUrl + registerPath, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                new ParameterizedTypeReference<ExternalApiResponse<Map<String, Object>>>() {});
    }

    /**
     * 人脸搜索结果
     */
    public static class FaceSearchMatch {
        private String id;
        private String name;
        private String extra;
        private Double similarity;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getExtra() { return extra; }
        public void setExtra(String extra) { this.extra = extra; }
        public Double getSimilarity() { return similarity; }
        public void setSimilarity(Double similarity) { this.similarity = similarity; }

        /** 从 extra JSON 中提取 student_id */
        public String getExtraId() {
            if (extra == null) return null;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var map = mapper.readValue(extra, Map.class);
                Object sid = map.get("student_id");
                return sid != null ? sid.toString() : null;
            } catch (Exception e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        public static List<FaceSearchMatch> fromVmResponse(Map<String, Object> vmData) {
            List<Map<String, Object>> results = (List<Map<String, Object>>) vmData.get("results");
            if (results == null) return List.of();
            return results.stream().map(r -> {
                FaceSearchMatch m = new FaceSearchMatch();
                m.setId((String) r.get("id"));
                m.setName((String) r.get("name"));
                m.setExtra((String) r.get("extra"));
                m.setSimilarity(r.get("similarity") != null ? ((Number) r.get("similarity")).doubleValue() : null);
                return m;
            }).toList();
        }
    }
}
