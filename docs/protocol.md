# 接口契约 · Protocol v0.1

> 目的：让**任何** Agent 服务用最小的代价接上 Cat 面板。
> 一个服务只要实现 `POST /api/text`，面板就能用；其余两个接口都是可选增强。

---

## 0. 设计原则

1. **三个接口封顶。** 接口越多，能接的人越少。新能力通过可选接口和响应字段扩展，不加新端点。
2. **不规定你怎么实现 Agent。** 你可以是 OpenClaw、可以是 OpenAI 兼容服务、可以是一个 shell 脚本。面板只关心 HTTP 的进和出。
3. **无状态优先。** 服务端可以完全无状态；需要多轮上下文时由服务端自己按 `session_id` 维护。
4. **不传音频给第三方。** 本协议不涉及任何外部服务；服务端如果自己调云端 ASR/模型，由服务端负责告知用户。

---

## 1. 认证

除 `/api/health` 外，所有请求带：

```
Authorization: Bearer <token>
```

`token` 由用户在服务端生成，和地址一起填进手机。

**兼容（v0.1 参考实现保留）**：也接受 `X-Panel-Key: <token>`，或 query 参数 `?k=<token>`（给不便于加 header 的简单脚本用）。

**约定**：token 至少 16 字符随机串（`openssl rand -hex 8`）。服务端只接受常量时间比较，不要用 `==` 直接比字符串。

失败返回：

```json
HTTP 401
{"error": {"code": "unauthorized", "message": "invalid token"}}
```

---

## 2. 公共响应格式

成功：

```json
{ "ok": true, ... }
```

失败：

```json
HTTP 4xx/5xx
{ "ok": false, "error": { "code": "string", "message": "人类可读的一句话" } }
```

**`message` 会被直接显示在手机的状态栏上**，所以它必须是一句人话，不要写堆栈、不要写英文错误码。

错误码固定这几个，别自创：

| code | 含义 | 手机端表现 |
|---|---|---|
| `unauthorized` | 口令不对 | 提示"口令不对" |
| `bad_request` | 参数缺失/格式错 | 提示原文 |
| `not_supported` | 该能力没实现 | 自动降级，不报错 |
| `busy` | 上一次还没回完 | 提示"上一个问题还在处理" |
| `upstream_error` | 你的 Agent 出错 | 显示你的 message |
| `timeout` | 处理超时 | 提示可重试 |

---

## 3. `GET /api/health`

**无需认证。** 手机点"测试连接"时调它。

响应：

```json
{
  "ok": true,
  "name": "my-agent",
  "version": "0.1.0",
  "capabilities": ["text", "asr"]
}
```

- `name`：显示在设置页，让用户确认连对了服务
- `capabilities`：省略视为 `["text"]`
- 必须 **1 秒内**返回；超时手机就判定连不上

> 为什么不需要认证：手机在填地址阶段还没有口令，需要一个不需要凭证就能判断"地址通不通"的探针。

---

## 4. `POST /api/text` —— 核心接口

请求：

```json
{
  "text": "帮我把这段话润色一下：明天上午的会我可能迟到",
  "session_id": "s-20261029-abc",
  "client": { "app": "cat-panel", "version": "0.1.0", "device": "android" }
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `text` | ✅ | 用户说的话或打的字，已去首尾空白，非空 |
| `session_id` | ❌ | 同一轮对话的标识。首次可省略，服务端在响应里返回，之后由客户端带回 |
| `client` | ❌ | 便于服务端做兼容/统计，可忽略 |

响应：

```json
{
  "ok": true,
  "reply": "改好了：明天上午的会我可能会晚到一会儿。",
  "session_id": "s-20261029-abc"
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `reply` | ✅ | **纯文本，可以是多行**。手机端按原样显示，不做 Markdown 渲染 |
| `session_id` | ❌ | 回传，服务端想维护多轮就用它 |

**约定：**

- 超时建议 **120 秒**。手机端等待上限 180 秒。
- 慢任务不要憋着不返回：可以先返回一句"我去查一下"，让用户知道收到了（本项目后续会做真流式，见 §7）。
- `reply` 里不要放 HTML/Markdown 表格 —— 手机是纯文本框，显示不了。

---

## 5. `POST /api/asr` —— 可选：语音转文字

> 不实现也能用：手机端可以只用本地转写，或只做纯文字输入。
> 返回 `not_supported` 时手机自动降级。

请求（二选一，推荐 A）：

**A. 分片流式（推荐）**

```
POST /api/asr?stream=1
Content-Type: application/octet-stream
X-Audio-Format: pcm16/16000/mono

<裸 PCM 字节，每片约 0.4 秒>
```

服务端边收边转，用 **NDJSON** 逐行返回（一行一个 JSON）：

```json
{"type":"partial","text":"帮我把这段"}
{"type":"partial","text":"帮我把这段话润色一下"}
{"type":"final","text":"帮我把这段话润色一下：明天上午的会我可能迟到"}
```

**B. 一次性**

```
POST /api/asr
Content-Type: application/json

{"audio_base64":"...","format":"webm/opus"}
```

响应 `{"ok":true,"text":"..."}`

**约定：**

- 音频**不要**在服务端落盘。转完即弃，除非用户明确要求保留。
- 采样率固定 16 kHz、单声道、16-bit PCM；面板端负责转好再发。
- **API Key 不放在 App 里。** 服务端自己持有云端 ASR 的凭证，手机只认服务端口令。这是这个协议存在的核心安全前提。

---

## 6. 适配任意 Agent 的三种写法

### 6.1 直接对接 OpenClaw

参考实现 `server/adapters/openclaw.py`：调用本机 OpenClaw 的会话接口，把 `text` 塞进一个**独立会话**（不要用主会话，否则多人会串台）。

### 6.2 对接任何 OpenAI 兼容服务

`server/adapters/openai_compat.py`：`base_url` + `api_key` + `model` 三行配置即可。DeepSeek、Kimi、GLM、vLLM、Ollama（`/v1`）都吃这套。

### 6.3 对接任何自定义逻辑

`server/adapters/webhook.py`：把 `text` POST 到你自己写的地址，取响应里的一个字段当 `reply`。适合 n8n、飞书机器人、自己写的脚本。

---

## 7. 未定项（v0.2 再说）

- **流式回复**：`Accept: text/event-stream` 返回 SSE，逐段推送。协议要先定分片语义，不急。
- **多模态**：图片/文件。等有人真需要再做。
- **多设备**：同一账号多台手机。

---

## 8. 自测清单（实现完照这个验）

```bash
BASE=http://127.0.0.1:8899
KEY=你的口令

# 1 健康检查（不带凭证）
curl -s $BASE/api/health

# 2 认证拒绝
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/text \
  -H 'Content-Type: application/json' -d '{"text":"hi"}'      # 期望 401

# 3 正常问答
curl -s -X POST $BASE/api/text \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"text":"说一句话证明你活着"}'

# 4 空文本要拒
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/text \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"text":"   "}'                                          # 期望 400
```

四条都过，手机端就一定连得上。

---

_版本：v0.1 · 2026-10-29 · 任何改动都要同步更新本文件与 README 的接口表_
