# process_faces_step4.py 执行命令

**工作目录:** `/home/zebra/Downloads/官渡一中初一班-0526`

## 策略

对 Qdrant 中每个人脸向量做 ANN 搜索（余弦相似度 ≥ threshold），构建邻接图后 BFS 连通分量聚类，保存簇并自动标注学生。

相比 O(n²) 全量比较（214k² ≈ 460 亿次），ANN 搜索利用 Qdrant HNSW 索引将复杂度降至 O(n log n)，实测 ~140 点/秒 × 4 线程，全量约 **25 分钟**。

## 前置检查

```bash
# 确认 Qdrant 有数据
curl -s http://localhost:6333/collections/face_features | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print('Qdrant points:', d['result']['points_count'])"

# 确认 MySQL 连接
python3 -c "
import pymysql
conn = pymysql.connect(host='192.168.3.12', port=3307, user='root', password='123456',
    database='emotion_platform', charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
c = conn.cursor()
c.execute('SELECT COUNT(*) as cnt FROM face_record WHERE face_encoding IS NOT NULL AND face_encoding != \"\"')
print('face_records with encoding:', c.fetchone()['cnt'])
c.execute('SELECT COUNT(*) as cnt FROM face_cluster')
print('existing clusters:', c.fetchone()['cnt'])
c.close(); conn.close()
"
```

## 执行命令

### 1. 全量聚类（断点续传，推荐）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step4.py --threshold 0.75 --min-cluster 5 --parallel 4
```

- **threshold=0.75**: 余弦相似度阈值，两向量 ≥ 0.75 视为同一个人
- **min-cluster=5**: 至少 5 张人脸才形成有效簇
- **parallel=4**: 4 线程并发 ANN 搜索

### 2. 试跑 1000 点验证

```bash
# 修改脚本临时 --max 或直接 Ctrl+C 观察 checkpoint
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step4.py --threshold 0.75 --min-cluster 5 --parallel 4
```

### 3. 断点续传

脚本自动保存 checkpoint，中断后重跑即可续传：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step4.py --threshold 0.75 --min-cluster 5 --parallel 4 --resume
```

### 4. 仅自动标注已有簇

如果聚类已完成，只想补跑自动标注：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step4.py --annotate-only
```

### 5. 干跑（不写数据库）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step4.py --dry-run --threshold 0.75 --min-cluster 5 --parallel 4
```

### 6. 更低阈值（召回更多）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step4.py --threshold 0.7 --min-cluster 3 --parallel 4
```

## 执行后验证

```bash
# 1. 聚类总数
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT COUNT(*) AS clusters, status FROM emotion_platform.face_cluster GROUP BY status;"

# 2. 簇大小分布
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT sample_count, COUNT(*) AS cnt FROM emotion_platform.face_cluster GROUP BY sample_count ORDER BY sample_count LIMIT 20;"

# 3. 自动标注学生数
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT COUNT(*) AS students FROM emotion_platform.student WHERE student_no LIKE 'auto_%';"

# 4. face_record 回填率
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT student_id IS NOT NULL AS has_student, COUNT(*) AS cnt FROM emotion_platform.face_record GROUP BY has_student;"

# 5. 检查点状态
cat /tmp/face_clustering_checkpoint.json | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('processed:', len(d.get('processed_ids',[])))
print('clusters:', d.get('clusters_saved',0))
print('students:', d.get('students_created',0))
print('graph_edges:', d.get('graph_edges',0))
"
```

## 参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--threshold` | 0.7 | 余弦相似度阈值。越高簇越精确但召回少，推荐 0.75 |
| `--min-cluster` | 3 | 最小簇大小。低于此的连通分量视为离群点 |
| `--parallel` | 4 | ANN 搜索线程数。Qdrant 无连接限制，可开 4-8 |
| `--resume` | false | 断点续传，跳过已处理的 ID |
| `--annotate-only` | false | 仅自动标注已有 pending 簇 |
| `--dry-run` | false | 干跑模式，不写数据库 |

## 已知问题

1. **非同源聚类**：同一个人在不同日期/不同光线条件下的特征差异可能超过阈值
   → 可降低 threshold 到 0.65~0.7
2. **离群点**：低于 min_cluster 的 face_record 保持 student_id = NULL
   → 后续可通过人工标注补全
3. **ANN 近似性**：Qdrant ANN 是近似搜索，可能漏掉部分真阳性邻居
   → 可通过增大 `ANN_SEARCH_LIMIT`（脚本内 50 → 100）缓解

## 算法流程

```
Qdrant 214k 向量
      ↓
for each face_id:
  ANN search (cosine ≥ threshold)
  record edges in graph
      ↓
BFS 连通分量
      ↓
size ≥ min_cluster? → 保存 face_cluster (status='pending')
size < min_cluster? → 离群点
      ↓
auto_annotate:
  每个 cluster → 创建 student 记录
              → backfill face_record.student_id
              → cluster.status = 'auto_annotated'
