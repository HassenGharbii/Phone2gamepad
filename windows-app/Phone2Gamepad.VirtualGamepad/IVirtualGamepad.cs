using Phone2Gamepad.Core;

namespace Phone2Gamepad.VirtualGamepad;

public enum VirtualGamepadStatusKind { Disconnected, Connected, DriverMissing, Error }

public readonly record struct VirtualGamepadStatus(VirtualGamepadStatusKind Kind, string Message)
{
    public bool IsConnected => Kind == VirtualGamepadStatusKind.Connected;
}

/// <summary>
/// Abstraction over a virtual Xbox-compatible controller driver. Networking and UI depend only on this interface.
/// Values passed to <see cref="Submit"/> are already mapped; the implementation converts to the driver's format.
/// Implementations must be thread-safe.
/// </summary>
public interface IVirtualGamepad : IDisposable
{
    VirtualGamepadStatus Status { get; }
    event Action<VirtualGamepadStatus>? StatusChanged;

    /// <summary>Plugs the virtual controller in. Never throws for driver problems: check <see cref="Status"/>.</summary>
    bool Connect();

    /// <summary>Submits a complete controller state. No-op if not connected or state unchanged.</summary>
    void Submit(in GamepadState state);

    /// <summary>Releases every control (all buttons up, sticks centred, triggers 0).</summary>
    void Neutralize();

    /// <summary>Unplugs the virtual controller.</summary>
    void Disconnect();
}
