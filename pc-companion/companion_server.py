#!/usr/bin/env python3
"""
PALINDORME XR — PC Companion server.

Receives the phone's camera stream over the encrypted link, decodes H.264, runs
MediaPipe Hands on the PC, and streams the 21 hand landmarks back to the phone.

Protocol is defined by the Android side and must match exactly:
  app/src/main/java/com/example/xr/companion/CompanionProtocol.kt
  app/src/main/java/com/example/xr/companion/CompanionNetworkSecurity.kt

  TCP 19850 : encrypted JSON control channel (pairing, ping/pong, landmarks, input)
  UDP 19851 : AES-GCM wrapped H.264 camera access units (phone -> PC)

Usage:
    python companion_server.py                 # random 6-digit pairing code
    python companion_server.py --code 123456   # fixed code
    python companion_server.py --complexity 1  # heavier/more accurate hand model

Then in the app: PC Companion -> enter this PC's LAN IP (or use "Scan LAN"),
type the 6-digit code, accept the consent dialog, tap "Pair & Connect".
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import queue
import random
import socket
import struct
import sys
import threading
import time
from collections import deque

# --------------------------------------------------------------------------------------
# Protocol constants (mirror CompanionProtocol.kt)
# --------------------------------------------------------------------------------------

MAGIC = 0x5058            # 'PX'
HEADER_BYTES = 24
DEFAULT_CONTROL_PORT = 19850
DEFAULT_UDP_PORT = 19851
SERVICE_TYPE = "_palindormexr._tcp.local."

PKT_CAMERA_H264 = 1
PKT_STREAM_VIDEO = 2
PKT_UDP_HEARTBEAT = 3

KEY_SALT = "PALINDORME-XR-LAN-PSK-V1"

# MediaPipe hand landmark count
NUM_LANDMARKS = 21

_print_lock = threading.Lock()


def log(msg: str) -> None:
    with _print_lock:
        print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# --------------------------------------------------------------------------------------
# Crypto (mirror CompanionNetworkSecurity.kt)
# --------------------------------------------------------------------------------------

def derive_session_key(pairing_code: str, client_nonce: str, server_nonce: str) -> bytes:
    material = f"{KEY_SALT}:{pairing_code}:{client_nonce}:{server_nonce}"
    return hashlib.sha256(material.encode("utf-8")).digest()


def compute_auth_proof(session_key: bytes, transcript: str) -> str:
    return hmac.new(session_key, transcript.encode("utf-8"), hashlib.sha256).hexdigest()


def encrypt_aes_gcm(session_key: bytes, plaintext: bytes) -> bytes:
    """IV(12) || ciphertext || tag(16) — same layout the Android client expects."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    iv = os.urandom(12)
    return iv + AESGCM(session_key).encrypt(iv, plaintext, None)


def decrypt_aes_gcm(session_key: bytes, packet: bytes) -> bytes | None:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    if len(packet) <= 28:
        return None
    iv, ct = packet[:12], packet[12:]
    try:
        return AESGCM(session_key).decrypt(iv, ct, None)
    except Exception:
        return None


# --------------------------------------------------------------------------------------
# UDP framing (mirror CompanionProtocol.encodeUdpPacket / decodeUdpPacket)
# --------------------------------------------------------------------------------------

def decode_udp_packet(data: bytes):
    if len(data) < HEADER_BYTES:
        return None
    # magic(H=2) type(B=1) flags(B=1) seq(i=4) tsNs(q=8) payloadLen(I=4) reserved(i=4)
    magic, ptype, flags, seq, ts_ns, payload_len, _reserved = struct.unpack(
        ">HBBiqIi", data[:HEADER_BYTES]
    )
    if magic != MAGIC:
        return None
    if payload_len < 0 or HEADER_BYTES + payload_len > len(data):
        return None
    payload = data[HEADER_BYTES:HEADER_BYTES + payload_len]
    return ptype, flags, seq, ts_ns, payload


# --------------------------------------------------------------------------------------
# Output formatting (mirror CompanionProtocol.parseHandLandmarksMessage)
# --------------------------------------------------------------------------------------

def landmarks_to_message(frame_ts_ns: int, hands) -> str:
    payload = {
        "type": "landmarks",
        "frameTsNs": int(frame_ts_ns),
        "hands": hands,
    }
    return json.dumps(payload, separators=(",", ":"))


def build_hand_entry(handedness_label: str, confidence: float, landmarks, world_landmarks):
    img = [[round(float(p.x), 6), round(float(p.y), 6), round(float(p.z), 6)] for p in landmarks]
    if world_landmarks is not None:
        world = [[round(float(p.x), 6), round(float(p.y), 6), round(float(p.z), 6)] for p in world_landmarks]
    else:
        world = img
    side = "LEFT" if str(handedness_label).upper().startswith("LEFT") else "RIGHT"
    return {
        "side": side,
        "conf": round(float(confidence), 4),
        "img": img,
        "world": world,
    }


# --------------------------------------------------------------------------------------
# Connection session
# --------------------------------------------------------------------------------------

class Session:
    """One paired phone connection."""

    def __init__(self, conn: socket.socket, key: bytes, frame_queue: "queue.Queue"):
        self.conn = conn
        self.key = key
        self.frame_queue = frame_queue
        self.alive = True
        self._send_lock = threading.Lock()
        self._landmark_seq = 0
        self.last_frame_ts_ns = 0

    # ---- control channel ----

    def send_json(self, obj: dict) -> None:
        if not self.alive:
            return
        line = json.dumps(obj, separators=(",", ":"))
        blob = base64.b64encode(encrypt_aes_gcm(self.key, line.encode("utf-8"))).decode("ascii")
        with self._send_lock:
            try:
                self.conn.sendall(blob.encode("ascii") + b"\n")
            except OSError:
                self.alive = False

    def send_landmarks(self, frame_ts_ns: int, hands) -> None:
        self._landmark_seq += 1
        self.send_json({
            "type": "landmarks",
            "frameTsNs": int(frame_ts_ns),
            "hands": hands,
        })

    def read_loop(self, reader) -> None:
        while self.alive:
            line = reader.readline()
            if not line:
                break
            try:
                raw = base64.b64decode(line.strip(), validate=False)
            except Exception:
                continue
            plain = decrypt_aes_gcm(self.key, raw)
            if plain is None:
                continue
            try:
                obj = json.loads(plain.decode("utf-8"))
            except Exception:
                continue
            self._handle_control(obj)
        self.alive = False
        log("Control channel closed.")

    def _handle_control(self, obj: dict) -> None:
        kind = obj.get("type")
        if kind == "ping":
            t1 = int(obj.get("t1Ns", 0))
            t2 = time.monotonic_ns()
            self.send_json({"type": "pong", "t1Ns": t1, "t2PcNs": t2, "t3PcNs": time.monotonic_ns()})
        elif kind == "quality":
            log(f"Phone set quality tier={obj.get('tier')} cam={obj.get('camW')}x{obj.get('camH')}"
                f"@{obj.get('camFps')}fps")
        elif kind == "input_mouse":
            pass  # Remote mouse control: wire to pyautogui if you want PC control.
        elif kind == "input_key":
            pass
        else:
            log(f"Unhandled control message: {kind}")


def do_handshake(conn: socket.socket, pairing_code: str, udp_port: int):
    """Returns (key, buffered_reader) or None on failure."""
    conn.settimeout(10.0)
    reader = conn.makefile("rb")

    first = reader.readline()
    if not first:
        log("Handshake failed: no hello.")
        return None
    hello = json.loads(first.decode("utf-8"))
    if hello.get("type") != "hello":
        log(f"Handshake failed: expected hello, got {hello.get('type')}")
        return None
    client_nonce = hello.get("clientNonce", "")
    log(f"Hello from phone (nonce {client_nonce[:8]}…)")

    server_nonce = os.urandom(16).hex()
    key = derive_session_key(pairing_code, client_nonce, server_nonce)
    pc_proof = compute_auth_proof(key, f"SERVER:{client_nonce}:{server_nonce}")

    conn.sendall(json.dumps({
        "serverNonce": server_nonce,
        "proof": pc_proof,
        "udpPort": udp_port,
    }).encode("utf-8") + b"\n")

    auth_line = reader.readline()
    if not auth_line:
        log("Handshake failed: no auth response.")
        return None
    auth = json.loads(auth_line.decode("utf-8"))
    expected = compute_auth_proof(key, f"CLIENT:{server_nonce}:{client_nonce}")
    if not hmac.compare_digest(expected.lower(), str(auth.get("proof", "")).lower()):
        log("PAIRING CODE MISMATCH — phone sent a bad proof, rejecting.")
        conn.sendall(json.dumps({"ok": False, "error": "Pairing code mismatch"}).encode() + b"\n")
        return None

    conn.sendall(json.dumps({"ok": True}).encode("utf-8") + b"\n")
    conn.settimeout(None)
    log("Pairing OK. Link is encrypted and authenticated.")
    return key, reader


# --------------------------------------------------------------------------------------
# Video / inference pipeline
# --------------------------------------------------------------------------------------

def resolve_model_path(explicit: str | None) -> str | None:
    """Finds a hand_landmarker.task bundle.

    The app ships one in app/src/main/assets, so the PC can run exactly the same
    model the phone would have run locally. If it is missing we fall back to the
    legacy MediaPipe Solutions API instead.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        explicit,
        os.path.join(here, "hand_landmarker.task"),
        os.path.join(here, "..", "app", "src", "main", "assets", "hand_landmarker.task"),
    ]
    for candidate in candidates:
        if candidate and os.path.isfile(candidate):
            return os.path.abspath(candidate)
    return None


class HandDetector:
    """Runs MediaPipe hand landmarks and normalizes both API generations.

    MediaPipe 1.x removed the old `mp.solutions` namespace, so the modern Tasks
    API is preferred. The legacy path is kept because older installs (and users
    pinning 0.10.x) still expose only that.
    """

    def __init__(self, model_path: str | None, max_hands: int, complexity: int):
        self.max_hands = max_hands
        self._last_ts_ms = -1
        self._kind = "none"
        self._landmarker = None
        self._solutions = None
        self._mp = None

        if model_path:
            try:
                self._init_tasks(model_path)
                return
            except Exception as exc:  # noqa: BLE001 - fall through to legacy
                log(f"Tasks API unavailable ({exc}); trying legacy Solutions API.")

        self._init_legacy(complexity)

    # ---- modern Tasks API ----

    def _init_tasks(self, model_path: str) -> None:
        import importlib

        import mediapipe as mp

        vision = importlib.import_module("mediapipe.tasks.python.vision")
        base_options = importlib.import_module(
            "mediapipe.tasks.python.core.base_options"
        ).BaseOptions

        options = vision.HandLandmarkerOptions(
            base_options=base_options(model_asset_path=model_path),
            running_mode=vision.RunningMode.VIDEO,
            num_hands=self.max_hands,
            min_hand_detection_confidence=0.5,
            min_hand_presence_confidence=0.5,
            min_tracking_confidence=0.5,
        )
        self._landmarker = vision.HandLandmarker.create_from_options(options)
        self._mp = mp
        self._kind = "tasks"
        log(f"MediaPipe Tasks HandLandmarker ready (model={os.path.basename(model_path)}).")

    # ---- legacy Solutions API ----

    def _init_legacy(self, complexity: int) -> None:
        import mediapipe as mp

        solutions = getattr(mp, "solutions", None)
        if solutions is None or not hasattr(solutions, "hands"):
            raise RuntimeError(
                "This MediaPipe build has neither the Tasks API (needs a "
                "hand_landmarker.task model) nor mp.solutions.hands. "
                "Install mediapipe<0.11 or provide --model."
            )
        self._solutions = solutions.hands.Hands(
            static_image_mode=False,
            max_num_hands=self.max_hands,
            model_complexity=complexity,
            min_detection_confidence=0.5,
            min_tracking_confidence=0.5,
        )
        self._kind = "legacy"
        log(f"MediaPipe legacy Hands ready (complexity={complexity}).")

    # ---- inference ----

    def detect(self, rgb, ts_ns: int):
        if self._kind == "tasks":
            image = self._mp.Image(
                image_format=self._mp.ImageFormat.SRGB,
                data=rgb,
            )
            ts_ms = ts_ns // 1_000_000 if ts_ns > 0 else int(time.monotonic() * 1000)
            # VIDEO mode requires strictly increasing timestamps.
            ts_ms = max(self._last_ts_ms + 1, ts_ms)
            self._last_ts_ms = ts_ms
            result = self._landmarker.detect_for_video(image, ts_ms)
            return self._from_tasks(result)

        result = self._solutions.process(rgb)
        return self._from_legacy(result)

    def _from_tasks(self, result):
        entries = []
        world_all = getattr(result, "hand_world_landmarks", None) or []
        for idx, landmarks in enumerate(result.hand_landmarks):
            label, conf = "RIGHT", 0.9
            handedness = getattr(result, "handedness", None) or []
            if idx < len(handedness) and handedness[idx]:
                cat = handedness[idx][0]
                label = getattr(cat, "category_name", label) or label
                conf = float(getattr(cat, "score", conf))
            world = world_all[idx] if idx < len(world_all) else None
            entries.append(build_hand_entry(label, conf, landmarks, world))
        return entries

    def _from_legacy(self, result):
        entries = []
        if not result.multi_hand_landmarks:
            return entries
        for idx, landmarks in enumerate(result.multi_hand_landmarks):
            label, conf = "RIGHT", 0.9
            if result.multi_handedness and idx < len(result.multi_handedness):
                cls = result.multi_handedness[idx].classification[0]
                label, conf = cls.label, cls.score
            world = None
            if result.multi_hand_world_landmarks and idx < len(result.multi_hand_world_landmarks):
                world = result.multi_hand_world_landmarks[idx].landmark
            entries.append(build_hand_entry(label, conf, landmarks.landmark, world))
        return entries

    def close(self) -> None:
        try:
            if self._kind == "tasks" and self._landmarker is not None:
                self._landmarker.close()
            elif self._solutions is not None:
                self._solutions.close()
        except Exception:
            pass


class HandPipeline:
    """Decodes H.264 access units and runs MediaPipe Hands, dropping stale frames."""

    def __init__(
        self,
        session: Session,
        complexity: int,
        max_hands: int,
        show_stats: bool,
        model_path: str | None = None,
    ):
        self.session = session
        self.complexity = complexity
        self.max_hands = max_hands
        self.show_stats = show_stats
        self.model_path = model_path
        self._ts_queue: deque[int] = deque()
        self._last_ts = 0
        self._frames = 0
        self._detections = 0
        self._t0 = time.time()

    def run(self) -> None:
        import av

        codec = av.CodecContext.create("h264", "r")
        detector = HandDetector(self.model_path, self.max_hands, self.complexity)

        while self.session.alive:
            try:
                ts_ns, au = self.session.frame_queue.get(timeout=0.5)
            except queue.Empty:
                continue
            if ts_ns:
                self._ts_queue.append(ts_ns)
                self._last_ts = ts_ns

            try:
                packets = codec.parse(au)
                frames = []
                for packet in packets:
                    frames.extend(codec.decode(packet))
            except Exception:
                # Corruption after packet loss: drop until the next keyframe.
                try:
                    codec.flush_buffers()
                except Exception:
                    pass
                continue

            for frame in frames:
                frame_ts = self._ts_queue.popleft() if self._ts_queue else self._last_ts
                self._process(frame, detector, frame_ts)

        detector.close()
        log("Inference pipeline stopped.")

    def _process(self, frame, detector: HandDetector, frame_ts: int) -> None:
        import numpy as np

        try:
            rgb = frame.to_ndarray(format="rgb24")
        except Exception:
            return
        if rgb is None or rgb.size == 0:
            return

        try:
            entries = detector.detect(np.ascontiguousarray(rgb), frame_ts)
        except Exception as exc:  # noqa: BLE001 - one bad frame must not kill the link
            log(f"Inference error (frame dropped): {exc}")
            return
        self._frames += 1
        if entries:
            self._detections += 1

        # Always reply, even with an empty list, so the phone clears stale hands.
        self.session.send_landmarks(frame_ts, entries)

        if self.show_stats:
            elapsed = time.time() - self._t0
            if elapsed >= 2.0:
                fps = self._frames / elapsed
                log(f"decode {fps:4.1f} fps | hands seen {self._detections}x | "
                    f"link {'up' if self.session.alive else 'down'}")
                self._frames = 0
                self._detections = 0
                self._t0 = time.time()


def iter_nal_unit_types(access_unit: bytes):
    """Yields H.264 NAL unit types from an access unit.

    Handles both Annex-B start codes and AVCC 4-byte length prefixes, since
    MediaCodec output framing differs by device.
    """
    n = len(access_unit)

    # Annex-B
    found_annexb = False
    i = 0
    while True:
        j = access_unit.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        found_annexb = True
        header = j + 3
        if header < n:
            yield access_unit[header] & 0x1F
        i = j + 3
    if found_annexb:
        return

    # AVCC
    i = 0
    while i + 5 <= n:
        length = int.from_bytes(access_unit[i:i + 4], "big")
        if length <= 0 or i + 4 + length > n:
            break
        yield access_unit[i + 4] & 0x1F
        i += 4 + length


def is_keyframe(access_unit: bytes) -> bool:
    """True if the access unit contains an IDR slice (NAL type 5)."""
    return any(t == 5 for t in iter_nal_unit_types(access_unit))


def is_codec_config(access_unit: bytes) -> bool:
    """True for SPS/PPS buffers (NAL types 7/8), which carry no picture."""
    return any(t in (7, 8) for t in iter_nal_unit_types(access_unit))


def udp_receiver(session: Session, udp_port: int) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", udp_port))
    sock.settimeout(0.5)
    log(f"Camera UDP listener on :{udp_port}")
    frames = 0
    # A decoder needs SPS/PPS + an IDR to start, and after any dropped packet it
    # must wait for the next IDR rather than chew on corrupt reference frames.
    need_keyframe = True
    resyncs = 0
    while session.alive:
        try:
            data, _addr = sock.recvfrom(65535)
        except socket.timeout:
            continue
        except OSError:
            break
        pkt = decode_udp_packet(data)
        if pkt is None:
            continue
        ptype, _flags, _seq, ts_ns, payload = pkt
        if ptype != PKT_CAMERA_H264:
            continue
        plain = decrypt_aes_gcm(session.key, payload)
        if plain is None:
            continue

        keyframe = is_keyframe(plain)
        if need_keyframe and not keyframe:
            # SPS/PPS buffers carry no picture but are what makes the next IDR
            # decodable, so forward them while still waiting for the IDR.
            if not is_codec_config(plain):
                continue
        else:
            need_keyframe = False

        frames += 1
        if frames == 1:
            log(f"First camera access unit received ({len(plain)} bytes encrypted).")

        # Keep only the freshest frames so latency never creeps up. Dropping
        # reference frames makes the stream undecodable, so a drop forces a
        # resync at the next IDR (the phone emits one every second).
        try:
            session.frame_queue.put_nowait((ts_ns, plain))
        except queue.Full:
            while True:
                try:
                    session.frame_queue.get_nowait()
                except queue.Empty:
                    break
            need_keyframe = True
            resyncs += 1
            if resyncs == 1:
                log("PC fell behind — dropping to the next keyframe to resync.")
    sock.close()


# --------------------------------------------------------------------------------------
# mDNS advertising (optional)
# --------------------------------------------------------------------------------------

def advertise_mdns(control_port: int, udp_port: int):
    try:
        from zeroconf import ServiceInfo, Zeroconf
    except ImportError:
        log("zeroconf not installed — skipping mDNS (use manual IP entry in the app).")
        return None

    local_ip = "127.0.0.1"
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
    except OSError:
        pass
    finally:
        s.close()

    try:
        zeroconf = Zeroconf()
        info = ServiceInfo(
            SERVICE_TYPE,
            f"PalindormeXR-PC.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(local_ip)],
            port=control_port,
            properties={"udpPort": str(udp_port), "proto": "1"},
        )
        zeroconf.register_service(info)
        log(f"mDNS advertising {SERVICE_TYPE} at {local_ip}:{control_port}")
        return zeroconf, info
    except Exception as exc:
        log(f"mDNS advertising failed ({exc}) — use manual IP entry.")
        return None


# --------------------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------------------

def local_ip_hint() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="PALINDORME XR PC Companion server")
    parser.add_argument("--code", help="fixed 6-digit pairing code (default: random)")
    parser.add_argument("--control-port", type=int, default=DEFAULT_CONTROL_PORT)
    parser.add_argument("--udp-port", type=int, default=DEFAULT_UDP_PORT)
    parser.add_argument("--complexity", type=int, default=0, choices=[0, 1],
                        help="legacy MediaPipe model complexity: 0 = fastest (default), 1 = more accurate")
    parser.add_argument("--model", help="path to a hand_landmarker.task model "
                                        "(default: the app's bundled model)")
    parser.add_argument("--max-hands", type=int, default=2)
    parser.add_argument("--no-mdns", action="store_true", help="disable mDNS advertising")
    parser.add_argument("--quiet", action="store_true", help="disable periodic stats")
    args = parser.parse_args()

    code = args.code or f"{random.randint(0, 999999):06d}"
    if len(code) != 6 or not code.isdigit():
        print("Pairing code must be exactly 6 digits.", file=sys.stderr)
        return 2

    ip = local_ip_hint()
    print("=" * 66)
    print("  PALINDORME XR — PC Companion")
    print("=" * 66)
    print(f"  Control (TCP) : {ip}:{args.control_port}")
    print(f"  Camera  (UDP) : {ip}:{args.udp_port}")
    print(f"  PAIRING CODE  : {code}")
    print("=" * 66)
    print("  In the app: PC Companion -> enter the TCP IP above (or Scan LAN)")
    print("              -> type the pairing code -> accept consent -> Connect")
    print()

    if not args.no_mdns:
        advertise_mdns(args.control_port, args.udp_port)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.control_port))
    server.listen(1)
    log(f"Waiting for phone on TCP {args.control_port} …")

    try:
        while True:
            conn, addr = server.accept()
            log(f"Phone connected from {addr[0]}:{addr[1]}")
            try:
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                result = do_handshake(conn, code, args.udp_port)
                if result is None:
                    conn.close()
                    log("Rejected. Waiting for a new connection …")
                    continue
                key, reader = result

                frame_queue: "queue.Queue" = queue.Queue(maxsize=2)
                session = Session(conn, key, frame_queue)

                t_udp = threading.Thread(target=udp_receiver, args=(session, args.udp_port), daemon=True)
                t_read = threading.Thread(target=session.read_loop, args=(reader,), daemon=True)
                t_pipe = threading.Thread(
                    target=HandPipeline(
                        session,
                        args.complexity,
                        args.max_hands,
                        not args.quiet,
                        model_path=resolve_model_path(args.model),
                    ).run,
                    daemon=True,
                )
                t_udp.start()
                t_read.start()
                t_pipe.start()

                while session.alive:
                    time.sleep(0.25)
            except Exception as exc:
                log(f"Session error: {exc}")
            finally:
                try:
                    conn.close()
                except OSError:
                    pass
                log("Session ended. Waiting for the phone to reconnect …")
    except KeyboardInterrupt:
        print()
        log("Stopped by user.")
    finally:
        server.close()

    return 0


if __name__ == "__main__":
    try:
        import cryptography  # noqa: F401
        import av  # noqa: F401
        import mediapipe  # noqa: F401
    except ImportError as exc:
        print(f"Missing dependency: {exc.name}", file=sys.stderr)
        print("Install with:  pip install -r requirements.txt", file=sys.stderr)
        sys.exit(1)
    sys.exit(main())
