# 参考后端 · Reference Server

一个能跑的最小实现：**只依赖 Python 3.9+ 标准库**，不需要 `pip install` 任何东西。

它证明 `docs/protocol.md` 的契约是可实现的，也是给你改的起点 —— 通常你只需要动配置文件，不用动代码。

---

## 30 秒跑起来

```bash
cd server
cp config.example.json config.json
python3 -c "import json;c=json.load(open('config.json'));c['token']='$(openssl rand -hex 16)';json.dump(c,open('config.json','w'),indent=2,ensure_ascii=False)"
python3 main.py
```

第一行输出会告诉你监听地址。手机填 `http://<你的IP>:8899` + 那串 token。

自测（四条全过 = 手机一定能连）：

```bash
BASE=http://127.0.0.1:8899
KEY=$(python3 -c "import json;print(json.load(open('config.json'))['token'])")

curl -s $BASE/api/health
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/text \
  -H 'Content-Type: application/json' -d '{"text":"hi"}'                  # 401
curl -s -X POST $BASE/api/text -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' -d '{"text":"说一句话证明你活着"}'
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/api/text \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"text":"   "}'                                                      # 400
```

---

## 选一个适配器

改 `config.json` 的 `"adapter"` 字段，三种可选：

### `openai_compat`（最通用，推荐先试这个）

任何 OpenAI 兼容的 `/v1/chat/completions`：DeepSeek、Kimi、GLM、通义、vLLM、Ollama（`/v1`）、LM Studio。

```json
"adapter": "openai_compat",
"openai_compat": {
  "base_url": "https://api.deepseek.com/v1",
  "api_key_env": "DEEPSEEK_API_KEY",
  "model": "deepseek-chat",
  "system": "你正在通过手机语音回答。回答要短、口语化、不超过三句话。"
}
```

Key 走环境变量，不要写进配置文件（这个文件很可能被 git 提交）。

### `openclaw`

本机有 `openclaw` 命令就能用。每个 `session_id` 映射成一个独立会话，不会串台。

```json
"adapter": "openclaw",
"openclaw": { "bin": "openclaw", "session_prefix": "panel", "timeout": 150 }
```

> 坑：不要同时传 `--agent` 和 `--session-id`，OpenClaw 会优先解析到主会话，导致所有请求挤进同一个上下文。

### `webhook`

把 `text` POST 给任何地址，从响应里按点号路径取回复。适合 n8n、飞书/钉钉机器人、自己写的脚本。

```json
"adapter": "webhook",
"webhook": {
  "url": "http://127.0.0.1:5678/webhook/cat-panel",
  "reply_path": "data.answer",
  "headers": { "X-Secret": "..." }
}
```

---

## 语音转写（可选）

```json
"asr": { "provider": "dashscope", "model": "qwen3-asr-flash", "api_key_env": "DASHSCOPE_API_KEY" }
```

```bash
export DASHSCOPE_API_KEY=sk-...
```

不配就自动关闭，`/api/asr` 返回 `not_supported`，手机端降级 —— 其它功能不受影响。

**参考实现只做一次性上传，不支持分片流式**（分片要 websocket，与"零依赖"冲突）。客户端收到 `not_supported` 会自动降级。

---

## 加一种新的 Agent

新建 `adapters/your_thing.py`：

```python
from .base import BaseAdapter

class YourAdapter(BaseAdapter):
    name = "your_thing"

    def __init__(self, cfg):
        super().__init__(cfg)
        self.url = cfg["url"]

    def ask(self, text: str, session_id: str) -> str:
        ...   # 返回纯文本
```

然后 `adapters/__init__.py` 里加两行：

```python
from .your_thing import YourAdapter
REGISTRY = { ..., "your_thing": YourAdapter }
```

**只有 `ask()` 是必须的。** 抛 `TimeoutError` 会变成 `timeout` 错误码，其它异常会变成 `upstream_error` 并把 `friendly_error()` 的文案显示到手机上。

---

## 设计上的几个刻意选择

| 选择 | 原因 |
|---|---|
| 只用标准库 | 用户 `python3 main.py` 就能跑。装依赖是最大的劝退点 |
| 单进程 + 一把锁 | 面板是一问一答的；并发进同一个 Agent 会话会串台。多用户场景见 roadmap 的"会话隔离" |
| 错误消息必须是中文人话 | 它会**原样显示在手机状态栏**，堆栈没人看得懂 |
| token 用 `hmac.compare_digest` | 防时序侧信道。成本一次函数调用，没有不做的理由 |
| 401 时也把请求体读掉 | 否则残留字节会污染同一个 keep-alive 连接上的下一个请求（踩过） |
| 默认监听 `0.0.0.0` | 手机要能连。**但这意味着同网段的人都能访问到端口** —— 靠 token 拦，别指望网络隔离 |

---

## 安全提醒

- `config.json` 里有 token 和可能的 API key → **加进 `.gitignore`**（本仓库已忽略 `config.json`… 请确认你的 fork 也忽略）
- 这套服务**没有 HTTPS**。走公网请放在 caddy/nginx 后面，或用 frp + HTTPS
- 它不做用户体系，**只适合单人/小范围自用**。要给多人用必须先做会话隔离

---

_状态：三种适配器均在 macOS + Python 3.9 实测通过（openai_compat 对 DeepSeek 实测 0.7s；openclaw 实测 2.3s；webhook 对本地假服务实测 4ms）。_
