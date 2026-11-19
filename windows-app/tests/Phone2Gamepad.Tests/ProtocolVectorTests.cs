using System.Text.Json;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Tests;

public class ProtocolVectorTests
{
    private static readonly JsonElement V = JsonDocument.Parse(File.ReadAllText("vectors.json")).RootElement;

    private static byte[] Hex(string s) => Convert.FromHexString(s);
    private static byte[] Hex(JsonElement e, string name) => Hex(e.GetProperty(name).GetString()!);

    private static (uint Sid, byte[] KC, byte[] KS) Session()
    {
        var s = V.GetProperty("session");
        return (s.GetProperty("sessionId").GetUInt32(), Hex(s, "k_c2s"), Hex(s, "k_s2c"));
    }

    [Fact]
    public void SessionKdf_matches_reference()
    {
        var s = V.GetProperty("session");
        var dev = Hex(s, "deviceSecret"); var nc = Hex(s, "nonceC"); var ns = Hex(s, "nonceS");
        uint sid = s.GetProperty("sessionId").GetUInt32();
        var (kc, ks) = ProtocolCrypto.SessionKeys(dev, nc, ns, sid);
        Assert.Equal(Hex(s, "k_c2s"), kc);
        Assert.Equal(Hex(s, "k_s2c"), ks);
        Assert.Equal(Hex(s, "macC"), ProtocolCrypto.SessionMacClient(dev, nc, ns));
        Assert.Equal(Hex(s, "macS"), ProtocolCrypto.SessionMacServer(dev, nc, ns, sid));
    }

    [Fact]
    public void PairingKdf_matches_reference()
    {
        var p = V.GetProperty("pairing");
        var shared = Hex(p, "shared"); var nc = Hex(p, "nonceC"); var ns = Hex(p, "nonceS");
        var pubC = Hex(p, "pubC"); var pubS = Hex(p, "pubS");
        var ck = ProtocolCrypto.ConfirmKey(shared, p.GetProperty("pin").GetString()!, nc, ns);
        Assert.Equal(Hex(p, "confirmKey"), ck);
        Assert.Equal(Hex(p, "proofC"), ProtocolCrypto.PairProof(ck, true, pubC, pubS));
        Assert.Equal(Hex(p, "proofS"), ProtocolCrypto.PairProof(ck, false, pubC, pubS));
        Assert.Equal(Hex(p, "deviceSecret"), ProtocolCrypto.DeviceSecret(shared, nc, ns));
    }

    public static IEnumerable<object[]> StateVectors() =>
        V.GetProperty("state").EnumerateArray().Select(e => new object[] { e.GetProperty("name").GetString()! });

    [Theory, MemberData(nameof(StateVectors))]
    public void State_vectors_decode_and_reencode_byte_for_byte(string name)
    {
        var e = V.GetProperty("state").EnumerateArray().First(x => x.GetProperty("name").GetString() == name);
        var (sid, kc, _) = Session();
        var bytes = Hex(e, "hex");
        Assert.Equal(ProtocolConstants.StateLength, bytes.Length);
        Assert.Equal(PacketError.None, PacketCodec.TryDecode(bytes, sid, kc, out var p));
        var d = e.GetProperty("decoded");
        Assert.Equal(d.GetProperty("seq").GetUInt32(), p.Sequence);
        Assert.Equal(d.GetProperty("ts").GetUInt32(), p.Timestamp);
        Assert.Equal((ushort)d.GetProperty("btn").GetInt32(), (ushort)p.State.Buttons);
        Assert.Equal((byte)d.GetProperty("dpad").GetInt32(), (byte)p.State.Dpad);
        Assert.Equal((short)d.GetProperty("lx").GetInt32(), p.State.LeftX);
        Assert.Equal((short)d.GetProperty("ly").GetInt32(), p.State.LeftY);
        Assert.Equal((short)d.GetProperty("rx").GetInt32(), p.State.RightX);
        Assert.Equal((short)d.GetProperty("ry").GetInt32(), p.State.RightY);
        Assert.Equal((byte)d.GetProperty("lt").GetInt32(), p.State.LeftTrigger);
        Assert.Equal((byte)d.GetProperty("rt").GetInt32(), p.State.RightTrigger);
        // Encoding the decoded values must reproduce the vector exactly.
        Assert.Equal(bytes, PacketCodec.EncodeState(sid, p.Sequence, p.Timestamp, p.State, kc));
    }

    [Fact]
    public void Heartbeat_vectors()
    {
        var (sid, kc, _) = Session();
        foreach (var e in V.GetProperty("heartbeat").EnumerateArray())
        {
            var bytes = Hex(e, "hex");
            Assert.Equal(ProtocolConstants.HeartbeatLength, bytes.Length);
            Assert.Equal(PacketError.None, PacketCodec.TryDecode(bytes, sid, kc, out var p));
            var d = e.GetProperty("decoded");
            Assert.Equal(ProtocolConstants.TypeHeartbeat, p.Type);
            Assert.Equal(d.GetProperty("seq").GetUInt32(), p.Sequence);
            Assert.Equal(d.GetProperty("ts").GetUInt32(), p.Timestamp);
            Assert.Equal(d.GetProperty("echo").GetUInt32(), p.Echo);
            Assert.Equal((ushort)d.GetProperty("delay").GetInt32(), p.EchoDelay);
            Assert.Equal(bytes, PacketCodec.EncodeHeartbeat(sid, p.Sequence, p.Timestamp, p.Echo, p.EchoDelay, kc));
        }
    }

    [Fact]
    public void Invalid_vectors_are_rejected_for_the_documented_reason()
    {
        var (sid, kc, _) = Session();
        var map = new Dictionary<string, PacketError>
        {
            ["length"] = PacketError.Length, ["magic"] = PacketError.Magic, ["version"] = PacketError.Version,
            ["tag"] = PacketError.Tag, ["session"] = PacketError.Session,
        };
        var items = V.GetProperty("invalid").EnumerateArray().ToList();
        Assert.True(items.Count >= 9);
        foreach (var e in items)
        {
            var err = PacketCodec.TryDecode(Hex(e, "hex"), sid, kc, out _);
            Assert.True(map[e.GetProperty("reason").GetString()!] == err, $"{e.GetProperty("name")}: got {err}");
        }
    }

    [Fact]
    public void Sequence_vectors_serial_arithmetic()
    {
        foreach (var row in V.GetProperty("sequence").EnumerateArray())
        {
            uint last = row[0].GetUInt32(), seq = row[1].GetUInt32();
            Assert.Equal(row[2].GetBoolean(), SequenceTracker.IsNewer(last, seq));
        }
    }

    [Fact]
    public void Reserved_bits_are_ignored_but_tag_still_covers_them()
    {
        var (sid, kc, _) = Session();
        // Build a packet with reserved button bits + reserved byte set, correctly tagged: must decode with them masked off.
        var good = PacketCodec.EncodeState(sid, 1, 0, new GamepadState(GamepadButtons.A, DpadFlags.None, 0, 0, 0, 0, 0, 0), kc);
        var tampered = (byte[])good.Clone(); tampered[17] |= 0xF8; tampered[19] = 0x7F;
        Assert.Equal(PacketError.Tag, PacketCodec.TryDecode(tampered, sid, kc, out _)); // unauthenticated modification
        var d = BitConverter.GetBytes((ushort)0xF801);
        var raw = (byte[])good.Clone(); raw[16] = d[0]; raw[17] = d[1]; raw[19] = 0x7F;
        using var h = new System.Security.Cryptography.HMACSHA256(kc);
        h.ComputeHash(raw, 0, 30).AsSpan(0, 16).CopyTo(raw.AsSpan(30));
        Assert.Equal(PacketError.None, PacketCodec.TryDecode(raw, sid, kc, out var p));
        Assert.Equal(GamepadButtons.A, p.State.Buttons);
    }

    [Fact]
    public void Ecdh_roundtrip_and_bad_keys()
    {
        using var a = ProtocolCrypto.CreateEcdh(); using var b = ProtocolCrypto.CreateEcdh();
        var pa = ProtocolCrypto.ExportPublic(a); var pb = ProtocolCrypto.ExportPublic(b);
        Assert.Equal(65, pa.Length); Assert.Equal(4, pa[0]);
        Assert.Equal(ProtocolCrypto.DeriveShared(a, pb), ProtocolCrypto.DeriveShared(b, pa));
        Assert.ThrowsAny<System.Security.Cryptography.CryptographicException>(() => ProtocolCrypto.DeriveShared(a, new byte[65]));
        var offCurve = (byte[])pb.Clone(); offCurve[64] ^= 1;
        Assert.ThrowsAny<System.Security.Cryptography.CryptographicException>(() => ProtocolCrypto.DeriveShared(a, offCurve));
    }
}
