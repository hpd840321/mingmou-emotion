package com.school.emotion.service;

import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.repository.FaceRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

@Service
public class FaceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(FaceRegistrationService.class);

    private final RestTemplate restTemplate;
    private final FaceRecordRepository faceRecordRepository;
    private final String vmApiBase;

    public FaceRegistrationService(RestTemplate restTemplate,
                                    FaceRecordRepository faceRecordRepository,
                                    @Value("${visionmind.api.base-url:http://localhost:8080}") String vmApiBase) {
        this.restTemplate = restTemplate;
        this.faceRecordRepository = faceRecordRepository;
        this.vmApiBase = vmApiBase;
    }

    @Transactional
    public RegistrationResult registerFaceToLibrary(FaceRecord faceRecord, Path croppedImage,
                                                     long schoolId, long classId) {
        if (!Files.exists(croppedImage)) {
            return new RegistrationResult(false, "Cropped image not found");
        }

        String faceId = String.format("face_%d_%d_%d", schoolId, classId, faceRecord.getId());

        try {
            byte[] imageBytes = Files.readAllBytes(croppedImage);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of(
                "id", faceId,
                "name", faceId,
                "image", "data:image/jpeg;base64," + base64
            );

            var response = restTemplate.postForEntity(
                vmApiBase + "/v1/facedb/register",
                new HttpEntity<>(body, headers),
                Map.class);

            Map<String, Object> respBody = response.getBody();
            int code = (int) respBody.getOrDefault("code", 1);

            if (code == 0) {
                faceRecord.setLibFaceId(faceId);
                faceRecord.setRegisteredAt(OffsetDateTime.now());
                faceRecord.setLibRegisterStatus("registered");
                faceRecordRepository.save(faceRecord);
                return new RegistrationResult(true, faceId);
            } else {
                String msg = (String) respBody.getOrDefault("message", "unknown error");
                faceRecord.setLibRegisterStatus("failed");
                faceRecordRepository.save(faceRecord);
                return new RegistrationResult(false, msg);
            }
        } catch (IOException e) {
            return new RegistrationResult(false, "IO error: " + e.getMessage());
        } catch (Exception e) {
            faceRecord.setLibRegisterStatus("failed");
            faceRecordRepository.save(faceRecord);
            return new RegistrationResult(false, e.getMessage());
        }
    }

    public record RegistrationResult(boolean success, String message) {}
}
