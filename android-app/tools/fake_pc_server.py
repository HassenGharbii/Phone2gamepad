"""Fake Phone2Gamepad PC for exercising the Android app (independent Python implementation of PROTOCOL.md).

Usage: python fake_pc_server.py [--pin 123456] [--name FAKE-PC] [--store secrets.json] [--log server.log]
Auto-accepts pairing when the PIN proof is correct. Logs decoded controller state changes and packet counters.
Requires: pip install cryptography
"""
import argparse, base64, hashlib, hmac, json, os, secrets, socket, struct, threading, time
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization

ap = argparse.ArgumentParser()
ap.add_argument("--pin", default="123456")
ap.add_argument("--name", default="FAKE-PC")
ap.add_argument("--store", default="fake_pc_secrets.json")
ap.add_argument("--log", default="fake_pc.log")
ap.add_argument("--tcp", type=int, default=47821)
ap.add_argument("--udp", type=int, default=47822)
ap.add_argument("--disc", type=int, default=47820)
A = ap.parse_args()

logf = open(A.log, "a", buffering=1)
def log(*a):
    line = time.strftime("%H:%M:%S ") + " ".join(str(x) for x in a)
    print(line, flush=True); logf.write(line + "\n")

try: store = json.load(open(A.store))
except Exception: store = {}
def save(): json.dump(store, open(A.store, "w"))

def hkdf(ikm, salt, info, L):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest(); out = t = b""; i = 1
    while len(out) < L:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); out += t; i += 1
    return out[:L]
def hm(k, *p): return hmac.new(k, b"".join(p), hashlib.sha256).digest()
b64 = lambda b: base64.b64encode(b).decode()
unb = base64.b64decode

sessions = {}   # sid -> dict(keys, addr_ip, last_seq, hb_seq, peer_ts, peer_recv, start, last_state)
lock = threading.Lock()
counters = {"state": 0, "hb": 0, "bad": 0}
udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); udp.bind(("0.0.0.0", A.udp))

def newer(last, seq): return ((seq - last) & 0xFFFFFFFF) < 0x80000000 and seq != last

def udp_loop():
    while True:
        data, addr = udp.recvfrom(256)
        with lock:
            try:
                if len(data) < 12 or data[:2] != b"P2" or data[2] != 1: raise ValueError("hdr")
                typ = data[3]; need = {1: 46, 2: 40}[typ]
                if len(data) != need: raise ValueError("len")
                sid, seq = struct.unpack_from("<II", data, 4)
                s = sessions.get(sid)
                if not s or addr[0] != s["ip"]: raise ValueError("session/ip")
                if not hmac.compare_digest(hm(s["k_c2s"], data[:-16])[:16], data[-16:]): raise ValueError("tag")
                if not newer(s["last_seq"], seq): raise ValueError("seq")
                s["last_seq"] = seq; s["addr"] = addr; s["last_rx"] = time.time()
                ts = struct.unpack_from("<I", data, 12)[0]
                if typ == 1:
                    counters["state"] += 1
                    st = struct.unpack_from("<HBBhhhhBB", data, 16)
                    if st != s.get("last_state"):
                        s["last_state"] = st
                        log("STATE btn=0x%03x dpad=%x L=(%d,%d) R=(%d,%d) LT=%d RT=%d" % (st[0], st[1], st[3], st[4], st[5], st[6], st[7], st[8]))
                else:
                    counters["hb"] += 1
                    s["peer_ts"] = ts; s["peer_recv"] = time.time()
                    echo, delay = struct.unpack_from("<IH", data, 16)
                    if echo != 0xFFFFFFFF:
                        rtt = ((int((time.time() - s["start"]) * 1000) & 0xFFFFFFFF) - echo - delay) & 0xFFFFFFFF
                        if rtt < 10000: s["rtt"] = rtt
            except Exception as e:
                counters["bad"] += 1
                if counters["bad"] <= 5: log("UDP drop:", e)

def hb_loop():
    while True:
        time.sleep(0.5)
        with lock:
            for sid, s in list(sessions.items()):
                if "addr" not in s: continue
                now_ts = int((time.time() - s["start"]) * 1000) & 0xFFFFFFFF
                echo = s.get("peer_ts", 0xFFFFFFFF)
                delay = min(int((time.time() - s["peer_recv"]) * 1000), 0xFFFF) if "peer_ts" in s else 0
                s["hb_seq"] += 1
                body = b"P2" + bytes([1, 2]) + struct.pack("<IIIIHH", sid, s["hb_seq"], now_ts, echo, delay, 0)
                udp.sendto(body + hm(s["k_s2c"], body)[:16], s["addr"])
                if time.time() - s["last_rx"] > 0.5 and not s.get("neutral"):
                    s["neutral"] = True; log("STALE >500ms -> NEUTRALIZE")
                elif time.time() - s["last_rx"] <= 0.5: s["neutral"] = False

def stats_loop():
    while True:
        time.sleep(5)
        with lock:
            r = [s.get("rtt") for s in sessions.values()]
        log("counters", counters, "rtt_ms", r)

def disc_loop():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("0.0.0.0", A.disc))
    while True:
        d, addr = s.recvfrom(64)
        if len(d) == 8 and d[:2] == b"P2" and d[2] == 1 and d[3] == 0x10:
            name = A.name.encode()
            s.sendto(b"P2" + bytes([1, 0x11]) + d[4:8] + struct.pack("<HHBB", A.tcp, A.udp, 1, len(name)) + name, addr)
            log("discovery from", addr[0])

def rd(c):
    h = b""
    while len(h) < 4:
        x = c.recv(4 - len(h));
        if not x: raise EOFError
        h += x
    n = struct.unpack("<I", h)[0]; b = b""
    if not 1 <= n <= 16384: raise ValueError("frame len")
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return json.loads(b)
def wr(c, o):
    b = json.dumps(o).encode(); c.sendall(struct.pack("<I", len(b)) + b)

def handle(c, peer):
    sid_made = None
    try:
        m = rd(c); assert m["t"] == "hello"; dev = m["deviceId"]; dname = m["deviceName"]
        wr(c, {"t": "hello_ok", "ver": 1, "serverName": A.name, "paired": dev in store, "pairingOpen": True, "udpPort": A.udp, "caps": []})
        log("hello from", dname, "paired=", dev in store)
        while True:
            m = rd(c); t = m["t"]
            if t == "pair_request":
                pc = unb(m["pub"]); nc = unb(m["nonce"])
                k = ec.generate_private_key(ec.SECP256R1())
                ps = k.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
                ns = secrets.token_bytes(16)
                wr(c, {"t": "pair_challenge", "pub": b64(ps), "nonce": b64(ns)})
                shared = k.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), pc))
                ck = hkdf(shared + A.pin.encode(), nc + ns, b"P2G confirm v1", 32)
                p = rd(c); assert p["t"] == "pair_proof"
                if not hmac.compare_digest(unb(p["proof"]), hm(ck, b"client", pc, ps)):
                    wr(c, {"t": "pair_result", "ok": False, "reason": "bad_pin"}); log("pair: bad PIN"); return
                store[dev] = b64(hkdf(shared, nc + ns, b"P2G device secret v1", 32)); save()
                wr(c, {"t": "pair_result", "ok": True, "proof": b64(hm(ck, b"server", pc, ps))}); log("pair: OK (auto-accepted)", dname)
            elif t == "session_request":
                if dev not in store:
                    wr(c, {"t": "error", "code": "not_paired", "message": "pair first"}); return
                nc = unb(m["nonce"]); ns = secrets.token_bytes(16)
                wr(c, {"t": "session_challenge", "nonce": b64(ns)})
                a = rd(c); assert a["t"] == "session_auth"; ds = unb(store[dev])
                if not hmac.compare_digest(unb(a["mac"]), hm(ds, b"auth-c", nc, ns)):
                    wr(c, {"t": "error", "code": "auth_failed", "message": "bad mac"}); return
                sid = secrets.randbits(32) or 1
                okm = hkdf(ds, nc + ns, b"P2G session v1" + struct.pack("<I", sid), 64)
                with lock:
                    sessions[sid] = dict(k_c2s=okm[:32], k_s2c=okm[32:], ip=peer[0], last_seq=0, hb_seq=0, start=time.time(), last_rx=time.time())
                sid_made = sid
                wr(c, {"t": "session_start", "ok": True, "sessionId": sid, "mac": b64(hm(ds, b"auth-s", nc, ns, struct.pack("<I", sid)))})
                log("SESSION started sid=%08x" % sid)
            elif t == "disconnect":
                log("client disconnect:", m.get("reason")); return
            else:
                wr(c, {"t": "error", "code": "unsupported", "message": t})
    except (EOFError, ConnectionError, OSError):
        pass
    except Exception as e:
        log("conn error", repr(e))
    finally:
        if sid_made:
            with lock: sessions.pop(sid_made, None)
            log("SESSION ended -> NEUTRALIZE sid=%08x" % sid_made)
        c.close()

for f in (udp_loop, hb_loop, stats_loop, disc_loop):
    threading.Thread(target=f, daemon=True).start()
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); srv.bind(("0.0.0.0", A.tcp)); srv.listen(4)
log("fake PC listening tcp", A.tcp, "udp", A.udp, "disc", A.disc, "PIN", A.pin)
while True:
    c, peer = srv.accept()
    threading.Thread(target=handle, args=(c, peer), daemon=True).start()
