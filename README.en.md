# Cat Panel · An input layer for your own agent, on your phone

> Summon a floating bar over any app, speak, and send it straight to **your own** agent. The reply appears in place.
> No app switching. No chat window. No third-party server in the middle.

---

## The problem

Talking to an agent from a phone takes three steps: unlock → open WeChat/Feishu/Telegram → find the right chat → type or hold-to-talk → wait → switch back to whatever you were doing.

**Every single time, you get interrupted.** The real cost isn't typing — it's leaving the screen you were on. You're reading something, taking notes, in the middle of a conversation, and to ask the AI one question, your whole context gets yanked away.

Cat Panel compresses that into one movement: **from any screen, tap the floating mic, watch words appear as you speak, release to send, and the answer shows up right where you are.**

## What it is not

- **Not an agent.** No model, no inference, no cloud storage of your conversations. It's just a mouth and ears.
- **Not a chat app.** No contacts, no accounts, no server of ours.
- **Doesn't bypass any platform's rules.** It does not scrape other apps, does not auto-fill into them, does not use the accessibility service.

## How it works

```
Phone: floating bar (record + live transcription + reply)
  │  HTTP / HTTPS with a token
  ▼
Your own service (your laptop / NAS / small VPS)
  │
  ▼
Your own agent (OpenClaw / any LLM / n8n / anything that implements 3 endpoints)
```

**The middle layer is yours.** Your voice and your conversations only pass through your own machine. This project provides no server and needs none.

## Three endpoints is all it takes

Any service implementing these can drive the panel:

| Endpoint | Purpose | Required |
|---|---|---|
| `GET /api/health` | Connectivity check | ✅ |
| `POST /api/text` | Send text, get a reply | ✅ |
| `POST /api/asr` | Speech-to-text (optional) | Optional |

Full contract: [`docs/protocol.md`](docs/protocol.md) (Chinese; English translation in progress).

## Getting the phone to reach your agent

The project **deliberately does not lock you into one network solution.** It only needs a reachable `base URL` + a token. Pick whichever fits where you actually use it:

| Where you use it | Option | Doc |
|---|---|---|
| Home / office only | Same Wi-Fi, just an LAN IP | [setup-lan](docs/setup-lan.md) |
| **Anywhere, zero setup** | **Tailscale — two apps, one login** | [setup-tailscale](docs/setup-tailscale.md) |
| Mainland China, no money to spend | IPv6 + DDNS direct | [setup-ipv6-ddns](docs/setup-ipv6-ddns.md) |
| Anywhere, needs to be rock solid | Small VPS + frp | [setup-frp](docs/setup-frp.md) |

> **Mainland China note:** Tailscale's login and default relays are outside the country, so it's slow there. Prefer IPv6 or frp.

## Install on your phone

1. Grab the latest `.apk` from [Releases](../../releases)
2. Install (Android 8.0+)
3. Open it, follow the setup wizard: URL + token, then grant overlay and microphone permissions

For auto-updates: install [Obtainium](https://github.com/ImranR98/Obtainium) and paste this repo's URL — it will pull new releases for you.

**No USB. No computer needed to install.**

## Known limitations (honest list)

- **Android only.** iOS forbids cross-app floating recording — "talk from any screen" is impossible on iPhone (a keyboard extension or lock-screen widget is the closest, which is a different product).
- **First-run permissions are fiddly.** Overlay + microphone + battery optimisation each need manual approval; the OS won't grant them in one tap.
- **Xiaomi / Redmi users must read** [`docs/faq-xiaomi.md`](docs/faq-xiaomi.md): the home-screen widget won't add itself, and every reinstall resets that permission.
- **Audio travels over the network.** On LAN the latency is negligible; over the internet it goes the long way round.

## Status

Early. The core experience (floating recording, live transcription, send, inline reply) is in daily use; the open-source packaging (first-run wizard, reference server, multi-session isolation) is in progress. See [`docs/roadmap.md`](docs/roadmap.md).

## License

MIT. Use it, fork it, sell it. Just don't blame us.
