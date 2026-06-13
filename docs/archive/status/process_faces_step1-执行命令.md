# process_faces_step1.py 执行命令

**工作目录:** `/home/zebra/Downloads/官渡一中初一班-0526`

---

## 前置依赖检查

```bash
# 1. 确认 protobuf 编译产物存在
ls /tmp/proto_out/inference_pb2*.py

# 2. 确认 Python 依赖齐全
python3 -c "import grpc; import pymysql; from PIL import Image; print('deps OK')"

# 3. 确认 face_server 容器在运行
docker ps | grep face

# 4. 测试 gRPC 连通性
python3 -c "
import grpc, sys
sys.path.insert(0, '/tmp/proto_out')
from inference_pb2_grpc import FaceServiceStub
from inference_pb2 import FaceAnalysisRequest
channel = grpc.insecure_channel('localhost:50053')
stub = FaceServiceStub(channel)
req = FaceAnalysisRequest(image_data=b'test', enabled_features=0x01)
try:
    resp = stub.Analyze(req, timeout=5)
    print('gRPC OK')
except Exception as e:
    print(f'gRPC FAIL: {e}')
"
```

---

## 执行命令

### 1. 断点续传（推荐用这个）

跳过已处理的 ID，从上次中断处继续处理剩余 6,120 张 PENDING 图片：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step1.py --resume
```

### 2. 先试跑 10 张验证

快速验证 face_server 和管线正常：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step1.py --max 10
```

### 3. 从指定 ID 开始

跳过检查点，从某个 class_image ID 开始处理：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step1.py --start-id 2000
```

### 4. 重置检查点后全量跑

如果检查点文件损坏或想重跑：

```bash
rm -f /tmp/face_detection_checkpoint.json
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step1.py
```

### 5. 限速调试（处理前 50 张 + 断点续传）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step1.py --resume --max 50
```

---

## 执行后验证

```bash
# 查 class_image 状态分布
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT status, COUNT(*) AS cnt FROM emotion_platform.class_image GROUP BY status;"

# 查 face_record 总数
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "SELECT COUNT(*) AS face_records FROM emotion_platform.face_record;"

# 查裁剪图片数量
find /home/zebra/Downloads/官渡一中初一班-0526/emotion-platform/images/cropped -name '*.jpg' | wc -l

# 查看当前检查点状态
cat /tmp/face_detection_checkpoint.json | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'processed: {len(d[\"processed_ids\"])}, failed: {len(d[\"failed_ids\"])}, faces: {d[\"stats\"][\"total_faces\"]}')"
```

---

## 当前数据库状态（截至 2026-06-01）

| 表 | 状态 | 数量 |
|---|---|---|
| `class_image` COMPLETED | 已完成检测 | 1,133 |
| `class_image` PENDING | 待处理 | 6,120 |
| `face_record` | 已检出人脸记录 | 37,170 |
| 裁剪图片文件 | 已保存到 `images/cropped/` | 39,680 张 / 175MB |
| 检查点记录的检测总数 | — | 36,564 个人脸 |

---

## 脚本说明

- **脚本位置**: `/home/zebra/Downloads/官渡一中初一班-0526/process_faces_step1.py`
- **功能**: 人脸检测 + 裁剪，直连 `face_server:50053` gRPC，结果写入 MySQL `face_record` + 裁剪图片存盘
- **检查点文件**: `/tmp/face_detection_checkpoint.json`（每 50 张自动保存）
- **容错**: gRPC 失败自动重启 `docker-face-1-1` 容器并重试（最多 3 次）
- **裁剪输出**: `emotion-platform/images/cropped/官渡一中/初一班/{日期}/{时段}/`
