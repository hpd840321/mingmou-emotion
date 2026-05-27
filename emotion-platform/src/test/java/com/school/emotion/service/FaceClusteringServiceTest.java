package com.school.emotion.service;

import com.school.emotion.model.entity.FaceCluster;
import com.school.emotion.repository.FaceClusterRepository;
import com.school.emotion.service.ai.FaceClusteringService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FaceClusteringServiceTest {

    @Mock
    private FaceClusterRepository clusterRepository;

    @InjectMocks
    private FaceClusteringService clusteringService;

    @Test
    void clusterTokens_shouldGroupByPrefix() {
        clusteringService.offer("aaaaaaaa-0001-xxxx", 1L, OffsetDateTime.now());
        clusteringService.offer("aaaaaaaa-0002-xxxx", 1L, OffsetDateTime.now());
        clusteringService.offer("bbbbbbbb-0001-xxxx", 1L, OffsetDateTime.now());

        when(clusterRepository.findByClassIdAndStatusOrderBySampleCountDesc(anyLong(), anyString()))
                .thenReturn(List.of());

        clusteringService.processPendingClusters();

        ArgumentCaptor<FaceCluster> captor = ArgumentCaptor.forClass(FaceCluster.class);
        verify(clusterRepository, times(2)).save(captor.capture());
        assertEquals(2, captor.getAllValues().size());
    }

    @Test
    void processPendingClusters_shouldAppendToExistingCluster() {
        FaceCluster existing = new FaceCluster();
        existing.setId(1L);
        existing.setClusterKey("prefix_aaaaaaaa");
        existing.setFaceTokens("[\"aaaaaaaa-0000-xxxx\"]");
        existing.setSampleCount(1);
        existing.setClassId(1L);

        clusteringService.offer("aaaaaaaa-0001-xxxx", 1L, OffsetDateTime.now());

        when(clusterRepository.findByClassIdAndStatusOrderBySampleCountDesc(1L, "pending"))
                .thenReturn(List.of(existing));

        clusteringService.processPendingClusters();

        ArgumentCaptor<FaceCluster> captor = ArgumentCaptor.forClass(FaceCluster.class);
        verify(clusterRepository).save(captor.capture());

        FaceCluster updated = captor.getValue();
        assertEquals(2, updated.getSampleCount());
        assertTrue(updated.getFaceTokens().contains("aaaaaaaa-0001-xxxx"));
    }

    @Test
    void processPendingClusters_shouldDoNothingWhenQueueEmpty() {
        clusteringService.processPendingClusters();
        verify(clusterRepository, never()).save(any());
    }

    @Test
    void processPendingClusters_shouldBatchByClass() {
        clusteringService.offer("aaaaaaaa-0001", 1L, OffsetDateTime.now());
        clusteringService.offer("bbbbbbbb-0001", 2L, OffsetDateTime.now());

        when(clusterRepository.findByClassIdAndStatusOrderBySampleCountDesc(anyLong(), anyString()))
                .thenReturn(List.of());

        clusteringService.processPendingClusters();

        verify(clusterRepository, times(2)).save(any());
    }

    @Test
    void offer_shouldHandleSingleToken() {
        clusteringService.offer("cccccccc-0001", 1L, OffsetDateTime.now());

        when(clusterRepository.findByClassIdAndStatusOrderBySampleCountDesc(1L, "pending"))
                .thenReturn(List.of());

        clusteringService.processPendingClusters();
        verify(clusterRepository, times(1)).save(any());
    }
}
