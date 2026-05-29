package com.school.emotion.model.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FaceDetectionResultTest {

    @Test
    void fromVmResponse_shouldParseBboxAndConfidence() {
        Map<String, Object> vmData = Map.of(
            "faces", List.of(
                Map.of("bbox", List.of(10, 20, 100, 200), "confidence", 0.95),
                Map.of("bbox", List.of(30, 40, 50, 60), "confidence", 0.85)
            )
        );

        FaceDetectionResult result = FaceDetectionResult.fromVmResponse(vmData);
        assertEquals(2, result.getFaces().size());
        assertEquals(0.95f, result.getFaces().get(0).getConfidence(), 0.01);
        assertEquals(10, result.getFaces().get(0).getBbox().getX());
        assertEquals(50, result.getFaces().get(1).getBbox().getWidth());
    }

    @Test
    void fromVmResponse_shouldParseQuality() {
        Map<String, Object> vmData = Map.of(
            "faces", List.of(
                Map.of("bbox", List.of(0, 0, 10, 10), "confidence", 0.9, "quality", 0.85)
            )
        );

        FaceDetectionResult result = FaceDetectionResult.fromVmResponse(vmData);
        assertEquals(0.85f, result.getFaces().get(0).getQuality(), 0.01);
    }

    @Test
    void fromVmResponse_shouldHandleMissingQuality() {
        Map<String, Object> vmData = Map.of(
            "faces", List.of(
                Map.of("bbox", List.of(0, 0, 10, 10), "confidence", 0.9)
            )
        );

        FaceDetectionResult result = FaceDetectionResult.fromVmResponse(vmData);
        assertNull(result.getFaces().get(0).getQuality());
    }

    @Test
    void fromVmResponse_shouldReturnNullWhenNoFaces() {
        Map<String, Object> vmData = Map.of("faces", List.of());
        FaceDetectionResult result = FaceDetectionResult.fromVmResponse(vmData);
        assertTrue(result.getFaces().isEmpty());
    }

    @Test
    void fromVmResponse_shouldReturnNullFacesWhenKeyMissing() {
        Map<String, Object> vmData = Map.of();
        FaceDetectionResult result = FaceDetectionResult.fromVmResponse(vmData);
        assertNull(result.getFaces());
    }
}
