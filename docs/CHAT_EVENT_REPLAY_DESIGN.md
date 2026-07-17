# OpenAgent 聊天会话断线恢复与事件回放机制设计

> 本文档面向技术分享与简历素材整理，系统介绍 OpenAgent 项目中聊天会话的断线恢复、事件回放与 SSE 实时推送设计。

---

## 一、背景与挑战

OpenAgent 是一个自托管 AI Agent 平台，聊天模块需要支持：

- **流式回答**：用户发送消息后，AI 以 Server-Sent Events（SSE）方式逐字返回；
- **页面刷新/断网恢复**：用户刷新页面或网络闪断后，不能丢失正在生成的回答；
- **多端/多标签页订阅**：同一个会话可在多个浏览器标签页中同时打开，状态保持一致；
- **有限资源下的高并发**：长连接不能占用大量线程，避免连接数拖垮服务。

核心难点在于：

1. 模型调用是异步的，HTTP 连接断开后模型仍在继续生成；
2. 刷新后既要恢复历史消息，又要补拉断线期间的增量事件；
3. 回放过程中可能同时有新的持久化事件落库，必须做到**不重不漏**。

---

## 二、整体方案

项目采用两条 SSE 通道：

| 端点 | 职责 | 生命周期 |
|------|------|----------|
| `POST /api/chat/stream` | 发起新回合，转发流式增量 + 完整事件 | 一个回答结束即关闭 |
| `GET /api/chat/subscribe` | 长连接订阅会话事件，支持断线恢复 | 页面存活期间保持 |

配合一个单调递增的事件序号 `seq`，实现「基于事件序列号的断线重连与回放」。

---

## 三、核心设计要点

### 3.1 会话级单调递增事件序号 `seq`

每条持久化事件（`content` / `done` / `error`）写入 `session_events` 表时，都会分配一个在当前会话内单调递增的 `seq`：

```sql
INSERT INTO session_events (..., seq, ...)
SELECT ?, ..., COALESCE(MAX(seq), 0) + 1, ...
  FROM session_events
 WHERE user_id = ? AND agent_id = ? AND session_id = ?
RETURNING seq
```

该设计将序号分配下沉到数据库层，单条语句完成「取最大值 + 1」与「插入」，避免 JVM 侧锁竞争，天然适配 SQLite / PostgreSQL 的事务隔离。

### 3.2 持久化事件 vs 瞬时事件

| 类型 | 事件 | seq | 是否落库 | 用途 |
|------|------|-----|----------|------|
| 持久化事件 | `content`、`done`、`error` | `seq ≥ 1` | 是 | 断线后可回放 |
| 瞬时事件 | `content_delta` | `seq = -1` | 否 | 仅直播时逐字渲染 |

把高频逐字增量设计为瞬时事件，避免大量低价值数据写入数据库；页面刷新后只需拿到最终 `content` 即可恢复完整回答。

### 3.3 subscribe-before-replay：先占座，再补漏

恢复流程严格遵循「先订阅、再回放」顺序：

1. `GET /api/chat/subscribe` 建立 SSE 连接；
2. 立即向事件中心注册订阅，后续实时事件先暂存到 backlog；
3. 从数据库回放 `seq > cursor` 的历史事件；
4. 回放结束后执行 `goLive()`，将 backlog 中未送达的事件按 `seq` 去重刷出；
5. 之后进入实时推送模式。

这种方式解决了回放窗口与实时事件之间的竞态：如果先回放再订阅，回放期间落库的事件会丢失；先订阅则能把这些事件暂存，最终通过 `seq` 去重保证不重不漏。

### 3.4 SSE 协议与浏览器自动重连

事件帧格式如下：

```text
id: 43
data: {"seq":43,"type":"content","data":{"content":"你好"}}

: ping
```

- `id:` 行供浏览器 `EventSource` 自动维护 `Last-Event-ID`，断线重连时无需前端手动记忆游标；
- `data:` 行内嵌 `seq`，供前端二次校验去重；
- `: ping` 为 30 秒心跳注释帧，防止 nginx / ELB 等中间件因空闲而切断连接。

### 3.5 推模式 + 写失败即回收

事件中心采用推模式：发布线程直接回调所有订阅者的 listener，连接本身不占用任何轮询线程。

- 写失败（客户端已断开）立即关闭连接并注销订阅；
- 心跳帧同时充当死连接探测；
- 单个慢消费者/坏连接被注销，不影响其他订阅者和回合线程。

该设计使长连接数不再受限于 MVC 异步线程池容量，可支撑大量并发 SSE 连接。

### 3.6 异常路径保证收敛

无论模型调用失败、API Key 未配置还是网络超时，回合收尾处都会强制发布两个持久化事件：

1. `error`：告知前端出错原因；
2. `done`：明确标记回合结束。

这样前端不会出现「AI 正在输入中」无限转圈的情况，任何情况下都能正常收敛。

---

## 四、完整恢复时序

```text
页面刷新 / 网络恢复 / 切换会话
        │
        ▼
 GET /api/chat/history
        │
        ▼ 返回 { history, latestEventSeq }
        │
        ▼
 GET /api/chat/subscribe?since=latestEventSeq
        │
        ▼
  ① 注册订阅（占座）
  ② 回放 seq > cursor 的持久化事件
  ③ goLive() 去重刷出 backlog
        │
        ▼
  进入实时推送，后续事件直接下发
```

---

## 五、技术收益

| 维度 | 收益 |
|------|------|
| 可靠性 | 页面刷新、断网、多标签页场景下，聊天状态可完整恢复 |
| 一致性 | 基于数据库级 `seq` 分配与去重，保证事件不重不漏 |
| 可扩展性 | 推模式 SSE 不占用线程池，长连接数可线性扩展 |
| 容错性 | 异常路径强制 `error + done`，前端必然收敛 |
| 兼容性 | 对齐标准 SSE 协议与浏览器 `Last-Event-ID` 自动重连机制 |

---

## 六、可写在简历中的描述示例

> 负责 OpenAgent 聊天模块的 SSE 实时推送与断线恢复设计。通过引入会话级单调递增事件序号 `seq`，区分持久化事件与瞬时事件，实现「subscribe-before-replay」回放机制，解决页面刷新、网络闪断、多标签页订阅场景下的事件丢失与重复问题；采用推模式事件中心 + 写失败即回收策略，使 SSE 长连接不占用 MVC 异步线程池，提升并发连接承载能力；设计 `error + done` 兜底机制，确保任何异常路径下前端回合都能正常收敛。

---

## 七、关键代码位置

| 文件 | 说明 |
|------|------|
| `bootstrap/src/main/java/ai/openagent/bootstrap/chat/controller/ChatController.java` | `subscribe` / `stream` / `history` 端点 |
| `bootstrap/src/main/java/ai/openagent/bootstrap/chat/sse/ChatSseStreamFactory.java` | SSE 连接工厂、心跳调度 |
| `bootstrap/src/main/java/ai/openagent/bootstrap/chat/sse/ChatSseStream.java` | 单条 SSE 连接、闸门/回放/goLive/seq 去重 |
| `bootstrap/src/main/java/ai/openagent/bootstrap/chat/event/ChatEventHub.java` | 进程内发布订阅中心 |
| `bootstrap/src/main/java/ai/openagent/bootstrap/chat/service/impl/ChatServiceImpl.java` | 事件发布、回放、异常兜底 |
| `bootstrap/src/main/java/ai/openagent/bootstrap/persistence/ChatSessionRepository.java` | 事件持久化、seq 分配、回放查询 |
| `bootstrap/src/main/java/ai/openagent/bootstrap/chat/controller/vo/ChatHistoryVO.java` | 恢复游标 VO |

---

*文档维护者：Kimi Code CLI*  
*最后更新：2026-07-17*
