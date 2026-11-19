using System.Collections.ObjectModel;
using System.IO;
using System.Net;
using System.Text;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Microsoft.Extensions.Logging;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.VirtualGamepad;
using Phone2Gamepad.Windows.Services;

namespace Phone2Gamepad.Windows.ViewModels;

public sealed record PairedRow(string DeviceId, string Name, string PairedAt);

public sealed class ButtonMapRow(MainViewModel owner, GamepadButtons source) : ObservableObject
{
    private GamepadButtons _target = source;
    public GamepadButtons Source { get; } = source;
    public string SourceName => Source.ToString();
    public GamepadButtons Target { get => _target; set { if (Set(ref _target, value)) owner.ApplyMapping(); } }
}

public sealed class MainViewModel : ObservableObject, IDisposable
{
    public static readonly GamepadButtons[] AllButtons =
    {
        GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y, GamepadButtons.LeftBumper, GamepadButtons.RightBumper,
        GamepadButtons.Back, GamepadButtons.Start, GamepadButtons.LeftStick, GamepadButtons.RightStick, GamepadButtons.Guide,
    };

    private readonly ServerHost _host;
    private readonly AppSettings _settings;
    private readonly LogService _log;
    private readonly ILogger _logger;
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromMilliseconds(250) };
    private readonly DispatcherTimer _visual = new() { Interval = TimeSpan.FromMilliseconds(33) };
    private GamepadState _latestMapped;
    private string? _error;
    private bool _loadingProfile;

    /// <summary>Set by the shell: asks the user (modal) whether a pairing request may proceed.</summary>
    public Func<PairingRequestInfo, Task<bool>>? AskPairing { get; set; }
    /// <summary>Set by the shell: yes/no question used before enabling start-with-Windows.</summary>
    public Func<string, string, bool>? Confirm { get; set; }
    public event Action? ServerRunningChanged;

    public MainViewModel(ServerHost host, AppSettings settings, LogService log, ILogger logger)
    {
        _host = host; _settings = settings; _log = log; _logger = logger;
        _host.ConfirmPairing = (req, _) => Application.Current.Dispatcher.InvokeAsync(() => AskPairing?.Invoke(req) ?? Task.FromResult(false)).Task.Unwrap();
        _host.Sessions.StateApplied += (_, mapped) => _latestMapped = mapped;
        _host.Sessions.SessionEnded += _ => _latestMapped = default;
        _host.Sessions.InputStale += () => _latestMapped = default;
        _host.Devices.Changed += () => Application.Current.Dispatcher.BeginInvoke(ReloadDevices);
        _host.RunningChanged += () => Application.Current.Dispatcher.BeginInvoke(() => { OnPropertyChanged(string.Empty); ServerRunningChanged?.Invoke(); });

        ToggleServerCommand = new RelayCommand(ToggleServer);
        OpenPairingCommand = new RelayCommand(() => { _host.Pairing.Open(); _logger.LogInformation("Pairing window opened (120 s)"); }, () => _host.IsRunning);
        ClosePairingCommand = new RelayCommand(() => _host.Pairing.Close(), () => _host.Pairing.IsOpen);
        RevokeCommand = new RelayCommand(p => { if (p is PairedRow r) _host.Revoke(r.DeviceId); });
        RetryDriverCommand = new RelayCommand(() => { _host.Gamepad.Connect(); OnPropertyChanged(string.Empty); });
        ExportDiagnosticsCommand = new RelayCommand(ExportDiagnostics);
        SaveProfileCommand = new RelayCommand(SaveProfile);
        DeleteProfileCommand = new RelayCommand(DeleteProfile, () => SelectedProfile != "Default");
        RestoreDefaultsCommand = new RelayCommand(() => { LoadProfile(MappingProfile.CreateDefault()); SelectedProfile = "Default"; });

        foreach (var b in AllButtons) ButtonMap.Add(new ButtonMapRow(this, b));
        ProfileNames.Add("Default");
        foreach (var p in _settings.Profiles) ProfileNames.Add(p.Name);
        _selectedProfile = ProfileNames.Contains(_settings.ActiveProfile) ? _settings.ActiveProfile : "Default";
        LoadProfile(FindProfile(_selectedProfile));
        ReloadDevices();

        _refresh.Tick += (_, _) => OnPropertyChanged(string.Empty);
        _visual.Tick += (_, _) => LiveState = _host.Sessions.Current == null ? default : _latestMapped;
        _refresh.Start(); _visual.Start();
    }

    // ------------------------------------------------------------------ commands
    public ICommand ToggleServerCommand { get; }
    public ICommand OpenPairingCommand { get; }
    public ICommand ClosePairingCommand { get; }
    public ICommand RevokeCommand { get; }
    public ICommand RetryDriverCommand { get; }
    public ICommand ExportDiagnosticsCommand { get; }
    public ICommand SaveProfileCommand { get; }
    public ICommand DeleteProfileCommand { get; }
    public ICommand RestoreDefaultsCommand { get; }

    public void ToggleServer()
    {
        try
        {
            if (_host.IsRunning) _host.Stop(); else _host.Start();
            Error = null;
        }
        catch (Exception ex)
        {
            Error = $"Could not start the server: {ex.Message}. Another program may be using ports {_settings.ControlPort}/{_settings.InputPort}/{_settings.DiscoveryPort} - change them in Settings.";
            _logger.LogError(ex, "Server start failed");
        }
        OnPropertyChanged(string.Empty);
        ServerRunningChanged?.Invoke();
    }

    public bool IsServerRunning => _host.IsRunning;

    // ------------------------------------------------------------------ dashboard
    public string? Error { get => _error; set { if (Set(ref _error, value)) OnPropertyChanged(nameof(HasError)); } }
    public bool HasError => !string.IsNullOrEmpty(_error);
    public string ServerButtonText => _host.IsRunning ? "Stop server" : "Start server";
    public string ServerState => _host.IsRunning ? "Running" : "Stopped";

    public string ListenText => !_host.IsRunning ? "---" :
        string.Join(", ", NetUtil.LocalIPv4Addresses().Select(a => a.ToString()).DefaultIfEmpty("no private network address found"));
    public string PortsText => !_host.IsRunning ? "---" : $"TCP {_host.ControlPort} (pairing/session)   UDP {_host.InputPort} (input)   UDP {_host.DiscoveryPort?.ToString() ?? "off"} (discovery)";

    public bool PairingOpen => _host.Pairing.IsOpen;
    public string PinText => _host.Pairing.Pin is { } p ? $"{p[..3]} {p[3..]}" : "------";
    public string PairingStatus
    {
        get
        {
            if (!_host.IsRunning) return "Start the server first.";
            if (_host.Pairing.ExpiresAt is not { } e) return _host.Pairing.FailuresInWindow >= ProtocolConstants.MaxPinFailuresPerWindow
                ? "Closed: too many wrong PINs. Open pairing again." : "Closed. Click 'Open pairing' to show a PIN.";
            var left = e - DateTimeOffset.UtcNow;
            return $"Open - expires in {Math.Max(0, (int)left.TotalSeconds)} s. Enter this PIN on the phone, then confirm here.";
        }
    }

    private ActiveSession? Session => _host.Sessions.Current;
    public string PhoneName => Session?.DeviceName ?? "No phone connected";
    public bool Connected => Session != null;
    public string Transport => Session != null ? "Wi-Fi / LAN (UDP)" : "---";
    public string Uptime => Session == null ? "---" : (DateTimeOffset.UtcNow - Session.StartedUtc) is var t ? $"{(int)t.TotalHours:00}:{t.Minutes:00}:{t.Seconds:00}" : "---";
    public string Latency => Session?.RttMs is { } r ? $"{r:F0} ms" : "---";
    public string PacketRate => _host.PacketsPerSecond is { } r && Session != null ? $"{r:F0} /s" : "---";
    public string Received => _host.Stats.Received.ToString("N0");
    public string Invalid => _host.Stats.InvalidTotal.ToString("N0");
    public string Dropped => (_host.Stats.Duplicates + _host.Stats.OutOfOrder).ToString("N0");
    public string InputState => Session == null ? "---" : _host.Sessions.IsNeutralized ? "Neutral (no recent input)" : "Active";

    public string DriverStatus => _host.Gamepad.Status.Message;
    public bool DriverOk => _host.Gamepad.Status.IsConnected;
    public string DriverKind => _host.Gamepad.Status.Kind.ToString();
    public bool DriverNeedsHelp => _host.Gamepad.Status.Kind is VirtualGamepadStatusKind.DriverMissing or VirtualGamepadStatusKind.Error;
    public string DriverHelp => _host.Gamepad.Status.Kind == VirtualGamepadStatusKind.DriverMissing
        ? "Phone2Gamepad needs the ViGEmBus driver to create a virtual Xbox controller. It is not installed or not running. Download the signed installer from the ViGEmBus releases page on GitHub, run it yourself (administrator approval is required), then press Retry. The app never installs drivers on its own."
        : _host.Gamepad.Status.Kind == VirtualGamepadStatusKind.Error
            ? "The driver reported an error. Press Retry; if it persists, restart the PC or reinstall ViGEmBus. See the Log tab for details."
            : "";

    private GamepadState _liveState;
    public GamepadState LiveState { get => _liveState; private set => Set(ref _liveState, value); }

    // ------------------------------------------------------------------ devices
    public ObservableCollection<PairedRow> Devices { get; } = new();
    private void ReloadDevices()
    {
        Devices.Clear();
        foreach (var d in _host.Devices.List().OrderBy(d => d.PairedAtUtc))
            Devices.Add(new PairedRow(d.DeviceId, d.DeviceName, d.PairedAtUtc.LocalDateTime.ToString("g")));
    }

    // ------------------------------------------------------------------ mapping
    public ObservableCollection<ButtonMapRow> ButtonMap { get; } = new();
    public GamepadButtons[] TargetChoices => AllButtons;
    public ObservableCollection<string> ProfileNames { get; } = new();

    private bool _invLX, _invLY, _invRX, _invRY, _ltDigital, _rtDigital;
    private double _leftDead, _rightDead, _leftSens = 1, _rightSens = 1, _trigMin, _trigMax = 255;
    private string _selectedProfile = "Default", _profileName = "", _mappingMessage = "";

    public bool InvertLeftX { get => _invLX; set { if (Set(ref _invLX, value)) ApplyMapping(); } }
    public bool InvertLeftY { get => _invLY; set { if (Set(ref _invLY, value)) ApplyMapping(); } }
    public bool InvertRightX { get => _invRX; set { if (Set(ref _invRX, value)) ApplyMapping(); } }
    public bool InvertRightY { get => _invRY; set { if (Set(ref _invRY, value)) ApplyMapping(); } }
    public bool LeftTriggerDigital { get => _ltDigital; set { if (Set(ref _ltDigital, value)) ApplyMapping(); } }
    public bool RightTriggerDigital { get => _rtDigital; set { if (Set(ref _rtDigital, value)) ApplyMapping(); } }
    public double LeftDeadZone { get => _leftDead; set { if (Set(ref _leftDead, Math.Round(value, 2))) ApplyMapping(); } }
    public double RightDeadZone { get => _rightDead; set { if (Set(ref _rightDead, Math.Round(value, 2))) ApplyMapping(); } }
    public double LeftSensitivity { get => _leftSens; set { if (Set(ref _leftSens, Math.Round(value, 2))) ApplyMapping(); } }
    public double RightSensitivity { get => _rightSens; set { if (Set(ref _rightSens, Math.Round(value, 2))) ApplyMapping(); } }
    public double TriggerMin { get => _trigMin; set { if (Set(ref _trigMin, Math.Round(value))) ApplyMapping(); } }
    public double TriggerMax { get => _trigMax; set { if (Set(ref _trigMax, Math.Round(value))) ApplyMapping(); } }
    public string MappingMessage { get => _mappingMessage; private set => Set(ref _mappingMessage, value); }
    public string ProfileName { get => _profileName; set => Set(ref _profileName, value); }
    public string SelectedProfile
    {
        get => _selectedProfile;
        set
        {
            if (value == null || !Set(ref _selectedProfile, value)) return;
            LoadProfile(FindProfile(value));
            _settings.ActiveProfile = value; SaveSettings();
        }
    }

    private MappingProfile FindProfile(string name) => _settings.Profiles.FirstOrDefault(p => p.Name == name) ?? MappingProfile.CreateDefault();

    private void LoadProfile(MappingProfile p)
    {
        _loadingProfile = true;
        InvertLeftX = p.InvertLeftX; InvertLeftY = p.InvertLeftY; InvertRightX = p.InvertRightX; InvertRightY = p.InvertRightY;
        LeftDeadZone = p.LeftDeadZone; RightDeadZone = p.RightDeadZone; LeftSensitivity = p.LeftSensitivity; RightSensitivity = p.RightSensitivity;
        TriggerMin = p.TriggerMin; TriggerMax = p.TriggerMax; LeftTriggerDigital = p.LeftTriggerDigital; RightTriggerDigital = p.RightTriggerDigital;
        foreach (var row in ButtonMap) row.Target = p.ButtonMap.TryGetValue(row.Source, out var t) ? t : row.Source;
        _loadingProfile = false;
        ApplyMapping();
    }

    private MappingProfile BuildProfile(string name)
    {
        var p = new MappingProfile
        {
            Name = name, InvertLeftX = InvertLeftX, InvertLeftY = InvertLeftY, InvertRightX = InvertRightX, InvertRightY = InvertRightY,
            LeftDeadZone = LeftDeadZone, RightDeadZone = RightDeadZone, LeftSensitivity = LeftSensitivity, RightSensitivity = RightSensitivity,
            TriggerMin = (byte)Math.Clamp(TriggerMin, 0, 255), TriggerMax = (byte)Math.Clamp(TriggerMax, 0, 255),
            LeftTriggerDigital = LeftTriggerDigital, RightTriggerDigital = RightTriggerDigital,
        };
        foreach (var r in ButtonMap.Where(r => r.Target != r.Source)) p.ButtonMap[r.Source] = r.Target;
        return p;
    }

    /// <summary>Validates the editor state; applies it live only if valid (invalid combinations are explained, never applied).</summary>
    public void ApplyMapping()
    {
        if (_loadingProfile) return;
        var p = BuildProfile(SelectedProfile);
        var problems = p.Validate();
        if (problems.Count == 0) { _host.Mapping = p; MappingMessage = "Mapping applied."; }
        else MappingMessage = "Not applied: " + string.Join(" ", problems);
    }

    private void SaveProfile()
    {
        var name = ProfileName.Trim();
        if (name.Length == 0 || name.Equals("Default", StringComparison.OrdinalIgnoreCase)) { MappingMessage = "Enter a profile name other than 'Default'."; return; }
        var p = BuildProfile(name);
        var problems = p.Validate();
        if (problems.Count > 0) { MappingMessage = "Cannot save: " + string.Join(" ", problems); return; }
        _settings.Profiles.RemoveAll(x => x.Name == name);
        _settings.Profiles.Add(p);
        if (!ProfileNames.Contains(name)) ProfileNames.Add(name);
        _selectedProfile = name; OnPropertyChanged(nameof(SelectedProfile));
        _settings.ActiveProfile = name; SaveSettings();
        _host.Mapping = p;
        MappingMessage = $"Profile '{name}' saved.";
    }

    private void DeleteProfile()
    {
        var name = SelectedProfile;
        if (name == "Default") return;
        _settings.Profiles.RemoveAll(x => x.Name == name);
        ProfileNames.Remove(name);
        SelectedProfile = "Default";
    }

    // ------------------------------------------------------------------ settings
    public bool MinimizeToTrayOnClose { get => _settings.MinimizeToTrayOnClose; set { _settings.MinimizeToTrayOnClose = value; SaveSettings(); OnPropertyChanged(); } }
    public bool StartServerOnLaunch { get => _settings.StartServerOnLaunch; set { _settings.StartServerOnLaunch = value; SaveSettings(); OnPropertyChanged(); } }
    public bool StartWithWindows
    {
        get => _settings.StartWithWindows;
        set
        {
            if (value == _settings.StartWithWindows) return;
            if (value && Confirm?.Invoke("Start with Windows",
                    "Start Phone2Gamepad (minimized to the tray) every time you sign in to Windows?\n\nThis adds a per-user startup entry; you can turn it off here at any time.") != true)
            { OnPropertyChanged(); return; }
            try { StartupRegistration.Set(value); _settings.StartWithWindows = value; SaveSettings(); }
            catch (Exception ex) { Error = $"Could not change the startup setting: {ex.Message}"; _logger.LogError(ex, "Startup registration failed"); }
            OnPropertyChanged();
        }
    }
    public string[] LogLevels { get; } = { "Debug", "Information", "Warning", "Error" };
    public string LogLevel
    {
        get => _settings.LogLevel;
        set { _settings.LogLevel = value; if (Enum.TryParse<LogLevel>(value, out var l)) _log.MinimumLevel = l; SaveSettings(); OnPropertyChanged(); }
    }
    public string ServerName { get => _settings.ServerName; set { _settings.ServerName = value; SaveSettings(); OnPropertyChanged(); } }
    public int ControlPort { get => _settings.ControlPort; set { if (value is >= 1024 and <= 65535) { _settings.ControlPort = value; SaveSettings(); } OnPropertyChanged(); } }
    public int InputPort { get => _settings.InputPort; set { if (value is >= 1024 and <= 65535) { _settings.InputPort = value; SaveSettings(); } OnPropertyChanged(); } }
    public int DiscoveryPort { get => _settings.DiscoveryPort; set { if (value is >= 1024 and <= 65535) { _settings.DiscoveryPort = value; SaveSettings(); } OnPropertyChanged(); } }
    public string Version => typeof(MainViewModel).Assembly.GetName().Version?.ToString(3) ?? "0.1.0";
    public ObservableCollection<LogEntry> LogEntries => _log.Entries;

    private void SaveSettings()
    {
        try { _settings.Save(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { _logger.LogError(ex, "Settings save failed"); }
    }

    // ------------------------------------------------------------------ diagnostics (PINs/secrets always removed; IPs, device ids/names masked unless opted in)
    private bool _includeNetworkDetails;
    public bool IncludeNetworkDetails { get => _includeNetworkDetails; set => Set(ref _includeNetworkDetails, value); }

    public string BuildDiagnostics() =>
        DiagnosticsReport.Build(_host, Version, _settings.LogLevel, _settings.ActiveProfile, _log.Snapshot().Select(l => l.Text),
            new DiagnosticsOptions(MaskNetworkDetails: !IncludeNetworkDetails));

    private void ExportDiagnostics()
    {
        var dlg = new Microsoft.Win32.SaveFileDialog { FileName = $"Phone2Gamepad-diagnostics-{DateTime.Now:yyyyMMdd-HHmm}.txt", Filter = "Text (*.txt)|*.txt" };
        if (dlg.ShowDialog() != true) return;
        try { DiagnosticsReport.WriteTo(dlg.FileName, BuildDiagnostics()); _logger.LogInformation("Diagnostics exported"); }
        catch (IOException ex) { Error = $"Export failed: {ex.Message}"; }
    }

    public void Dispose() { _refresh.Stop(); _visual.Stop(); }
}
