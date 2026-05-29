package com.school.emotion.service;

import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.service.ai.EmotionRecognitionService;
import com.school.emotion.service.ai.FaceRecognitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageProcessingOrchestratorTest {

    @Mock
    private ClassImageRepository classImageRepository;
    @Mock
    private FaceRecognitionService faceService;
    @Mock
    private EmotionRecognitionService emotionService;
    @Mock
    private ImageProcessingPersistenceService persistenceService;

    private ImageProcessingOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ImageProcessingOrchestrator(
                classImageRepository, faceService, emotionService, persistenceService);
        ReflectionTestUtils.setField(orchestrator, "confidenceThreshold", 0.5f);
    }

    @Test
    void processImage_shouldFilterLowConfidenceFaces() throws Exception {
        ClassImage ci = createTestImage();
        FaceDetectionResult result = new FaceDetectionResult();
        FaceDetectionResult.Face lowConf = new FaceDetectionResult.Face();
        lowConf.setConfidence(0.3f);
        lowConf.setBbox(new FaceDetectionResult.BBox());
        FaceDetectionResult.Face highConf = new FaceDetectionResult.Face();
        highConf.setConfidence(0.6f);
        highConf.setBbox(new FaceDetectionResult.BBox());
        result.setFaces(List.of(lowConf, highConf));

        when(faceService.detectFaces(any())).thenReturn(result);
        when(emotionService.analyzeEmotion(any())).thenReturn(new EmotionAnalysisResult());

        orchestrator.processImage(ci);

        verify(persistenceService).saveResults(eq(ci.getId()), argThat(f -> f.getConfidence() == 0.6f), any(), eq(1));
    }

    @Test
    void processImage_shouldSkipWhenNoFacePassesThreshold() throws Exception {
        ClassImage ci = createTestImage();
        FaceDetectionResult result = new FaceDetectionResult();
        FaceDetectionResult.Face face = new FaceDetectionResult.Face();
        face.setConfidence(0.3f);
        face.setBbox(new FaceDetectionResult.BBox());
        result.setFaces(List.of(face));

        when(faceService.detectFaces(any())).thenReturn(result);

        orchestrator.processImage(ci);

        verify(persistenceService, never()).saveResults(any(), any(), any(), anyInt());
        verify(persistenceService).markCompleted(ci.getId());
    }

    @Test
    void processImage_shouldSelectHighestConfidenceFace() throws Exception {
        ClassImage ci = createTestImage();
        FaceDetectionResult result = new FaceDetectionResult();
        FaceDetectionResult.Face f1 = new FaceDetectionResult.Face();
        f1.setConfidence(0.7f);
        f1.setBbox(new FaceDetectionResult.BBox());
        FaceDetectionResult.Face f2 = new FaceDetectionResult.Face();
        f2.setConfidence(0.9f);
        f2.setBbox(new FaceDetectionResult.BBox());
        result.setFaces(List.of(f1, f2));

        when(faceService.detectFaces(any())).thenReturn(result);
        when(emotionService.analyzeEmotion(any())).thenReturn(new EmotionAnalysisResult());

        orchestrator.processImage(ci);

        verify(persistenceService).saveResults(eq(ci.getId()), argThat(f -> f.getConfidence() == 0.9f), any(), eq(1));
    }

    @Test
    void processImage_shouldHandleNoFacesGracefully() throws Exception {
        ClassImage ci = createTestImage();
        FaceDetectionResult result = new FaceDetectionResult();
        result.setFaces(List.of());

        when(faceService.detectFaces(any())).thenReturn(result);

        orchestrator.processImage(ci);

        verify(persistenceService, never()).saveResults(any(), any(), any(), anyInt());
        verify(persistenceService).markCompleted(ci.getId());
    }

    @Test
    void processImage_shouldHandleNullFaces() throws Exception {
        ClassImage ci = createTestImage();
        FaceDetectionResult result = new FaceDetectionResult();
        result.setFaces(null);

        when(faceService.detectFaces(any())).thenReturn(result);

        orchestrator.processImage(ci);

        verify(persistenceService, never()).saveResults(any(), any(), any(), anyInt());
        verify(persistenceService).markCompleted(ci.getId());
    }

    private ClassImage createTestImage() throws IOException {
        Path tempFile = Files.createTempFile("test-img-", ".jpg");
        Files.write(tempFile, new byte[]{0, 1, 2});
        ClassImage ci = new ClassImage();
        ci.setId(1L);
        ci.setImageUrl(tempFile.toAbsolutePath().toString());
        ci.setStatus(ImageStatus.PROCESSING);
        return ci;
    }
}
