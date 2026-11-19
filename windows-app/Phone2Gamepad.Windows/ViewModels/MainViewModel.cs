using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Microsoft.Extensions.Logging;
using Phone2Gamepad.Core;
using Phone2Gamepad.Core.Presentation;
using Phone2Gamepad.Network;
using Phone2Gamepad.VirtualGamepad;
using Phone2Gamepad.Windows.Services;

namespace Phone2Gamepad.Windows.ViewModels;

public sealed record NavItem(string Glyph, string Title, string Shortcut);

/// <summary>A styled yes/no question (replaces MessageBox). Destructive questions get a red primary button.</summary>
public sealed record DialogRequest(string Title, string Message, string PrimaryText, string SecondaryText = "Cancel", bool Destructive = false);

public sealed class AddressChip(string address, string adapter, bool primary)
{
    public string Address { get; } = address;
    public string Adapter { get; } = adapter;
    public bool IsPrimary { get; } = primary;
}

/// <summary>Shell view model: navigation, server control, home status, pairing, live controller and phones. See the partial files for mapping/settings/diagnostics.</summary>
public sealed partial class MainViewModel : ObservableObject, IDisposable
{
    public const string DriverReleasesUrl = "https://github.com/nefarius/ViGEmBus/releases";

    private readonly ServerHost _host;
    private readonly AppSettings _settings;
    private readonly LogService _log;
    private readonly ILogger _logger;
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromMilliseconds(250) };
    private readonly DispatcherTimer _visual = new() { Interval = TimeSpan.FromMilliseconds(33) };
    private GamepadState _latestMapped;
    private string? _error;
    private bool _everConnected;

    /// <summary>Set by the shell: asks the user whether a phone (PIN already verified) may pair. Cancelled if the phone gives up.</summary>
    public Func<PairingRequestInfo, CancellationToken, Task<bool>>? AskPairing { get; set; }
    /// <summary>Set by the shell: styled yes/no dialog.</summary>
    public Func<DialogRequest, bool>? Confirm { get; set; }
    public event Action? ServerRunningChanged;
    public event Action? StatusChanged;
    public event Action<string>? PhoneConnected;
    public event Action<string, string>? PhoneDisconnected;

    public MainViewModel(ServerHost host, AppSettings settings, LogService log, ILogger logger)
    {
        _host = host; _settings = settings; _log = log; _logger = logger;
        _everConnected = settings.PhoneLastSeen.Count > 0;
        _host.ConfirmPairing = (req, ct) => Application.Current.Dispatcher.InvokeAsync(() => AskPairing?.Invoke(req, ct) ?? Task.FromResult(false)).Task.Unwrap();
        _host.Sessions.StateApplied += (_, mapped) => _latestMapped = mapped;
        _host.Sessions.SessionStarted += s => Ui(() => OnSessionStarted(s));
        _host.Sessions.SessionEnded += e => { _latestMapped = default; Ui(() => OnSessionEnded(e)); };
        _host.Sessions.InputStale += () => _latestMapped = default;
        _host.Devices.Changed += () => Ui(ReloadDevices);
        _host.Pairing.Changed += () => Ui(RefreshLive);
        _host.RunningChanged += () => Ui(() => { RefreshAll(); ServerRunningChanged?.Invoke(); });

        NavigateCommand = new RelayCommand(p => { if (p is string s && int.TryParse(s, out var i)) SelectedPage = i; });
        ToggleServerCommand = new RelayCommand(ToggleServer);
        RestartServerCommand = new RelayCommand(RestartServer, () => _host.IsRunning);
        OpenPairingCommand = new RelayCommand(OpenPairing, () => _host.IsRunning);
        ClosePairingCommand = new RelayCommand(() => _host.Pairing.Close(), () => _host.Pairing.IsOpen);
        StatusActionCommand = new RelayCommand(RunStatusAction);
        RetryDriverCommand = new RelayCommand(() => { _host.Gamepad.Connect(); RefreshAll(); });
        OpenDriverHelpCommand = new RelayCommand(() => OpenUrl(DriverReleasesUrl));
        OpenJoyCplCommand = new RelayCommand(() => Shell("joy.cpl"));
        CopyCommand = new RelayCommand(p => { if (p is string s && s.Length > 0) CopyText(s); });
        DismissErrorCommand = new RelayCommand(() => Error = null);

        InitMapping();
        InitDiagnostics();
        ReloadDevices();
        ReloadAddresses();

        _refresh.Tick += (_, _) => RefreshLive();
        _visual.Tick += (_, _) => { IsLive = _host.Sessions.Current != null; LiveState = IsLive ? _latestMapped : default; };
        _refresh.Start(); _visual.Start();
    }

    private static void Ui(Action a) => Application.Current?.Dispatcher.BeginInvoke(a);

    // ================================================================== navigation
    public IReadOnlyList<NavItem> Pages { get; } = new NavItem[]
    {
        new("", "Home", "Ctrl+1"),
        new("", "Controller", "Ctrl+2"),
        new("", "Phones", "Ctrl+3"),
        new("", "Mapping", "Ctrl+4"),
        new("", "Diagnostics", "Ctrl+5"),
        new("", "Settings", "Ctrl+6"),
    };
    private int _selectedPage;
    public int SelectedPage { get => _selectedPage; set { if (value >= 0 && value < Pages.Count) Set(ref _selectedPage, value); } }
    public ICommand NavigateCommand { get; }

    // ================================================================== commands
    public ICommand ToggleServerCommand { get; }
    public ICommand RestartServerCommand { get; }
    public ICommand OpenPairingCommand { get; }
    public ICommand ClosePairingCommand { get; }
    public ICommand StatusActionCommand { get; }
    public ICommand RetryDriverCommand { get; }
    public ICommand OpenDriverHelpCommand { get; }
    public ICommand OpenJoyCplCommand { get; }
    public ICommand CopyCommand { get; }
    public ICommand DismissErrorCommand { get; }

    // ================================================================== server
    public void ToggleServer()
    {
        try
        {
            if (_host.IsRunning) _host.Stop(); else { _host.Start(); ReloadAddresses(); }
            Error = null;
        }
        catch (Exception ex)
        {
            Error = $"Another program may be using port {_settings.ControlPort}, {_settings.InputPort} or {_settings.DiscoveryPort}. Close it, or choose other ports in Settings. ({ex.Message})";
            _logger.LogError(ex, "Server start failed");
        }
        RefreshAll();
        ServerRunningChanged?.Invoke();
    }

    private void RestartServer()
    {
        if (_host.IsRunning) _host.Stop();
        ToggleServer();
        RestartNeeded = false;
    }

    public void OpenPairing()
    {
        if (!_host.IsRunning) ToggleServer();
        if (!_host.IsRunning) return;
        _host.Pairing.Open();
        _logger.LogInformation("Pairing window opened (120 s)");
        SelectedPage = 0;
        RefreshLive();
    }

    public bool IsServerRunning => _host.IsRunning;
    public string ServerStateText => _host.IsRunning ? "Server on" : "Server off";
    public string ServerToggleText => _host.IsRunning ? "Stop server" : "Start server";
    public string? Error { get => _error; set { if (Set(ref _error, value)) { OnPropertyChanged(nameof(HasError)); RefreshAll(); } } }
    public bool HasError => !string.IsNullOrEmpty(_error);

    // ================================================================== home status
    private HomeStatus _status = HomeStatusRules.Compute(new HomeFacts(false, false, false, null, null, false, false, 0));
    public HomeStatus Status => _status;
    public string StatusGlyph => _status.Tone switch
    {
        StatusTone.Success => "", StatusTone.Critical => "", StatusTone.Attention => "",
        _ => _host.IsRunning ? "" : "",
    };
    public string StatusToneKey => _status.Tone.ToString();
    public bool HasStatusAction => _status.Action != HomeAction.None;

    private void RunStatusAction()
    {
        switch (_status.Action)
        {
            case HomeAction.StartServer: if (!_host.IsRunning) ToggleServer(); else RestartServer(); break;
            case HomeAction.OpenPairing: OpenPairing(); break;
            case HomeAction.DriverHelp: OpenUrl(DriverReleasesUrl); break;
            case HomeAction.RetryDriver: _host.Gamepad.Connect(); RefreshAll(); break;
        }
    }

    private void ComputeStatus()
    {
        var kind = _host.Gamepad.Status.Kind;
        var s = Session;
        var next = HomeStatusRules.Compute(new HomeFacts(
            kind == VirtualGamepadStatusKind.DriverMissing, kind == VirtualGamepadStatusKind.Error, _host.IsRunning, _error,
            s == null ? null : DisplayName(s.DeviceId, s.DeviceName), _host.Sessions.IsNeutralized && s != null, _host.Pairing.IsOpen, Phones.Count));
        if (next != _status)
        {
            _status = next;
            OnPropertyChanged(nameof(Status)); OnPropertyChanged(nameof(StatusGlyph)); OnPropertyChanged(nameof(StatusToneKey)); OnPropertyChanged(nameof(HasStatusAction));
            StatusChanged?.Invoke();
        }
    }

    /// <summary>One-line status for the tray tooltip/menu.</summary>
    public string TrayStatusLine => _status.Title;
    public TrayBadge TrayBadge => _status.Tone switch
    {
        StatusTone.Success => TrayBadge.Playing, StatusTone.Critical => TrayBadge.Problem,
        _ => _host.IsRunning ? TrayBadge.Ready : TrayBadge.None,
    };

    // ================================================================== setup checklist
    public bool DriverReady => _host.Gamepad.Status.IsConnected;
    public bool DriverMissing => _host.Gamepad.Status.Kind == VirtualGamepadStatusKind.DriverMissing;
    public string DriverText => _host.Gamepad.Status.Kind switch
    {
        VirtualGamepadStatusKind.Connected => "Virtual Xbox 360 controller ready (ViGEmBus).",
        VirtualGamepadStatusKind.DriverMissing => "ViGEmBus isn't installed. Install it yourself, then choose Try again.",
        VirtualGamepadStatusKind.Error => "The driver reported an error: " + _host.Gamepad.Status.Message,
        _ => "Not connected yet.",
    };
    public bool PhonePaired => Phones.Count > 0;
    /// <summary>A phone has reached this PC at least once, which also proves the firewall lets it in.</summary>
    public bool NetworkVerified => _everConnected;
    public bool SetupComplete => DriverReady && _host.IsRunning && PhonePaired && NetworkVerified;

    // ================================================================== pairing
    public bool PairingOpen => _host.Pairing.IsOpen;
    public bool PairingClosed => !_host.Pairing.IsOpen;
    public string PinText => UiFormat.Pin(_host.Pairing.Pin);
    public string PinForScreenReader => _host.Pairing.Pin is { } p ? "PIN " + string.Join(" ", p.ToCharArray()) : "No PIN";
    private (int Seconds, double Fraction) Countdown => UiFormat.Countdown(_host.Pairing.ExpiresAt, DateTimeOffset.UtcNow, ProtocolConstants.PinLifetime);
    public double PinFraction => Countdown.Fraction;
    public string PinSecondsText => PairingOpen ? $"{Countdown.Seconds}s" : "";
    public string PairingHint
    {
        get
        {
            if (!_host.IsRunning) return "Start the server to pair a phone.";
            if (_host.Pairing.IsOpen) return "On your phone: open Phone2Gamepad, pick this PC and enter the PIN. You'll be asked to approve it here.";
            if (_host.Pairing.FailuresInWindow >= ProtocolConstants.MaxPinFailuresPerWindow) return "Pairing stopped after too many wrong PINs. Start again for a new PIN.";
            return "Show a one-time PIN, valid for 2 minutes.";
        }
    }

    // ================================================================== live controller + metrics
    private GamepadState _liveState;
    public GamepadState LiveState
    {
        get => _liveState;
        private set
        {
            if (!Set(ref _liveState, value)) return;
            OnPropertyChanged(nameof(LeftStickText)); OnPropertyChanged(nameof(RightStickText)); OnPropertyChanged(nameof(TriggersText));
            OnPropertyChanged(nameof(PressedText)); OnPropertyChanged(nameof(LiveSummary));
        }
    }
    public string LeftStickText => IsLive ? $"{LiveState.LeftX}, {LiveState.LeftY}" : UiFormat.Unavailable;
    public string RightStickText => IsLive ? $"{LiveState.RightX}, {LiveState.RightY}" : UiFormat.Unavailable;
    public string TriggersText => IsLive ? $"{LiveState.LeftTrigger}, {LiveState.RightTrigger}" : UiFormat.Unavailable;
    public string PressedText
    {
        get
        {
            if (!IsLive) return "No phone connected";
            var names = AllButtons.Where(b => (LiveState.Buttons & b) != 0).Select(UiFormat.ButtonShort).ToList();
            if ((LiveState.Dpad & DpadFlags.Up) != 0) names.Add("D-pad up");
            if ((LiveState.Dpad & DpadFlags.Down) != 0) names.Add("D-pad down");
            if ((LiveState.Dpad & DpadFlags.Left) != 0) names.Add("D-pad left");
            if ((LiveState.Dpad & DpadFlags.Right) != 0) names.Add("D-pad right");
            return names.Count == 0 ? "Nothing" : string.Join(" · ", names);
        }
    }
    private bool _isLive;
    public bool IsLive
    {
        get => _isLive;
        private set { if (Set(ref _isLive, value)) { OnPropertyChanged(nameof(LeftStickText)); OnPropertyChanged(nameof(RightStickText)); OnPropertyChanged(nameof(TriggersText)); OnPropertyChanged(nameof(PressedText)); OnPropertyChanged(nameof(LiveSummary)); } }
    }
    public string LiveSummary => !IsLive ? "Waiting for input from a phone" :
        $"Left ({LiveState.LeftX}, {LiveState.LeftY})   Right ({LiveState.RightX}, {LiveState.RightY})   LT {LiveState.LeftTrigger}   RT {LiveState.RightTrigger}";

    private ActiveSession? Session => _host.Sessions.Current;
    public bool Connected => Session != null;
    public string LatencyText => UiFormat.Latency(Session?.RttMs);
    public string RateText => Session == null ? UiFormat.Unavailable : UiFormat.Rate(_host.PacketsPerSecond);
    public string UptimeText => Session == null ? UiFormat.Unavailable : UiFormat.Duration(DateTimeOffset.UtcNow - Session.StartedUtc);
    public string ReceivedText => _host.IsRunning ? UiFormat.Count(_host.Stats.Received) : UiFormat.Unavailable;
    public string InvalidText => _host.IsRunning ? UiFormat.Count(_host.Stats.InvalidTotal) : UiFormat.Unavailable;
    public string DroppedText => _host.IsRunning ? UiFormat.Count(_host.Stats.Duplicates + _host.Stats.OutOfOrder) : UiFormat.Unavailable;
    public string InputStateText => Session == null ? "No phone connected" : _host.Sessions.IsNeutralized ? "Released (no recent input)" : "Receiving input";

    // ================================================================== network
    public ObservableCollection<AddressChip> OtherAddresses { get; } = new();
    private AddressChip? _primary;
    public AddressChip? PrimaryAddress { get => _primary; private set => Set(ref _primary, value); }
    public bool HasOtherAddresses => OtherAddresses.Count > 0;
    public string PortsText => $"TCP {_settings.ControlPort} · UDP {_settings.InputPort} · discovery UDP {_settings.DiscoveryPort}";

    private void ReloadAddresses()
    {
        var all = NetworkInfo.Addresses();
        var primary = AddressRanking.Primary(all);
        PrimaryAddress = primary == null ? null : new AddressChip(primary.Address.ToString(), primary.AdapterName, true);
        OtherAddresses.Clear();
        foreach (var a in all.Where(a => a != primary)) OtherAddresses.Add(new AddressChip(a.Address.ToString(), a.AdapterName, false));
        OnPropertyChanged(nameof(HasOtherAddresses));
    }

    private string? _copied;
    public string? CopiedNotice { get => _copied; private set => Set(ref _copied, value); }
    private void CopyText(string s)
    {
        try { Clipboard.SetText(s); CopiedNotice = $"Copied {s}"; }
        catch (System.Runtime.InteropServices.COMException) { CopiedNotice = "Couldn't copy (clipboard busy)"; }
        var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        t.Tick += (_, _) => { t.Stop(); CopiedNotice = null; };
        t.Start();
    }

    // ================================================================== phones
    public ObservableCollection<PhoneRow> Phones { get; } = new();
    public bool HasPhones => Phones.Count > 0;
    public bool NoPhones => Phones.Count == 0;

    public string DisplayName(string deviceId, string fallback) =>
        _settings.PhoneLabels.TryGetValue(deviceId, out var l) && !string.IsNullOrWhiteSpace(l) ? l : fallback;

    private void ReloadDevices()
    {
        Phones.Clear();
        foreach (var d in _host.Devices.List().OrderByDescending(d => _settings.PhoneLastSeen.GetValueOrDefault(d.DeviceId, d.PairedAtUtc)))
            Phones.Add(new PhoneRow(this, d));
        OnPropertyChanged(nameof(HasPhones)); OnPropertyChanged(nameof(NoPhones)); OnPropertyChanged(nameof(PhonePaired)); OnPropertyChanged(nameof(SetupComplete));
        ComputeStatus();
    }

    internal void RevokePhone(PhoneRow row)
    {
        if (Confirm?.Invoke(new DialogRequest("Remove this phone?",
                $"\"{row.DisplayName}\" will be disconnected and its pairing key deleted. To use it again you'll need to pair it with a new PIN.",
                "Remove", "Cancel", Destructive: true)) != true) return;
        _host.Revoke(row.DeviceId);
        _settings.PhoneLabels.Remove(row.DeviceId);
        _settings.PhoneLastSeen.Remove(row.DeviceId);
        SaveSettings();
        _logger.LogInformation("Paired phone removed by the user");
    }

    internal void RenamePhone(PhoneRow row, string label)
    {
        label = label.Trim();
        if (label.Length > AppSettings.MaxPhoneLabelLength) label = label[..AppSettings.MaxPhoneLabelLength];
        if (label.Length == 0 || label == row.OriginalName) _settings.PhoneLabels.Remove(row.DeviceId);
        else _settings.PhoneLabels[row.DeviceId] = label;
        SaveSettings();
        ReloadDevices();
    }

    internal bool IsConnected(string deviceId) => Session?.DeviceId == deviceId;
    internal DateTimeOffset? LastSeen(string deviceId) => _settings.PhoneLastSeen.TryGetValue(deviceId, out var t) ? t : null;

    private void OnSessionStarted(ActiveSession s)
    {
        _everConnected = true;
        _settings.PhoneLastSeen[s.DeviceId] = DateTimeOffset.UtcNow;
        SaveSettings();
        ReloadDevices();
        RefreshAll();
        PhoneConnected?.Invoke(DisplayName(s.DeviceId, s.DeviceName));
    }

    private void OnSessionEnded(SessionEndedEventArgs e)
    {
        _settings.PhoneLastSeen[e.Session.DeviceId] = DateTimeOffset.UtcNow;
        SaveSettings();
        ReloadDevices();
        RefreshAll();
        PhoneDisconnected?.Invoke(DisplayName(e.Session.DeviceId, e.Session.DeviceName), e.Reason);
    }

    // ================================================================== refresh
    private static readonly string[] LiveProps =
    {
        nameof(IsServerRunning), nameof(ServerStateText), nameof(ServerToggleText), nameof(PairingOpen), nameof(PairingClosed), nameof(PinText),
        nameof(PinForScreenReader), nameof(PinFraction), nameof(PinSecondsText), nameof(PairingHint), nameof(LiveSummary), nameof(Connected),
        nameof(LatencyText), nameof(RateText), nameof(UptimeText), nameof(ReceivedText), nameof(InvalidText), nameof(DroppedText), nameof(InputStateText),
        nameof(DriverReady), nameof(DriverMissing), nameof(DriverText), nameof(NetworkVerified), nameof(SetupComplete), nameof(PortsText),
    };

    private void RefreshLive()
    {
        foreach (var p in LiveProps) OnPropertyChanged(p);
        ComputeStatus();
        RefreshDiagnosticsCounters();
        foreach (var r in Phones) r.Refresh();
    }

    private void RefreshAll()
    {
        RefreshLive();
        CommandManager.InvalidateRequerySuggested();
    }

    // ================================================================== helpers
    private void OpenUrl(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException) { Error = $"Couldn't open the browser. Visit {url} manually."; }
    }

    private void Shell(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException) { _logger.LogWarning("Could not open {Target}: {Message}", target, ex.Message); }
    }

    internal void SaveSettings()
    {
        try { _settings.Save(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { _logger.LogError(ex, "Settings save failed"); }
    }

    public void Dispose() { _refresh.Stop(); _visual.Stop(); }
}

/// <summary>One paired phone in the Phones page.</summary>
public sealed class PhoneRow : ObservableObject
{
    private readonly MainViewModel _owner;
    private bool _editing;
    private string _editText = "";

    public PhoneRow(MainViewModel owner, PairedDevice d)
    {
        _owner = owner;
        DeviceId = d.DeviceId; OriginalName = d.DeviceName; PairedAt = d.PairedAtUtc;
        RevokeCommand = new RelayCommand(() => _owner.RevokePhone(this));
        EditCommand = new RelayCommand(() => { EditText = DisplayName; IsEditing = true; });
        SaveCommand = new RelayCommand(() => { IsEditing = false; _owner.RenamePhone(this, EditText); });
        CancelCommand = new RelayCommand(() => IsEditing = false);
    }

    public string DeviceId { get; }
    public string OriginalName { get; }
    public DateTimeOffset PairedAt { get; }
    public string DisplayName => _owner.DisplayName(DeviceId, OriginalName);
    public bool HasCustomName => DisplayName != OriginalName;
    public bool IsConnected => _owner.IsConnected(DeviceId);
    public string Subtitle =>
        (HasCustomName ? OriginalName + " · " : "") + $"Paired {PairedAt.ToLocalTime():d MMM yyyy}" +
        (IsConnected ? "" : $" · Last connected {UiFormat.RelativeTime(_owner.LastSeen(DeviceId), DateTimeOffset.UtcNow).ToLowerInvariant()}");
    public bool IsEditing { get => _editing; set => Set(ref _editing, value); }
    public string EditText { get => _editText; set => Set(ref _editText, value); }
    public ICommand RevokeCommand { get; }
    public ICommand EditCommand { get; }
    public ICommand SaveCommand { get; }
    public ICommand CancelCommand { get; }

    public void Refresh() { OnPropertyChanged(nameof(IsConnected)); OnPropertyChanged(nameof(Subtitle)); }
}
