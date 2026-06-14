package com.school.emotion.controller;

import com.school.emotion.repository.FaceRecordRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImageController {

    private final FaceRecordRepository faceRecordRepository;

    public ImageController(FaceRecordRepository faceRecordRepository) {
        this.faceRecordRepository = faceRecordRepository;
    }

    @GetMapping("/img/{faceRecordId}")
    public ResponseEntity<?> getFaceImage(@PathVariable Long faceRecordId) {
        var optFr = faceRecordRepository.findById(faceRecordId);
        if (optFr.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String cropUrl = optFr.get().getCroppedImageUrl();
        if (cropUrl == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            java.nio.file.Path imgPath = java.nio.file.Path.of(cropUrl);
            if (!java.nio.file.Files.exists(imgPath)) {
                String relative = cropUrl.replace("/images/cropped/", "");
                imgPath = java.nio.file.Path.of(
                    System.getProperty("user.dir"), "images", "cropped", relative);
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(imgPath);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
