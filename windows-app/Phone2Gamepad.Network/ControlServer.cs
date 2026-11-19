using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Network;

public sealed record PairingRequestInfo(string DeviceId, string DeviceName, IPAddress RemoteIp);

/// <summary>TCP control channel: hello, PIN pairing and session establishment (PROTOCOL.md sections 5-6).</summary>
public sealed class ControlServer : IDisposable
{
    private const int MaxBadFrames = 4;
    private static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan ConfirmTimeout = TimeSpan.FromSeconds(60);

    private readonly TcpListener _listener;
    private readonly string _serverName;
    private readonly bool _privateOnly;
    private readonly IPairedDeviceStore _store;
    private readonly PairingManager _pairing;
    private readonly SessionManager _sessions;
    private readonly Func<int> _udpPort;
    private readonly Func<bool> _padReady;
    private readonly ILogger _log;
    private readonly string? _serverId;
    private readonly CancellationTokenSource _cts = new();
    private readonly PairingManager _authLimiter = new(); // reused only for its per-IP failure rate limiting
    private Task? _accept;

    /// <summary>Asked when a correct PIN was presented; must return true for the pairing to complete. Null = always deny.</summary>
    public Func<PairingRequestInfo, CancellationToken, Task<bool>>? ConfirmPairing { get; set; }

    public ControlServer(IPAddress bind, int port, string serverName, bool privateOnly, IPairedDeviceStore store, PairingManager pairing,
        SessionManager sessions, Func<int> udpPort, Func<bool> padReady, ILogger? log = null, string? serverId = null)
    {
        _serverName = serverName; _privateOnly = privateOnly; _store = store; _pairing = pairing; _sessions = sessions;
        _udpPort = udpPort; _padReady = padReady; _serverId = serverId; _log = log ?? NullLogger.Instance;
        _listener = new TcpListener(bind, port);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
    }

    public int Port { get; }

    public void Start() => _accept = Task.Run(AcceptLoop);

    private async Task AcceptLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var c = await _listener.AcceptTcpClientAsync(_cts.Token);
                _ = Task.Run(() => HandleClient(c));
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (Exception ex) { _log.LogError(ex, "Accept failed"); }
        }
    }

    private sealed class Conn
    {
        public string? DeviceId, DeviceName;
        public bool Hello;
        // pairing
        public ECDiffieHellman? Ecdh; public byte[]? PubC, PubS, NonceC, NonceS, Shared;
        // session
        public byte[]? SessNonceC, SessNonceS;
        public ActiveSession? Session;
    }

    private async Task HandleClient(TcpClient client)
    {
        using var _ = client;
        var remote = ((IPEndPoint)client.Client.RemoteEndPoint!).Address;
        if (_privateOnly && !NetUtil.IsPrivateOrLoopback(remote))
        {
            _log.LogWarning("Rejected control connection from non-local address");
            return;
        }
        client.NoDelay = true;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
        string closeReason = "closed";
        var conn = new Conn();
        var ch = new ControlChannel(client.GetStream());
        int badFrames = 0;
        try
        {
            while (!linked.IsCancellationRequested)
            {
                JsonObject? msg;
                try
                {
                    using var frameCts = CancellationTokenSource.CreateLinkedTokenSource(linked.Token);
                    if (conn.Session == null) frameCts.CancelAfter(HandshakeTimeout);
                    msg = await ch.ReadAsync(frameCts.Token);
                }
                catch (BadFrameException ex)
                {
                    await TrySend(ch, ControlChannel.Error("bad_frame", ex.Message));
                    if (++badFrames > MaxBadFrames) { closeReason = "too many bad frames"; break; }
                    if (ex.Message.Contains("length") || ex.Message.Contains("Truncated")) { closeReason = "framing lost"; break; }
                    continue;
                }
                if (msg == null) { closeReason = "peer closed"; break; }

                if (!await Dispatch(conn, ch, msg, remote, linked))
                {
                    if (++badFrames > MaxBadFrames) { closeReason = "too many errors"; break; }
                }
                if (conn.Session != null && ControlChannel.Type(msg) == "disconnect") { closeReason = "client disconnect"; break; }
            }
        }
        catch (OperationCanceledException) { closeReason = conn.Session?.Id != null ? "server closed" : "handshake timeout"; }
        catch (IOException) { closeReason = "connection lost"; }
        catch (SocketException) { closeReason = "connection lost"; }
        catch (Exception ex) { _log.LogError(ex, "Control connection error"); closeReason = "internal error"; }
        finally
        {
            conn.Ecdh?.Dispose();
            if (conn.Session != null) _sessions.End(conn.Session, closeReason);
        }
    }

    private static async Task TrySend(ControlChannel ch, JsonObject o)
    {
        try { using var c = new CancellationTokenSource(2000); await ch.WriteAsync(o, c.Token); } catch { }
    }

    /// <returns>false if the message was answered with an error (counted against the connection's error budget).</returns>
    private async Task<bool> Dispatch(Conn c, ControlChannel ch, JsonObject m, IPAddress remote, CancellationTokenSource linked)
    {
        var ct = linked.Token;
        string t = ControlChannel.Type(m);
        async Task<bool> Err(string code, string msg) { await ch.WriteAsync(ControlChannel.Error(code, msg), ct); return false; }

        if (t == "hello")
        {
            if (m["ver"]?.GetValue<int>() != ProtocolConstants.Version) return await Err("bad_version", "Unsupported protocol version.");
            var id = ControlChannel.Str(m, "deviceId", 64); var name = ControlChannel.Str(m, "deviceName", 64);
            if (string.IsNullOrWhiteSpace(id) || string.IsNullOrWhiteSpace(name)) return await Err("bad_frame", "deviceId/deviceName required.");
            c.DeviceId = id; c.DeviceName = name; c.Hello = true;
            var helloOk = new JsonObject
            {
                ["t"] = "hello_ok", ["ver"] = ProtocolConstants.Version, ["serverName"] = _serverName,
                ["paired"] = _store.Get(id) != null, ["pairingOpen"] = _pairing.IsOpen, ["udpPort"] = _udpPort(),
                ["caps"] = new JsonArray(),
            };
            if (_serverId != null) helloOk["serverId"] = _serverId; // additive, optional: stable per-PC identity (lets clients key secrets per PC)
            await ch.WriteAsync(helloOk, ct);
            return true;
        }
        if (t == "disconnect") return true;
        if (t == "error") return true;
        if (!c.Hello) return await Err("bad_frame", "hello required first.");

        switch (t)
        {
            case "pair_request": return await OnPairRequest(c, ch, m, remote, ct, Err);
            case "pair_proof": return await OnPairProof(c, ch, m, remote, ct, Err);
            case "session_request": return await OnSessionRequest(c, ch, m, ct, Err);
            case "session_auth": return await OnSessionAuth(c, ch, m, remote, linked, ct, Err);
            case "config_get": return await Err("unsupported", "Configuration over the network is not supported in this version.");
            default: return await Err("unsupported", $"Unknown message type '{t}'.");
        }
    }

    private async Task<bool> OnPairRequest(Conn c, ControlChannel ch, JsonObject m, IPAddress remote, CancellationToken ct, Func<string, string, Task<bool>> err)
    {
        if (!_pairing.IsOpen) return await err("pairing_closed", "Pairing is not open on the PC. Click 'Open pairing' in the dashboard.");
        if (_pairing.IsRateLimited(remote)) return await err("rate_limited", "Too many failed attempts. Wait a moment and retry.");
        var pubC = ControlChannel.Bytes(m, "pub", ProtocolCrypto.PublicKeyLength);
        var nonceC = ControlChannel.Bytes(m, "nonce", ProtocolCrypto.NonceLength);
        if (pubC == null || nonceC == null) return await err("bad_frame", "pub/nonce malformed.");

        c.Ecdh?.Dispose();
        c.Ecdh = ProtocolCrypto.CreateEcdh();
        try { c.Shared = ProtocolCrypto.DeriveShared(c.Ecdh, pubC); }
        catch (CryptographicException) { return await err("bad_frame", "Invalid public key."); }
        c.PubC = pubC; c.PubS = ProtocolCrypto.ExportPublic(c.Ecdh);
        c.NonceC = nonceC; c.NonceS = ProtocolCrypto.RandomBytes(ProtocolCrypto.NonceLength);
        await ch.WriteAsync(new JsonObject
        {
            ["t"] = "pair_challenge", ["pub"] = Convert.ToBase64String(c.PubS), ["nonce"] = Convert.ToBase64String(c.NonceS),
        }, ct);
        return true;
    }

    private async Task<bool> OnPairProof(Conn c, ControlChannel ch, JsonObject m, IPAddress remote, CancellationToken ct, Func<string, string, Task<bool>> err)
    {
        if (c.Shared == null) return await err("bad_frame", "pair_request required first.");
        var proof = ControlChannel.Bytes(m, "proof", 32);
        if (proof == null) return await err("bad_frame", "proof malformed.");
        var pin = _pairing.Pin;
        if (pin == null) return await err("pairing_closed", "Pairing window closed or expired.");

        var confirmKey = ProtocolCrypto.ConfirmKey(c.Shared, pin, c.NonceC!, c.NonceS!);
        var expected = ProtocolCrypto.PairProof(confirmKey, true, c.PubC!, c.PubS!);
        if (!ProtocolCrypto.FixedEquals(expected, proof))
        {
            _pairing.RegisterFailure(remote);
            _log.LogWarning("Pairing failed: wrong PIN from {Device}", c.DeviceName);
            c.Shared = null; // a fresh pair_request (new ECDH exchange) is required for every guess
            await ch.WriteAsync(new JsonObject { ["t"] = "pair_result", ["ok"] = false, ["reason"] = "bad_pin" }, ct);
            return true;
        }

        bool accepted;
        using (var cc = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            cc.CancelAfter(ConfirmTimeout);
            try { accepted = ConfirmPairing != null && await ConfirmPairing(new PairingRequestInfo(c.DeviceId!, c.DeviceName!, remote), cc.Token); }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { accepted = false; }
        }
        if (!accepted)
        {
            _log.LogInformation("Pairing declined for {Device}", c.DeviceName);
            c.Shared = null;
            await ch.WriteAsync(new JsonObject { ["t"] = "pair_result", ["ok"] = false, ["reason"] = "rejected_by_user" }, ct);
            return true;
        }

        var secret = ProtocolCrypto.DeviceSecret(c.Shared, c.NonceC!, c.NonceS!);
        _store.Save(new PairedDevice(c.DeviceId!, c.DeviceName!, secret, DateTimeOffset.UtcNow));
        _pairing.Close(); // single use
        _log.LogInformation("Device paired: {Device}", c.DeviceName);
        await ch.WriteAsync(new JsonObject
        {
            ["t"] = "pair_result", ["ok"] = true,
            ["proof"] = Convert.ToBase64String(ProtocolCrypto.PairProof(confirmKey, false, c.PubC!, c.PubS!)),
        }, ct);
        c.Shared = null;
        return true;
    }

    private async Task<bool> OnSessionRequest(Conn c, ControlChannel ch, JsonObject m, CancellationToken ct, Func<string, string, Task<bool>> err)
    {
        if (_store.Get(c.DeviceId!) == null) return await err("not_paired", "This device is not paired with the PC.");
        var nonceC = ControlChannel.Bytes(m, "nonce", ProtocolCrypto.NonceLength);
        if (nonceC == null) return await err("bad_frame", "nonce malformed.");
        c.SessNonceC = nonceC; c.SessNonceS = ProtocolCrypto.RandomBytes(ProtocolCrypto.NonceLength);
        await ch.WriteAsync(new JsonObject { ["t"] = "session_challenge", ["nonce"] = Convert.ToBase64String(c.SessNonceS) }, ct);
        return true;
    }

    private async Task<bool> OnSessionAuth(Conn c, ControlChannel ch, JsonObject m, IPAddress remote, CancellationTokenSource linked, CancellationToken ct, Func<string, string, Task<bool>> err)
    {
        if (c.SessNonceC == null) return await err("bad_frame", "session_request required first.");
        var dev = _store.Get(c.DeviceId!);
        if (dev == null) return await err("not_paired", "This device is not paired with the PC.");
        if (_authLimiter.IsRateLimited(remote)) return await err("rate_limited", "Too many failed attempts.");
        var mac = ControlChannel.Bytes(m, "mac", 32);
        var expected = ProtocolCrypto.SessionMacClient(dev.Secret, c.SessNonceC, c.SessNonceS!);
        if (mac == null || !ProtocolCrypto.FixedEquals(expected, mac))
        {
            _authLimiter.RegisterFailure(remote, countsTowardWindow: false);
            _log.LogWarning("Session authentication failed for {Device}", c.DeviceName);
            c.SessNonceC = null;
            await ch.WriteAsync(new JsonObject { ["t"] = "session_start", ["ok"] = false, ["reason"] = "auth_failed" }, ct);
            return true;
        }
        if (!_padReady())
            return await err("internal", "The virtual controller driver is unavailable on the PC. Open the dashboard for details.");

        uint sid;
        do { sid = BitConverter.ToUInt32(RandomNumberGenerator.GetBytes(4)); } while (sid == 0);
        var (kC2S, kS2C) = ProtocolCrypto.SessionKeys(dev.Secret, c.SessNonceC, c.SessNonceS!, sid);
        // Invoked by the SessionManager when the session ends for any reason: tell the phone, then drop the TCP connection.
        void CloseTcp(string reason) => _ = Task.Run(async () =>
        {
            await TrySend(ch, new JsonObject { ["t"] = "disconnect", ["reason"] = reason });
            try { linked.Cancel(); } catch (ObjectDisposedException) { }
        });
        var result = _sessions.TryStart(c.DeviceId!, c.DeviceName!, remote, kC2S, kS2C, sid, CloseTcp, out var session);
        if (result == StartResult.Busy)
            return await err("busy", "Another phone already has an active session.");

        var macS = ProtocolCrypto.SessionMacServer(dev.Secret, c.SessNonceC, c.SessNonceS!, sid);
        c.Session = session;
        c.SessNonceC = null;
        await ch.WriteAsync(new JsonObject
        {
            ["t"] = "session_start", ["ok"] = true, ["sessionId"] = sid, ["mac"] = Convert.ToBase64String(macS),
        }, ct);
        return true;
    }

    public void Dispose()
    {
        _cts.Cancel();
        _listener.Stop();
        try { _accept?.Wait(1000); } catch { }
        _cts.Dispose();
    }
}
