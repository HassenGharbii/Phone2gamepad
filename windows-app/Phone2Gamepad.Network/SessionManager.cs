using System.Net;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Phone2Gamepad.Core;
using Phone2Gamepad.VirtualGamepad;

namespace Phone2Gamepad.Network;

/// <summary>One authenticated phone session. All mutable fields are guarded by the owning SessionManager's lock.</summary>
public sealed class ActiveSession
{
    internal ActiveSession(uint id, string deviceId, string deviceName, IPAddress remoteIp, byte[] kC2S, byte[] kS2C, long startTs, Action<string> closeTcp)
    {
        Id = id; DeviceId = deviceId; DeviceName = deviceName; RemoteIp = remoteIp; KeyC2S = kC2S; KeyS2C = kS2C;
        StartTimestamp = startTs; LastRxTimestamp = startTs; CloseTcp = closeTcp;
    }

    public uint Id { get; }
    public string DeviceId { get; }
    public string DeviceName { get; }
    public IPAddress RemoteIp { get; }
    public DateTimeOffset StartedUtc { get; internal set; }
    internal byte[] KeyC2S { get; }
    internal byte[] KeyS2C { get; }
    internal long StartTimestamp { get; }
    internal long LastRxTimestamp { get; set; }
    internal SequenceTracker Rx { get; } = new();
    internal IPEndPoint? PhoneEndpoint { get; set; }
    internal uint TxSeq;
    internal uint LastPeerTs = ProtocolConstants.NoEcho;
    internal long LastPeerTsTimestamp;
    internal bool Neutralized;
    internal Action<string> CloseTcp { get; }
    public GamepadState LastRaw { get; internal set; }
    public double? RttMs { get; internal set; }
}

public enum StartResult { Started, Busy }

public sealed class SessionEndedEventArgs(ActiveSession session, string reason)
{
    public ActiveSession Session { get; } = session;
    public string Reason { get; } = reason;
}

/// <summary>
/// Single-session state machine: validates input datagrams, drives the virtual gamepad, neutralizes on stale input
/// (500 ms) and terminates the session (5 s) or when the control connection closes.
/// </summary>
public sealed class SessionManager(IVirtualGamepad gamepad, Func<MappingProfile> mapping, ServerStats stats,
    ILogger? log = null, TimeProvider? time = null)
{
    private readonly TimeProvider _time = time ?? TimeProvider.System;
    private readonly ILogger _log = log ?? NullLogger.Instance;
    private readonly object _lock = new();
    private ActiveSession? _current;

    public ActiveSession? Current { get { lock (_lock) return _current; } }
    public bool IsNeutralized { get { lock (_lock) return _current?.Neutralized ?? true; } }

    public event Action<ActiveSession>? SessionStarted;
    public event Action<SessionEndedEventArgs>? SessionEnded;
    /// <summary>Raised with (raw wire state, mapped state) after each accepted state packet.</summary>
    public event Action<GamepadState, GamepadState>? StateApplied;
    /// <summary>Raised when the controller was neutralized because input went stale.</summary>
    public event Action? InputStale;

    private long Now => _time.GetTimestamp();
    private double ToMs(long ticks) => ticks * 1000.0 / _time.TimestampFrequency;

    /// <summary>Elapsed milliseconds since session start, used as this side's heartbeat timestamp (wraps like u32).</summary>
    public uint SessionClockMs(ActiveSession s) => unchecked((uint)(long)ToMs(Now - s.StartTimestamp));

    public StartResult TryStart(string deviceId, string deviceName, IPAddress remoteIp, byte[] kC2S, byte[] kS2C, uint sessionId, Action<string> closeTcp, out ActiveSession? session)
    {
        ActiveSession? replaced = null;
        lock (_lock)
        {
            if (_current != null)
            {
                if (_current.DeviceId != deviceId) { session = null; return StartResult.Busy; }
                replaced = _current; // same device reconnecting: the old session loses control immediately
            }
            session = new ActiveSession(sessionId, deviceId, deviceName, remoteIp, kC2S, kS2C, Now, closeTcp) { StartedUtc = _time.GetUtcNow() };
            _current = session;
            stats.Reset();
        }
        if (replaced != null)
        {
            gamepad.Neutralize();
            replaced.CloseTcp("replaced");
            SessionEnded?.Invoke(new SessionEndedEventArgs(replaced, "replaced"));
        }
        _log.LogInformation("Session {Session:X8} started for {Device}", sessionId, deviceName);
        SessionStarted?.Invoke(session);
        return StartResult.Started;
    }

    public void End(ActiveSession s, string reason)
    {
        lock (_lock)
        {
            if (!ReferenceEquals(_current, s)) return;
            _current = null;
        }
        gamepad.Neutralize();
        _log.LogInformation("Session {Session:X8} ended: {Reason}", s.Id, reason);
        try { s.CloseTcp(reason); } catch (Exception ex) { _log.LogDebug(ex, "closing control connection"); }
        SessionEnded?.Invoke(new SessionEndedEventArgs(s, reason));
    }

    public void EndCurrent(string reason)
    {
        var s = Current;
        if (s != null) End(s, reason);
    }

    /// <summary>Entry point for every datagram received on the input port (validation order: PROTOCOL.md section 4).</summary>
    public void OnDatagram(ReadOnlySpan<byte> data, IPEndPoint source)
    {
        stats.OnReceived();
        ActiveSession? s;
        lock (_lock) s = _current;
        if (s == null) { stats.OnNoSession(); return; }

        var err = PacketCodec.TryDecode(data, s.Id, s.KeyC2S, out var pkt);
        if (err != PacketError.None) { stats.OnInvalid(err); return; }
        // Source IP must match the control connection's peer (checked after the tag so garbage cannot probe the address).
        if (!source.Address.MapToIPv6().Equals(s.RemoteIp.MapToIPv6())) { stats.OnBadSource(); return; }

        GamepadState raw = default, mapped = default;
        bool applied = false;
        lock (_lock)
        {
            if (!ReferenceEquals(_current, s)) return;
            switch (s.Rx.Accept(pkt.Sequence))
            {
                case SequenceResult.Duplicate: stats.OnDuplicate(); return;
                case SequenceResult.OutOfOrder: stats.OnOutOfOrder(); return;
            }
            long now = Now;
            s.LastRxTimestamp = now;
            s.PhoneEndpoint = source;
            s.LastPeerTs = pkt.Timestamp;
            s.LastPeerTsTimestamp = now;
            s.Neutralized = false;
            stats.OnAccepted(pkt.Type == ProtocolConstants.TypeState);

            if (pkt.Type == ProtocolConstants.TypeState)
            {
                s.LastRaw = raw = pkt.State;
                mapped = ControllerMapper.Apply(raw, mapping());
                applied = true;
            }
            else if (pkt.Echo != ProtocolConstants.NoEcho)
            {
                uint rtt = unchecked(SessionClockMsAt(s, now) - pkt.Echo - pkt.EchoDelay);
                if (rtt < 10_000) s.RttMs = rtt; // discard impossible values (clock wrap/forgery) rather than show them
            }
        }
        if (applied)
        {
            gamepad.Submit(mapped);
            StateApplied?.Invoke(raw, mapped);
        }
    }

    private uint SessionClockMsAt(ActiveSession s, long ts) => unchecked((uint)(long)ToMs(ts - s.StartTimestamp));

    /// <summary>Periodic check (called every ~50 ms): neutralize after 500 ms of silence, end after 5 s.</summary>
    public void Tick()
    {
        ActiveSession? s; bool neutralize = false, terminate = false;
        lock (_lock)
        {
            s = _current;
            if (s == null) return;
            var idle = TimeSpan.FromMilliseconds(ToMs(Now - s.LastRxTimestamp));
            if (idle >= ProtocolConstants.TerminateAfter) terminate = true;
            else if (idle >= ProtocolConstants.NeutralizeAfter && !s.Neutralized) { s.Neutralized = true; neutralize = true; }
        }
        if (terminate) { End(s!, "timeout"); return; }
        if (neutralize)
        {
            gamepad.Neutralize();
            _log.LogWarning("Input stale for {Ms} ms; controller neutralized", ProtocolConstants.NeutralizeAfter.TotalMilliseconds);
            InputStale?.Invoke();
        }
    }

    /// <summary>Builds the next outgoing heartbeat for the active session, or null if the phone's UDP endpoint is not known yet.</summary>
    public (byte[] Packet, IPEndPoint Target)? BuildHeartbeat()
    {
        lock (_lock)
        {
            var s = _current;
            if (s?.PhoneEndpoint == null) return null;
            long now = Now;
            uint echo = s.LastPeerTs;
            ushort delay = 0;
            if (echo != ProtocolConstants.NoEcho)
                delay = (ushort)Math.Min(ushort.MaxValue, (long)ToMs(now - s.LastPeerTsTimestamp));
            uint seq = ++s.TxSeq;
            return (PacketCodec.EncodeHeartbeat(s.Id, seq, SessionClockMsAt(s, now), echo, delay, s.KeyS2C), s.PhoneEndpoint);
        }
    }
}
