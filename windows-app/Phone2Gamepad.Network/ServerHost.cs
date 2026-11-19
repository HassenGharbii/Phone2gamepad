using System.Net;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Phone2Gamepad.Core;
using Phone2Gamepad.VirtualGamepad;

namespace Phone2Gamepad.Network;

public sealed class ServerOptions
{
    public string ServerName { get; set; } = Environment.MachineName;
    /// <summary>Stable random id of this PC, sent in hello_ok (see <see cref="ServerIdentity"/>). Null = not sent.</summary>
    public string? ServerId { get; set; }
    public int DiscoveryPort { get; set; } = ProtocolConstants.DefaultDiscoveryPort;
    public int ControlPort { get; set; } = ProtocolConstants.DefaultControlPort;
    public int InputPort { get; set; } = ProtocolConstants.DefaultInputPort;
    public bool EnableDiscovery { get; set; } = true;
    /// <summary>Interface to listen on. Any = all interfaces, but see <see cref="PrivateNetworksOnly"/>.</summary>
    public IPAddress BindAddress { get; set; } = IPAddress.Any;
    /// <summary>Default true: peers outside loopback / RFC1918 / link-local ranges are refused, so the service is never usable from the Internet.</summary>
    public bool PrivateNetworksOnly { get; set; } = true;
}

/// <summary>Composition root for the server side: control, input and discovery channels + session manager + virtual pad.</summary>
public sealed class ServerHost : IDisposable
{
    private readonly ServerOptions _opt;
    private readonly ILogger _log;
    private readonly TimeProvider _time;
    private ControlServer? _control;
    private InputChannel? _input;
    private DiscoveryResponder? _discovery;
    private Timer? _statsTimer;

    public ServerHost(ServerOptions options, IVirtualGamepad gamepad, IPairedDeviceStore store, ILogger? log = null, TimeProvider? time = null)
    {
        _opt = options; Gamepad = gamepad; Devices = store; _log = log ?? NullLogger.Instance; _time = time ?? TimeProvider.System;
        Pairing = new PairingManager(_time);
        Sessions = new SessionManager(gamepad, () => Mapping, Stats, _log, _time);
    }

    public IVirtualGamepad Gamepad { get; }
    public IPairedDeviceStore Devices { get; }
    public PairingManager Pairing { get; }
    public SessionManager Sessions { get; }
    public ServerStats Stats { get; } = new();
    public MappingProfile Mapping { get; set; } = MappingProfile.CreateDefault();

    public bool IsRunning { get; private set; }
    public int ControlPort => _control?.Port ?? _opt.ControlPort;
    public int InputPort => _input?.Port ?? _opt.InputPort;
    public int? DiscoveryPort => _discovery?.Port;

    /// <summary>Packets/second received during the last sampling interval (null until two samples exist).</summary>
    public double? PacketsPerSecond { get; private set; }
    public event Action? StatsUpdated;
    public event Action? RunningChanged;

    public Func<PairingRequestInfo, CancellationToken, Task<bool>>? ConfirmPairing { get; set; }

    public void Start()
    {
        if (IsRunning) return;
        Gamepad.Connect(); // failure is reported via Gamepad.Status; the server still starts so the UI can explain it
        try
        {
            _input = new InputChannel(Sessions, _opt.BindAddress, _opt.InputPort, _log);
            _control = new ControlServer(_opt.BindAddress, _opt.ControlPort, _opt.ServerName, _opt.PrivateNetworksOnly, Devices, Pairing, Sessions,
                () => _input!.Port, () => Gamepad.Status.IsConnected, _log, _opt.ServerId)
            {
                ConfirmPairing = (req, ct) => ConfirmPairing?.Invoke(req, ct) ?? Task.FromResult(false),
            };
            if (_opt.EnableDiscovery)
                _discovery = new DiscoveryResponder(_opt.BindAddress, _opt.DiscoveryPort, _opt.ServerName, _control.Port, _input.Port,
                    () => Pairing.IsOpen, _opt.PrivateNetworksOnly, _log);
            _input.Start(); _control.Start(); _discovery?.Start();
        }
        catch
        {
            StopChannels();
            throw;
        }
        IsRunning = true;
        _statsTimer = new Timer(_ =>
        {
            PacketsPerSecond = Stats.SampleRate(_time.GetTimestamp(), _time.TimestampFrequency);
            StatsUpdated?.Invoke();
        }, null, 1000, 1000);
        _log.LogInformation("Server started: control {Control}, input {Input}, discovery {Discovery}", _control.Port, _input.Port, _discovery?.Port);
        RunningChanged?.Invoke();
    }

    public void Stop()
    {
        if (!IsRunning) return;
        IsRunning = false;
        _statsTimer?.Dispose(); _statsTimer = null;
        Pairing.Close();
        Sessions.EndCurrent("server stopped");
        StopChannels();
        Gamepad.Neutralize();
        Gamepad.Disconnect();
        PacketsPerSecond = null;
        _log.LogInformation("Server stopped");
        RunningChanged?.Invoke();
    }

    private void StopChannels()
    {
        _discovery?.Dispose(); _discovery = null;
        _control?.Dispose(); _control = null;
        _input?.Dispose(); _input = null;
    }

    /// <summary>Revokes a paired device: its secret is deleted and any active session of that device is ended.</summary>
    public bool Revoke(string deviceId)
    {
        bool removed = Devices.Remove(deviceId);
        if (Sessions.Current?.DeviceId == deviceId) Sessions.EndCurrent("revoked");
        if (removed) _log.LogInformation("Paired device revoked");
        return removed;
    }

    public void Dispose()
    {
        Stop();
        Gamepad.Dispose();
    }
}
