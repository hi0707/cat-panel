"""接任何自定义 HTTP 服务（n8n / 飞书机器人 / 自己写的脚本）

把 text POST 给一个地址，从响应里按点号路径取一个字段当回复。

配置：
    "webhook": {
      "url": "http://127.0.0.1:5678/webhook/cat-panel",
      "method": "POST",
      "reply_path": "reply",          # 支持 "data.answer" 这种嵌套
      "headers": { "X-Secret": "..." },
      "body_template": null,          # 不想用默认 body 就写一个 JSON 模板，
                                      # 里面用 {{text}} / {{session_id}} 占位
      "timeout": 120
    }
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from .base import BaseAdapter


class WebhookAdapter(BaseAdapter):
    name = "webhook"

    def __init__(self, cfg: dict):
        super().__init__(cfg)
        self.url = self.cfg.get("url") or ""
        if not self.url:
            raise SystemExit("adapter=webhook 必须配置 url")
        self.method = (self.cfg.get("method") or "POST").upper()
        self.reply_path = self.cfg.get("reply_path") or "reply"
        self.headers = dict(self.cfg.get("headers") or {})
        self.headers.setdefault("Content-Type", "application/json")
        self.template = self.cfg.get("body_template")
        self.timeout = int(self.cfg.get("timeout") or 120)

    def _payload(self, text: str, session_id: str) -> bytes:
        obj = {"text": text, "session_id": session_id,
               "client": {"app": "cat-panel", "via": "webhook"}}
        raw = json.dumps(obj, ensure_ascii=False)
        if self.template:
            raw = (self.template
                   .replace("{{text}}", json.dumps(text, ensure_ascii=False)[1:-1])
                   .replace("{{session_id}}", session_id))
        return raw.encode()

    def ask(self, text: str, session_id: str) -> str:
        data = self._payload(text, session_id) if self.method != "GET" else None
        url = self.url
        if self.method == "GET":
            from urllib.parse import quote
            url = "%s%stext=%s&session_id=%s" % (
                url, "&" if "?" in url else "?", quote(text), quote(session_id))

        req = urllib.request.Request(url, data=data, headers=self.headers,
                                     method=self.method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                body = r.read()
        except urllib.error.HTTPError as e:
            raise RuntimeError("你的服务返回 %s：%s" % (
                e.code, e.read()[:200].decode("utf-8", "ignore")))
        except urllib.error.URLError as e:
            raise RuntimeError("连不上你的服务（%s）" % e.reason)

        try:
            obj = json.loads(body)
        except json.JSONDecodeError:
            # 不是 JSON 就把整段当纯文本回复，最省事
            return body.decode("utf-8", "ignore").strip()

        val = obj
        for part in self.reply_path.split("."):
            if isinstance(val, dict) and part in val:
                val = val[part]
            else:
                val = None
                break
        if isinstance(val, str) and val.strip():
            return val.strip()
        raise RuntimeError("响应里找不到 %r 字段，看看 reply_path 配对没" % self.reply_path)

    def friendly_error(self, e: Exception) -> str:
        return str(e)[:300]
