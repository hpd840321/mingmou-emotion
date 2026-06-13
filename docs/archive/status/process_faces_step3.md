# Step 3: 人脸聚类 (Face Clustering)

## 概述

基于 Qdrant 中已注册的人脸特征向量，使用 BFS 连通分量算法进行人脸聚类，将同一个人在不同课堂照片中的多张人脸归为一簇。

## 原理

1. **从 Qdrant 读取全部特征向量**（`face_features` collection，128-dim Cosine）
2. **构建相似度图**：余弦相似度 ≥ 阈值（默认 0.7）的两个向量建边
3. **BFS 连通分量**：簇大小 ≥ minClusterSize（默认 3）保留
4. **自动标注**：
   - 为每个有效簇创建 Student 记录
   - 回填 `face_record.student_id`
   - 触发情绪聚合

## 前提

- [ ] Step 1 完成（人脸检测+裁剪） ✅
- [ ] Step 2 完成（情绪识别） ✅
- [ ] Step 2 图库注册进行中（等待 Qdrant 数据写入）
- [ ] Qdrant 服务运行中（`localhost:6333`）
- [ ] `face_features` collection 存在（128-dim Cosine）

## 执行方式

### 方式 A: 通过后端 API 触发

```bash
# 后端已内置 FaceClusteringServiceV2.scheduledClustering()
# 每 1 小时自动运行
# 也可手动触发（需 Spring Boot actuator / 自定义 endpoint）

# 启动后端服务
cd /home/zebra/Downloads/官渡一中初一班-0526/emotion-platform
java -jar target/emotion-platform-0.1.0-SNAPSHOT.jar --server.port=8090 --spring.profiles.active=dev
```

### 方式 B: Python 脚本（推荐，支持断点续传）

见 `process_faces_step3.py`。

### 配置项

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `similarity-threshold` | 0.7 | 余弦相似度阈值，越高越严格 |
| `min-cluster-size` | 3 | 最小簇大小，低于此视为离群点 |

## 验证

```bash
# 查看 cluster 数量
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT COUNT(*) AS clusters, status FROM emotion_platform.face_cluster GROUP BY status;"

# 查看自动标注的学生
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT id, student_no, name FROM emotion_platform.student WHERE student_no LIKE 'auto_%' LIMIT 10;"

# 查看 face_record 回填情况
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT student_id IS NOT NULL AS has_student, COUNT(*) FROM emotion_platform.face_record GROUP BY has_student;"

# 查看 Qdrant 点数量
curl -s http://localhost:6333/collections/face_features | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print('Qdrant points:', d['result']['points_count'])"
```

## 已知问题

1. **O(n²) 复杂度**：BFS 对所有向量做两两比较，219k 向量 ≈ 240 亿次比较，需数小时
   - 优化：改用 Qdrant 内置 search + HNSW 索引做近邻搜索，避免全量 O(n²)
   - 或按 `class_image.date` / `period_label` 分桶，减少比较范围
2. **非同源聚类**：不同日期/不同位置的同一个人，特征差异可能超过阈值
   - 考虑降低阈值到 0.5~0.6
3. **离群点**：低于 minClusterSize（3）的向量不会被聚类
   - 这些 face_record 保持 `student_id = NULL`

## 预期结果

| 指标 | 估计值 |
|------|--------|
| Qdrant 向量数 | ~219,603 |
| 聚类数 | ~600-900（全班 ~50 人 × 12 个时段） |
| 自动标注学生数 | ~600-900（含去重） |
| 离群点 | ~10%-20% |
| 计算时间（O(n²)） | ~3-5 小时 |
