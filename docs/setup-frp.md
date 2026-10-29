# 第三档 · 国内小服务器 + frp（最稳，30–60 元/月）

> 最适合：到处都要用（含公司网络、外地、酒店 WiFi），并且地址要不变成固定。
> 优点：任何网络环境都能连；地址固定；完全可控。
> 缺点：多一台机器、每月几十块、要配端口。

---

## 为什么这一档最通用

家用宽带的最大不确定性是**入站**：运营商封端口、路由器不给 IPv6、公司网络只放 443 出站。

frp 把这个问题反过来了 —— **你的电脑主动向外连服务器**（出站，永不被封），手机只需连服务器（一个正常的公网地址）。所以它能穿透几乎所有网络环境。

---

## 1. 买一台服务器

- 配置：**2 核 2G 足够**（它只做流量转发，不跑模型）
- 带宽：按你说的用量，`3–5 Mbps` 就够音频和文字；想更快就 10 Mbps
- 位置：**选离你最近的国内机房**（延迟 10–30ms）
- 系统：Debian / Ubuntu 都行

⚠️ **备案提醒（重要）**
境内服务器对外提供 Web 服务，按法规需要备案。
- 用 **非标端口**（如 7000/8899）实践中大多可以绕开，但**部分机商会封非标端口**，买之前问清楚
- 想走 80/443 就必须有备案域名
- 纯个人自用、量很小，风险低，但这是你的选择，不是我的建议

## 2. 服务器上装 frps（服务端）

```bash
# 以 Debian 12 / amd64 为例
cd /tmp
VER=0.61.0   # 用 frp 官方 releases 里的最新稳定版
wget https://github.com/fatedier/frp/releases/download/v${VER}/frp_${VER}_linux_amd64.tar.gz
tar xzf frp_${VER}_linux_amd64.tar.gz
sudo mv frp_${VER}_linux_amd64/frps /usr/local/bin/
```

`/etc/frp/frps.toml`：

```toml
bindPort = 7000

# 一定要设，否则任何人都能往你的服务器挂隧道
auth.method = "token"
auth.token = "换成一串足够长的随机值"

# 可选：只给面板用的端口范围，防止被人乱用
# allowPorts = [{ start = 8899, end = 8900 }]
```

systemd 常驻：

```bash
sudo systemctl enable --now frps
```

开放端口（云厂商安全组 + 本机防火墙**都要**）：

- `7000/tcp`（隧道）
- `8899/tcp`（面板入口）

## 3. 你的电脑上装 frpc（客户端）

macOS：

```bash
brew install frpc
```

`frpc.toml`：

```toml
serverAddr = "你的服务器公网IP"
serverPort = 7000

auth.method = "token"
auth.token = "和服务端一致"

[[proxies]]
name = "cat-panel"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8899
remotePort = 8899
```

启动：

```bash
frpc -c frpc.toml
```

开机自启（macOS）：

```bash
brew services start frpc
```

## 4. 手机里填

```
http://<服务器公网IP>:8899
```

⚠️ 如果跑在纯 HTTP 上，注意：**你的语音和对话会明文经过公网**。见下一节。

---

## 5. 强烈建议：加一层 HTTPS

纯 HTTP 意味着路径上任何人（机房、运营商、同一 WiFi）都能看到你的对话内容。加 HTTPS 有两条路：

**A. 有域名（推荐）**

```bash
# 服务器上用 caddy 做反代，自动申请证书
caddy reverse-proxy --from panel.example.com:8899 --to 127.0.0.1:8899
```

然后把 frpc 的 `remotePort` 改成指向 caddy 的端口。手机填 `https://panel.example.com:8899`。

**B. 没有域名**

用 Cloudflare Tunnel 代替 frp（`cloudflared`），白送一个 `https://xxx.trycloudflare.com` 的 HTTPS 地址。免费、不用买域名，但地址是随机的（可换成固定子域，需要域名）。

---

## 6. 安全清单（别省）

- [ ] `auth.token` 足够长（32 位以上随机）
- [ ] 服务端 token 和面板口令**不是同一个**（隧道凭证 != 应用凭证）
- [ ] 云安全组只开 7000 和 8899，其余全关
- [ ] SSH 用密钥登录，禁用密码登录，改非标端口
- [ ] 面板服务自身仍需校验口令（frp 只是通道，不做鉴权）
- [ ] 有条件就上 HTTPS

---

## 验收标准

- 手机断 WiFi 用流量，浏览器打开 `http://<服务器IP>:8899/api/health` 有 JSON
- 面板「测试连接」通过
- 说一句话能收到回复，延迟可接受（音频小，通常 100ms 内）
- 电脑重启后，frpc 自动重连（`brew services list` 里显示 started）

---

_状态：frp 骨架命令按官方文档 v0.5x/0.6x 语法编写，**未在作者环境完整实测**（本机当前使用临时隧道方案）。实测后更新本节。_
