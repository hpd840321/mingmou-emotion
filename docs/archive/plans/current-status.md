# 当前系统状态

## 已完成（全部就绪）

| 组件 | 状态 | 说明 |
|------|:----:|------|
| 后端应用 | ✅ | 运行在 :8090，MySQL 连接正常 |
| 34 个测试 | ✅ | 全部通过 |
| 数据扫描 | ✅ | `--app.scan.auto=true` 可自动导入 data/ |
| student 自动关联 | ✅ | pipeline 自动创建 Student + 关联 face_record |
| 人脸抠图 | ✅ | Java BufferedImage 裁剪 + 30% 扩边 |
| 人脸库注册 | ✅ | /v1/facedb/register |
| 聚类分析 | ✅ | Qdrant 向量 + 余弦相似度 BFS 聚类 |
| 多维聚合 | ✅ | 按 class×date 聚合，每10分钟自动运行 |

## 阻塞问题

**face_server 引擎返回 0 个人脸** — InspireFace C++ 服务能启动、模型加载成功、GPU 正常，但对所有图片返回空检测结果。该问题在容器多次重启后出现，可能原因：
- Megatron 模型包损坏或与当前 TensorRT 版本不兼容
- GPU 推理状态退化
- 检测模型未正确加载

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/admin/pipeline/run` | 启动完整处理管线 |

## 引擎修复后操作

```bash
# 1. 确认 face_server 恢复
curl -X POST http://localhost:8080/v1/face/detect \
  -H "Content-Type: application/json" \
  -d '{"image_base64":"<base64_image>"}'

# 2. 重置图片状态
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "UPDATE emotion_platform.class_image SET status='PENDING', error_message=NULL;"

# 3. 触发管线
curl -X POST http://localhost:8090/api/v1/admin/pipeline/run
```
