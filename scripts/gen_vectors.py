"""Reference generator for shared/protocol/test-vectors/vectors.json (independent of C#/Kotlin)."""
import hmac, hashlib, struct, json, os

def hkdf(ikm, salt, info, L):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, t, i = b"", b"", 1
    while len(out) < L:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); out += t; i += 1
    return out[:L]

def tag(key, data): return hmac.new(key, data, hashlib.sha256).digest()[:16]
def hdr(typ, sid, seq, ver=1): return b"P2" + bytes([ver, typ]) + struct.pack("<II", sid, seq)

def state(sid, seq, ts, btn, dpad, lx, ly, rx, ry, lt, rt, key, reserved=0, ver=1):
    b = hdr(1, sid, seq, ver) + struct.pack("<IHBBhhhhBB", ts, btn, dpad, reserved, lx, ly, rx, ry, lt, rt)
    return b + tag(key, b)

def hb(sid, seq, ts, echo, delay, key):
    b = hdr(2, sid, seq) + struct.pack("<IIHH", ts, echo, delay, 0)
    return b + tag(key, b)

# KDF vector
dev = bytes(range(32)); nc = bytes(range(16)); ns = bytes(range(16, 32)); sid = 0x11223344
okm = hkdf(dev, nc + ns, b"P2G session v1" + struct.pack("<I", sid), 64)
kc, ks = okm[:32], okm[32:]
shared = bytes(range(100, 132)); pin = b"123456"
pub_c = bytes([4]) + bytes(range(1, 65)); pub_s = bytes([4]) + bytes(range(65, 129))
confirm = hkdf(shared + pin, nc + ns, b"P2G confirm v1", 32)
V = {
 "session": {"sessionId": sid, "deviceSecret": dev.hex(), "nonceC": nc.hex(), "nonceS": ns.hex(),
   "k_c2s": kc.hex(), "k_s2c": ks.hex(),
   "macC": hmac.new(dev, b"auth-c"+nc+ns, hashlib.sha256).hexdigest(),
   "macS": hmac.new(dev, b"auth-s"+nc+ns+struct.pack("<I", sid), hashlib.sha256).hexdigest()},
 "pairing": {"shared": shared.hex(), "pin": "123456", "nonceC": nc.hex(), "nonceS": ns.hex(),
   "pubC": pub_c.hex(), "pubS": pub_s.hex(), "confirmKey": confirm.hex(),
   "proofC": hmac.new(confirm, b"client"+pub_c+pub_s, hashlib.sha256).hexdigest(),
   "proofS": hmac.new(confirm, b"server"+pub_c+pub_s, hashlib.sha256).hexdigest(),
   "deviceSecret": hkdf(shared, nc+ns, b"P2G device secret v1", 32).hex()},
 "state": [], "heartbeat": [], "invalid": []
}
def S(name, **kw):
    a = dict(sid=sid, seq=1, ts=0, btn=0, dpad=0, lx=0, ly=0, rx=0, ry=0, lt=0, rt=0); a.update(kw)
    pkt = state(a["sid"], a["seq"], a["ts"], a["btn"], a["dpad"], a["lx"], a["ly"], a["rx"], a["ry"], a["lt"], a["rt"], kc)
    V["state"].append({"name": name, "hex": pkt.hex(), "decoded": {k: a[k] for k in ("seq","ts","btn","dpad","lx","ly","rx","ry","lt","rt")}})
S("neutral")
S("all-buttons-max", seq=0xFFFFFFFF, ts=0xFFFFFFFF, btn=0x07FF, dpad=0x0F, lx=32767, ly=32767, rx=32767, ry=32767, lt=255, rt=255)
S("negative-extremes", seq=2, ts=1000, lx=-32768, ly=-32768, rx=-32768, ry=-32768)
S("a-and-lb", seq=3, ts=16, btn=0b10001, dpad=0b0001, lx=-1, ly=1, lt=128)
V["heartbeat"].append({"name":"hb-with-echo","hex":hb(sid,10,5000,4900,3,kc).hex(),"decoded":{"seq":10,"ts":5000,"echo":4900,"delay":3}})
V["heartbeat"].append({"name":"hb-no-echo","hex":hb(sid,11,5500,0xFFFFFFFF,0,kc).hex(),"decoded":{"seq":11,"ts":5500,"echo":4294967295,"delay":0}})
good = bytes.fromhex(V["state"][0]["hex"])
bad_tag = bytearray(good); bad_tag[-1] ^= 1
bad_body = bytearray(good); bad_body[16] ^= 1
V["invalid"] += [
 {"name":"truncated","hex":good[:-1].hex(),"reason":"length"},
 {"name":"too-long","hex":(good+b"\0").hex(),"reason":"length"},
 {"name":"empty","hex":"","reason":"length"},
 {"name":"bad-magic","hex":(b"XX"+good[2:]).hex(),"reason":"magic"},
 {"name":"unknown-version","hex":state(sid,1,0,0,0,0,0,0,0,0,0,kc,ver=2).hex(),"reason":"version"},
 {"name":"bad-tag","hex":bytes(bad_tag).hex(),"reason":"tag"},
 {"name":"tampered-body","hex":bytes(bad_body).hex(),"reason":"tag"},
 {"name":"wrong-session","hex":state(0xDEADBEEF,1,0,0,0,0,0,0,0,0,0,kc).hex(),"reason":"session"},
 {"name":"wrong-key-s2c","hex":state(sid,1,0,0,0,0,0,0,0,0,0,ks).hex(),"reason":"tag"},
]
# serial-number arithmetic: [last, seq, expectedNewer]
V["sequence"] = [[0,1,True],[1,1,False],[5,4,False],[0xFFFFFFFF,0,True],[0xFFFFFFF0,0x10,True],[0x10,0xFFFFFFF0,False],[0,0x7FFFFFFF,True],[0,0x80000000,False]]
out = os.path.join(os.path.dirname(__file__), "..", "shared", "protocol", "test-vectors", "vectors.json")
json.dump(V, open(out, "w"), indent=1)
print("wrote", os.path.abspath(out), len(V["state"]), "state vectors")
