# push_to_external.py 执行命令

**脚本位置:** `/home/zebra/Downloads/官渡一中初一班-0526/push_to_external.py`
**功能:** 将本地人脸情绪数据推送到 ylcs.htface.cn 外部平台

---

## 前置检查

```bash
# Python 依赖
python3 -c "import pymysql; import requests; print('deps OK')"

# 确认服务器地址可达
curl -s -o /dev/null -w "%{http_code}" http://ylcs.htface.cn:33895/api/Page/Execute -X POST -H "Content-Type: application/json" -d '{}' 2>/dev/null

# 确认后端在运行（用于图片 URL 转换）
curl -s -o /dev/null -w "%{http_code}" http://localhost:8090/api/v1/auth/login -X POST -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}' 2>/dev/null
```

---

## 执行命令

### 1. 推送 Top 50 学生 + 情绪（推荐）

只推送人脸数最多的 50 个学生（覆盖 94.8% 的人脸数据，排除过分割碎片）：

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/push_to_external.py --top 50
```

### 2. 干跑验证（不实际发送）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/push_to_external.py --top 50 --dry-run
```

### 3. 断点续传

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/push_to_external.py --top 50 --resume
```

### 4. 仅推送学生信息（不含情绪）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/push_to_external.py --top 50 --students-only
```

### 5. 仅推送情绪记录（不含学生信息）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/push_to_external.py --top 50 --emotions-only
```

### 6. 推送全部学生（419 个）

```bash
python3 /home/zebra/Downloads/官渡一中初一班-0526/push_to_external.py
```

---

## 验证

```bash
# 查看检查点状态
cat /tmp/external_push_checkpoint.json | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('Students pushed:', len(d.get('pushed_student_ids',[])))
print('Emotions pushed:', len(d.get('pushed_emotion_ids',[])))
print('Emotions failed:', len(d.get('failed_emotion_ids',[])))
print('Confirmations:', len(d.get('confirmations',[])))
if d.get('confirmations'):
    last = d['confirmations'][-1]
    print('Last batch:', last.get('batch'), 'inserted:', last.get('inserted_count'))
"
```

---

## 参数说明

| 参数 | 默认 | 说明 |
|------|------|------|
| `--top N` | 全部 | 只推送人脸数最多的 N 个学生 |
| `--students-only` | false | 仅推送学生信息（含照片 URL） |
| `--emotions-only` | false | 仅推送情绪记录 |
| `--dry-run` | false | 干跑，不实际发送 HTTP 请求 |
| `--resume` | false | 断点续传，跳过已推送的记录 |
| `--start-id N` | 0 | 从指定 emotion_record ID 开始推送 |
| `--max N` | 0 | 最多推送 N 条情绪记录 |
| `--batch N` | 200 | AddEmotion 每批条数 |
| `--server URL` | http://localhost:8090 | 后端地址（图片 URL 转换用） |
| `--camera CODE` | CAM_DEFAULT | 摄像头编码 |

---

## 情绪映射

| 内部 DB 值 | 外部 API 编码 |
|------------|--------------|
| happy | happy |
| sad | sad |
| angry | angry |
| disgust | angry |
| surprise | surprised |
| fear | fearful |
| neutral | calm |
| contempt | calm |

---

## 推送数据量

| 模式 | 学生数 | 情绪记录数 |
|------|--------|-----------|
| `--top 50` | 50 | ~82,000 |
| 全部 | 419 | ~87,000 |
