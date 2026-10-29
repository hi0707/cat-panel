#!/usr/bin/env python3
"""Cat Panel · 参考后端（Reference Server）

只依赖 Python 3.9+ 标准库，不需要 pip install 任何东西。
实现 docs/protocol.md 的 v0.1 契约。

跑起来：
    cp config.example.json config.json   # 填 token 和 adapter
    python3 main.py                      # 或 --port 8899

作者：Cat Panel contributors · MIT
"""
from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adapters import load_adapter  # noqa: E402
from asr import Asr  # noqa: E402

VERSION = "0.1.0"
MAX_BODY = 8 * 1024 * 1024          # 8 MB，够一段几分钟的压缩音频
HEALTH_BUDGET_MS = 1000             # /api/health 必须 1 秒内返回

CFG: dict = {}
TOKEN: str = ""
ADAPTER = None
ASR: Asr | None = None
AGENT_LOCK = threading.Lock()       # 同一时刻只跑一轮，防止两条语音交叉进上下文


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- 配置

def load_config(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        cfg = json.load(f)

    token = cfg.get("token") or os.environ.get("CAT_PANEL_TOKEN", "")
    if not token or len(token) < 16:
        # 没有 token 就现生成一个并打印出来，绝不默认放行
        token = os.urandom(16).hex()
        log("!! 没有配置 token，已临时生成：", token)
        log("!! 把它填进 config.json 的 \"token\" 字段，否则重启后会变")
    cfg["token"] = token

    cfg.setdefault("listen", {})
    cfg["listen"].setdefault("host", "0.0.0.0")
    cfg["listen"].setdefault("port", 8899)
    cfg.setdefault("adapter", "openai_compat")
    return cfg


# ---------------------------------------------------------------- 协议工具

def err(code: str, message: str) -> dict:
    return {"ok": False, "error": {"code": code, "message": message}}


def normalize_text(raw) -> str:
    if not isinstance(raw, str):
        return ""
    t = raw.replace("\r\n", "\n").strip()
    return re.sub(r"\n{3,}", "\n\n", t)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CatPanel/" + VERSION

    # ------------------------------------------------------------ 基础设施

    def log_message(self, fmt, *args):  # 静默默认日志，用自己的格式
        log("%s %s" % (self.address_string(), fmt % args))

    def _body(self) -> bytes:
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0:
            return b""
        if n > MAX_BODY:
            raise ValueError("body too large")
        return self.rfile.read(n)

    def _json(self, code: int, obj: dict):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def _authed(self) -> bool:
        got = ""
        auth = self.headers.get("Authorization") or ""
        if auth.lower().startswith("bearer "):
            got = auth[7:].strip()
        if not got:
            got = self.headers.get("X-Panel-Key") or ""
        if not got:
            got = (parse_qs(urlparse(self.path).query).get("k") or [""])[0]
        # 常量时间比较，避免时序侧信道
        return bool(got) and hmac.compare_digest(got, TOKEN)

    def _reject_unauthorized(self):
        try:
            self._body()          # 把请求体读掉，否则残留在 socket 里会污染下一个请求
        except Exception:
            pass
        self._json(401, err("unauthorized", "口令不对，检查一下手机里填的 token"))

    # ------------------------------------------------------------ 路由

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path in ("/api/health", "/health"):
            return self._health()
        if path == "/":
            return self._json(200, {
                "ok": True, "name": CFG.get("name", "cat-panel-server"),
                "version": VERSION, "docs": "docs/protocol.md"})
        return self._json(404, err("bad_request", "没有这个接口"))

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        try:
            if path == "/api/text":
                return self._text()
            if path == "/api/asr":
                return self._asr()
            return self._json(404, err("bad_request", "没有这个接口"))
        except ValueError as e:
            return self._json(400, err("bad_request", str(e)))
        except Exception:
            log("未捕获异常:\n" + traceback.format_exc())
            return self._json(500, err("upstream_error", "服务端内部出错，看日志"))

    # ------------------------------------------------------------ 接口实现

    def _health(self):
        t0 = time.time()
        caps = ["text"]
        if ASR and ASR.enabled:
            caps.append("asr")
        # 缓存上游健康结果 10 秒，避免手机每次点"测试连接"都真跑一次
        self._json(200, {
            "ok": True,
            "name": CFG.get("name", "cat-panel-server"),
            "version": VERSION,
            "adapter": CFG.get("adapter"),
            "capabilities": caps,
            "latency_ms": int((time.time() - t0) * 1000),
        })

    def _text(self):
        if not self._authed():
            return self._reject_unauthorized()
        try:
            payload = json.loads(self._body() or b"{}")
        except json.JSONDecodeError:
            return self._json(400, err("bad_request", "请求体不是合法 JSON"))

        text = normalize_text(payload.get("text"))
        if not text:
            return self._json(400, err("bad_request", "text 不能为空"))
        if len(text) > 8000:
            return self._json(400, err("bad_request", "一次说的话太长了，分两次发"))

        session_id = (payload.get("session_id") or "").strip() or "default"

        if not AGENT_LOCK.acquire(blocking=False):
            return self._json(429, err("busy", "上一个问题还在处理，等它回完"))

        t0 = time.time()
        try:
            log("→ [%s] %s" % (session_id, text[:60].replace("\n", " ")))
            reply = ADAPTER.ask(text, session_id)
            log("← %.1fs %d 字" % (time.time() - t0, len(reply)))
            self._json(200, {"ok": True, "reply": reply, "session_id": session_id,
                             "elapsed_ms": int((time.time() - t0) * 1000)})
        except TimeoutError:
            self._json(504, err("timeout", "处理超时了，可以重试或换个说法"))
        except PermissionError as e:
            self._json(500, err("upstream_error", str(e)))
        except Exception as e:
            log("adapter 出错:\n" + traceback.format_exc())
            self._json(502, err("upstream_error", ADAPTER.friendly_error(e)))
        finally:
            AGENT_LOCK.release()

    def _asr(self):
        if not self._authed():
            return self._reject_unauthorized()
        if ASR is None or not ASR.enabled:
            return self._json(501, err("not_supported", "这个服务端没配语音转写，用手机本地识别"))

        qs = parse_qs(urlparse(self.path).query)
        if (qs.get("stream") or [""])[0] in ("1", "true"):
            # v0.1 参考实现不做分片流式：明确告知，客户端会自动降级为一次性上传
            return self._json(501, err("not_supported", "参考实现不支持流式，请用一次性上传"))

        ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip()
        raw = self._body()
        if not raw:
            return self._json(400, err("bad_request", "没收到音频"))

        t0 = time.time()
        try:
            if ctype == "application/json":
                obj = json.loads(raw)
                text = ASR.transcribe_base64(obj.get("audio_base64", ""), obj.get("format", "wav"))
            else:
                fmt = (self.headers.get("X-Audio-Format") or "wav").split("/")[0].strip() or "wav"
                import base64
                text = ASR.transcribe_base64(base64.b64encode(raw).decode(), fmt)
        except Exception as e:
            log("asr 出错:\n" + traceback.format_exc())
            return self._json(502, err("upstream_error", "识别失败：%s" % e))

        log("asr %.1fs → %s" % (time.time() - t0, text[:40]))
        self._json(200, {"ok": True, "text": text, "elapsed_ms": int((time.time() - t0) * 1000)})


# ---------------------------------------------------------------- 启动

def main():
    global CFG, TOKEN, ADAPTER, ASR

    ap = argparse.ArgumentParser(description="Cat Panel reference server")
    ap.add_argument("-c", "--config", default="config.json")
    ap.add_argument("--host")
    ap.add_argument("--port", type=int)
    args = ap.parse_args()

    if not os.path.exists(args.config):
        print("找不到 %s，先复制一份：cp config.example.json config.json" % args.config)
        sys.exit(2)

    CFG = load_config(args.config)
    if args.host:
        CFG["listen"]["host"] = args.host
    if args.port:
        CFG["listen"]["port"] = args.port
    TOKEN = CFG["token"]

    ADAPTER = load_adapter(CFG)
    ASR = Asr(CFG.get("asr") or {})

    host, port = CFG["listen"]["host"], CFG["listen"]["port"]
    srv = ThreadingHTTPServer((host, port), Handler)
    srv.daemon_threads = True

    log("Cat Panel server %s  adapter=%s  asr=%s" % (
        VERSION, CFG["adapter"], "on" if ASR.enabled else "off"))
    log("监听 http://%s:%d   （手机填你的局域网/组网地址）" % (host, port))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("bye")


if __name__ == "__main__":
    main()
