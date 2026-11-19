using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Network;

public sealed record PairOutcome(bool Ok, string? Reason, byte[]? DeviceSecret, string? ServerName, string? ServerId = null);

/// <summary>
/// Reference implementation of the phone side of the protocol (pairing, session, UDP input + heartbeat).
/// Used by the TestClient tool and the integration tests; it is the executable spec for the Android client.
/// </summary>
public sealed class PhoneClient(string deviceId, string deviceName)
{
    public string DeviceId { get; } = deviceId;
    public string DeviceName { get; } = deviceName;

    private static JsonObject Hello(string id, string name) => new()
    {
        ["t"] = "hello", ["ver"] = ProtocolConstants.Version, ["deviceId"] = id, ["deviceName"] = name, ["caps"] = new JsonArray(),
    };

    private static async Task<JsonObject> Expect(ControlChannel ch, CancellationToken ct)
    {
        var r = await ch.ReadAsync(ct) ?? throw new IOException("Server closed the connection.");
        return r;
    }

    public async Task<PairOutcome> PairAsync(string host, int controlPort, string pin, CancellationToken ct, bool tamperProof = false)
    {
        using var tcp = new TcpClient { NoDelay = true };
        await tcp.ConnectAsync(host, controlPort, ct);
        var ch = new ControlChannel(tcp.GetStream());
        await ch.WriteAsync(Hello(DeviceId, DeviceName), ct);
        var hello = await Expect(ch, ct);
        if (ControlChannel.Type(hello) != "hello_ok") return new(false, ControlChannel.Str(hello, "code") ?? "hello_failed", null, null);
        var serverName = ControlChannel.Str(hello, "serverName");
        var serverId = ControlChannel.Str(hello, "serverId", 64);

        using var ecdh = ProtocolCrypto.CreateEcdh();
        var pubC = ProtocolCrypto.ExportPublic(ecdh);
        var nonceC = ProtocolCrypto.RandomBytes(ProtocolCrypto.NonceLength);
        await ch.WriteAsync(new JsonObject { ["t"] = "pair_request", ["pub"] = Convert.ToBase64String(pubC), ["nonce"] = Convert.ToBase64String(nonceC) }, ct);
        var chal = await Expect(ch, ct);
        if (ControlChannel.Type(chal) != "pair_challenge") return new(false, ControlChannel.Str(chal, "code") ?? "unexpected", null, serverName, serverId);
        var pubS = ControlChannel.Bytes(chal, "pub", ProtocolCrypto.PublicKeyLength);
        var nonceS = ControlChannel.Bytes(chal, "nonce", ProtocolCrypto.NonceLength);
        if (pubS == null || nonceS == null) return new(false, "bad_challenge", null, serverName, serverId);

        var shared = ProtocolCrypto.DeriveShared(ecdh, pubS);
        var confirmKey = ProtocolCrypto.ConfirmKey(shared, pin, nonceC, nonceS);
        var proof = ProtocolCrypto.PairProof(confirmKey, true, pubC, pubS);
        if (tamperProof) proof[0] ^= 1;
        await ch.WriteAsync(new JsonObject { ["t"] = "pair_proof", ["proof"] = Convert.ToBase64String(proof) }, ct);
        var res = await Expect(ch, ct);
        if (ControlChannel.Type(res) != "pair_result") return new(false, ControlChannel.Str(res, "code") ?? "unexpected", null, serverName, serverId);
        if (res["ok"]?.GetValue<bool>() != true) return new(false, ControlChannel.Str(res, "reason") ?? "failed", null, serverName, serverId);

        var proofS = ControlChannel.Bytes(res, "proof", 32);
        var expectedS = ProtocolCrypto.PairProof(confirmKey, false, pubC, pubS);
        if (proofS == null || !ProtocolCrypto.FixedEquals(expectedS, proofS)) return new(false, "server_not_authenticated", null, serverName, serverId);
        return new(true, null, ProtocolCrypto.DeviceSecret(shared, nonceC, nonceS), serverName, serverId);
    }

    /// <summary>Opens the control connection, authenticates with the stored secret and starts the UDP side.</summary>
    public async Task<ClientSession> StartSessionAsync(string host, int controlPort, byte[] deviceSecret, CancellationToken ct)
    {
        var tcp = new TcpClient { NoDelay = true };
        try
        {
            await tcp.ConnectAsync(host, controlPort, ct);
            var ch = new ControlChannel(tcp.GetStream());
            await ch.WriteAsync(Hello(DeviceId, DeviceName), ct);
            var hello = await Expect(ch, ct);
            if (ControlChannel.Type(hello) != "hello_ok") throw new ProtocolException(ControlChannel.Str(hello, "code") ?? "hello_failed");
            if (hello["paired"]?.GetValue<bool>() != true) throw new ProtocolException("not_paired");
            int udpPort = hello["udpPort"]!.GetValue<int>();

            var nonceC = ProtocolCrypto.RandomBytes(ProtocolCrypto.NonceLength);
            await ch.WriteAsync(new JsonObject { ["t"] = "session_request", ["nonce"] = Convert.ToBase64String(nonceC) }, ct);
            var chal = await Expect(ch, ct);
            if (ControlChannel.Type(chal) != "session_challenge") throw new ProtocolException(ControlChannel.Str(chal, "code") ?? "unexpected");
            var nonceS = ControlChannel.Bytes(chal, "nonce", ProtocolCrypto.NonceLength) ?? throw new ProtocolException("bad_challenge");

            var mac = ProtocolCrypto.SessionMacClient(deviceSecret, nonceC, nonceS);
            await ch.WriteAsync(new JsonObject { ["t"] = "session_auth", ["mac"] = Convert.ToBase64String(mac) }, ct);
            var start = await Expect(ch, ct);
            if (ControlChannel.Type(start) != "session_start") throw new ProtocolException(ControlChannel.Str(start, "code") ?? "unexpected");
            if (start["ok"]?.GetValue<bool>() != true) throw new ProtocolException(ControlChannel.Str(start, "reason") ?? "auth_failed");
            uint sid = start["sessionId"]!.GetValue<uint>();
            var macS = ControlChannel.Bytes(start, "mac", 32);
            if (macS == null || !ProtocolCrypto.FixedEquals(ProtocolCrypto.SessionMacServer(deviceSecret, nonceC, nonceS, sid), macS))
                throw new ProtocolException("server_not_authenticated");
            var (kC2S, kS2C) = ProtocolCrypto.SessionKeys(deviceSecret, nonceC, nonceS, sid);

            var addr = (await Dns.GetHostAddressesAsync(host, ct)).First(a => a.AddressFamily == AddressFamily.InterNetwork);
            return new ClientSession(tcp, ch, sid, kC2S, kS2C, new IPEndPoint(addr, udpPort));
        }
        catch { tcp.Dispose(); throw; }
    }
}

public sealed class ProtocolException(string code) : Exception(code)
{
    public string Code { get; } = code;
}

/// <summary>An established session from the phone's point of view.</summary>
public sealed class ClientSession : IDisposable
{
    private readonly TcpClient _tcp;
    private readonly ControlChannel _ch;
    private readonly byte[] _kC2S, _kS2C;
    private readonly UdpClient _udp = new(AddressFamily.InterNetwork);
    private readonly IPEndPoint _server;
    private readonly CancellationTokenSource _cts = new();
    private readonly System.Diagnostics.Stopwatch _clock = System.Diagnostics.Stopwatch.StartNew();
    private readonly SequenceTracker _rx = new();
    private uint _txSeq;
    private uint _lastPeerTs = ProtocolConstants.NoEcho;
    private long _lastPeerTsAt;
    private int _heartbeatsEnabled = 1;

    internal ClientSession(TcpClient tcp, ControlChannel ch, uint sid, byte[] kC2S, byte[] kS2C, IPEndPoint server)
    {
        _tcp = tcp; _ch = ch; SessionId = sid; _kC2S = kC2S; _kS2C = kS2C; _server = server;
        _udp.Connect(server);
        _ = Task.Run(ReceiveUdp);
        _ = Task.Run(HeartbeatLoop);
        _ = Task.Run(WatchControl);
    }

    public uint SessionId { get; }
    public double? RttMs { get; private set; }
    public string? ClosedReason { get; private set; }
    public bool IsClosed => ClosedReason != null;
    private uint NowMs => (uint)_clock.ElapsedMilliseconds;

    public void SendState(in GamepadState s)
    {
        var pkt = PacketCodec.EncodeState(SessionId, Interlocked.Increment(ref _txSeq), NowMs, s, _kC2S);
        try { _udp.Send(pkt, pkt.Length); } catch (SocketException) { } catch (ObjectDisposedException) { }
    }

    /// <summary>Test hook: sends an arbitrary pre-built datagram on the input socket.</summary>
    public void SendRaw(byte[] datagram) { _udp.Send(datagram, datagram.Length); }

    /// <summary>Test hook: next sequence number that would be used (lets tests craft replays/duplicates).</summary>
    public byte[] BuildState(uint seq, in GamepadState s) => PacketCodec.EncodeState(SessionId, seq, NowMs, s, _kC2S);

    public void StopHeartbeats() => Interlocked.Exchange(ref _heartbeatsEnabled, 0);

    private async Task ReceiveUdp()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var r = await _udp.ReceiveAsync(_cts.Token);
                if (PacketCodec.TryDecode(r.Buffer, SessionId, _kS2C, out var p) != PacketError.None) continue;
                if (_rx.Accept(p.Sequence) != SequenceResult.Accepted || p.Type != ProtocolConstants.TypeHeartbeat) continue;
                _lastPeerTs = p.Timestamp; _lastPeerTsAt = _clock.ElapsedMilliseconds;
                if (p.Echo != ProtocolConstants.NoEcho)
                {
                    uint rtt = unchecked(NowMs - p.Echo - p.EchoDelay);
                    if (rtt < 10_000) RttMs = rtt;
                }
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { await Task.Delay(50); }
        }
    }

    private async Task HeartbeatLoop()
    {
        using var t = new PeriodicTimer(TimeSpan.FromMilliseconds(500));
        try
        {
            while (await t.WaitForNextTickAsync(_cts.Token))
            {
                if (Volatile.Read(ref _heartbeatsEnabled) == 0) continue;
                uint echo = _lastPeerTs;
                ushort delay = echo == ProtocolConstants.NoEcho ? (ushort)0 : (ushort)Math.Min(ushort.MaxValue, _clock.ElapsedMilliseconds - _lastPeerTsAt);
                var pkt = PacketCodec.EncodeHeartbeat(SessionId, Interlocked.Increment(ref _txSeq), NowMs, echo, delay, _kC2S);
                try { await _udp.SendAsync(pkt, pkt.Length); } catch (SocketException) { }
            }
        }
        catch (OperationCanceledException) { }
        catch (ObjectDisposedException) { }
    }

    private async Task WatchControl()
    {
        try
        {
            while (!_cts.IsCancellationRequested)
            {
                var m = await _ch.ReadAsync(_cts.Token);
                if (m == null) { ClosedReason ??= "server closed the connection"; return; }
                if (ControlChannel.Type(m) == "disconnect") { ClosedReason ??= ControlChannel.Str(m, "reason") ?? "disconnect"; return; }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception) { ClosedReason ??= "connection lost"; }
    }

    /// <summary>Simulates a Wi-Fi drop: stops everything without telling the server.</summary>
    public void Abort() { _cts.Cancel(); _udp.Dispose(); _tcp.Dispose(); ClosedReason ??= "aborted"; }

    public async Task DisconnectAsync()
    {
        try { using var c = new CancellationTokenSource(1000); await _ch.WriteAsync(new JsonObject { ["t"] = "disconnect", ["reason"] = "user" }, c.Token); } catch { }
        Dispose();
    }

    public void Dispose()
    {
        _cts.Cancel();
        _udp.Dispose();
        _tcp.Dispose();
        ClosedReason ??= "disposed";
    }
}
