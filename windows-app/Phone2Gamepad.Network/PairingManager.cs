using System.Net;
using System.Security.Cryptography;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Network;

/// <summary>
/// Owns the short-lived pairing window (6-digit PIN, 120 s, single use, max 5 wrong proofs) and per-IP failure
/// rate limiting (1 failed attempt per second). The PIN is only exposed to the UI; it is never logged.
/// </summary>
public sealed class PairingManager(TimeProvider? time = null)
{
    private readonly TimeProvider _time = time ?? TimeProvider.System;
    private readonly object _lock = new();
    private string? _pin;
    private DateTimeOffset _expires;
    private int _failures;
    private readonly Dictionary<IPAddress, DateTimeOffset> _lastFailure = new();

    public event Action? Changed;

    public bool IsOpen { get { lock (_lock) return IsOpenLocked(); } }
    public string? Pin { get { lock (_lock) return IsOpenLocked() ? _pin : null; } }
    public DateTimeOffset? ExpiresAt { get { lock (_lock) return IsOpenLocked() ? _expires : null; } }
    public int FailuresInWindow { get { lock (_lock) return _failures; } }

    private bool IsOpenLocked() => _pin != null && _time.GetUtcNow() < _expires && _failures < ProtocolConstants.MaxPinFailuresPerWindow;

    /// <summary>Opens a new pairing window with a fresh PIN (replacing any existing one) and returns the PIN for display.</summary>
    public string Open()
    {
        string pin = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
        lock (_lock) { _pin = pin; _expires = _time.GetUtcNow() + ProtocolConstants.PinLifetime; _failures = 0; }
        Changed?.Invoke();
        return pin;
    }

    public void Close()
    {
        lock (_lock) { _pin = null; _failures = 0; }
        Changed?.Invoke();
    }

    /// <summary>True when this IP made a failed attempt less than one second ago.</summary>
    public bool IsRateLimited(IPAddress ip)
    {
        lock (_lock)
            return _lastFailure.TryGetValue(ip, out var t) && _time.GetUtcNow() - t < TimeSpan.FromSeconds(1);
    }

    public void RegisterFailure(IPAddress ip, bool countsTowardWindow = true)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            _lastFailure[ip] = now;
            if (_lastFailure.Count > 256)
                foreach (var k in _lastFailure.Where(kv => now - kv.Value > TimeSpan.FromMinutes(1)).Select(kv => kv.Key).ToList()) _lastFailure.Remove(k);
            if (countsTowardWindow && _pin != null && ++_failures >= ProtocolConstants.MaxPinFailuresPerWindow) _pin = null;
        }
        Changed?.Invoke();
    }
}
