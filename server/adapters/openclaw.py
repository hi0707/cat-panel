"""接 OpenClaw：shell 出去调 `openclaw agent`，把它当子进程用。

为什么不走 OpenClaw 的内部 API：不给用户增加依赖，也不假设它的内部结构稳定。
你只要本机有 `openclaw` 命令能用，这个适配器就能用。

配置：
    "openclaw": {
      "bin": "openclaw",
      "session_prefix": "panel",   # 每个 session_id 映射成一个独立会话
      "timeout": 150,
      "extra_args": []
    }
"""
from __future__ import annotations

import json
import re
import subprocess

from .base import BaseAdapter


class OpenClawAdapter(BaseAdapter):
    name = "openclaw"

    def __init__(self, cfg: dict):
        super().__init__(cfg)
        self.bin = self.cfg.get("bin") or "openclaw"
        self.prefix = self.cfg.get("session_prefix") or "panel"
        self.timeout = int(self.cfg.get("timeout") or 150)
        self.extra = list(self.cfg.get("extra_args") or [])

    def _session(self, session_id: str) -> str:
        safe = re.sub(r"[^A-Za-z0-9_.:-]", "-", session_id)[:40] or "default"
        return "%s-%s" % (self.prefix, safe)

    def ask(self, text: str, session_id: str) -> str:
        # 注意：不要同时传 --agent 和 --session-id，OpenClaw 会优先解析到主会话，
        # 导致所有面板请求挤进同一个上下文（多人使用会串台）。
        cmd = [self.bin, "agent",
               "--session-id", self._session(session_id),
               "--message", text, "--json"] + self.extra
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=self.timeout)
        except subprocess.TimeoutExpired:
            raise TimeoutError()

        out = (p.stdout or "").strip()
        err = (p.stderr or "").strip()

        m = re.search(r"\{.*\}", out, re.S)
        if m:
            try:
                got = self._dig(json.loads(m.group(0)))
                if got:
                    return got.strip()
            except json.JSONDecodeError:
                pass

        plain = re.sub(r"\x1b\[[0-9;]*m", "", out)
        if plain and not plain.lstrip().startswith("{"):
            return plain.strip()

        if err:
            raise RuntimeError(err[-300:])
        return "（没拿到回复，稍后再试）"

    @staticmethod
    def _dig(obj):
        """从 openclaw --json 的任意结构里挖出真正的回复文本。"""
        if isinstance(obj, str):
            return obj if obj.strip() else None
        if isinstance(obj, list):
            for v in obj:
                t = OpenClawAdapter._dig(v)
                if t:
                    return t
            return None
        if isinstance(obj, dict):
            for k in ("payloads", "reply", "text", "message", "content",
                      "response", "output", "result", "data"):
                if k in obj:
                    t = OpenClawAdapter._dig(obj[k])
                    if t:
                        return t
        return None
