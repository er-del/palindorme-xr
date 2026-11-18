# PALINDORME XR — PC Companion

Offloads MediaPipe hand tracking from the phone to your PC. The phone streams its
camera as H.264 over the encrypted link; this server decodes it, runs MediaPipe
Hands, and sends the 21 landmarks back. The phone then stops running MediaPipe
locally (`HandTrackingEngine.offloadHandsToPc`).

## Install

```bash
cd pc-companion
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt   # Windows
# source .venv/bin/activate && pip install -r requirements.txt   # macOS/Linux
```

Python 3.10–3.12 recommended (MediaPipe wheels lag on brand-new Python versions).

## Run

```bash
python companion_server.py
```

It prints the local IP, the two ports and a **6-digit pairing code**:

```
==================================================================
  PALINDORME XR — PC Companion
==================================================================
  Control (TCP) : 192.168.1.42:19850
  Camera  (UDP) : 192.168.1.42:19851
  PAIRING CODE  : 481920
==================================================================
```

Options:

| Flag | Purpose |
|---|---|
| `--code 123456` | fixed pairing code instead of a random one |
| `--model path.task` | override the hand model (defaults to the app's bundled `hand_landmarker.task`) |
| `--complexity 1` | heavier legacy Solutions model (only used if the Tasks API is unavailable) |
| `--max-hands 1` | only track one hand (faster) |
| `--no-mdns` | skip mDNS advertising |
| `--quiet` | no periodic fps stats |

MediaPipe 1.x removed the old `mp.solutions` namespace. The server detects that
and uses the modern Tasks API with the model the app already bundles at
`app/src/main/assets/hand_landmarker.task`, so the PC runs the same network as
the phone. On older MediaPipe (<0.11) it falls back to `mp.solutions.hands`.

## Connect from the phone

In the app: **PC Companion** section.

1. Enter the TCP address printed above (e.g. `192.168.1.42`), **or** tap **Scan LAN (mDNS)**.
2. Type the 6-digit code.
3. Accept the consent dialog (required before camera frames are sent).
4. Tap **Pair & Connect Encrypted PC Link**.

If the code is wrong you'll get `6-digit pairing code mismatch` — that's the
mutual HMAC proof failing, by design.

## Wired vs wireless

| Path | Works? | How |
|---|---|---|
| **Wi-Fi** | ✅ full | Same network, enter the PC's `192.168.x.x` |
| **USB tethering** | ✅ full | Enable USB tethering on the phone, then use the PC's `192.168.42.x` address |
| **`adb reverse`** | ⚠️ partial | `adb reverse` is **TCP-only**, so the control channel connects but camera UDP never arrives. Don't use it for hand offload. |

## Windows firewall

The first run will likely prompt. Allow **Python** on **Private networks**.
If you miss the prompt, add both inbound rules manually (TCP 19850, UDP 19851).

## Troubleshooting

| Symptom | Cause |
|---|---|
| `Connection refused` | server not running, wrong IP, or firewall blocking TCP 19850 |
| Pairs, then "no hands" forever | UDP 19851 blocked, or phone/camera permission not granted |
| `PAIRING CODE MISMATCH` in console | code typed differently in the app |
| Logs show "First camera access unit received" but no detections | hands not in frame, or `--complexity 1` needed |

## Protocol reference

Defined by `CompanionProtocol.kt` / `CompanionNetworkSecurity.kt` on the Android side.

- TCP 19850 — plaintext JSON handshake, then `base64(IV(12) || AES-256-GCM(json))` per line
- UDP 19851 — 24-byte header: `magic 0x5058`, type(1), flags(1), seq(4), tsNs(8), len(4), reserved(4)
- Session key = `SHA-256("PALINDORME-XR-LAN-PSK-V1:<code>:<clientNonce>:<serverNonce>")`
- PC proof = `HMAC-SHA256(key, "SERVER:<client>:<server>")`
- Client proof = `HMAC-SHA256(key, "CLIENT:<server>:<client>")`
- Landmarks reply: `{"type":"landmarks","frameTsNs":<phone ns>,"hands":[{"side","conf","img":[[x,y,z]x21],"world":[...]}]}`

`frameTsNs` must be the phone's original capture timestamp (taken from the UDP
header), because the phone computes end-to-end latency as `now - frameTsNs`.
