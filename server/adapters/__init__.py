"""适配器：把不同来源的 Agent 统一成 ask(text, session_id) -> str

加一种新 Agent = 新建一个文件 + 在 REGISTRY 里加一行。
"""
from .base import BaseAdapter
from .openclaw import OpenClawAdapter
from .openai_compat import OpenAICompatAdapter
from .webhook import WebhookAdapter

REGISTRY = {
    "openclaw": OpenClawAdapter,
    "openai_compat": OpenAICompatAdapter,
    "webhook": WebhookAdapter,
}


def load_adapter(cfg: dict):
    name = (cfg.get("adapter") or "openai_compat").strip()
    cls = REGISTRY.get(name)
    if cls is None:
        raise SystemExit("未知的 adapter: %r。可选：%s" % (name, ", ".join(REGISTRY)))
    return cls(cfg.get(name) or {})


__all__ = ["BaseAdapter", "REGISTRY", "load_adapter"]
