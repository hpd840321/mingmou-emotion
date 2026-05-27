package com.school.emotion.controller;

import com.school.emotion.model.dto.ImageIngestRequest;
import com.school.emotion.model.dto.ImageIngestResponse;
import com.school.emotion.service.ImageIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/images")
public class ImageIngestController {

    private final ImageIngestionService ingestionService;

    public ImageIngestController(ImageIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/ingest", consumes = "multipart/form-data")
    public ResponseEntity<ImageIngestResponse> ingestImage(@Valid ImageIngestRequest request)
            throws IOException {
        ImageIngestResponse response = ingestionService.ingest(
                request.getClassId(),
                request.getImage().getBytes(),
                request.getImage().getOriginalFilename(),
                request.getCaptureTime(),
                request.getPeriodLabel()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
