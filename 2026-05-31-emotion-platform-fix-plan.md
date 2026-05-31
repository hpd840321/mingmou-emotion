# emotion-platform 数据流修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通 classroom_image → face_detection → emotion → aggregation → dashboard 完整数据链路

**Architecture:** 基于复盘发现的 P0 级数据流断点，按依赖顺序修复：聚类后触发聚合 → 补全控制器数据转换 → 修复 DashboardService 空数据

**Tech Stack:** Spring Boot 3 + JPA + MySQL, Vue 3 + Pinia + ECharts, VisionMind gRPC

---

## 文件改动总览

| 文件 | 操作 | 职责 |
|------|------|------|
| `FaceClusteringServiceV2.java` | 修改 | autoAnnotateClusters 末尾触发聚合 |
| `FaceProcessingPipeline.java` | 修改 | pipeline 完成后触发类级聚合 |
| `StudentController.java` | 修改 | emotion-timeline 返回完整 StudentProfileData |
| `ClassController.java` | 修改 | dashboard 返回学生列表 |
| `DashboardService.java` | 修改 | SchoolOverview 填充真实数据 |
| `StudentRepository.java` | 修改 | 新增按 classId 查询 |
| `EmotionAggregationService.java` | 修改 | 新增批量聚合方法 |
| `SchoolTreeController.java` | 修改 | 补充 sampleImages URL 路径格式化 |

---

### Task 1: 聚类完成后触发学生级聚合

**问题:** `FaceClusteringServiceV2.autoAnnotateClusters()` 创建 Student 并回填 `face_record.student_id` 后从不调用 `EmotionAggregationService.aggregate()`，导致学生级聚合永不触发。

**Files:**
- Modify: `FaceClusteringServiceV2.java:156-229`
- Modify: `EmotionAggregationService.java:43-94`

- [ ] **Step 1.1: 注入 EmotionAggregationService**

```java
// FaceClusteringServiceV2.java - 在文件顶部 @Autowired 块后添加字段
private final EmotionAggregationService emotionAggregationService;

// 构造函数参数列表末尾添加:
EmotionAggregationService emotionAggregationService
// 构造函数体内添加:
this.emotionAggregationService = emotionAggregationService;
```

- [ ] **Step 1.2: autoAnnotateClusters 末尾调用聚合**

在 `FaceClusteringServiceV2.java:212`（`cluster.setStudentId(student.getId())`）之后、`cluster.setStatus("auto_annotated")` 之前插入：

```java
// Trigger per-student emotion aggregation for this student
try {
    emotionAggregationService.aggregate(student.getId(), LocalDate.now(), 0L);
} catch (Exception aggEx) {
    log.warn("Failed to aggregate emotion for student {}: {}", student.getId(), aggEx.getMessage());
}
```

添加 import：`import java.time.LocalDate;`

- [ ] **Step 1.3: 复盘验证 — 检查聚合触发**

```bash
# 1. 检查编译是否通过
cd /home/zebra/Downloads/官渡一中初一班-0526/emotion-platform
mvn compile -q 2>&1 | tail -5

# 2. 检查 FaceClusteringServiceV2 中 emotionAggregationService 调用
grep -n "emotionAggregationService\|EmotionAggregationService" \
  src/main/java/com/school/emotion/service/FaceClusteringServiceV2.java
# 预期输出: 字段声明 + 构造参数 + 调用处 至少 3 处

# 3. 确认 autoAnnotateCluster 中 aggregate 调用存在于 Student 创建之后
awk '/student = studentRepository.save/,/cluster.setStatus/' \
  src/main/java/com/school/emotion/service/FaceClusteringServiceV2.java | grep "aggregate"
# 预期输出: 包含 emotionAggregationService.aggregate(student.getId()
```

---

### Task 2: Pipeline 完成后触发类级聚合

**问题:** FaceProcessingPipeline 处理完图片后不触发任何聚合，需要等 10 分钟定时任务。

**Files:**
- Modify: `FaceProcessingPipeline.java:240-252`
- Modify: `EmotionAggregationService.java` - 新增批量聚合方法

- [ ] **Step 2.1: EmotionAggregationService 新增批量聚合方法**

```java
// EmotionAggregationService.java - 新增方法
@Async
@Transactional
public void aggregateByClass(Long classId, LocalDate date) {
    List<FaceRecord> faceRecords = faceRecordRepository.findAll();  // 可优化为按 classId 查询
    if (faceRecords.isEmpty()) return;
    
    int total = 0;
    Map<String, Integer> emotionCounts = new HashMap<>();
    for (var fr : faceRecords) {
        if (fr.getClassImage() == null || 
            fr.getClassImage().getClazz() == null || 
            !fr.getClassImage().getClazz().getId().equals(classId)) continue;
        var er = emotionRecordRepository.findByFaceRecordId(fr.getId());
        if (er != null) {
            emotionCounts.merge(er.getDominantEmotion(), 1, Integer::sum);
            total++;
        }
    }
    if (total == 0) return;
    
    // ... 与 aggregate() 相同的比率计算逻辑 ...
    // 设置 studentId=0L 表示类级聚合
    EmotionAggregation agg = aggregationRepository
            .findByClassIdAndDate(classId, date)
            .stream().findFirst().orElse(new EmotionAggregation());
    agg.setStudentId(0L);
    agg.setClassId(classId);
    agg.setDate(date);
    agg.setPeriodId(0L);
    // ... 设置比率字段 ...
    aggregationRepository.save(agg);
}
```

- [ ] **Step 2.2: FaceProcessingPipeline.processImage 末尾调用**

```java
// 在 markCompleted(ci) 之前插入:
try {
    LocalDate captureDate = ci.getCaptureTime().toLocalDate();
    emotionAggregationService.aggregateByClass(ci.getClazz().getId(), captureDate);
} catch (Exception e) {
    log.warn("Failed to trigger aggregation for image {}: {}", ci.getId(), e.getMessage());
}
```

- [ ] **Step 2.3: 复盘验证**

```bash
# 1. 编译检查
mvn compile -q 2>&1 | tail -5

# 2. 确认 pipeline 中有聚合调用
grep -n "aggregateByClass\|emotionAggregationService" \
  src/main/java/com/school/emotion/service/FaceProcessingPipeline.java

# 3. 确认 EmotionAggregationService 有新方法
grep -n "aggregateByClass\|public void aggregate" \
  src/main/java/com/school/emotion/service/EmotionAggregationService.java
```

---

### Task 3: 修复 StudentController 数据转换

**问题:** `GET /api/v1/students/{id}/emotion-timeline` 返回原始聚合实体，前端 `StudentProfileData` 期望 `studentName`, `studentNo`, `className`, `kpis`, `trendData`, `weekDistribution` 等转换后字段。

**Files:**
- Modify: `StudentController.java:26-37`
- Modify (if needed): `StudentRepository.java`

- [ ] **Step 3.1: 注入 StudentRepository 和 SchoolClassRepository**

```java
// StudentController.java 现有字段后添加:
private final StudentRepository studentRepository;
private final SchoolClassRepository schoolClassRepository;

// 构造函数参数列表末尾添加:
StudentRepository studentRepository,
SchoolClassRepository schoolClassRepository
// 构造函数体内添加:
this.studentRepository = studentRepository;
this.schoolClassRepository = schoolClassRepository;
```

- [ ] **Step 3.2: 重写 emotion-timeline 端点**

```java
@GetMapping("/{id}/emotion-timeline")
public ResponseEntity<?> timeline(
        @PathVariable Long id,
        @RequestParam(required = false) String date,
        @RequestParam(required = false) String period) {
    LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
    LocalDate startDate = queryDate.minusDays(7);
    
    Optional<Student> studentOpt = studentRepository.findById(id);
    if (studentOpt.isEmpty()) {
        return ResponseEntity.status(404).body(Map.of("code", 404, "message", "student not found"));
    }
    Student student = studentOpt.get();
    
    var aggs = aggregationRepository.findByStudentIdAndDateBetween(id, startDate, queryDate);
    var alerts = alertLogRepository.findByStudentIdOrderByCreatedAtDesc(id);
    var interventions = interventionLogRepository.findByStudentIdOrderByCreatedAtDesc(id);
    
    // Build week distribution from aggs
    Map<String, Float> weekDist = new HashMap<>();
    weekDist.put("happy", 0f); weekDist.put("sad", 0f); weekDist.put("angry", 0f);
    weekDist.put("surprise", 0f); weekDist.put("fear", 0f); weekDist.put("disgust", 0f);
    weekDist.put("neutral", 0f);
    if (!aggs.isEmpty()) {
        for (var agg : aggs) {
            if (agg.getRatioHappy() != null) weekDist.merge("happy", agg.getRatioHappy(), Float::sum);
            if (agg.getRatioSad() != null) weekDist.merge("sad", agg.getRatioSad(), Float::sum);
            if (agg.getRatioAngry() != null) weekDist.merge("angry", agg.getRatioAngry(), Float::sum);
            if (agg.getRatioSurprise() != null) weekDist.merge("surprise", agg.getRatioSurprise(), Float::sum);
            if (agg.getRatioFear() != null) weekDist.merge("fear", agg.getRatioFear(), Float::sum);
            if (agg.getRatioDisgust() != null) weekDist.merge("disgust", agg.getRatioDisgust(), Float::sum);
            if (agg.getRatioNeutral() != null) weekDist.merge("neutral", agg.getRatioNeutral(), Float::sum);
        }
        int count = aggs.size();
        if (count > 0) {
            weekDist.replaceAll((k, v) -> v / count);
        }
    }
    
    double avgEngagement = aggs.stream().mapToDouble(a -> 
        a.getEngagementScore() != null ? a.getEngagementScore() : 0).average().orElse(0);
    
    Map<String, Object> data = new HashMap<>();
    data.put("studentId", id);
    data.put("studentName", student.getName());
    data.put("studentNo", student.getStudentNo());
    data.put("className", student.getClazz() != null ? student.getClazz().getName() : "");
    data.put("tags", List.of());
    data.put("kpis", List.of(
        Map.of("label", "情绪健康度", "value", Math.round((1 - weekDist.getOrDefault("sad", 0f) - weekDist.getOrDefault("angry", 0f) - weekDist.getOrDefault("fear", 0f) - weekDist.getOrDefault("disgust", 0f)) * 100), "unit", "%", "change", null, "changeDirection", "flat", "status", "good"),
        Map.of("label", "课堂参与度", "value", Math.round(avgEngagement), "unit", "%", "change", null, "changeDirection", "flat", "status", avgEngagement > 60 ? "good" : "warning")
    ));
    data.put("trendData", aggs.stream().map(agg -> {
        Map<String, Object> point = new HashMap<>();
        point.put("date", agg.getDate() != null ? agg.getDate().toString() : "");
        point.put("happy", agg.getRatioHappy() != null ? agg.getRatioHappy() : 0);
        point.put("sad", agg.getRatioSad() != null ? agg.getRatioSad() : 0);
        point.put("angry", agg.getRatioAngry() != null ? agg.getRatioAngry() : 0);
        point.put("surprise", agg.getRatioSurprise() != null ? agg.getRatioSurprise() : 0);
        point.put("fear", agg.getRatioFear() != null ? agg.getRatioFear() : 0);
        point.put("disgust", agg.getRatioDisgust() != null ? agg.getRatioDisgust() : 0);
        point.put("neutral", agg.getRatioNeutral() != null ? agg.getRatioNeutral() : 0);
        return point;
    }).toList());
    data.put("weekDistribution", weekDist);
    data.put("periodComparison", List.of());
    data.put("alertTimeline", alerts.stream().map(a -> {
        Map<String, Object> item = new HashMap<>();
        item.put("date", a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate().toString() : "");
        item.put("period", "");
        item.put("desc", a.getMessage());
        item.put("triggerValue", 0);
        return item;
    }).toList());
    data.put("interventions", interventions);
    
    return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
}
```

- [ ] **Step 3.3: 复盘验证**

```bash
# 1. 编译检查
mvn compile -q 2>&1 | tail -5

# 2. 确认返回字段匹配前端 StudentProfileData 类型定义
# 前端期望: studentId, studentName, studentNo, className, tags, kpis, trendData, weekDistribution, periodComparison, alertTimeline, interventions
grep -A1 "data.put" src/main/java/com/school/emotion/controller/StudentController.java | grep "\"studentName\"\|\"studentNo\"\|\"className\"\|\"kpis\"\|\"trendData\"\|\"weekDistribution\""

# 3. 测试 API 响应结构（需要编译部署后）
# curl -s http://localhost:8090/api/v1/students/1/emotion-timeline -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

---

### Task 4: 修复 ClassController 学生列表

**问题:** `GET /api/v1/classes/{id}/dashboard` 不返回 `students` 列表，前端 `ClassDashboardData.students: StudentRow[]` 永远为空。

**Files:**
- Modify: `ClassController.java:29-42`

- [ ] **Step 4.1: 注入 StudentRepository**

```java
// ClassController.java 现有字段后添加:
private final StudentRepository studentRepository;

// 构造函数参数列表末尾添加:
StudentRepository studentRepository
// 构造函数体内添加:
this.studentRepository = studentRepository;
```

- [ ] **Step 4.2: dashboard 端点补充学生列表**

```java
@GetMapping("/{id}/dashboard")
public ResponseEntity<?> dashboard(
        @PathVariable Long id,
        @RequestParam(required = false) String date,
        @RequestParam(required = false) String periodLabel) {
    LocalDate queryDate = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : LocalDate.now();
    var aggs = aggregationRepository.findByClassIdAndDate(id, queryDate);
    
    // Build student rows from Student records
    List<Student> students = studentRepository.findByClazz_Id(id);
    List<Map<String, Object>> studentRows = students.stream().map(s -> {
        Map<String, Object> row = new HashMap<>();
        row.put("id", s.getId());
        row.put("name", s.getName());
        row.put("studentNo", s.getStudentNo());
        
        // Find aggregation for this student
        var studentAgg = aggs.stream()
                .filter(a -> a.getStudentId() != null && a.getStudentId().equals(s.getId()))
                .findFirst();
        
        if (studentAgg.isPresent()) {
            var agg = studentAgg.get();
            // Determine dominant emotion
            Map<String, Float> ratios = new HashMap<>();
            ratios.put("happy", agg.getRatioHappy() != null ? agg.getRatioHappy() : 0);
            ratios.put("neutral", agg.getRatioNeutral() != null ? agg.getRatioNeutral() : 0);
            ratios.put("sad", agg.getRatioSad() != null ? agg.getRatioSad() : 0);
            ratios.put("angry", agg.getRatioAngry() != null ? agg.getRatioAngry() : 0);
            ratios.put("surprise", agg.getRatioSurprise() != null ? agg.getRatioSurprise() : 0);
            ratios.put("fear", agg.getRatioFear() != null ? agg.getRatioFear() : 0);
            ratios.put("disgust", agg.getRatioDisgust() != null ? agg.getRatioDisgust() : 0);
            var max = ratios.entrySet().stream().max(Map.Entry.comparingByValue());
            
            row.put("dominantEmotion", max.isPresent() && max.get().getValue() > 0 ? max.get().getKey() : null);
            row.put("dominantConfidence", max.isPresent() ? max.get().getValue() : null);
            row.put("happy", agg.getRatioHappy() != null ? Math.round(agg.getRatioHappy() * 100) : 0);
            row.put("neutral", agg.getRatioNeutral() != null ? Math.round(agg.getRatioNeutral() * 100) : 0);
            row.put("sad", agg.getRatioSad() != null ? Math.round(agg.getRatioSad() * 100) : 0);
            row.put("angry", agg.getRatioAngry() != null ? Math.round(agg.getRatioAngry() * 100) : 0);
            row.put("engagement", agg.getEngagementScore() != null ? Math.round(agg.getEngagementScore()) : 0);
        } else {
            row.put("dominantEmotion", null);
            row.put("dominantConfidence", null);
            row.put("happy", 0); row.put("neutral", 0); row.put("sad", 0); row.put("angry", 0);
            row.put("engagement", 0);
        }
        row.put("isAlert", false);
        row.put("isAbsent", false);
        return row;
    }).toList();
    
    Map<String, Object> data = new HashMap<>();
    data.put("classId", id);
    data.put("date", queryDate.toString());
    data.put("periodLabel", periodLabel);
    data.put("aggregations", aggs);
    data.put("students", studentRows);
    return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
}
```

- [ ] **Step 4.3: 复盘验证**

```bash
# 1. 编译检查
mvn compile -q 2>&1 | tail -5

# 2. 确认 students 字段在返回中
grep -n '"students"' src/main/java/com/school/emotion/controller/ClassController.java

# 3. 确认 studentRows 字段完整
grep -n '"dominantEmotion"\|"engagement"\|"isAlert"' src/main/java/com/school/emotion/controller/ClassController.java
```

---

### Task 5: 修复 SchoolOverview/DashboardService

**问题:** `DashboardService.getSchoolOverview()` 返回 `gradeComparison`, `alertRanking`, `crossClassAlerts` 全为空列表。前端的校级大盘图表全空。

**Files:**
- Modify: `DashboardService.java:23-41`

- [ ] **Step 5.1: 注入缺失的 Repository**

```java
// DashboardService.java 现有字段后添加:
private final EmotionAggregationRepository aggregationRepository;
private final SchoolClassRepository schoolClassRepository;
private final GradeRepository gradeRepository;

// 构造函数参数列表末尾添加:
SchoolClassRepository schoolClassRepository,
GradeRepository gradeRepository
// 构造函数体内添加:
this.schoolClassRepository = schoolClassRepository;
this.gradeRepository = gradeRepository;
```

- [ ] **Step 5.2: 重写 getSchoolOverview 填充真实数据**

```java
public SchoolOverviewDTO getSchoolOverview(Long gradeId, String period) {
    SchoolOverviewDTO dto = new SchoolOverviewDTO();
    List<EmotionAggregation> aggs = aggregationRepository.findAll();
    
    // KPI (keep existing logic)
    double avgHealth = aggs.stream().mapToDouble(a -> {
        float pos = a.getPositiveRatio() != null ? a.getPositiveRatio() : 0;
        return pos * 100;
    }).average().orElse(0);
    double avgEngagement = aggs.stream().mapToDouble(a -> 
        a.getEngagementScore() != null ? a.getEngagementScore() : 0).average().orElse(0);
    double avgNegative = aggs.stream().mapToDouble(a -> {
        float neg = a.getNegativeRatio() != null ? a.getNegativeRatio() : 0;
        return neg * 100;
    }).average().orElse(0);
    
    dto.setKpis(List.of(
        createKpi("情绪健康度", Math.round(avgHealth), "%", avgHealth > 60 ? "good" : "warning"),
        createKpi("课堂参与度", Math.round(avgEngagement), "%", avgEngagement > 60 ? "good" : "warning"),
        createKpi("异常情绪率", Math.round(avgNegative), "%", avgNegative < 20 ? "good" : "danger"),
        createKpi("重点关注", 0, "人", "good")
    ));
    
    // Grade comparison - group by class
    List<Grade> grades = gradeRepository.findAll();
    List<SchoolOverviewDTO.GradeComparison> gradeComp = new ArrayList<>();
    for (Grade g : grades) {
        List<SchoolClass> classes = schoolClassRepository.findByGrade_Id(g.getId());
        double gAvg = 0;
        int classCount = 0;
        for (SchoolClass c : classes) {
            var classAggs = aggregationRepository.findByClassIdAndDate(c.getId(), LocalDate.now());
            if (!classAggs.isEmpty()) {
                gAvg += classAggs.stream().mapToDouble(a -> 
                    a.getPositiveRatio() != null ? a.getPositiveRatio() : 0).average().orElse(0);
                classCount++;
            }
        }
        if (classCount > 0) {
            var item = new SchoolOverviewDTO.GradeComparison();
            item.setName(g.getName());
            item.setValue(Math.round(gAvg / classCount * 100));
            gradeComp.add(item);
        }
    }
    dto.setGradeComparison(gradeComp);
    
    // Alert ranking - calculate negative ratio per class
    List<SchoolOverviewDTO.AlertRanking> rankings = new ArrayList<>();
    for (Grade g : grades) {
        List<SchoolClass> classes = schoolClassRepository.findByGrade_Id(g.getId());
        for (SchoolClass c : classes) {
            var classAggs = aggregationRepository.findByClassIdAndDate(c.getId(), LocalDate.now());
            if (!classAggs.isEmpty()) {
                double negRate = classAggs.stream().mapToDouble(a -> 
                    a.getNegativeRatio() != null ? a.getNegativeRatio() : 0).average().orElse(0);
                var item = new SchoolOverviewDTO.AlertRanking();
                item.setClassName(c.getName());
                item.setRate(Math.round(negRate * 100) / 100.0);
                rankings.add(item);
            }
        }
    }
    rankings.sort((a, b) -> Double.compare(b.getRate(), a.getRate()));
    if (rankings.size() > 5) rankings = rankings.subList(0, 5);
    dto.setAlertRanking(rankings);
    
    dto.setCrossClassAlerts(new ArrayList<>());
    return dto;
}
```

添加 import：`import java.time.LocalDate;`, `import com.school.emotion.model.entity.SchoolClass;`, `import com.school.emotion.repository.SchoolClassRepository;`, `import com.school.emotion.repository.GradeRepository;`

- [ ] **Step 5.3: 复盘验证**

```bash
# 1. 编译检查
mvn compile -q 2>&1 | tail -5

# 2. 确认 gradeComparison 和 alertRanking 不再为空
grep -n "gradeComp\|rankings\|gradeComparison\|alertRanking" \
  src/main/java/com/school/emotion/service/DashboardService.java | head -10
```

---

### Task 6: 修复 ClassController heatmap 端点

**问题:** `GET /api/v1/classes/{id}/heatmap` 返回 `{classId, totalImages}` 存根，前端 `SeatHeatmapData` 期望 `seats[]`, `rows`, `cols`, `distribution`, `lowEngagementAlerts`。

**Files:**
- Modify: `ClassController.java:55-63`

- [ ] **Step 6.1: 重写 heatmap 端点**

```java
@GetMapping("/{id}/heatmap")
public ResponseEntity<?> heatmap(
        @PathVariable Long id,
        @RequestParam(required = false) String date,
        @RequestParam(required = false) String periodLabel) {
    LocalDate queryDate = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : LocalDate.now();
    List<Student> students = studentRepository.findByClazz_Id(id);
    var aggs = aggregationRepository.findByClassIdAndDate(id, queryDate);
    
    List<Map<String, Object>> seats = new ArrayList<>();
    int[] row = {0};
    int[] col = {0};
    students.forEach(s -> {
        Map<String, Object> seat = new HashMap<>();
        seat.put("row", row[0]++ / 8);
        seat.put("col", col[0]++ % 8);
        seat.put("studentId", s.getId());
        seat.put("studentName", s.getName());
        seat.put("studentNo", s.getStudentNo());
        
        var match = aggs.stream().filter(a -> s.getId().equals(a.getStudentId())).findFirst();
        if (match.isPresent()) {
            seat.put("engagement", match.get().getEngagementScore());
            Map<String, Float> ratios = new HashMap<>();
            ratios.put("happy", match.get().getRatioHappy()); // ... etc
            var max = ratios.entrySet().stream().max(Map.Entry.comparingByValue());
            seat.put("dominantEmotion", max.isPresent() && max.get().getValue() > 0 ? max.get().getKey() : null);
        } else {
            seat.put("engagement", null);
            seat.put("dominantEmotion", null);
        }
        seat.put("isAbsent", false);
        seat.put("isEmpty", false);
        seats.add(seat);
    });
    
    Map<String, Object> data = new HashMap<>();
    data.put("seats", seats);
    data.put("rows", students.isEmpty() ? 0 : (students.size() - 1) / 8 + 1);
    data.put("cols", 8);
    data.put("distribution", List.of());
    data.put("lowEngagementAlerts", List.of());
    
    return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
}
```

- [ ] **Step 6.2: 复盘验证**

```bash
# 1. 编译检查
mvn compile -q 2>&1 | tail -5

# 2. 确认 seats 字段存在
grep -n '"seats"\|"rows"\|"cols"' src/main/java/com/school/emotion/controller/ClassController.java
```

---

### 执行顺序与依赖

```
Task 1 (聚类→聚合) ──┐
                     ├── Task 4 (ClassController) ──┐
Task 2 (Pipeline→聚合)┘                              ├── 端到端验证
                                                    │
Task 3 (StudentController) ─────────────────────────┘
Task 5 (DashboardService) ──────────────────────────┐
                                                    ├── 端到端验证
Task 6 (heatmap) ───────────────────────────────────┘
```

**推荐执行顺序:** Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6

每个 Task 完成后需要编译检查 + 对应复盘验证步骤。
