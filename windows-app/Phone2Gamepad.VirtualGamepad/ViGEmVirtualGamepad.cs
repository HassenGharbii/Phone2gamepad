using Microsoft.Extensions.Logging;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.VirtualGamepad;

/// <summary>
/// Xbox 360 / XInput virtual controller via ViGEmBus (retired upstream, last release 1.22.0 - see docs/DRIVER_DECISION.md).
/// The driver must already be installed; this class never installs anything.
/// </summary>
public sealed class ViGEmVirtualGamepad : IVirtualGamepad
{
    private readonly ILogger? _log;
    private readonly object _lock = new();
    private ViGEmClient? _client;
    private IXbox360Controller? _pad;
    private GamepadState _last;
    private bool _haveLast;
    private VirtualGamepadStatus _status = new(VirtualGamepadStatusKind.Disconnected, "Virtual controller not connected.");

    public ViGEmVirtualGamepad(ILogger? log = null) => _log = log;

    public VirtualGamepadStatus Status { get { lock (_lock) return _status; } }
    public event Action<VirtualGamepadStatus>? StatusChanged;

    /// <summary>XInput user index (0-3) assigned to the virtual pad, if connected and reported by the driver.</summary>
    public int? UserIndex
    {
        get { lock (_lock) { try { return _pad is { } p ? (int)p.UserIndex : null; } catch { return null; } } }
    }

    public bool Connect()
    {
        lock (_lock)
        {
            if (_pad != null) return true;
            try
            {
                _client = new ViGEmClient();
                // The first plug-in after the driver has been idle was observed to fail once with a Win32Exception; a retry succeeds.
                for (int attempt = 1; ; attempt++)
                {
                    try
                    {
                        _pad = _client.CreateXbox360Controller();
                        _pad.AutoSubmitReport = false;
                        _pad.Connect();
                        break;
                    }
                    catch (System.ComponentModel.Win32Exception ex) when (attempt < 3)
                    {
                        _log?.LogWarning(ex, "ViGEm plug-in attempt {Attempt} failed; retrying", attempt);
                        _pad = null;
                        Thread.Sleep(250);
                    }
                }
                // A freshly plugged-in pad reports leftover non-zero stick values (observed (-3356,-1869) / (-3255,-848)) until the first
                // report is submitted, which a game would read as stick drift. Send an explicit neutral report right away.
                WaitUntilEnumerated();
                // An all-zero first report is ignored (it equals the driver's idea of the current report), so nudge one axis by 1 and
                // then send the real neutral report; this overwrites the leftover values.
                _haveLast = false;
                Submit(new GamepadState(GamepadButtons.None, DpadFlags.None, 1, 0, 0, 0, 0, 0));
                Neutralize2();
                Publish(new(VirtualGamepadStatusKind.Connected, "Virtual Xbox 360 controller connected via ViGEmBus."));
                _log?.LogInformation("Virtual Xbox 360 controller connected");
                return true;
            }
            catch (Nefarius.ViGEm.Client.Exceptions.VigemBusNotFoundException)
            {
                Cleanup();
                Publish(new(VirtualGamepadStatusKind.DriverMissing,
                    "ViGEmBus driver not found. Install ViGEmBus (v1.22.0 from github.com/nefarius/ViGEmBus/releases) and restart the app. See docs/DRIVER_DECISION.md."));
                _log?.LogError("ViGEmBus driver not found");
                return false;
            }
            catch (Exception ex)
            {
                Cleanup();
                Publish(new(VirtualGamepadStatusKind.Error, $"Could not create virtual controller: {ex.Message}"));
                _log?.LogError(ex, "ViGEm connect failed");
                return false;
            }
        }
    }

    /// <summary>Reports sent before Windows has finished enumerating the new pad are dropped, so wait (bounded) until the driver reports a user index.</summary>
    private void WaitUntilEnumerated()
    {
        var deadline = Environment.TickCount64 + 2000;
        while (Environment.TickCount64 < deadline)
        {
            try { _ = _pad!.UserIndex; return; }
            catch (Exception) { Thread.Sleep(50); }
        }
        _log?.LogWarning("Virtual controller was not enumerated within 2 s; continuing anyway");
    }

    public void Submit(in GamepadState s)
    {
        lock (_lock)
        {
            if (_pad == null) return;
            if (_haveLast && s == _last) return;
            try
            {
                var p = _pad;
                p.SetButtonState(Xbox360Button.A, Has(s.Buttons, GamepadButtons.A));
                p.SetButtonState(Xbox360Button.B, Has(s.Buttons, GamepadButtons.B));
                p.SetButtonState(Xbox360Button.X, Has(s.Buttons, GamepadButtons.X));
                p.SetButtonState(Xbox360Button.Y, Has(s.Buttons, GamepadButtons.Y));
                p.SetButtonState(Xbox360Button.LeftShoulder, Has(s.Buttons, GamepadButtons.LeftBumper));
                p.SetButtonState(Xbox360Button.RightShoulder, Has(s.Buttons, GamepadButtons.RightBumper));
                p.SetButtonState(Xbox360Button.Back, Has(s.Buttons, GamepadButtons.Back));
                p.SetButtonState(Xbox360Button.Start, Has(s.Buttons, GamepadButtons.Start));
                p.SetButtonState(Xbox360Button.LeftThumb, Has(s.Buttons, GamepadButtons.LeftStick));
                p.SetButtonState(Xbox360Button.RightThumb, Has(s.Buttons, GamepadButtons.RightStick));
                p.SetButtonState(Xbox360Button.Guide, Has(s.Buttons, GamepadButtons.Guide));
                p.SetButtonState(Xbox360Button.Up, (s.Dpad & DpadFlags.Up) != 0);
                p.SetButtonState(Xbox360Button.Down, (s.Dpad & DpadFlags.Down) != 0);
                p.SetButtonState(Xbox360Button.Left, (s.Dpad & DpadFlags.Left) != 0);
                p.SetButtonState(Xbox360Button.Right, (s.Dpad & DpadFlags.Right) != 0);
                // Wire range and XInput range are both signed 16-bit with positive Y = up, so no conversion is needed.
                p.SetAxisValue(Xbox360Axis.LeftThumbX, s.LeftX);
                p.SetAxisValue(Xbox360Axis.LeftThumbY, s.LeftY);
                p.SetAxisValue(Xbox360Axis.RightThumbX, s.RightX);
                p.SetAxisValue(Xbox360Axis.RightThumbY, s.RightY);
                p.SetSliderValue(Xbox360Slider.LeftTrigger, s.LeftTrigger);
                p.SetSliderValue(Xbox360Slider.RightTrigger, s.RightTrigger);
                p.SubmitReport();
                _last = s; _haveLast = true;
            }
            catch (Exception ex)
            {
                _log?.LogError(ex, "ViGEm submit failed");
                Publish(new(VirtualGamepadStatusKind.Error, $"Driver error while updating controller: {ex.Message}"));
            }
        }
    }

    private static bool Has(GamepadButtons v, GamepadButtons f) => (v & f) != 0;

    public void Neutralize()
    {
        lock (_lock)
        {
            if (_pad == null) return;
            _haveLast = false; // force the write even if the cached state is neutral
        }
        Submit(GamepadState.Neutral);
    }

    public void Disconnect()
    {
        lock (_lock)
        {
            if (_pad == null) return;
            try { Neutralize2(); _pad.Disconnect(); } catch (Exception ex) { _log?.LogWarning(ex, "ViGEm disconnect"); }
            Cleanup();
            Publish(new(VirtualGamepadStatusKind.Disconnected, "Virtual controller disconnected."));
        }
    }

    private void Neutralize2()
    {
        // Called with the lock held (Monitor is re-entrant).
        _haveLast = false;
        Submit(GamepadState.Neutral);
    }

    private void Cleanup()
    {
        try { _client?.Dispose(); } catch { }
        _pad = null; _client = null;
    }

    private void Publish(VirtualGamepadStatus s)
    {
        _status = s;
        StatusChanged?.Invoke(s);
    }

    public void Dispose() => Disconnect();
}
