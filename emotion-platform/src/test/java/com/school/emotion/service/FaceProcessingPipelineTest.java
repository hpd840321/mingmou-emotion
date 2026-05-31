package com.school.emotion.service;

import com.school.emotion.client.VisionMindClient;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.GradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FaceProcessingPipelineTest {

    @Mock private ClassImageRepository classImageRepository;
    @Mock private FaceRecordRepository faceRecordRepository;
    @Mock private EmotionRecordRepository emotionRecordRepository;
    @Mock private GradeRepository gradeRepository;
    @Mock private VisionMindClient visionMindClient;
    @Mock private FaceCroppingService croppingService;
    @Mock private FaceRegistrationService registrationService;
    @Mock private PipelineProgressService progressService;
    @Mock private com.school.emotion.service.ai.GrpcFaceServiceClient grpcFaceClient;
    @Mock private org.springframework.core.task.TaskExecutor pipelineExecutor;

    private FaceProcessingPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new FaceProcessingPipeline(
                classImageRepository, faceRecordRepository, emotionRecordRepository,
                gradeRepository, visionMindClient,
                croppingService, registrationService, progressService, grpcFaceClient, pipelineExecutor, 0.3f, 50);
    }

    @Test
    void processImage_fileNotFound_returnsNoFace() {
        ClassImage ci = new ClassImage();
        ci.setId(1L);
        ci.setImageUrl("/nonexistent/missing.jpg");
        ci.setStatus(ImageStatus.PENDING);

        var result = pipeline.processImage(ci);
        assertFalse(result.faceDetected());
    }

    @Test
    void processImage_detectionError_marksFailed() throws IOException {
        Path tempFile = Files.createTempFile("test-detect-err-", ".jpg");
        Files.write(tempFile, new byte[]{0, 1, 2});

        ClassImage ci = new ClassImage();
        ci.setId(1L);
        ci.setImageUrl(tempFile.toAbsolutePath().toString());
        ci.setStatus(ImageStatus.PENDING);

        when(visionMindClient.detectFaces(any())).thenThrow(new RuntimeException("API unavailable"));

        var result = pipeline.processImage(ci);
        assertFalse(result.faceDetected());

        Files.deleteIfExists(tempFile);
    }

    @Test
    void processImage_noFaces_marksCompleted() throws IOException {
        Path tempFile = Files.createTempFile("test-noface-", ".jpg");
        Files.write(tempFile, new byte[]{0, 1, 2});

        ClassImage ci = new ClassImage();
        ci.setId(2L);
        ci.setImageUrl(tempFile.toAbsolutePath().toString());
        ci.setStatus(ImageStatus.PENDING);

        when(visionMindClient.detectFaces(any())).thenReturn(new FaceDetectionResult());

        var result = pipeline.processImage(ci);
        assertFalse(result.faceDetected());

        Files.deleteIfExists(tempFile);
    }

    @Test
    void processImage_selectsHighestConfidenceFace() throws IOException {
        Path tempFile = Files.createTempFile("test-select-", ".jpg");
        Files.write(tempFile, new byte[]{0, 1, 2});

        ClassImage ci = new ClassImage();
        ci.setId(3L);
        ci.setImageUrl(tempFile.toAbsolutePath().toString());
        ci.setStatus(ImageStatus.PENDING);

        FaceDetectionResult result = new FaceDetectionResult();
        FaceDetectionResult.Face f1 = new FaceDetectionResult.Face();
        f1.setConfidence(0.5f);
        f1.setBbox(new FaceDetectionResult.BBox());
        FaceDetectionResult.Face f2 = new FaceDetectionResult.Face();
        f2.setConfidence(0.8f);
        f2.setBbox(new FaceDetectionResult.BBox());
        result.setFaces(List.of(f1, f2));

        when(visionMindClient.detectFaces(any())).thenReturn(result);
        when(faceRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var presult = pipeline.processImage(ci);
        assertTrue(presult.faceDetected());

        Files.deleteIfExists(tempFile);
    }

    @Test
    void processImage_filtersLowConfidenceFaces() throws IOException {
        Path tempFile = Files.createTempFile("test-low-", ".jpg");
        Files.write(tempFile, new byte[]{0, 1, 2});

        ClassImage ci = new ClassImage();
        ci.setId(4L);
        ci.setImageUrl(tempFile.toAbsolutePath().toString());
        ci.setStatus(ImageStatus.PENDING);

        FaceDetectionResult result = new FaceDetectionResult();
        FaceDetectionResult.Face face = new FaceDetectionResult.Face();
        face.setConfidence(0.1f);
        face.setBbox(new FaceDetectionResult.BBox());
        result.setFaces(List.of(face));

        when(visionMindClient.detectFaces(any())).thenReturn(result);

        var presult = pipeline.processImage(ci);
        assertFalse(presult.faceDetected());

        Files.deleteIfExists(tempFile);
    }
}
