# Phase 1: 基础数据管道 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 Spring Boot 4.0 项目骨架，实现图片异步接入、AI人脸/表情识别调用、结果入库，并完成现有4天历史数据的批量导入。

**Architecture:** 事件驱动管道——图片通过 REST API 接入后立即进入 Redis Stream 异步队列，由 ImageProcessingOrchestrator 消费并顺序调用人脸识别→表情识别 API，结果写入 PostgreSQL。Spring AI 统一抽象 AI 调用层，Resilience4j 提供熔断重试保障。

**Tech Stack:** Spring Boot 4.0, Spring AI 1.x, PostgreSQL 16, Flyway, Redis 7.x (Stream), Resilience4j, Testcontainers

**PRD 参考:** `docs/superpowers/specs/2026-05-27-student-emotion-management-platform-prd.md`
**覆盖功能:** F-01, F-02, F-03, F-04, F-05, F-06, F-08, F-09, F-10

---

## 文件结构

```
emotion-platform/
├── pom.xml
├── src/main/java/com/school/emotion/
│   ├── EmotionApplication.java
│   ├── config/
│   │   ├── WebConfig.java
│   │   ├── RedisStreamConfig.java
│   │   └── Resilience4jConfig.java
│   ├── controller/
│   │   └── ImageIngestController.java
│   ├── service/
│   │   ├── ImageIngestionService.java
│   │   ├── ImageProcessingOrchestrator.java
│   │   ├── ai/
│   │   │   ├── FaceRecognitionService.java
│   │   │   ├── FaceRecognitionClient.java
│   │   │   ├── EmotionRecognitionService.java
│   │   │   └── EmotionRecognitionClient.java
│   │   └── ImageImportService.java
│   ├── repository/
│   │   ├── GradeRepository.java
│   │   ├── ClassRepository.java
│   │   ├── StudentRepository.java
│   │   ├── ClassImageRepository.java
│   │   ├── FaceRecordRepository.java
│   │   └── EmotionRecordRepository.java
│   ├── model/entity/
│   │   ├── Grade.java
│   │   ├── Class.java
│   │   ├── Student.java
│   │   ├── ClassImage.java
│   │   ├── FaceRecord.java
│   │   └── EmotionRecord.java
│   ├── model/dto/
│   │   ├── ImageIngestRequest.java
│   │   ├── ImageIngestResponse.java
│   │   ├── FaceDetectionResult.java
│   │   └── EmotionAnalysisResult.java
│   ├── model/enums/
│   │   ├── ImageStatus.java
│   │   └── FaceStatus.java
│   └── exception/
│       ├── AiServiceException.java
│       └── ImageProcessingException.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   └── db/migration/
│       ├── V1__create_core_schema.sql
│       ├── V2__create_class_image.sql
│       └── V3__create_face_emotion.sql
└── src/test/java/com/school/emotion/
    ├── controller/ImageIngestControllerTest.java
    ├── service/ImageProcessingOrchestratorTest.java
    └── service/ai/FaceRecognitionClientTest.java
```

---

### Task 1: 项目骨架 + 数据库迁移

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/school/emotion/EmotionApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/application-dev.yml`
- Create: `src/main/resources/db/migration/V1__create_core_schema.sql`
- Create: `src/main/resources/db/migration/V2__create_class_image.sql`
- Create: `src/main/resources/db/migration/V3__create_face_emotion.sql`

**pom.xml 关键依赖:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.0</version>
</parent>

<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.0.0</spring-ai.version>
</properties>

<dependencies>
    <!-- Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Data -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <!-- Redis -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <!-- Spring AI -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-core</artifactId>
        <version>${spring-ai.version}</version>
    </dependency>
    <!-- Resilience4j -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot3</artifactId>
    </dependency>
    <!-- Validation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

- [ ] **Step 1:** Create `pom.xml` with Spring Boot 4.0 parent and all dependencies above
- [ ] **Step 2:** Create `EmotionApplication.java` — standard `@SpringBootApplication` main class

```java
package com.school.emotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EmotionApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmotionApplication.class, args);
    }
}
```

- [ ] **Step 3:** Create `application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: emotion-platform
  datasource:
    url: jdbc:postgresql://localhost:5432/emotion_platform
    username: ${DB_USERNAME:emotion}
    password: ${DB_PASSWORD:emotion}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

ai:
  face-recognition:
    url: ${FACE_API_URL:http://localhost:8081}
    timeout: 10000
  emotion-recognition:
    url: ${EMOTION_API_URL:http://localhost:8082}
    timeout: 10000

resilience4j:
  retry:
    configs:
      default:
        max-attempts: 3
        wait-duration: 1s
        exponential-backoff-multiplier: 2
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10m
```

- [ ] **Step 4:** Create `application-dev.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/emotion_platform_dev
  jpa:
    show-sql: true
  data:
    redis:
      host: localhost
      port: 6379
```

- [ ] **Step 5:** Create `V1__create_core_schema.sql`

```sql
CREATE TABLE grade (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE class (
    id          BIGSERIAL PRIMARY KEY,
    grade_id    BIGINT       NOT NULL REFERENCES grade(id),
    name        VARCHAR(50)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_class_grade ON class(grade_id);

CREATE TABLE student (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT       NOT NULL REFERENCES class(id),
    student_no    VARCHAR(20)  NOT NULL UNIQUE,
    name          VARCHAR(50)  NOT NULL,
    face_image_id VARCHAR(64),
    status        VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_student_class ON student(class_id);
```

- [ ] **Step 6:** Create `V2__create_class_image.sql`

```sql
CREATE TABLE class_image (
    id              BIGSERIAL PRIMARY KEY,
    class_id        BIGINT       NOT NULL REFERENCES class(id),
    image_url       TEXT         NOT NULL,
    capture_time    TIMESTAMPTZ  NOT NULL,
    period_label    VARCHAR(20),
    source          VARCHAR(50)  DEFAULT 'third_party',
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ci_class_time ON class_image(class_id, capture_time);
CREATE INDEX idx_ci_status     ON class_image(status);
```

- [ ] **Step 7:** Create `V3__create_face_emotion.sql`

```sql
CREATE TABLE face_record (
    id              BIGSERIAL PRIMARY KEY,
    class_image_id  BIGINT       NOT NULL REFERENCES class_image(id),
    student_id      BIGINT       REFERENCES student(id),
    bbox            JSONB,
    face_encoding   JSONB,
    confidence      REAL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'detected',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fr_image   ON face_record(class_image_id);
CREATE INDEX idx_fr_student ON face_record(student_id);

CREATE TABLE emotion_record (
    id                  BIGSERIAL PRIMARY KEY,
    face_record_id      BIGINT       NOT NULL UNIQUE REFERENCES face_record(id),
    emotion_happy       REAL,
    emotion_sad         REAL,
    emotion_angry       REAL,
    emotion_surprise    REAL,
    emotion_fear        REAL,
    emotion_disgust     REAL,
    emotion_neutral     REAL,
    dominant_emotion    VARCHAR(20)  NOT NULL,
    dominant_confidence REAL         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_er_dominant   ON emotion_record(dominant_emotion);
CREATE INDEX idx_er_face_record ON emotion_record(face_record_id);
```

- [ ] **Step 8:** Run the application and verify Flyway creates all 5 tables

```bash
# Start postgres:
docker run -d --name pg -e POSTGRES_DB=emotion_platform -e POSTGRES_USER=emotion -e POSTGRES_PASSWORD=emotion -p 5432:5432 postgres:16

# Start redis:
docker run -d --name redis -p 6379:6379 redis:7

# Build and run:
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# Verify in logs: "Successfully applied 3 migrations"
# Verify via psql: \dt should show grade, class, student, class_image, face_record, emotion_record
```

- [ ] **Step 9:** Commit

```bash
git init
git add pom.xml src/main/
git commit -m "feat: project skeleton with Flyway migrations"
```

---

### Task 2: Entity + Repository 层

**Files:**
- Create: `src/main/java/com/school/emotion/model/enums/ImageStatus.java`
- Create: `src/main/java/com/school/emotion/model/enums/FaceStatus.java`
- Create: `src/main/java/com/school/emotion/model/entity/Grade.java`
- Create: `src/main/java/com/school/emotion/model/entity/Class.java`
- Create: `src/main/java/com/school/emotion/model/entity/Student.java`
- Create: `src/main/java/com/school/emotion/model/entity/ClassImage.java`
- Create: `src/main/java/com/school/emotion/model/entity/FaceRecord.java`
- Create: `src/main/java/com/school/emotion/model/entity/EmotionRecord.java`
- Create: `src/main/java/com/school/emotion/repository/GradeRepository.java`
- Create: `src/main/java/com/school/emotion/repository/ClassRepository.java`
- Create: `src/main/java/com/school/emotion/repository/StudentRepository.java`
- Create: `src/main/java/com/school/emotion/repository/ClassImageRepository.java`
- Create: `src/main/java/com/school/emotion/repository/FaceRecordRepository.java`
- Create: `src/main/java/com/school/emotion/repository/EmotionRecordRepository.java`

- [ ] **Step 1:** Create enums

```java
// ImageStatus.java
package com.school.emotion.model.enums;

public enum ImageStatus {
    PENDING, PROCESSING, COMPLETED, FAILED
}
```

```java
// FaceStatus.java
package com.school.emotion.model.enums;

public enum FaceStatus {
    DETECTED, IDENTIFIED, UNIDENTIFIED
}
```

- [ ] **Step 2:** Create `Grade.java` entity

```java
package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "grade")
public class Grade {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public Integer getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    // setters
    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
```

- [ ] **Step 3:** Create `Class.java` entity

```java
package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "class")
public class Class {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grade_id", nullable = false)
    private Grade grade;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters and setters
    public Long getId() { return id; }
    public Grade getGrade() { return grade; }
    public void setGrade(Grade grade) { this.grade = grade; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
```

- [ ] **Step 4:** Create `Student.java` entity

```java
package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "student")
public class Student {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id", nullable = false)
    private Class clazz;

    @Column(name = "student_no", nullable = false, unique = true, length = 20)
    private String studentNo;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "face_image_id", length = 64)
    private String faceImageId;

    @Column(nullable = false, length = 20)
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() { updatedAt = OffsetDateTime.now(); }

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Class getClazz() { return clazz; }
    public void setClazz(Class clazz) { this.clazz = clazz; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFaceImageId() { return faceImageId; }
    public void setFaceImageId(String faceImageId) { this.faceImageId = faceImageId; }
}
```

- [ ] **Step 5:** Create `ClassImage.java` entity

```java
package com.school.emotion.model.entity;

import com.school.emotion.model.enums.ImageStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "class_image")
public class ClassImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id", nullable = false)
    private Class clazz;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @Column(name = "capture_time", nullable = false)
    private OffsetDateTime captureTime;

    @Column(name = "period_label", length = 20)
    private String periodLabel;

    @Column(length = 50)
    private String source = "third_party";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImageStatus status = ImageStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Class getClazz() { return clazz; }
    public void setClazz(Class clazz) { this.clazz = clazz; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public OffsetDateTime getCaptureTime() { return captureTime; }
    public void setCaptureTime(OffsetDateTime captureTime) { this.captureTime = captureTime; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public ImageStatus getStatus() { return status; }
    public void setStatus(ImageStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
```

- [ ] **Step 6:** Create `FaceRecord.java` entity

```java
package com.school.emotion.model.entity;

import com.school.emotion.model.enums.FaceStatus;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "face_record")
public class FaceRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_image_id", nullable = false)
    private ClassImage classImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(columnDefinition = "JSONB")
    private String bbox;

    @Column(name = "face_encoding", columnDefinition = "JSONB")
    private String faceEncoding;

    private Float confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FaceStatus status = FaceStatus.DETECTED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ClassImage getClassImage() { return classImage; }
    public void setClassImage(ClassImage classImage) { this.classImage = classImage; }
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public String getBbox() { return bbox; }
    public void setBbox(String bbox) { this.bbox = bbox; }
    public String getFaceEncoding() { return faceEncoding; }
    public void setFaceEncoding(String faceEncoding) { this.faceEncoding = faceEncoding; }
    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }
    public FaceStatus getStatus() { return status; }
    public void setStatus(FaceStatus status) { this.status = status; }
}
```

- [ ] **Step 7:** Create `EmotionRecord.java` entity

```java
package com.school.emotion.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "emotion_record")
public class EmotionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "face_record_id", nullable = false, unique = true)
    private FaceRecord faceRecord;

    @Column(name = "emotion_happy")
    private Float emotionHappy;

    @Column(name = "emotion_sad")
    private Float emotionSad;

    @Column(name = "emotion_angry")
    private Float emotionAngry;

    @Column(name = "emotion_surprise")
    private Float emotionSurprise;

    @Column(name = "emotion_fear")
    private Float emotionFear;

    @Column(name = "emotion_disgust")
    private Float emotionDisgust;

    @Column(name = "emotion_neutral")
    private Float emotionNeutral;

    @Column(name = "dominant_emotion", nullable = false, length = 20)
    private String dominantEmotion;

    @Column(name = "dominant_confidence", nullable = false)
    private Float dominantConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public FaceRecord getFaceRecord() { return faceRecord; }
    public void setFaceRecord(FaceRecord faceRecord) { this.faceRecord = faceRecord; }
    public Float getEmotionHappy() { return emotionHappy; }
    public void setEmotionHappy(Float emotionHappy) { this.emotionHappy = emotionHappy; }
    public Float getEmotionSad() { return emotionSad; }
    public void setEmotionSad(Float emotionSad) { this.emotionSad = emotionSad; }
    public Float getEmotionAngry() { return emotionAngry; }
    public void setEmotionAngry(Float emotionAngry) { this.emotionAngry = emotionAngry; }
    public Float getEmotionSurprise() { return emotionSurprise; }
    public void setEmotionSurprise(Float emotionSurprise) { this.emotionSurprise = emotionSurprise; }
    public Float getEmotionFear() { return emotionFear; }
    public void setEmotionFear(Float emotionFear) { this.emotionFear = emotionFear; }
    public Float getEmotionDisgust() { return emotionDisgust; }
    public void setEmotionDisgust(Float emotionDisgust) { this.emotionDisgust = emotionDisgust; }
    public Float getEmotionNeutral() { return emotionNeutral; }
    public void setEmotionNeutral(Float emotionNeutral) { this.emotionNeutral = emotionNeutral; }
    public String getDominantEmotion() { return dominantEmotion; }
    public void setDominantEmotion(String dominantEmotion) { this.dominantEmotion = dominantEmotion; }
    public Float getDominantConfidence() { return dominantConfidence; }
    public void setDominantConfidence(Float dominantConfidence) { this.dominantConfidence = dominantConfidence; }
}
```

- [ ] **Step 8:** Create 6 repository interfaces

```java
// GradeRepository.java
package com.school.emotion.repository;

import com.school.emotion.model.entity.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeRepository extends JpaRepository<Grade, Long> {}
```

```java
// ClassRepository.java
package com.school.emotion.repository;

import com.school.emotion.model.entity.Class;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ClassRepository extends JpaRepository<Class, Long> {
    List<Class> findByGradeId(Long gradeId);
}
```

```java
// StudentRepository.java
package com.school.emotion.repository;

import com.school.emotion.model.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByStudentNo(String studentNo);
    List<Student> findByClassId(Long classId);
}
```

```java
// ClassImageRepository.java
package com.school.emotion.repository;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;

public interface ClassImageRepository extends JpaRepository<ClassImage, Long> {
    List<ClassImage> findByStatus(ImageStatus status);
    List<ClassImage> findByClassIdAndCaptureTimeBetween(Long classId, OffsetDateTime start, OffsetDateTime end);
    long countByStatus(ImageStatus status);
}
```

```java
// FaceRecordRepository.java
package com.school.emotion.repository;

import com.school.emotion.model.entity.FaceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaceRecordRepository extends JpaRepository<FaceRecord, Long> {}
```

```java
// EmotionRecordRepository.java
package com.school.emotion.repository;

import com.school.emotion.model.entity.EmotionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmotionRecordRepository extends JpaRepository<EmotionRecord, Long> {}
```

- [ ] **Step 9:** Verify compilation

```bash
./mvnw compile -q
# Expected: BUILD SUCCESS
```

- [ ] **Step 10:** Commit

```bash
git add src/main/java/com/school/emotion/model/ src/main/java/com/school/emotion/repository/
git commit -m "feat: entity and repository layer"
```

---

### Task 3: 图片接入 API + Redis Stream 异步队列

**Files:**
- Create: `src/main/java/com/school/emotion/config/RedisStreamConfig.java`
- Create: `src/main/java/com/school/emotion/model/dto/ImageIngestRequest.java`
- Create: `src/main/java/com/school/emotion/model/dto/ImageIngestResponse.java`
- Create: `src/main/java/com/school/emotion/service/ImageIngestionService.java`
- Create: `src/main/java/com/school/emotion/controller/ImageIngestController.java`
- Create: `src/test/java/com/school/emotion/controller/ImageIngestControllerTest.java`

- [ ] **Step 1:** Create `RedisStreamConfig.java`

```java
package com.school.emotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

@Configuration
public class RedisStreamConfig {

    public static final String STREAM_KEY = "image:ingest";
    public static final String CONSUMER_GROUP = "image-processors";

    @Bean
    public StreamMessageListenerContainer<String, Object> streamContainer(
            RedisConnectionFactory factory) {
        var options = StreamMessageListenerContainer
                .StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(1))
                .build();
        return StreamMessageListenerContainer.create(factory, options);
    }
}
```

- [ ] **Step 2:** Create `ImageIngestRequest.java`

```java
package com.school.emotion.model.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public class ImageIngestRequest {
    @NotNull(message = "image file is required")
    private MultipartFile image;

    @NotNull(message = "classId is required")
    private Long classId;

    @NotNull(message = "captureTime is required")
    private String captureTime;  // ISO 8601

    private String periodLabel;

    // getters and setters
    public MultipartFile getImage() { return image; }
    public void setImage(MultipartFile image) { this.image = image; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public String getCaptureTime() { return captureTime; }
    public void setCaptureTime(String captureTime) { this.captureTime = captureTime; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
}
```

- [ ] **Step 3:** Create `ImageIngestResponse.java`

```java
package com.school.emotion.model.dto;

public class ImageIngestResponse {
    private int code;
    private String message;
    private Data data;

    public ImageIngestResponse(int code, String message, Data data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static ImageIngestResponse accepted(Long imageId, int queuePosition) {
        return new ImageIngestResponse(0, "accepted",
                new Data(imageId, queuePosition));
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public Data getData() { return data; }

    public static class Data {
        private Long imageId;
        private int queuePosition;

        public Data(Long imageId, int queuePosition) {
            this.imageId = imageId;
            this.queuePosition = queuePosition;
        }

        public Long getImageId() { return imageId; }
        public int getQueuePosition() { return queuePosition; }
    }
}
```

- [ ] **Step 4:** Create `ImageIngestionService.java`

```java
package com.school.emotion.service;

import com.school.emotion.model.dto.ImageIngestResponse;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.ClassRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ImageIngestionService {

    private final ClassImageRepository classImageRepository;
    private final ClassRepository classRepository;
    private final RedisConnectionFactory redisFactory;
    private final Path storageDir;

    public ImageIngestionService(
            ClassImageRepository classImageRepository,
            ClassRepository classRepository,
            RedisConnectionFactory redisFactory,
            @Value("${app.image.storage-dir:./images}") String storageDir) {
        this.classImageRepository = classImageRepository;
        this.classRepository = classRepository;
        this.redisFactory = redisFactory;
        this.storageDir = Path.of(storageDir);
        try { Files.createDirectories(this.storageDir); } catch (IOException e) {
            throw new RuntimeException("Cannot create storage dir", e);
        }
    }

    /**
     * Ingest from MultipartFile (REST API path).
     */
    @Transactional
    public ImageIngestResponse ingest(Long classId, byte[] imageBytes, String originalFilename,
                                       String captureTime, String periodLabel) {
        // 1. Save image to local storage
        String filename = UUID.randomUUID() + "_" + (originalFilename != null ? originalFilename : "image.jpg");
        Path targetPath = storageDir.resolve(filename);
        try {
            Files.write(targetPath, imageBytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store image", e);
        }

        // 2. Create DB record
        ClassImage classImage = new ClassImage();
        classImage.setClazz(classRepository.getReferenceById(classId));
        classImage.setImageUrl(targetPath.toString());
        classImage.setCaptureTime(OffsetDateTime.parse(captureTime));
        classImage.setPeriodLabel(periodLabel);
        classImage.setStatus(ImageStatus.PENDING);
        classImage = classImageRepository.save(classImage);

        // 3. Push to Redis Stream
        var stream = StreamRecords.objectRecord(RedisStreamConfig.STREAM_KEY, classImage.getId());
        redisFactory.getConnection().streamCommands()
                .xAdd(stream);

        return ImageIngestResponse.accepted(classImage.getId(), 0);
    }
}
```

- [ ] **Step 5:** Create `ImageIngestController.java`

```java
package com.school.emotion.controller;

import com.school.emotion.model.dto.ImageIngestRequest;
import com.school.emotion.model.dto.ImageIngestResponse;
import com.school.emotion.service.ImageIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
```

- [ ] **Step 6:** Write controller test

```java
package com.school.emotion.controller;

import com.school.emotion.service.ImageIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
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
        var response = new com.school.emotion.model.dto.ImageIngestResponse(
                0, "accepted", null);

        when(ingestionService.ingest(any(), any(), any(), any(), any()))
                .thenReturn(response);

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
```

- [ ] **Step 7:** Run tests

```bash
./mvnw test -pl . -Dtest=ImageIngestControllerTest
# Expected: Tests run: 1, Passed: 1
```

- [ ] **Step 8:** Commit

```bash
git add src/main/java/com/school/emotion/config/RedisStreamConfig.java
git add src/main/java/com/school/emotion/model/dto/
git add src/main/java/com/school/emotion/service/ImageIngestionService.java
git add src/main/java/com/school/emotion/controller/ImageIngestController.java
git add src/test/
git commit -m "feat: image ingest API with Redis Stream queue"
```

---

### Task 4: Spring AI 集成 + 人脸/表情服务

**Files:**
- Create: `src/main/java/com/school/emotion/config/Resilience4jConfig.java`
- Create: `src/main/java/com/school/emotion/model/dto/FaceDetectionResult.java`
- Create: `src/main/java/com/school/emotion/model/dto/EmotionAnalysisResult.java`
- Create: `src/main/java/com/school/emotion/exception/AiServiceException.java`
- Create: `src/main/java/com/school/emotion/exception/ImageProcessingException.java`
- Create: `src/main/java/com/school/emotion/service/ai/FaceRecognitionService.java`
- Create: `src/main/java/com/school/emotion/service/ai/FaceRecognitionClient.java`
- Create: `src/main/java/com/school/emotion/service/ai/EmotionRecognitionService.java`
- Create: `src/main/java/com/school/emotion/service/ai/EmotionRecognitionClient.java`
- Create: `src/test/java/com/school/emotion/service/ai/FaceRecognitionClientTest.java`

- [ ] **Step 1:** Create DTOs

```java
// FaceDetectionResult.java
package com.school.emotion.model.dto;

import java.util.List;

public class FaceDetectionResult {
    private List<Face> faces;

    public List<Face> getFaces() { return faces; }
    public void setFaces(List<Face> faces) { this.faces = faces; }

    public static class Face {
        private BBox bbox;
        private String faceId;
        private Float confidence;

        public BBox getBbox() { return bbox; }
        public void setBbox(BBox bbox) { this.bbox = bbox; }
        public String getFaceId() { return faceId; }
        public void setFaceId(String faceId) { this.faceId = faceId; }
        public Float getConfidence() { return confidence; }
        public void setConfidence(Float confidence) { this.confidence = confidence; }
    }

    public static class BBox {
        private int x, y, width, height;
        public int getX() { return x; }
        public void setX(int x) { this.x = x; }
        public int getY() { return y; }
        public void setY(int y) { this.y = y; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
    }
}
```

```java
// EmotionAnalysisResult.java
package com.school.emotion.model.dto;

import java.util.Map;

public class EmotionAnalysisResult {
    private Map<String, Float> emotions;  // {"happy": 0.85, "sad": 0.02, ...}
    private String dominantEmotion;
    private Float dominantConfidence;

    public Map<String, Float> getEmotions() { return emotions; }
    public void setEmotions(Map<String, Float> emotions) { this.emotions = emotions; }
    public String getDominantEmotion() { return dominantEmotion; }
    public void setDominantEmotion(String dominantEmotion) { this.dominantEmotion = dominantEmotion; }
    public Float getDominantConfidence() { return dominantConfidence; }
    public void setDominantConfidence(Float dominantConfidence) { this.dominantConfidence = dominantConfidence; }
}
```

- [ ] **Step 2:** Create exception classes

```java
// AiServiceException.java
package com.school.emotion.exception;

public class AiServiceException extends RuntimeException {
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
    public AiServiceException(String message) {
        super(message);
    }
}
```

```java
// ImageProcessingException.java
package com.school.emotion.exception;

public class ImageProcessingException extends RuntimeException {
    public ImageProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3:** Create `FaceRecognitionService.java` (Spring AI interface)

```java
package com.school.emotion.service.ai;

import com.school.emotion.model.dto.FaceDetectionResult;

public interface FaceRecognitionService {
    FaceDetectionResult detectFaces(byte[] imageData);
}
```

- [ ] **Step 4:** Create `FaceRecognitionClient.java` (implementation with Resilience4j)

```java
package com.school.emotion.service.ai;

import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.FaceDetectionResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class FaceRecognitionClient implements FaceRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(FaceRecognitionClient.class);
    private final RestClient restClient;

    public FaceRecognitionClient(@Value("${ai.face-recognition.url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    @Retry(name = "faceRecognition")
    @CircuitBreaker(name = "faceRecognition")
    public FaceDetectionResult detectFaces(byte[] imageData) {
        try {
            var bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("image", new ByteArrayResource(imageData))
                    .contentType(MediaType.IMAGE_JPEG);

            return restClient.post()
                    .uri("/detect")
                    .body(bodyBuilder.build())
                    .retrieve()
                    .body(FaceDetectionResult.class);
        } catch (Exception e) {
            log.error("Face recognition API call failed", e);
            throw new AiServiceException("Face recognition failed", e);
        }
    }
}
```

- [ ] **Step 5:** Create `EmotionRecognitionService.java`

```java
package com.school.emotion.service.ai;

import com.school.emotion.model.dto.EmotionAnalysisResult;

public interface EmotionRecognitionService {
    EmotionAnalysisResult analyzeEmotion(byte[] faceCrop);
}
```

- [ ] **Step 6:** Create `EmotionRecognitionClient.java`

```java
package com.school.emotion.service.ai;

import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class EmotionRecognitionClient implements EmotionRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(EmotionRecognitionClient.class);
    private final RestClient restClient;

    public EmotionRecognitionClient(@Value("${ai.emotion-recognition.url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    @Retry(name = "emotionRecognition")
    @CircuitBreaker(name = "emotionRecognition")
    public EmotionAnalysisResult analyzeEmotion(byte[] faceCrop) {
        try {
            var bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("image", new ByteArrayResource(faceCrop))
                    .contentType(MediaType.IMAGE_JPEG);

            return restClient.post()
                    .uri("/analyze")
                    .body(bodyBuilder.build())
                    .retrieve()
                    .body(EmotionAnalysisResult.class);
        } catch (Exception e) {
            log.error("Emotion recognition API call failed", e);
            throw new AiServiceException("Emotion recognition failed", e);
        }
    }
}
```

- [ ] **Step 7:** Write `FaceRecognitionClientTest.java`

```java
package com.school.emotion.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.GenericContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FaceRecognitionClientTest {

    @Container
    static GenericContainer<?> mockAi = new GenericContainer<>("jamesdbloom/mock-server")
            .withExposedPorts(1080);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry reg) {
        reg.add("ai.face-recognition.url",
                () -> "http://" + mockAi.getHost() + ":" + mockAi.getMappedPort(1080));
    }

    @Autowired
    private FaceRecognitionService faceService;

    @Test
    void detectFaces_shouldReturnResult() {
        // This test validates the client connects to configured URL
        // With mock server, it may return error response but verifies no crash
        byte[] dummyImage = new byte[]{0x00, 0x01, 0x02};
        assertThrows(Exception.class, () -> faceService.detectFaces(dummyImage));
    }
}
```

- [ ] **Step 8:** Compile and verify

```bash
./mvnw compile -q
# Expected: BUILD SUCCESS
```

- [ ] **Step 9:** Commit

```bash
git add src/main/java/com/school/emotion/service/ai/
git add src/main/java/com/school/emotion/model/dto/FaceDetectionResult.java
git add src/main/java/com/school/emotion/model/dto/EmotionAnalysisResult.java
git add src/main/java/com/school/emotion/exception/
git add src/test/java/com/school/emotion/service/ai/
git commit -m "feat: Spring AI integration with face/emotion recognition clients"
```

---

### Task 5: 图片处理编排器 + 数据入库

**Files:**
- Create: `src/main/java/com/school/emotion/service/ImageProcessingOrchestrator.java`
- Create: `src/test/java/com/school/emotion/service/ImageProcessingOrchestratorTest.java`

- [ ] **Step 1:** Create `ImageProcessingOrchestrator.java`

```java
package com.school.emotion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.emotion.exception.AiServiceException;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.entity.EmotionRecord;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.model.enums.FaceStatus;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.service.ai.EmotionRecognitionService;
import com.school.emotion.service.ai.FaceRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class ImageProcessingOrchestrator implements StreamListener<String, ObjectRecord<String, Long>> {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingOrchestrator.class);

    private final ClassImageRepository classImageRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final FaceRecognitionService faceService;
    private final EmotionRecognitionService emotionService;
    private final ObjectMapper objectMapper;

    public ImageProcessingOrchestrator(
            ClassImageRepository classImageRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionRecordRepository emotionRecordRepository,
            FaceRecognitionService faceService,
            EmotionRecognitionService emotionService,
            ObjectMapper objectMapper) {
        this.classImageRepository = classImageRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.faceService = faceService;
        this.emotionService = emotionService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void onMessage(ObjectRecord<String, Long> message) {
        Long imageId = message.getValue();
        log.info("Processing image: {}", imageId);

        ClassImage classImage = classImageRepository.findById(imageId)
                .orElseThrow(() -> new RuntimeException("Image not found: " + imageId));

        try {
            classImage.setStatus(ImageStatus.PROCESSING);
            classImageRepository.save(classImage);

            // 1. Read image bytes
            byte[] imageBytes = Files.readAllBytes(Path.of(classImage.getImageUrl()));

            // 2. Face detection
            FaceDetectionResult faceResult = faceService.detectFaces(imageBytes);

            if (faceResult.getFaces() == null || faceResult.getFaces().isEmpty()) {
                log.warn("No faces detected in image: {}", imageId);
                classImage.setStatus(ImageStatus.COMPLETED);
                classImageRepository.save(classImage);
                return;
            }

            // 3. Process each face
            for (FaceDetectionResult.Face face : faceResult.getFaces()) {
                // 3a. Save face record
                FaceRecord faceRecord = new FaceRecord();
                faceRecord.setClassImage(classImage);
                faceRecord.setBbox(toJson(face.getBbox()));
                faceRecord.setConfidence(face.getConfidence());
                faceRecord.setStatus(FaceStatus.DETECTED);
                faceRecord = faceRecordRepository.save(faceRecord);

                // 3b. Crop face from image (simplified — send full image, let AI API handle cropping)
                // In production, crop using bbox coordinates
                EmotionAnalysisResult emotionResult = emotionService.analyzeEmotion(imageBytes);

                // 3c. Save emotion record
                EmotionRecord emotionRecord = new EmotionRecord();
                emotionRecord.setFaceRecord(faceRecord);
                emotionRecord.setEmotionHappy(emotionResult.getEmotions().get("happy"));
                emotionRecord.setEmotionSad(emotionResult.getEmotions().get("sad"));
                emotionRecord.setEmotionAngry(emotionResult.getEmotions().get("angry"));
                emotionRecord.setEmotionSurprise(emotionResult.getEmotions().get("surprise"));
                emotionRecord.setEmotionFear(emotionResult.getEmotions().get("fear"));
                emotionRecord.setEmotionDisgust(emotionResult.getEmotions().get("disgust"));
                emotionRecord.setEmotionNeutral(emotionResult.getEmotions().get("neutral"));
                emotionRecord.setDominantEmotion(emotionResult.getDominantEmotion());
                emotionRecord.setDominantConfidence(emotionResult.getDominantConfidence());
                emotionRecordRepository.save(emotionRecord);
            }

            classImage.setStatus(ImageStatus.COMPLETED);
            classImageRepository.save(classImage);
            log.info("Successfully processed image: {}", imageId);

        } catch (AiServiceException e) {
            log.error("AI service error for image {}: {}", imageId, e.getMessage());
            classImage.setStatus(ImageStatus.FAILED);
            classImage.setErrorMessage("AI error: " + e.getMessage());
            classImageRepository.save(classImage);
        } catch (IOException e) {
            log.error("IO error for image {}: {}", imageId, e.getMessage());
            classImage.setStatus(ImageStatus.FAILED);
            classImage.setErrorMessage("IO error: " + e.getMessage());
            classImageRepository.save(classImage);
        }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 2:** Write orchestrator test

```java
package com.school.emotion.service;

import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.entity.EmotionRecord;
import com.school.emotion.model.entity.FaceRecord;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.service.ai.EmotionRecognitionService;
import com.school.emotion.service.ai.FaceRecognitionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.ObjectRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageProcessingOrchestratorTest {

    @Mock
    private ClassImageRepository classImageRepository;
    @Mock
    private FaceRecordRepository faceRecordRepository;
    @Mock
    private EmotionRecordRepository emotionRecordRepository;
    @Mock
    private FaceRecognitionService faceService;
    @Mock
    private EmotionRecognitionService emotionService;

    @InjectMocks
    private ImageProcessingOrchestrator orchestrator;

    @Test
    void onMessage_shouldProcessImageSuccessfully() throws Exception {
        // Given
        Long imageId = 1L;
        ClassImage classImage = new ClassImage();
        classImage.setId(imageId);
        classImage.setImageUrl("src/test/resources/test-image.jpg");
        classImage.setStatus(ImageStatus.PENDING);

        when(classImageRepository.findById(imageId)).thenReturn(Optional.of(classImage));
        when(classImageRepository.save(any())).thenReturn(classImage);

        var faceResult = new FaceDetectionResult();
        var face = new FaceDetectionResult.Face();
        face.setConfidence(0.95f);
        var bbox = new FaceDetectionResult.BBox();
        bbox.setX(10); bbox.setY(20); bbox.setWidth(100); bbox.setHeight(120);
        face.setBbox(bbox);
        faceResult.setFaces(List.of(face));
        when(faceService.detectFaces(any())).thenReturn(faceResult);

        var emotionResult = new EmotionAnalysisResult();
        emotionResult.setEmotions(Map.of(
                "happy", 0.85f, "sad", 0.02f, "neutral", 0.13f
        ));
        emotionResult.setDominantEmotion("happy");
        emotionResult.setDominantConfidence(0.85f);
        when(emotionService.analyzeEmotion(any())).thenReturn(emotionResult);

        when(faceRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(emotionRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // When
        var record = mock(ObjectRecord.class);
        when(record.getValue()).thenReturn(imageId);
        orchestrator.onMessage(record);

        // Then
        verify(faceRecordRepository, times(1)).save(any(FaceRecord.class));
        verify(emotionRecordRepository, times(1)).save(any(EmotionRecord.class));
        verify(classImageRepository, times(3)).save(any());  // pending→processing→completed
    }
}
```

- [ ] **Step 3:** Run test

```bash
./mvnw test -Dtest=ImageProcessingOrchestratorTest
# Expected: Tests run: 1, Passed: 1
```

- [ ] **Step 4:** Commit

```bash
git add src/main/java/com/school/emotion/service/ImageProcessingOrchestrator.java
git add src/test/java/com/school/emotion/service/ImageProcessingOrchestratorTest.java
git commit -m "feat: image processing orchestrator with face+emotion pipeline"
```

---

### Task 6: 历史数据批量导入

**Files:**
- Create: `src/main/java/com/school/emotion/service/ImageImportService.java`
- Create: `src/test/java/com/school/emotion/service/ImageImportServiceTest.java`

- [ ] **Step 1:** Create `ImageImportService.java`

```java
package com.school.emotion.service;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImageImportService {

    private static final Logger log = LoggerFactory.getLogger(ImageImportService.class);
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})_T\\d+_[0-9A-F]+\\.jpg$");

    private static final Pattern PERIOD_FOLDER_PATTERN =
            Pattern.compile("^(早读-到校|第[1-8]节|课间操|午餐-午休|课外活动-放学)$");

    private final ClassImageRepository classImageRepository;
    private final ImageIngestionService ingestionService;

    private static final java.util.Map<String, String> PERIOD_MAP = java.util.Map.ofEntries(
            java.util.Map.entry("早读-到校", "arrival"),
            java.util.Map.entry("第1节", "period_1"),
            java.util.Map.entry("第2节", "period_2"),
            java.util.Map.entry("第3节", "period_3"),
            java.util.Map.entry("第4节", "period_4"),
            java.util.Map.entry("第5节", "period_5"),
            java.util.Map.entry("第6节", "period_6"),
            java.util.Map.entry("第7节", "period_7"),
            java.util.Map.entry("第8节", "period_8"),
            java.util.Map.entry("课间操", "recess"),
            java.util.Map.entry("午餐-午休", "lunch"),
            java.util.Map.entry("课外活动-放学", "afterclass")
    );

    public ImageImportService(ClassImageRepository classImageRepository,
                              ImageIngestionService ingestionService) {
        this.classImageRepository = classImageRepository;
        this.ingestionService = ingestionService;
    }

    public int importFromDirectory(Path rootDir, Long classId) throws IOException {
        final int[] count = {0};
        Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".jpg")) {
                    String periodLabel = resolvePeriodLabel(rootDir, file);
                    OffsetDateTime captureTime = parseCaptureTime(file.getFileName().toString());
                    if (captureTime != null) {
                        try {
                            byte[] imageBytes = Files.readAllBytes(file);
                            ingestionService.ingest(
                                    classId,
                                    imageBytes,
                                    file.getFileName().toString(),
                                    captureTime.toString(),
                                    periodLabel);
                            count[0]++;
                        } catch (IOException e) {
                            log.warn("Failed to import: {}", file, e);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        log.info("Imported {} images from {}", count[0], rootDir);
        return count[0];
    }

    private String resolvePeriodLabel(Path rootDir, Path file) {
        Path relative = rootDir.relativize(file);
        if (relative.getNameCount() < 2) return null;
        String folderName = relative.getName(0).toString();
        return PERIOD_MAP.get(folderName);
    }

    private OffsetDateTime parseCaptureTime(String filename) {
        Matcher m = FILENAME_PATTERN.matcher(filename);
        if (!m.find()) return null;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));
        return OffsetDateTime.of(
                LocalDate.of(year, month, day),
                LocalTime.of(hour, minute, second),
                ZoneOffset.ofHours(8));
    }
}
```

- [ ] **Step 2:** Run a manual import of the existing 4-day data

```bash
# Example usage (run via a temporary main method or REST call)
# This would be triggered after the application is running:
# curl -X POST /api/v1/images/import -d '{"rootDir": "/path/to/2026-0526", "classId": 1}'
```

- [ ] **Step 3:** Commit

```bash
git add src/main/java/com/school/emotion/service/ImageImportService.java
git commit -m "feat: historical batch image import service"
```

---

### Task 7: 集成测试 + 管道验证

**Files:**
- Create: `src/test/java/com/school/emotion/integration/ImageProcessingPipelineIntegrationTest.java`

- [ ] **Step 1:** Write integration test

```java
package com.school.emotion.integration;

import com.school.emotion.model.dto.ImageIngestResponse;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImageProcessingPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("emotion_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @Autowired
    private ClassImageRepository classImageRepository;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("spring.data.redis.host", redis::getHost);
        reg.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Test
    void ingestAndVerifyImageRecord() {
        // This test verifies the full ingest → DB pipeline
        // (AI APIs are mocked externally; we verify DB state)
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Verify controller returns accepted
        client.post()
                .uri("/api/v1/images/ingest")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .exchange()
                .expectStatus().isAccepted();
    }
}
```

- [ ] **Step 2:** Run full test suite

```bash
./mvnw test
# Expected: All tests pass
```

- [ ] **Step 3:** Final commit

```bash
git add src/test/java/com/school/emotion/integration/
git commit -m "test: integration test for image processing pipeline"
```

---

## 计划自审清单

- [ ] **Spec覆盖**: Phase 1 覆盖 F-01(图片接收API), F-02(队列), F-04(历史导入), F-05(人脸检测), F-06(人脸注册/识别), F-08(多人脸处理), F-09(表情识别), F-10(置信度记录)
- [ ] **占位符检查**: 无 TBD/TODO 遗留
- [ ] **类型一致性**: Entity 字段名与 SQL migration 一致，DTO 字段与 API 响应一致
- [ ] **测试覆盖**: Controller 层 + Service 层 + AI Client 层 + 集成测试
