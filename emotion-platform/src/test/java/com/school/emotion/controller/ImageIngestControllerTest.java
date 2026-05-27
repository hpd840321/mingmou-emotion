package com.school.emotion.controller;

import com.school.emotion.model.dto.ImageIngestResponse;
import com.school.emotion.service.ImageIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImageIngestController.class)
class ImageIngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ImageIngestionService ingestionService;

    @Test
    void ingestImage_shouldReturn202() throws Exception {
        var mockImage = new MockMultipartFile("image", "test.jpg",
                "image/jpeg", "fake-image-data".getBytes());

        when(ingestionService.ingest(any(), any(), any(), any(), any()))
                .thenReturn(new ImageIngestResponse(0, "accepted", null));

        mockMvc.perform(multipart("/api/v1/images/ingest")
                        .file(mockImage)
                        .param("classId", "1")
                        .param("captureTime", "2026-05-26T08:00:00+08:00")
                        .param("periodLabel", "第1节"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("accepted"));
    }
}
