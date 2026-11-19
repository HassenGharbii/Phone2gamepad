using Phone2Gamepad.Core;

namespace Phone2Gamepad.VirtualGamepad;

/// <summary>In-memory implementation for tests and for running without a driver.</summary>
public sealed class MockVirtualGamepad : IVirtualGamepad
{
    private readonly object _lock = new();
    private VirtualGamepadStatus _status = new(VirtualGamepadStatusKind.Disconnected, "Mock controller not connected.");

    public List<GamepadState> Submitted { get; } = new();
    public int NeutralizeCount { get; private set; }
    public GamepadState Current { get; private set; }

    public VirtualGamepadStatus Status { get { lock (_lock) return _status; } }
    public event Action<VirtualGamepadStatus>? StatusChanged;

    public bool Connect()
    {
        Set(new(VirtualGamepadStatusKind.Connected, "Mock controller connected."));
        return true;
    }

    public void Submit(in GamepadState state)
    {
        lock (_lock)
        {
            if (!_status.IsConnected || state == Current) return;
            Current = state;
            Submitted.Add(state);
        }
    }

    public void Neutralize()
    {
        lock (_lock) { Current = default; NeutralizeCount++; Submitted.Add(default); }
    }

    public void Disconnect() => Set(new(VirtualGamepadStatusKind.Disconnected, "Mock controller disconnected."));

    private void Set(VirtualGamepadStatus s)
    {
        lock (_lock) _status = s;
        StatusChanged?.Invoke(s);
    }

    public void Dispose() => Disconnect();
}
