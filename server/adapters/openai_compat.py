"""接任何 OpenAI 兼容的 /v1/chat/completions

DeepSeek / Kimi / GLM / 通义 / vLLM / Ollama（/v1）/ LM Studio 都吃这套。
一个 base_url + 一个 key + 一个 model 就够。

配置：
    "openai_compat": {
      "base_url": "https://api.deepseek.com/v1",
      "api_key_env": "DEEPSEEK_API_KEY",     # 推荐：从环境变量读，不写进配置文件
      "model": "deepseek-chat",
      "system": "你在手机上通过语音回答问题。回答口语化，不超过三句。",
      "timeout": 120,
      "max_tokens": 800
    }
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

from .base import BaseAdapter


class OpenAICompatAdapter(BaseAdapter):
    name = "openai_compat"

    def __init__(self, cfg: dict):
        super().__init__(cfg)
        self.base = (self.cfg.get("base_url") or "").rstrip("/")
        if not self.base:
            raise SystemExit("adapter=openai_compat 必须配置 base_url")
        self.model = self.cfg.get("model") or "gpt-4o-mini"
        self.system = self.cfg.get("system") or (
            "你正在通过手机语音回答。回答要短、口语化、直接给结论，不超过三句话。")
        self.timeout = int(self.cfg.get("timeout") or 120)
        self.max_tokens = int(self.cfg.get("max_tokens") or 800)
        self.key = self._resolve_key()

        if not self.key:
            raise SystemExit(
                "openai_compat 缺少 api_key。二选一：\n"
                "  1) 环境变量 export %s=sk-...\n"
                "  2) 配置文件里写 \"api_key\": \"sk-...\"（不推荐，会进 git）"
                % (self.cfg.get("api_key_env") or "OPENAI_API_KEY"))

    def _resolve_key(self) -> str:
        env_name = self.cfg.get("api_key_env") or "OPENAI_API_KEY"
        return (os.environ.get(env_name) or self.cfg.get("api_key") or "").strip()

    def ask(self, text: str, session_id: str) -> str:
        body = json.dumps({
            "model": self.model,
            "messages": [
                {"role": "system", "content": self.system},
                {"role": "user", "content": text},
            ],
            "max_tokens": self.max_tokens,
            "stream": False,
        }).encode()

        req = urllib.request.Request(
            self.base + "/chat/completions", data=body,
            headers={"Authorization": "Bearer " + self.key,
                     "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                d = json.loads(r.read())
        except urllib.error.HTTPError as e:
            detail = e.read()[:300].decode("utf-8", "ignore")
            raise RuntimeError("上游返回 %s：%s" % (e.code, detail))
        except urllib.error.URLError as e:
            raise RuntimeError("连不上上游（%s），检查 base_url 和网络" % e.reason)

        try:
            return (d["choices"][0]["message"]["content"] or "").strip()
        except (KeyError, IndexError):
            raise RuntimeError("上游返回格式不认识：%s" % str(d)[:200])

    def friendly_error(self, e: Exception) -> str:
        return str(e)[:300]
