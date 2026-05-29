package com.school.emotion.controller;

import com.school.emotion.model.dto.AnnotateRequest;
import com.school.emotion.service.FaceLibraryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/face-clusters")
public class FaceClusterController {

    private final FaceLibraryService faceLibraryService;

    public FaceClusterController(FaceLibraryService faceLibraryService) {
        this.faceLibraryService = faceLibraryService;
    }

    @GetMapping
    public ResponseEntity<?> listClusters(
            @RequestParam("class_id") Long classId,
            @RequestParam(defaultValue = "pending") String status) {
        return ResponseEntity.ok(Map.of("code", 0, "data", faceLibraryService.listPendingClusters(classId, status)));
    }

    @PostMapping("/{id}/annotate")
    public ResponseEntity<?> annotate(@PathVariable Long id, @Valid @RequestBody AnnotateRequest request) {
        faceLibraryService.annotateCluster(id, request);
        return ResponseEntity.ok(Map.of("code", 0, "message", "annotated"));
    }

    @PostMapping("/{id}/merge")
    public ResponseEntity<?> merge(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        faceLibraryService.mergeCluster(id, body.get("studentId"));
        return ResponseEntity.ok(Map.of("code", 0, "message", "merged"));
    }
}
