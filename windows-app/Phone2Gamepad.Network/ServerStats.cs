using System.Collections.Concurrent;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Network;

/// <summary>Thread-safe packet counters (no fabricated values: rate is computed from real samples).</summary>
public sealed class ServerStats
{
    private long _received, _accepted, _duplicates, _outOfOrder, _noSession, _badSource, _stateAccepted;
    private readonly ConcurrentDictionary<PacketError, long> _invalid = new();
    private long _lastSampleReceived;
    private long _lastSampleTicks;

    public long Received => Interlocked.Read(ref _received);
    public long Accepted => Interlocked.Read(ref _accepted);
    public long StatePackets => Interlocked.Read(ref _stateAccepted);
    public long Duplicates => Interlocked.Read(ref _duplicates);
    public long OutOfOrder => Interlocked.Read(ref _outOfOrder);
    public long NoSession => Interlocked.Read(ref _noSession);
    public long BadSource => Interlocked.Read(ref _badSource);
    public long Invalid(PacketError e) => _invalid.TryGetValue(e, out var v) ? v : 0;
    public long InvalidTotal => _invalid.Values.Sum() + NoSession + BadSource;
    /// <summary>Everything that was received but not applied: invalid + duplicate + out-of-order.</summary>
    public long DroppedTotal => InvalidTotal + Duplicates + OutOfOrder;

    internal void OnReceived() => Interlocked.Increment(ref _received);
    internal void OnAccepted(bool state) { Interlocked.Increment(ref _accepted); if (state) Interlocked.Increment(ref _stateAccepted); }
    internal void OnInvalid(PacketError e) => _invalid.AddOrUpdate(e, 1, (_, v) => v + 1);
    internal void OnDuplicate() => Interlocked.Increment(ref _duplicates);
    internal void OnOutOfOrder() => Interlocked.Increment(ref _outOfOrder);
    internal void OnNoSession() => Interlocked.Increment(ref _noSession);
    internal void OnBadSource() => Interlocked.Increment(ref _badSource);

    /// <summary>Packets received per second since the previous call (null on the first call).</summary>
    public double? SampleRate(long nowTicks, long ticksPerSecond)
    {
        long rx = Received;
        double? rate = null;
        if (_lastSampleTicks != 0 && nowTicks > _lastSampleTicks)
            rate = (rx - _lastSampleReceived) * (double)ticksPerSecond / (nowTicks - _lastSampleTicks);
        _lastSampleReceived = rx; _lastSampleTicks = nowTicks;
        return rate;
    }

    public void Reset()
    {
        _received = _accepted = _duplicates = _outOfOrder = _noSession = _badSource = _stateAccepted = 0;
        _invalid.Clear(); _lastSampleReceived = 0; _lastSampleTicks = 0;
    }
}
