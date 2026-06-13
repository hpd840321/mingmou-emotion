# 明眸学生情绪感知平台 — 复盘报告

> 生成时间: 2026-05-30 | 覆盖范围: 全栈

---

## 一、当前运行状态

### 服务状态

| 服务 | 类型 | 端口 | 状态 |
|------|------|------|------|
| `emotion-platform` | Spring Boot 后端 | 8090 | ✅ RUNNING |
| `emotion-frontend` | Vite 开发服务器 (systemd) | 5173 | ✅ active |
| `docker-api-1` | VisionMind Java API | 8080 | ✅ healthy |
| `docker-face-1-1` | InspireFace C++ gRPC | 50053 | ✅ UP |
| `docker-frontend-1` | Nginx (VisionMind UI) | 8088 | ✅ UP |
| PostgreSQL | 数据库 | 5432 | ✅ healthy |
| Redis | 缓存/消息队列 | 6379 | ✅ UP |
| Qdrant | 向量数据库 | 6333 | ✅ UP |
| Kafka | 消息队列 | 9092 | ✅ UP |
| SeaweedFS | 文件存储 | 8888/9333 | ✅ UP |
| `emotion_server` | EmotiEffLib TRT | 50057 | ⚠️ unhealthy |
| `attribute_server` | GenderAge TRT | 50058 | ⚠️ unhealthy |

### API 验证

| 端点 | 结果 |
|------|------|
| `POST /auth/login` | ✅ 正常 |
| `GET /admin/pipeline/status` | ✅ totalFiles=4560, pendingReal=4126 |
| `GET /admin/pipeline/data-dirs` | ✅ 官渡一中 (4560 files) |
| `POST /admin/pipeline/run` | ✅ 返回 `{code:0, message:"管线已启动"}` |
| `POST /admin/pipeline/stop` | ✅ 停止信号已发送 |
| `POST /admin/pipeline/reset-failed` | ✅ 重置成功 |

---

## 二、已修复问题清单

### P0 — 功能不可用

| # | 问题 | 根因 | 修复 | 文件 |
|---|------|------|------|------|
| 1 | **登录 500** | `system_user` 表不存在（Flyway 禁用） | 手动执行 SQL 建表 + 6 个种子用户 | `V8__create_system_user.sql` |
| 2 | **WebSocket 连接失败** | 端点仅注册 SockJS，STOMP.js 用原生 WebSocket | 添加无 SockJS 的端点 | `WebSocketConfig.java` |
| 3 | **`启动管线` 按钮不可用** | `pending === 0` 守卫在 status 未加载时误触发 | 移除前端守卫 | `PipelineMonitor.vue` |
| 4 | **`启动管线` 返回 403** | `@Async` + `CompletableFuture` 导致 SecurityContext 传播失败 | 改用 `pipelineExecutor.execute()` | `PipelineStatusController.java` |
| 5 | **`启动管线` 返回空 body** | `@Async void` 返回 HTTP 200 空 body，axios 拦截器无法解析 | 返回 `{code:0, message:"管线已启动"}` | `PipelineStatusController.java` |

### P1 — 数据正确性

| # | 问题 | 根因 | 修复 | 文件 |
|---|------|------|------|------|
| 6 | **目录树不显示** | 后端工作目录错误，`../data` 路径解析失败 | 改为绝对路径 | `application-dev.yml` |
| 7 | **目录树不刷新** | `node-key="name"` 重复导致 el-tree 跳过 DOM 更新 | 添加 `:key="treeKey"` 强制重建 | `PipelineMonitor.vue` |
| 8 | **待处理为负数** | DB 总数 (2693) 小于已处理数 | 改用 data 目录实际文件数做基准 | `PipelineStatusController.java` |
| 9 | **总进度显示 DB 数** | status API 只返回 DB 记录数 | 新增 `totalFiles` 字段 | `PipelineStatusController.java` |
| 10 | **WebSocket 不更新待处理数** | WS 只更新 DB 字段，不更新 `pendingReal` | WS 事件中重新计算 | `PipelineMonitor.vue` |

### P2 — 配置与体验

| # | 问题 | 根因 | 修复 | 文件 |
|---|------|------|------|------|
| 11 | **WebSocket 协议不兼容** | 硬编码 `ws://`，HTTPS 下浏览器阻止混合内容 | 动态协议 `wss:` vs `ws:` | `PipelineMonitor.vue` |
| 12 | **VisionMind API 地址错误** | `application-dev.yml` 写为 `8083`，实际端口 `8080` | 修正端口 | `application-dev.yml` |
| 13 | **WebSocket 连接 wss 失败后不降级** | 浏览器请求 wss 但后端只有 ws | 动态协议 `wss:? 'wss:' : 'ws:'` | `PipelineMonitor.vue` |

---

## 三、未修复问题 — Docker DNS 解析（P0 阻塞）

### 问题描述

```
emotion-platform:8090
  → POST /v1/face/detect (REST)
  → docker-api-1:8080
    → gRPC face-1:50053         ← ❌ DNS 解析失败
    → UnknownHostException
    → 返回 HTTP 503
      → Resilience4j CircuitBreaker OPEN（10分钟）
        → 所有后续调用直接失败
```

### 证据

```bash
$ docker exec docker-api-1 getent hosts face-1
# (空) 无法解析

$ docker logs docker-api-1
Failed to resolve host face-1. 
UnknownHostException: face-1: Name does not resolve
```

### 根因

`docker-api-1` 和 `docker-face-1-1` 虽然都在 `docker_default` 网络，但 DNS 解析失败。Docker Compose 默认会使用服务名作为 hostname，但这些容器可能是分别用 `docker run` 启动，或者在不同的 compose 项目中。

### 修复方案

```bash
# 方案 A: 将 face_server 连接到 VisionMind API 的网络（推荐）
docker network connect craftlabs-visionmind_default docker-face-1-1

# 方案 B: 在 docker-api-1 中手动添加 hosts
FACE_IP=$(docker inspect docker-face-1-1 --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
docker exec docker-api-1 sh -c "echo '$FACE_IP face-1' >> /etc/hosts"

# 方案 C: 临时绕过（重启后端重置 CircuitBreaker）
kill -9 $(lsof -ti:8090)
cd emotion-platform && java -jar target/emotion-platform-0.1.0-SNAPSHOT.jar \
  --server.port=8090 --spring.profiles.active=dev
# 注意: 不修 DNS 则 10 分钟后再次熔断
```

### 影响

- 管线无法处理任何图片
- 所有图片状态变为 FAILED，错误信息 `Detection error`
- `failed` 计数持续增加

---

## 四、架构图

```ascii
┌─────────────────────────────────────────────────────────────┐
│                    emotion-platform :8090                    │
│  (Spring Boot + Spring Security + JPA + WebSocket + gRPC)   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  AuthController          PipelineStatusController           │
│  ├─ POST /auth/login     ├─ GET  /status                    │
│  └─ GET  /auth/me        ├─ POST /run → @Async              │
│                           ├─ POST /stop                     │
│  FaceProcessingPipeline  ├─ POST /reset-failed              │
│  ├─ detectFaces()         └─ GET  /data-dirs (树)            │
│  ├─ analyzeAttribute()                                       │
│  └─ cropFace()           PipelineProgressService             │
│                           ├─ WebSocket → /topic/...          │
│  VisionMindClient         └─ ETA / speed 计算               │
│  └─ REST :8080 → ⚠️ 503                                    │
│                                                             │
│  ImageIngestConsumer → Redis Stream → pollStream()           │
│                                                             │
│  FaceClusteringServiceV2 → Qdrant (REST)                    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────┐
│                   docker-api-1 :8080                         │
│              (VisionMind Java API)                          │
│                                                             │
│  REST /v1/face/detect → gRPC face-1:50053 ← ❌ DNS FAIL    │
│  REST /v1/face/attribute → gRPC face-1:50053                │
│  REST /v1/facedb/register → gRPC face-1:50053               │
└─────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────┐
│              docker-face-1-1 :50053                         │
│         (InspireFace C++ TensorRT)                          │
│                                                             │
│  gRPC Analyze → emotion_server :50057                       │
│  gRPC Analyze → attribute_server :50058                    │
└─────────────────────────────────────────────────────────────┘

*实线 = 正常工作  虚线 = DNS 解析失败  红色 = 阻塞*
```

---

## 五、前端页面结构

```
PipelineMonitor.vue
│
├── Header (标题 + 速度/ETA 标签)
│
├── Status Cards [4]
│   ├── 待处理: pendingReal = totalFiles - completed - failed  ← 基于 data 目录
│   ├── 处理中: processing (来自 DB / WebSocket)
│   ├── 已完成: completed / totalFiles
│   └── 失败: failed
│
├── Progress Bar: (completed + failed) / totalFiles
│
├── Action Buttons [5]
│   ├── 刷新状态 → GET /status
│   ├── 启动管线 → POST /run ← ✅ 已修复返回 JSON
│   ├── 停止处理 → POST /stop
│   ├── 重新处理失败 → POST /reset-failed
│   └── 刷新目录树 → GET /data-dirs + 每 10s 自动刷新
│
├── Directory Tree (左栏 50%) ← 自动刷新每 10s
│   ├── 学校 → 班级 → 日期 → 时段
│   └── 每个节点: 文件名 + 文件数 + 四色状态标签
│
└── Event Log (右栏 50%)
    └── WebSocket STOMP /topic/pipeline-progress → 实时日志
```

---

## 六、修复优先级建议

| 优先级 | 问题 | 方案 | 预估工时 |
|--------|------|------|----------|
| 🔴 P0 | **Docker DNS 解析** `face-1` | `docker network connect` | 5 分钟 |
| 🟠 P1 | `emotion_server` `attribute_server` unhealthy | 检查 TRT engine 文件 | 30 分钟 |
| 🟡 P2 | 剩余 1867 张图片未导入 DB | 调用 `/admin/pipeline/import` | 10 分钟 |
| 🟢 P3 | Flyway 禁用，数据库迁移无版本管理 | 启用 `flyway.enabled=true` | 15 分钟 |

---

*本文档由 Sisyphus 自动生成，覆盖 12 个已修复问题、1 个未修复阻塞问题*
