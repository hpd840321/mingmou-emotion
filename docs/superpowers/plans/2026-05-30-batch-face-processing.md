# 批量人脸处理 Implementation Plan

**Goal:** Process ALL faces per image, eliminate redundant JPEG decoding, generate annotated images, persist per-face errors.

**Architecture:** 5-phase pipeline: load→detect→attribute→annotate+crop→persist. Index-matched emotion. BufferedImage copy for annotation safety.

**Reference:** `docs/superpowers/specs/2026-05-30-batch-face-processing-design.md`

---

### Task 1: DB Migration + Entity Fields

**Files:**
- Create: `emotion-platform/src/main/resources/db/migration/V9__add_face_stat_fields.sql`
- Modify: `FaceRecord.java`, `ClassImage.java`

```sql
-- V9__add_face_stat_fields.sql
ALTER TABLE class_image
  ADD COLUMN face_detected_count INT DEFAULT 0 NOT NULL,
  ADD COLUMN emotion_recognized_count INT DEFAULT 0 NOT NULL,
  ADD COLUMN annotated_image_url VARCHAR(1000) DEFAULT NULL;

ALTER TABLE face_record
  ADD COLUMN error_message VARCHAR(500) DEFAULT NULL;
```

FaceRecord: add `private String errorMessage;` + getter/setter.
ClassImage: add `private Integer faceDetectedCount = 0; private Integer emotionRecognizedCount = 0; private String annotatedImageUrl;` + getters/setters.

---

### Task 2: VisionMindClient — tile params + multi-emotion API

**Files:**
- Modify: `VisionMindClient.java`

1. Add constructor params `tileWidth`/`tileHeight` (default 640), add to detectFaces() body as `body.put("tile_width", tileWidth)` etc.
2. Add `application-dev.yml` override: `visionmind.face.detect.tile-width: 320`
3. Add new method `analyzeAttributes()` returning raw attribute list:
```java
public List<Map<String, Object>> analyzeAttributes(byte[] imageData) {
    // POST /v1/face/attribute → parse data.attributes as List<Map>
}
```

---

### Task 3: FaceCroppingService — BufferedImage overload

Add `cropFace(BufferedImage, int x, int y, int w, int h, ...)` identical logic but no file I/O.

---

### Task 4: FaceProcessingPipeline — 5-phase rewrite

**Files:**
- Modify: `FaceProcessingPipeline.java`

New `processImage()` structure:

```java
@Transactional
public ProcessResult processImage(ClassImage ci) {
    // Mark PROCESSING (existing code)

    // Phase 0: Load once
    byte[] imageBytes = Files.readAllBytes(path);
    BufferedImage fullImage = ImageIO.read(new ByteArrayInputStream(imageBytes));

    // Phase 1: Detect all faces (tile=320)
    List<Face> allFaces = visionMindClient.detectFaces(imageBytes).getFaces();
    List<Face> qualified = filterByConfidence(allFaces);  // >= threshold

    // Phase 2: Batch emotion + index match
    List<Map<String,Object>> attrs = visionMindClient.analyzeAttributes(imageBytes);
    // for i in [0, min(qualified.size(), attrs.size())): emotionMap[i] = attrs[i].emotion
    Map<Integer, Map<String,Object>> emotionMap = matchEmotionsByIndex(qualified.size(), attrs);

    // Phase 3: Annotated image (copy fullImage, draw bboxes, save to data/annotated/...)
    String annotatedUrl = generateAnnotatedImage(fullImage, qualified, ci);

    // Phase 4: Process each face (crop + register + emotion)
    List<FaceRecord> batch = new ArrayList<>();
    for (int i = 0; i < qualified.size(); i++) {
        FaceRecord fr = createFaceRecord(ci, qualified.get(i));
        cropFace(fullImage, qualified.get(i).getBbox(), fr, ci);
        registerFace(fr);
        if (emotionMap.containsKey(i)) saveEmotion(fr, emotionMap.get(i));
        batch.add(fr);
    }

    // Phase 5: Persist with error isolation
    ci.setFaceDetectedCount(qualified.size());
    ci.setEmotionRecognizedCount(emotionMap.size());
    ci.setAnnotatedImageUrl(annotatedUrl);
    for (FaceRecord fr : batch) {
        try { faceRecordRepository.save(fr); }
        catch (Exception e) { fr.setErrorMessage(e.getMessage()); faceRecordRepository.save(fr); }
    }
    markCompleted(ci);
}
```

---

### Task 5: Annotated image generation helper + error handling

Add private methods to FaceProcessingPipeline for drawing bboxes on a copied BufferedImage, saving to `data/annotated/` mirror structure.

---

### Task 6: PipelineProgressService — image-level counting

No structural change needed — `onStatusChange` already fires once per image from `markCompleted()`.

---

### Task 7: Retry API

**Files:**
- Modify: `PipelineStatusController.java`

Add `POST /admin/pipeline/retry-failed-faces`:
```java
@PostMapping("/retry-failed-faces")
public ResponseEntity<?> retryFailedFaces() {
    // Find face_records with error_message IS NOT NULL
    // Group by class_image_id
    // For each image: if COMPLETED → re-process only failed faces
    //                  if FAILED → reset image to PENDING
}
```

---

### Task 8: Gitignore update

Add `data/annotated/` to `.gitignore`.

---

### Task 9: Unit tests

Update `FaceProcessingPipelineTest.java` to cover multi-face scenarios.
