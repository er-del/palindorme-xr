"""Runtime smoke test: real H.264 decode + MediaPipe inference.

Requires `av`, `mediapipe` and the app's bundled model. Proves the whole PC
pipeline works: synthetic H.264 -> decode -> hand landmarks -> JSON reply.

    python pc-companion/tests/test_pipeline.py
"""

from __future__ import annotations

import io
import queue
import sys
import threading
import time
from pathlib import Path

import av
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import companion_server as srv  # noqa: E402

failures: list[str] = []


def check(cond, label):
    print(f"  {'PASS' if cond else 'FAIL'}  {label}")
    if not cond:
        failures.append(label)


class FakeSession:
    def __init__(self):
        self.alive = True
        self.frame_queue: queue.Queue = queue.Queue(maxsize=200)
        self.sent = []

    def send_landmarks(self, frame_ts_ns, hands):
        self.sent.append((frame_ts_ns, hands))


def make_access_units(width=640, height=480, n_frames=10):
    raw = io.BytesIO()
    container = av.open(raw, mode="w", format="h264")
    stream = container.add_stream("libx264", rate=30)
    stream.width = width
    stream.height = height
    stream.pix_fmt = "yuv420p"
    stream.options = {"preset": "ultrafast", "tune": "zerolatency", "x264-params": "keyint=15"}

    for i in range(n_frames):
        img = np.zeros((height, width, 3), dtype=np.uint8)
        cy = int(height * 0.5 + 60 * np.sin(i / 3.0))
        img[max(0, cy - 45):cy + 45, width // 2 - 35:width // 2 + 35] = 220
        for packet in stream.encode(av.VideoFrame.from_ndarray(img, format="rgb24")):
            container.mux(packet)
    for packet in stream.encode(None):
        container.mux(packet)
    container.close()

    data = raw.getvalue()
    print(f"  (encoded {len(data)} bytes of H.264)")

    starts = []
    idx = 0
    while True:
        j = data.find(b"\x00\x00\x01", idx)
        if j < 0:
            break
        starts.append(j - 1 if j > 0 and data[j - 1] == 0 else j)
        idx = j + 3
    starts.append(len(data))

    aus = [data[a:b] for a, b in zip(starts, starts[1:])]
    print(f"  (split into {len(aus)} access units)")
    return aus


def main() -> int:
    aus = make_access_units()
    check(len(aus) > 0, "produced H.264 access units")

    model = srv.resolve_model_path(None)
    print(f"  (model: {model})")
    check(model is not None, "resolved the app's bundled hand_landmarker.task")

    session = FakeSession()
    pipeline = srv.HandPipeline(session, complexity=0, max_hands=2, show_stats=False, model_path=model)

    for i, au in enumerate(aus):
        try:
            session.frame_queue.put_nowait((1_000_000_000 + i * 33_333_333, au))
        except queue.Full:
            pass

    t = threading.Thread(target=pipeline.run, daemon=True)
    t.start()

    deadline = time.time() + 60
    while time.time() < deadline and not session.sent:
        time.sleep(0.3)

    session.alive = False
    t.join(timeout=10)

    check(len(session.sent) > 0, "HandPipeline emitted a landmarks reply")
    if session.sent:
        ts, hands = session.sent[0]
        check(ts >= 1_000_000_000, "reply carries the phone capture timestamp")
        check(isinstance(hands, list), "hands payload is a list")
        if hands:
            hand = hands[0]
            check(hand["side"] in ("LEFT", "RIGHT"), "hand has a side")
            check(len(hand["img"]) == 21, "img has 21 landmarks")
            check(len(hand["world"]) == 21, "world has 21 landmarks")
            check(0.0 <= hand["conf"] <= 1.0, "confidence in range")
        else:
            print("  (no hands in the synthetic blob — geometry is not a real hand)")

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        return 1
    print("PIPELINE TEST PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
