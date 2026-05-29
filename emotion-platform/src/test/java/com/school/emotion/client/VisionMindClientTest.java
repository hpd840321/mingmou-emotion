package com.school.emotion.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class VisionMindClientTest {

    private VisionMindClient client;
    private MockRestServiceServer mockServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplateBuilder().build();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        client = new VisionMindClient(new RestTemplateBuilder(), objectMapper,
                "http://localhost:8080",
                "/v1/face/detect", "/v1/face/attribute",
                "/v1/face/search", "/v1/facedb/register");
        client.setRestTemplate(restTemplate);
    }

    @Test
    void detectFaces_shouldReturnFaces() throws Exception {
        String base64 = Base64.getEncoder().encodeToString("test-image".getBytes());
        Map<String, Object> mockResponse = Map.of(
                "code", 0, "message", "success",
                "data", Map.of("faces", new Object[]{
                        Map.of("bbox", List.of(10, 20, 100, 150), "confidence", 0.95)
                }));

        mockServer.expect(requestTo("http://localhost:8080/v1/face/detect"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        FaceDetectionResult result = client.detectFaces("test-image".getBytes());
        assertNotNull(result);
        assertNotNull(result.getFaces());
        assertEquals(1, result.getFaces().size());
        assertEquals(0.95f, result.getFaces().get(0).getConfidence(), 0.01);
        assertNotNull(result.getFaces().get(0).getBbox());
        assertEquals(10, result.getFaces().get(0).getBbox().getX());
    }

    @Test
    void detectFaces_shouldParseFloatBbox() throws Exception {
        String base64 = Base64.getEncoder().encodeToString("test-image".getBytes());
        Map<String, Object> mockResponse = Map.of(
                "code", 0, "message", "success",
                "data", Map.of("faces", new Object[]{
                        Map.of("bbox", List.of(10.5, 20.3, 100.7, 150.2), "confidence", 0.95)
                }));

        mockServer.expect(requestTo("http://localhost:8080/v1/face/detect"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        FaceDetectionResult result = client.detectFaces("test-image".getBytes());
        assertNotNull(result.getFaces().get(0).getBbox());
        assertEquals(10.5f, result.getFaces().get(0).getBbox().getX(), 0.01);
        assertEquals(20.3f, result.getFaces().get(0).getBbox().getY(), 0.01);
        assertEquals(100.7f, result.getFaces().get(0).getBbox().getWidth(), 0.01);
        assertEquals(150.2f, result.getFaces().get(0).getBbox().getHeight(), 0.01);
    }

    @Test
    void detectFaces_shouldThrowOnError() throws Exception {
        Map<String, Object> errorResponse = Map.of("code", 400, "message", "image_base64 required");

        mockServer.expect(requestTo("http://localhost:8080/v1/face/detect"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(errorResponse), MediaType.APPLICATION_JSON));

        assertThrows(com.school.emotion.exception.AiServiceException.class,
                () -> client.detectFaces("test".getBytes()));
    }

    @Test
    void analyzeAttribute_shouldReturnEmotion() throws Exception {
        Map<String, Object> mockResponse = Map.of(
                "code", 0, "message", "success",
                "data", Map.of("emotion", Map.of("label", "happy", "probability", 0.87)));

        mockServer.expect(requestTo("http://localhost:8080/v1/face/attribute"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        EmotionAnalysisResult result = client.analyzeAttribute("test".getBytes());
        assertNotNull(result);
        assertEquals("happy", result.getDominantEmotion());
        assertEquals(0.87f, result.getDominantConfidence(), 0.01);
    }

    @Test
    void searchFaces_shouldReturnEmptyOnNoMatch() throws Exception {
        Map<String, Object> mockResponse = Map.of(
                "code", 0, "message", "success",
                "data", Map.of("results", new Object[]{}));

        mockServer.expect(requestTo("http://localhost:8080/v1/face/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        var results = client.searchFaces("test".getBytes(), 5, 0.5);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchFaces_shouldReturnMatches() throws Exception {
        Map<String, Object> mockResponse = Map.of(
                "code", 0, "message", "success",
                "data", Map.of("results", new Object[]{
                        Map.of("id", "face-001", "name", "张三",
                                "extra", "{\"student_id\":\"42\"}", "similarity", 0.92)
                }));

        mockServer.expect(requestTo("http://localhost:8080/v1/face/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        var results = client.searchFaces("test".getBytes(), 5, 0.5);
        assertEquals(1, results.size());
        assertEquals("face-001", results.get(0).getId());
        assertEquals("42", results.get(0).getExtraId());
    }

    @Test
    void registerFace_shouldSucceed() throws Exception {
        mockServer.expect(requestTo("http://localhost:8080/v1/facedb/register"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK));

        assertDoesNotThrow(() -> client.registerFace("stu-001", "张三", "{}", "test".getBytes()));
    }

}
