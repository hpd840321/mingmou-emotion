# 教室空间位置辅助人脸匹配 — 详细实施方案

> 基于 current pipeline (init_data_pipeline.py) | 2026-06-14 | 预计执行 1-2 小时

---

## 一、问题与现状

### 1.1 当前方案

管线已处理约 850/4560 张教室全景图，检测到约 2000+ 张人脸，创建 452 名学生。人脸匹配策略：

```
512-dim ArcFace 特征 → L2 归一化 → 余弦相似度 → 阈值 0.55 → 匹配/新建
```

### 1.2 现有数据（已采集但未充分利用）

每条 `face_record` 包含：

| 字段 | 示例 | 含义 |
|------|------|------|
| `bbox` | `{"x":1034,"y":512,"width":42,"height":48}` | 全景图中人脸框 |
| `class_image.capture_time` | `2026-05-28T08:30:00` | 拍摄时间 |
| `class_image.period_label` | `第1节` | 节次 |
| `face_encoding` | base64 512-dim float32 | 特征向量 |
| `student_id` | 已关联学生 | 链接到的学生 |

每个 bbox 的几何中心 `(cx = x + w/2, cy = y + h/2)` 编码了学生在教室全景图中的粗略座位位置。

### 1.3 核心洞察

中学课堂的特点：**学生座位基本固定，同一时段同一座位反复出现的就是同一个人。**

| 场景 | 空间规律 |
|------|---------|
| 同一个人在不同天同一节课 | bbox 中心距通常 < 80px |
| 相邻同学（隔一个座位） | bbox 中心距约 80-120px |
| 隔一排（前后座） | bbox 中心距约 150-200px |
| 不同人（完全不同区域） | bbox 中心距 > 400px |

### 1.4 当前代码中的 gap

```python
# init_data_pipeline.py:102-130  FaceMatcher.match()
def match(self, feature_vec, bbox_center=None, same_period=False):
    ...
    for pid, entry in self.persons.items():
        sim = cosine_sim(query, lib_vec)
        # bbox_center 传进来了，但从未使用
        if same_period:                  # same_period 永远是 False
            sim += SAME_PERIOD_MATCH_BOOST
    ...

# init_data_pipeline.py:435  调用处
pid, sim = matcher.match(feature_vec, bbox_center, same_period=False)  # 写死 False
```

`SPATIAL_DISTANCE_THRESHOLD = 200` 和 `SAME_PERIOD_MATCH_BOOST = 0.05` 定义了但从未生效。

---

## 二、执行方案（分两阶段）

### 阶段 A — 离线空间校准脚本（优先执行）

对已处理的 ~2000 条 face_record 做离线 SQL+Python 分析：
1. 计算每个学生的座位分布
2. 检测空间异常匹配（特征匹配了但座位不在该学生区域 -> 疑似误召回）
3. 检测未关联人脸的空间归属（student_id IS NULL 但座位靠近某学生 -> 建议关联）
4. 输出校准报告，人工确认后执行修复

**目标：验证空间辅助的有效性，不干扰正在跑的管线。**

### 阶段 B — 在线匹配增强（阶段 A 验证有效后再实施）

修改 `FaceMatcher.match()`，在匹配决策中真正使用 `bbox_center`：
- 为每个 person 维护座位中心列表
- 特征相似度 + 空间距离加权联合评分
- 降低模糊匹配的误报率

---

## 三、阶段 A — 详细步骤

### A1 座位分布 SQL 分析

```sql
-- A1.1 每个学生的座位统计
SELECT fr.student_id, s.name, s.student_no,
       AVG((fr.bbox::json->>'x')::int + (fr.bbox::json->>'width')::int / 2.0) AS avg_cx,
       AVG((fr.bbox::json->>'y')::int + (fr.bbox::json->>'height')::int / 2.0) AS avg_cy,
       STDDEV((fr.bbox::json->>'x')::int + (fr.bbox::json->>'width')::int / 2.0) AS std_cx,
       STDDEV((fr.bbox::json->>'y')::int + (fr.bbox::json->>'height')::int / 2.0) AS std_cy,
       COUNT(*) AS face_count,
       MIN(ci.capture_time) AS first_seen,
       MAX(ci.capture_time) AS last_seen
FROM face_record fr
JOIN student s ON fr.student_id = s.id
JOIN class_image ci ON fr.class_image_id = ci.id
WHERE fr.student_id IS NOT NULL
GROUP BY fr.student_id, s.name, s.student_no
ORDER BY COUNT(*) DESC;
```

```sql
-- A1.2 按节次分组的座位分布（同节次座位更集中）
SELECT fr.student_id, ci.period_label,
       AVG(x.cx) AS avg_cx, AVG(x.cy) AS avg_cy,
       STDDEV(x.cx) AS std_cx, STDDEV(x.cy) AS std_cy,
       COUNT(*) AS n
FROM face_record fr
JOIN class_image ci ON fr.class_image_id = ci.id
CROSS JOIN LATERAL (
    SELECT (fr.bbox::json->>'x')::int + (fr.bbox::json->>'width')::int / 2.0 AS cx,
           (fr.bbox::json->>'y')::int + (fr.bbox::json->>'height')::int / 2.0 AS cy
) x
WHERE fr.student_id IS NOT NULL
GROUP BY fr.student_id, ci.period_label
HAVING COUNT(*) >= 3
ORDER BY fr.student_id, ci.period_label;
```

### A2 Python 校准脚本

#### 脚本名：`scripts/spatial_calibration.py`

#### 输入：PostgreSQL face_record + class_image + student

#### 输出：

| 输出文件 | 内容 |
|---------|------|
| `report_student_seat_map.csv` | 每个学生的座位统计 |
| `report_spatial_outliers.csv` | 空间异常匹配（特征匹配但远离该学生 seat 中心） |
| `report_missed_links.csv` | 建议关联的未归属人脸 |
| `report_summary.json` | 汇总统计 |

#### 算法细节

**A2.1 座位模型构建**

对每个学生，收集其所有 face_record 的 bbox 中心点，得到该学生的"座位分布"：
- `seat_center = (avg_cx, avg_cy)` — 历史中心
- `seat_radius = max(std_cx, std_cy) * 2.5` — 正常范围（约 95% 置信）
- `min_period_distance` — 同节次内的位置稳定性

**A2.2 空间异常检测**

```python
def detect_outliers(cur):
    """找出 student_id 与 bbox 位置矛盾的人脸记录"""
    # 加载每个学生的座位模型
    seat_models = {}
    rows = query("""
        SELECT student_id,
               AVG(cx) AS avg_cx, AVG(cy) AS avg_cy,
               STDDEV(cx) AS std_cx, STDDEV(cy) AS std_cy,
               COUNT(*) AS n
        FROM face_bbox_centers
        WHERE student_id IS NOT NULL
        GROUP BY student_id HAVING COUNT(*) >= 3
    """)
    for r in rows:
        seat_models[r.student_id] = {
            'cx': r.avg_cx, 'cy': r.avg_cy,
            'radius': max(r.std_cx, r.std_cy) * 2.5,
            'n': r.n
        }
    
    # 扫描所有 face_record，计算与 seat 中心的距离
    outliers = []
    rows = query("""
        SELECT fr.id, fr.student_id,
               (fr.bbox::json->>'x')::int + (fr.bbox::json->>'width')::int / 2 AS cx,
               (fr.bbox::json->>'y')::int + (fr.bbox::json->>'height')::int / 2 AS cy,
               ci.period_label, ci.capture_time
        FROM face_record fr
        JOIN class_image ci ON fr.class_image_id = ci.id
        WHERE fr.student_id IS NOT NULL
    """)
    for r in rows:
        model = seat_models.get(r.student_id)
        if not model or model['n'] < 3:
            continue
        dist = hypot(r.cx - model['cx'], r.cy - model['cy'])
        if dist > model['radius'] and dist > 200:
            outliers.append({
                'face_record_id': r.id,
                'student_id': r.student_id,
                'dist': dist,
                'radius': model['radius'],
                'period': r.period_label,
                'cx': r.cx, 'cy': r.cy,
                'seat_cx': model['cx'], 'seat_cy': model['cy']
            })

    # 输出异常（按 dist/radius 比值排序）
    outliers.sort(key=lambda x: -x['dist'] / x['radius'])
    save_csv('report_spatial_outliers.csv', outliers)
    return outliers
```

**A2.3 未归属人脸的空间归属**

```python
def suggest_links(cur):
    """对 student_id IS NULL 的 face_record，空间推测应归属的学生"""
    # 对每个无归属 face，找到与其最接近的 3 个学生的 seat 中心
    suggestions = []
    rows = query("""
        SELECT fr.id, fr.student_id AS null_student,
               x.cx, x.cy, ci.period_label
        FROM face_record fr
        JOIN class_image ci ON fr.class_image_id = ci.id
        CROSS JOIN LATERAL (
            SELECT (fr.bbox::json->>'x')::int + (fr.bbox::json->>'width')::int / 2.0 AS cx,
                   (fr.bbox::json->>'y')::int + (fr.bbox::json->>'height')::int / 2.0 AS cy
        ) x
        WHERE fr.student_id IS NULL
    """)
    # 对每张无归属 face，计算与各学生 seat 中心的距离
    # 若最小距离 < SPATIAL_THRESHOLD (200px)，建议关联到该学生
    ...
```

**A2.4 汇总统计**

```json
{
  "total_students_with_seats": 450,
  "students_with_enough_data": 400,
  "potential_outliers_detected": 12,
  "suggested_new_links": 45,
  "seat_overlap_pairs": [
    {"student_a": 1001, "student_b": 1002, "overlap_pct": 23.5},
    ...
  ]
}
```

### A3 人工复核与修复

1. 审查 `report_spatial_outliers.csv`：对异常 face_record，打开 `/img/{id}` 确认人脸，决定是否重新关联
2. 审查 `report_missed_links.csv`：确认空间推测合理的，执行 UPDATE 关联
3. 审查 `report_summary.json` 中的 `seat_overlap_pairs`：座位高度重叠的学生可能是特征匹配未区分的同一个人，合并

---

## 四、阶段 B — 详细步骤（阶段 A 验证后实施）

### B1 修改 FaceMatcher 数据结构

```python
class FaceMatcher:
    def __init__(self):
        self.persons = {}  
        # persons[pid] = {
        #     'avg_feature': [...],
        #     'face_count': int,
        #     'student_id': None,
        #     'seats': [(cx, cy, period), ...],    # 新增：历史座位列表
        #     'seat_centroid': (cx, cy),             # 新增：缓存座位中心
        # }
```

### B2 修改 match() 方法

```python
def match(self, feature_vec, bbox_center=None, period=None):
    """
    特征 + 空间联合匹配
    - feature_vec: 512-dim float32
    - bbox_center: (cx, cy) 当前人脸在教室全景图中的位置
    - period: 当前节次标签
    返回: (person_id, score)
    """
    if feature_vec is None or not self.persons:
        return None, 0.0
    
    query_n = self._normalize(feature_vec)
    candidates = []

    for pid, entry in self.persons.items():
        lib_vec = np.array(entry['avg_feature'], dtype=np.float32)
        sim = float(np.dot(query_n, self._normalize(lib_vec)))
        score = sim

        # === 空间加分因子 ===
        seats = entry.get('seats', [])
        if bbox_center and seats:
            # 到最近历史座位中心的最小距离
            min_dist = min(
                hypot(bbox_center[0] - sx, bbox_center[1] - sy)
                for sx, sy, _ in seats
            )
            
            # 同节次：只考虑相同 period 的历史座位
            if period:
                period_dists = [
                    hypot(bbox_center[0] - sx, bbox_center[1] - sy)
                    for sx, sy, p in seats if p == period
                ]
                if period_dists:
                    min_period_dist = min(period_dists)
                    period_boost = 0.04
                else:
                    min_period_dist = min_dist
                    period_boost = 0.0
            else:
                min_period_dist = min_dist
                period_boost = 0.0

            # 空间加分规则
            if min_dist < SPATIAL_DISTANCE_THRESHOLD:
                score += 0.08                     # 基本空间匹配
                if min_period_dist < 100:
                    score += period_boost         # 同节次额外加分
            elif min_dist > SPATIAL_DISTANCE_THRESHOLD * 3:
                score -= 0.10                      # 空间明显不匹配 -> 惩罚

        candidates.append((score, pid, entry['face_count']))

    # 选最优
    best_score, best_pid, best_cnt = max(candidates, key=lambda x: x[0])
    
    # 阈值判断（同时考虑特征 + 空间）
    if best_score >= FEATURE_MATCH_THRESHOLD:
        return best_pid, best_score
    
    # 边缘情况：特征接近阈值 + 空间强匹配 -> 通过
    base_sim = float(np.dot(query_n, self._normalize(
        np.array(self.persons[best_pid]['avg_feature'], dtype=np.float32))))
    if base_sim >= FEATURE_MATCH_THRESHOLD - 0.10 and best_score >= FEATURE_MATCH_THRESHOLD:
        return best_pid, best_score
    
    return None, best_score
```

### B3 新增方法：更新座位历史

```python
def record_seat(self, person_id, bbox_center, period=None):
    """记录一个人脸的座位位置"""
    if person_id in self.persons:
        seats = self.persons[person_id].setdefault('seats', [])
        seats.append((bbox_center[0], bbox_center[1], period))
        # 保持 seats 最多 100 条最新记录（滑动窗口）
        if len(seats) > 100:
            seats.pop(0)
        # 缓存座位中心
        cx = np.mean([s[0] for s in seats])
        cy = np.mean([s[1] for s in seats])
        self.persons[person_id]['seat_centroid'] = (cx, cy)
```

### B4 修改管线主循环调用点

```python
# init_data_pipeline.py 管线主循环中（~line 435）
# 改前：
pid, sim = matcher.match(feature_vec, bbox_center, same_period=False)

# 改后：
pid, sim = matcher.match(feature_vec, bbox_center, period=period_label)
if pid:
    matcher.update_person(pid, feature_vec)
    matcher.record_seat(pid, bbox_center, period_label)  # 新增
```

---

## 五、关键配置参数（建议值）

| 参数 | 当前值 | 建议值 | 说明 |
|------|--------|--------|------|
| `FEATURE_MATCH_THRESHOLD` | 0.55 | 0.50 | 空间辅助后可以适当降低特征阈值 |
| `SPATIAL_DISTANCE_THRESHOLD` | 200px | 200px | 合理，同排座位间距 |
| `SAME_PERIOD_MATCH_BOOST` | 0.05 | 0.04 | 同节次加分（拆分用在了 match() 逻辑中） |
| `SPATIAL_BOOST` (新增) | — | 0.08 | 基础空间匹配加分 |
| `SPATIAL_PENALTY` (新增) | — | 0.10 | 空间不匹配扣分 |
| `OUTLIER_Z_SCORE` (新增) | — | 2.5 | 座位异常检测的 z-score 阈值 |

---

## 六、预期效果

### 6.1 正面效果

| 场景 | 改前 | 改后 |
|------|------|------|
| 同人不同光照（特征 0.45-0.50） | 创建新人 | 空间加分 → 正确匹配 |
| 同人，节次相同、特征稳定 | 匹配成功（不变） | 匹配成功（更稳，加分只会巩固） |
| 邻座同学侧脸相似（特征 0.60-0.65） | 可能误匹配 | 空间扣分 → 拒绝，减少误报 |
| 未归属人脸靠近已有学生座位 | 无法关联 | 空间推测建议关联 |

### 6.2 量化预期（估算，基于 2000 face_record）

- 异常匹配检出率（spatial outlier）：约 5-15 条（1% 以内）
- 未归属人脸建议关联：约 30-60 条（占 student_id IS NULL 的 20-40%）
- 特征阈值从 0.55 降至 0.50 后，新增正确匹配（防漏）：约 30-50 条

### 6.3 风险与注意事项

| 风险 | 影响 | 缓解 |
|------|------|------|
| 拍摄角度变化导致 bbox 漂移 | 空间失效 | 限制同节次内使用空间信号（同节次拍摄位置固定） |
| 调座位 | 历史座位模型失效 | 检测到连续异常后重新校准 seat 中心 |
| 站立/走动（课间） | bbox 位置不固定 | 课间类的 period 禁用空间加权 |
| 人脸过小（38px） | bbox 精度低 | 空间信号只在 CX > 200px 的图像上使用 |

---

## 七、执行顺序

```
步骤 0: 确认数据库连接、导出 face_record 数据样本  (5 min)
步骤 1: 运行 A1 SQL 分析，查看座位分布概览          (2 min)
步骤 2: 运行 A2 Python 校准脚本                      (5 min)
步骤 3: 人工审查 report_spatial_outliers.csv         (10-20 min)
步骤 4: 执行修复 SQL（根据人工审查结果）              (2 min)
步骤 5: 审查 report_missed_links.csv                 (5 min)
步骤 6: 阶段 A 汇报：空间信号是否有效？              (5 min)
步骤 7: 如果有效：实施阶段 B，修改 matcher           (30 min)
步骤 8: 重启管线 --resume，验证新匹配效果             (持续监控)
```

---

## 八、相关文件

| 文件 | 用途 |
|------|------|
| `scripts/spatial_calibration.py` | 阶段 A 校准脚本（待创建） |
| `init_data_pipeline.py` | 阶段 B 待修改的主管线 |
| `face_record` (DB) | 存储 bbox JSON, student_id, face_encoding |
| `class_image` (DB) | 存储 capture_time, period_label |
| `report_*.csv` | 脚本输出（待生成） |
