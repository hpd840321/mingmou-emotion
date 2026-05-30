package com.school.emotion.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class ExternalEmotionPushClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalEmotionPushClient.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String pageId;
    private final String cameraCode;
    private final boolean enabled;

    public ExternalEmotionPushClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.external-push.base-url:http://ylcs.htface.cn:33895}") String baseUrl,
            @Value("${app.external-push.page-id:Emotion}") String pageId,
            @Value("${app.external-push.camera-code:CAM_DEFAULT}") String cameraCode,
            @Value("${app.external-push.enabled:false}") boolean enabled) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.pageId = pageId;
        this.cameraCode = cameraCode;
        this.enabled = enabled;
    }

    public String getCameraCode() {
        return cameraCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public PushResult updateStudent(String studentCode, String studentName, List<String> imageUrls) {
        if (!enabled) {
            log.debug("External push disabled, skipping updateStudent for {}", studentCode);
            return new PushResult(true, "disabled");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pageID", pageId);
            body.put("method", "updateStudent");
            body.put("student_code", studentCode);
            body.put("student_name", studentName);
            body.put("ImageUrl", imageUrls);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var response = restTemplate.exchange(
                    baseUrl + "/api/Page/Execute",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class);
            Map<String, Object> resp = response.getBody();
            boolean success = resp != null && Boolean.TRUE.equals(resp.get("success"));
            if (!success) {
                String error = resp != null ? (String) resp.getOrDefault("error", "unknown") : "no response";
                log.warn("External push updateStudent failed for {}: {}", studentCode, error);
                return new PushResult(false, error);
            }
            log.debug("External push updateStudent success for {}", studentCode);
            return new PushResult(true, "ok");
        } catch (Exception e) {
            log.warn("External push updateStudent error for {}: {}", studentCode, e.getMessage());
            return new PushResult(false, e.getMessage());
        }
    }

    public PushResult addEmotions(List<ExternalEmotionPushRecord> emotions) {
        if (!enabled) {
            return new PushResult(true, "disabled");
        }
        if (emotions.isEmpty()) {
            return new PushResult(true, "empty");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pageID", pageId);
            body.put("method", "AddEmotion");
            body.put("emotions", emotions);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var response = restTemplate.exchange(
                    baseUrl + "/api/Page/Execute",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class);
            Map<String, Object> resp = response.getBody();
            if (resp != null && Boolean.TRUE.equals(resp.get("success"))) {
                log.info("External push AddEmotion success, batch size: {}", emotions.size());
                return new PushResult(true, "ok");
            } else {
                String error = resp != null ? (String) resp.getOrDefault("error", "unknown") : "no response";
                log.warn("External push AddEmotion failed: {}", error);
                return new PushResult(false, error);
            }
        } catch (Exception e) {
            log.warn("External push AddEmotion error: {}", e.getMessage());
            return new PushResult(false, e.getMessage());
        }
    }

    public static class PushResult {
        private final boolean success;
        private final String message;
        public PushResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }
}
