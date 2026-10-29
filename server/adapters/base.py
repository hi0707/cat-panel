"""所有适配器的基类。只有一个方法必须实现：ask()。"""


class BaseAdapter:
    name = "base"

    def __init__(self, cfg: dict):
        self.cfg = cfg or {}

    def ask(self, text: str, session_id: str) -> str:
        raise NotImplementedError

    def friendly_error(self, e: Exception) -> str:
        """把异常翻译成给人看的一句话 —— 这句会直接显示在手机上。"""
        msg = str(e) or e.__class__.__name__
        return "你的 Agent 出错了：%s" % msg[:200]
