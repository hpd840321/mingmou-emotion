# process_faces_step3.py 执行命令

**工作目录:** `/home/zebra/Downloads/官渡一中初一班-0526`

## 策略

对每张**原图**调用 gRPC Analyze（含 FEAT_RECOGNITION），按 bbox 中心点匹配已有 face_record，提取 128-dim 特征写入 MySQL + Qdrant。

原图级调用（~7k 次）而非裁剪图级（~219k 次），避免 face_server 因小图崩溃。
face_server 存在已知 C++ 内存 double-free 问题，脚本内置 Docker 自动重启 + 重连逻辑。

## 前置检查

```bash
docker ps | grep -E "face|qdrant"
ls /tmp/proto_out/inference_pb2*.py
python3 -c "import grpc; import pymysql; import requests; print('deps OK')"
```

## 执行

### 断点续传（推荐）
```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step3.py --resume
```

### 从指定 ID 开始
```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step3.py --start-id 100
```

### 试跑 10 张图验证
```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step3.py --max 10
```

### 重置检查点全量
```bash
rm -f /tmp/face_feature_checkpoint.json
python3 /home/zebra/Downloads/官渡一中初一班-0526/process_faces_step3.py
```

## 验证

```bash
# face_encoding 数量
mysql -h 192.168.3.12 -P 3307 -u root -p123456 -e \
  "SELECT COUNT(*) FROM emotion_platform.face_record WHERE face_encoding IS NOT NULL AND face_encoding != '';"

# Qdrant 向量数
curl -s http://localhost:6333/collections/face_features | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print('Qdrant:', d['result']['points_count'])"

# 检查点
cat /tmp/face_feature_checkpoint.json | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print('CI:', len(d.get('processed_ci_ids',[])), 'faces:', d.get('stats',{}).get('faces',0))"
```

## 容错

- face_server 内存 double-free 导致 UNAVAILABLE → 自动 docker restart
- 重启后重建 gRPC channel → 继续处理
- 每 200 CI 存一次 checkpoint
- 失败 CI 单独记录，不阻塞后续
