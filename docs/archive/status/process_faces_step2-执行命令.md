# process_faces_step2.py 执行命令

**工作目录:** `/home/zebra/Downloads/官渡一中初一班-0526`

---

## 前置依赖检查

```bash
# 1. 确认 Docker 服务全部运行中
docker ps | grep -E "face|emotion|attribute|api|qdrant|postgres|redis"

# 必须看到:
#   docker-face-1-1      visionmind-face:latest        (port 50053)
#   docker-emotion-1     visionmind-emotion:latest     (port 50057)
#   docker-attribute-1   visionmind-attribute:latest   (port 50058)
#   docker-api-1         visionmind-api:latest         (port 8080)
#   docker-qdrant-1      qdrant/qdrant:v1.16.2        (port 6333)

# 2. 确认 protobuf 编译产物存在
ls /tmp/proto_out/inference_pb2*.py

# 3. 确认 Python 依赖齐全
python3 -c "import grpc; import pymysql; import requests; print('deps OK')"

# 4. 测试 emotion_server gRPC 连通性（用真实裁剪图测试）
python3 -c "
import os, sys, grpc
sys.path.insert(0, '/tmp/proto_out')
from inference_pb2_grpc import EmotionServiceStub
from inference_pb2 import EmotionRequest
channel = grpc.insecure_channel('localhost:50057')
stub = EmotionServiceStub(channel)
crop = '/home/zebra/Downloads/官渡一中初一班-0526/emotion-platform/images/cropped'
for r,d,f in os.walk(crop):
    for fn in f[:1]:
        fp = os.path.join(r,fn)
        with open(fp,'rb') as fh:
            resp = stub.Predict(EmotionRequest(image_data=fh.read()), timeout=15)
        if resp.success:
            print(f'gRPC OK: {resp.emotion.label} ({resp.emotion.emotion})')
            break
    break
"

# 5. 查看当前待处理数量
python3 -c "
import pymysql
conn = pymysql.connect(host='192.168.3.12', port=3307, user='root', password='123456', database='emotion_platform', charset='utf8mb4')
c = conn.cursor()
c.execute(\"SELECT COUNT(*) FROM face_record fr LEFT JOIN emotion_record er ON er.face_record_id = fr.id WHERE er.id IS NULL\")
print(f'待处理情绪识别: {c.fetchone()[0]} 条')
c.execute(\"SELECT COUNT(*) FROM face_record WHERE status='IDENTIFIED' AND (lib_register_status IS NULL OR lib_register_status='pending')\")
print(f'待注册到图库: {c.fetchone()[0]} 条')
c.close(); conn.close()
"
```

---

## 执行命令

### 1. 首次运行（全量处理情绪识别）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py
```

处理所有 219,315 条待处理 face_record，预计耗时 ~1.5 小时（38+条/秒）。

### 2. 断点续传（推荐）

处理中断后继续，跳过已处理的 ID：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py --resume
```

### 3. 限速调试（先跑 100 条验证）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py --max 100
```

### 4. 从指定 ID 开始

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py --start-id 50000
```

### 5. 断点续传 + 限速

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py --resume --max 5000
```

### 6. 情绪识别 + 图库注册

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py --register
```

### 7. 仅图库注册（跳过情绪识别）

用于已经完成情绪识别的 face_record 补注册：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py --register-only
```

### 8. 重置检查点后全量重跑

```bash
rm -f /tmp/face_emotion_checkpoint.json
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py
```

---

## 执行后验证

```bash
# 1. emotion_record 总数
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT COUNT(*) AS emotion_records FROM emotion_platform.emotion_record;"

# 2. face_record 状态分布
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT status, COUNT(*) AS cnt FROM emotion_platform.face_record GROUP BY status;"

# 3. 情绪类别分布
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT dominant_emotion, COUNT(*) AS cnt FROM emotion_platform.emotion_record GROUP BY dominant_emotion ORDER BY cnt DESC;"

# 4. 图库注册状态
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT lib_register_status, COUNT(*) AS cnt FROM emotion_platform.face_record GROUP BY lib_register_status;"

# 5. 查看检查点
cat /tmp/face_emotion_checkpoint.json | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'processed: {len(d[\"processed_ids\"])}, failed: {len(d[\"failed_ids\"])}, registered: {len(d[\"registered_ids\"])}')
print(f'stats: {d[\"stats\"]}')
"
```

---

## 脚本说明

| 项目 | 说明 |
|------|------|
| **脚本位置** | `/home/zebra/Downloads/官渡一中初一班-0526/process_faces_step2.py` |
| **功能** | 情绪识别 + 人脸注册到图库 |
| **情绪引擎** | `emotion_server` (EmotiEffLib TRT, port 50057, 8-class) |
| **图库注册** | `visionmind-api` REST (port 8080, POST /v1/facedb/register) |
| **检查点文件** | `/tmp/face_emotion_checkpoint.json`（每 500 条自动保存） |
| **容错** | gRPC 失败自动重试 3 次 |
| **软最大** | logits → softmax → 概率，存 8 维 emotion 向量 |

## 数据库状态（截至 2026-06-02）

| 表 | 状态 | 数量 |
|---|---|---|
| `emotion_record` | 情绪记录 | 298（测试写入） |
| `face_record` DETECTED | 待识别情绪 | 219,305 |
| `face_record` IDENTIFIED | 已识别情绪 | 298 |
| 待处理总数 | — | ~219,305 条 |

**预计全量处理耗时**: ~1.5 小时（基于测试速度 ~40 条/秒）
