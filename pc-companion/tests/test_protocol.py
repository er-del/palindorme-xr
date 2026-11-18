"""Protocol-level self tests for the PALINDORME XR PC companion.

Runs without MediaPipe/PyAV — only `cryptography` is required. Covers the
pairing handshake, encrypted control channel, landmark message shape, and the
H.264 NAL classification used by the keyframe gate.

    python pc-companion/tests/test_protocol.py
"""

from __future__ import annotations

import base64
import json
import queue
import socket
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import companion_server as srv  # noqa: E402

CODE = "123456"
UDP_PORT = 19851
failures: list[str] = []


def check(cond, label):
    print(f"  {'PASS' if cond else 'FAIL'}  {label}")
    if not cond:
        failures.append(label)


def avcc(nal_type: int, payload: bytes = b"\x00" * 8) -> bytes:
    body = bytes([nal_type]) + payload
    return len(body).to_bytes(4, "big") + body


def test_nal_gate():
    print("\n[NAL classification]")
    annexb = lambda t: b"\x00\x00\x00\x01" + bytes([t]) + b"\x00" * 8
    check(srv.is_keyframe(annexb(0x65)), "Annex-B IDR is a keyframe")
    check(not srv.is_keyframe(annexb(0x41)), "Annex-B P-slice is not a keyframe")
    check(srv.is_codec_config(annexb(0x67)), "Annex-B SPS is codec config")
    check(srv.is_keyframe(avcc(5)), "AVCC IDR is a keyframe")
    check(srv.is_codec_config(avcc(7)), "AVCC SPS is codec config")
    check(srv.is_codec_config(avcc(8)), "AVCC PPS is codec config")
    check(srv.is_keyframe(avcc(7) + avcc(5)), "AVCC config+IDR is a keyframe")
    check(not srv.is_keyframe(b""), "empty AU is not a keyframe")
    check(not srv.is_keyframe(b"\xde\xad\xbe\xef"), "garbage AU is not a keyframe")


def test_handshake_and_control():
    print("\n[Pairing handshake + encrypted control]")
    c_sock, s_sock = socket.socketpair()
    result = []

    t = threading.Thread(target=lambda: result.append(srv.do_handshake(s_sock, CODE, UDP_PORT)), daemon=True)
    t.start()

    f = c_sock.makefile("rb")
    client_nonce = "aabbccddeeff00112233445566778899"
    c_sock.sendall(json.dumps({"type": "hello", "proto": 1, "clientNonce": client_nonce}).encode() + b"\n")

    challenge = json.loads(f.readline().decode())
    server_nonce = challenge["serverNonce"]
    key = srv.derive_session_key(CODE, client_nonce, server_nonce)

    check(
        challenge["proof"] == srv.compute_auth_proof(key, f"SERVER:{client_nonce}:{server_nonce}"),
        "PC proof verifies on the phone side",
    )
    check(challenge.get("udpPort") == UDP_PORT, "PC announces the UDP port")

    c_sock.sendall(
        json.dumps(
            {
                "type": "auth",
                "proof": srv.compute_auth_proof(key, f"CLIENT:{server_nonce}:{client_nonce}"),
                "quality": "AUTO",
            }
        ).encode() + b"\n"
    )
    check(json.loads(f.readline().decode()).get("ok") is True, "PC acks auth with ok=true")

    t.join(timeout=5)
    handed = result[0] if result else None
    check(handed is not None, "server completed the handshake")
    if handed is None:
        return

    server_key, reader = handed
    check(server_key == key, "both sides derived the same session key")

    session = srv.Session(s_sock, server_key, queue.Queue(maxsize=2))
    threading.Thread(target=session.read_loop, args=(reader,), daemon=True).start()

    c_sock.sendall(
        base64.b64encode(srv.encrypt_aes_gcm(key, json.dumps({"type": "ping", "seq": 1, "t1Ns": 111_222_333}).encode()))
        + b"\n"
    )
    pong = json.loads(srv.decrypt_aes_gcm(key, base64.b64decode(f.readline().strip())).decode())
    check(pong.get("type") == "pong", "encrypted ping gets a pong")
    check(pong.get("t1Ns") == 111_222_333, "pong echoes t1Ns")
    check(isinstance(pong.get("t2PcNs"), int) and isinstance(pong.get("t3PcNs"), int), "pong carries t2PcNs/t3PcNs")

    hands = [{"side": "RIGHT", "conf": 0.9, "img": [[0.5, 0.5, 0.0]] * 21, "world": [[0.0, 0.0, 0.0]] * 21}]
    session.send_landmarks(987_654_321, hands)
    msg = json.loads(srv.decrypt_aes_gcm(key, base64.b64decode(f.readline().strip())).decode())
    check(msg.get("type") == "landmarks", "server emits landmarks")
    check(msg.get("frameTsNs") == 987_654_321, "landmarks echo the capture timestamp")
    check(len(msg["hands"][0]["img"]) == 21, "landmark payload has 21 points")


def test_wrong_code_rejected():
    print("\n[Wrong pairing code]")
    c_sock, s_sock = socket.socketpair()
    result = []
    t = threading.Thread(target=lambda: result.append(srv.do_handshake(s_sock, "999999", UDP_PORT)), daemon=True)
    t.start()

    f = c_sock.makefile("rb")
    nonce = "11223344556677889900112233445566"
    c_sock.sendall(json.dumps({"type": "hello", "proto": 1, "clientNonce": nonce}).encode() + b"\n")
    ch = json.loads(f.readline().decode())
    wrong_key = srv.derive_session_key("000000", nonce, ch["serverNonce"])
    c_sock.sendall(
        json.dumps({"type": "auth", "proof": srv.compute_auth_proof(wrong_key, "CLIENT:x:y")}).encode() + b"\n"
    )
    response = json.loads(f.readline().decode())
    t.join(timeout=5)

    check(response.get("ok") is False, "wrong code is rejected")
    check(result and result[0] is None, "server aborts the session on a bad proof")


def main() -> int:
    test_nal_gate()
    test_handshake_and_control()
    test_wrong_code_rejected()
    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        return 1
    print("PROTOCOL TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
