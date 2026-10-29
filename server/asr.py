"""语音转文字（可选）

默认接阿里云 DashScope 的 OpenAI 兼容接口（qwen3-asr-flash）。
没配 key 就自动关闭 —— /api/asr 返回 not_supported，手机端降级，不影响其它功能。

为什么不做本地 whisper：那要装 torch/ffmpeg，几分钟内跑不起来，
与"python3 main.py 就能用"的目标冲突。想要本地识别可以自己实现同一个函数签名。

配置：
    "asr": {
      "provider": "dashscope",
      "model": "qwen3-asr-flash",
      "api_key_env": "DASHSCOPE_API_KEY"
    }
"""
from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.request

ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
# 上游认的音频容器格式；手机传的格式不认识就依次试
FALLBACK_FORMATS = ["wav", "mp3", "aac", "ogg", "amr", "webm"]


class Asr:
    def __init__(self, cfg: dict):
        self.cfg = cfg or {}
        self.model = self.cfg.get("model") or "qwen3-asr-flash"
        env_name = self.cfg.get("api_key_env") or "DASHSCOPE_API_KEY"
        self.key = (os.environ.get(env_name) or self.cfg.get("api_key") or "").strip()
        self.timeout = int(self.cfg.get("timeout") or 90)

    @property
    def enabled(self) -> bool:
        return bool(self.key) and (self.cfg.get("provider") or "dashscope") == "dashscope"

    def transcribe_base64(self, b64: str, fmt: str) -> str:
        if not b64:
            raise ValueError("音频是空的")
        last = None
        tried = []
        for cand in [fmt] + [f for f in FALLBACK_FORMATS if f != fmt]:
            tried.append(cand)
            try:
                return self._call(b64, cand)
            except Exception as e:      # 格式不对时上游报错，换下一个试
                last = e
        raise RuntimeError("转写失败（试过 %s）：%s" % (",".join(tried), last))

    def _call(self, b64: str, fmt: str) -> str:
        body = json.dumps({
            "model": self.model,
            "messages": [{"role": "user", "content": [
                {"type": "input_audio",
                 "input_audio": {"data": "data:audio/%s;base64,%s" % (fmt, b64),
                                 "format": fmt}}]}],
        }).encode()
        req = urllib.request.Request(
            ENDPOINT, data=body,
            headers={"Authorization": "Bearer " + self.key,
                     "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                d = json.loads(r.read())
        except urllib.error.HTTPError as e:
            raise RuntimeError("%s %s" % (e.code, e.read()[:150].decode("utf-8", "ignore")))
        return (d["choices"][0]["message"]["content"] or "").strip()
