using System.Diagnostics;
using System.IO;
using System.Windows.Input;
using Microsoft.Extensions.Logging;
using Phone2Gamepad.Core;
using Phone2Gamepad.Windows.Services;

namespace Phone2Gamepad.Windows.ViewModels;

public sealed partial class MainViewModel
{
    private bool _restartNeeded;
    private string _portsMessage = "";

    /// <summary>Set by the shell to apply a theme preference immediately.</summary>
    public Action<string>? ApplyTheme { get; set; }

    public bool MinimizeToTrayOnClose { get => _settings.MinimizeToTrayOnClose; set { _settings.MinimizeToTrayOnClose = value; SaveSettings(); OnPropertyChanged(); } }
    public bool StartServerOnLaunch { get => _settings.StartServerOnLaunch; set { _settings.StartServerOnLaunch = value; SaveSettings(); OnPropertyChanged(); } }
    public bool NotifyOnPhoneConnect { get => _settings.NotifyOnPhoneConnect; set { _settings.NotifyOnPhoneConnect = value; SaveSettings(); OnPropertyChanged(); } }

    public bool StartWithWindows
    {
        get => _settings.StartWithWindows;
        set
        {
            if (value == _settings.StartWithWindows) return;
            if (value && Confirm?.Invoke(new DialogRequest("Start with Windows?",
                    "Phone2Gamepad will start minimized in the system tray each time you sign in to Windows. This adds a startup entry for your account only; you can turn it off here at any time.",
                    "Turn on")) != true)
            { OnPropertyChanged(); return; }
            try { StartupRegistration.Set(value); _settings.StartWithWindows = value; SaveSettings(); }
            catch (Exception ex) { Error = $"Couldn't change the startup setting: {ex.Message}"; _logger.LogError(ex, "Startup registration failed"); }
            OnPropertyChanged();
        }
    }

    public string[] ThemeOptions { get; } = { "System", "Dark", "Light" };
    public string Theme
    {
        get => _settings.Theme;
        set
        {
            if (value == null || !ThemeOptions.Contains(value) || value == _settings.Theme) return;
            _settings.Theme = value; SaveSettings(); OnPropertyChanged();
            ApplyTheme?.Invoke(value);
        }
    }

    public string[] LogLevels { get; } = AppSettings.LogLevels;
    public string LogLevel
    {
        get => _settings.LogLevel;
        set
        {
            if (value == null || !AppSettings.LogLevels.Contains(value)) return;
            _settings.LogLevel = value;
            if (Enum.TryParse<Microsoft.Extensions.Logging.LogLevel>(value, out var l)) _log.MinimumLevel = l;
            SaveSettings(); OnPropertyChanged();
        }
    }

    public string ServerName
    {
        get => _settings.ServerName;
        set
        {
            var v = (value ?? "").Trim();
            if (v.Length is 0 or > 63) { PortsMessage = "The PC name must be 1–63 characters."; OnPropertyChanged(); return; }
            if (v == _settings.ServerName) return;
            _settings.ServerName = v; SaveSettings(); OnPropertyChanged(); MarkRestart();
        }
    }

    public string ControlPortText { get => _settings.ControlPort.ToString(); set => SetPort(value, p => _settings.ControlPort = p, "Pairing (TCP)"); }
    public string InputPortText { get => _settings.InputPort.ToString(); set => SetPort(value, p => _settings.InputPort = p, "Input (UDP)"); }
    public string DiscoveryPortText { get => _settings.DiscoveryPort.ToString(); set => SetPort(value, p => _settings.DiscoveryPort = p, "Discovery (UDP)"); }
    public string PortsMessage { get => _portsMessage; private set { if (Set(ref _portsMessage, value)) OnPropertyChanged(nameof(HasPortsMessage)); } }
    public bool HasPortsMessage => !string.IsNullOrEmpty(_portsMessage);
    public bool RestartNeeded { get => _restartNeeded; private set => Set(ref _restartNeeded, value); }

    private void SetPort(string? text, Action<int> apply, string label)
    {
        if (!int.TryParse(text, out var p) || p is < 1024 or > 65535)
        { PortsMessage = $"{label}: enter a number from 1024 to 65535."; RefreshPortProps(); return; }
        var others = new[] { _settings.ControlPort, _settings.InputPort, _settings.DiscoveryPort };
        var current = label.StartsWith("Pairing") ? _settings.ControlPort : label.StartsWith("Input") ? _settings.InputPort : _settings.DiscoveryPort;
        if (p == current) { PortsMessage = ""; return; }
        if (others.Contains(p)) { PortsMessage = $"{label}: port {p} is already used by another Phone2Gamepad port."; RefreshPortProps(); return; }
        apply(p);
        PortsMessage = "";
        SaveSettings();
        RefreshPortProps();
        MarkRestart();
    }

    private void RefreshPortProps()
    {
        OnPropertyChanged(nameof(ControlPortText)); OnPropertyChanged(nameof(InputPortText)); OnPropertyChanged(nameof(DiscoveryPortText)); OnPropertyChanged(nameof(PortsText));
    }

    private void MarkRestart() { if (_host.IsRunning) RestartNeeded = true; }

    public string Version => typeof(MainViewModel).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
    public ICommand OpenDataFolderCommand => new RelayCommand(() => OpenFolder(AppSettings.Directory_));

    private void OpenFolder(string path)
    {
        try { Directory.CreateDirectory(path); Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or IOException or UnauthorizedAccessException)
        { _logger.LogWarning("Could not open folder: {Message}", ex.Message); }
    }
}
