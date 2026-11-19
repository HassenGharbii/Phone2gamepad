using System.IO;
using System.Net;
using System.Threading;
using System.Windows;
using System.Windows.Forms;
using Microsoft.Extensions.Logging;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.VirtualGamepad;
using Phone2Gamepad.Windows.Services;
using Phone2Gamepad.Windows.ViewModels;
using Phone2Gamepad.Windows.Views;
using Application = System.Windows.Application;
using MessageBox = System.Windows.MessageBox;

namespace Phone2Gamepad.Windows;

/// <summary>
/// Composition root. Command line (support/testing): --minimized, and for isolated test instances
/// --settings &lt;file&gt; --store &lt;file&gt; --instance &lt;name&gt; (separate settings/pairing data and single-instance lock),
/// --mock-gamepad (no driver) and --simulate-missing-driver (shows the driver-missing experience).
/// </summary>
public partial class App : Application
{
    private Mutex? _singleInstance;
    private LogService? _log;
    private ServerHost? _host;
    private TrayService? _tray;
    private MainWindow? _window;
    private MainViewModel? _vm;
    private ThemeService? _theme;
    private bool _exiting;

    private static string? Arg(string[] args, string name) { int i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        var args = e.Args;

        var instance = Arg(args, "--instance");
        _singleInstance = new Mutex(true, @"Local\Phone2Gamepad.SingleInstance" + (instance == null ? "" : "." + instance), out bool first);
        if (!first)
        {
            MessageBox.Show("Phone2Gamepad is already running. Look for its icon in the system tray.", "Phone2Gamepad");
            Shutdown();
            return;
        }

        DispatcherUnhandledException += (_, ex) =>
        {
            _log?.CreateLogger("app").LogError(ex.Exception, "Unhandled UI exception");
            ex.Handled = true;
        };

        var settingsPath = Arg(args, "--settings");
        _log = new LogService(Path.Combine(settingsPath != null ? Path.GetDirectoryName(Path.GetFullPath(settingsPath))! : AppSettings.Directory_, "logs", "app.log"));
        var logger = _log.CreateLogger("app");
        var settings = AppSettings.Load(settingsPath, warn: m => logger.LogWarning("{Message}", m));
        _log.MinimumLevel = Enum.TryParse<LogLevel>(settings.LogLevel, out var lvl) ? lvl : LogLevel.Information;

        _theme = new ThemeService(Resources);
        _theme.Apply(settings.Theme);

        IVirtualGamepad pad = args.Contains("--simulate-missing-driver") ? new MissingDriverGamepad()
            : args.Contains("--mock-gamepad") ? new MockVirtualGamepad()
            : new ViGEmVirtualGamepad(_log.CreateLogger("driver"));
        var store = new DpapiPairedDeviceStore(Arg(args, "--store") ?? DpapiPairedDeviceStore.DefaultPath(), _log.CreateLogger("pairing"));
        _host = new ServerHost(new ServerOptions
        {
            ServerName = settings.ServerName, ControlPort = settings.ControlPort, InputPort = settings.InputPort,
            DiscoveryPort = settings.DiscoveryPort, BindAddress = IPAddress.Any, ServerId = ServerIdentity.LoadOrCreate(),
        }, pad, store, _log.CreateLogger("server"));

        _vm = new MainViewModel(_host, settings, _log, logger)
        {
            AskPairing = ConfirmPairingAsync,
            Confirm = r => DialogWindow.Ask(_window, r),
            ApplyTheme = t => _theme.Apply(t),
        };
        _window = new MainWindow(_vm, settings) { HideOnClose = () => settings.MinimizeToTrayOnClose && !_exiting };
        _theme.ApplyTitleBar(_window);
        using (var icon = IconFactory.Create(64, System.Drawing.Color.FromArgb(0x60, 0xCD, 0xFF), TrayBadge.None)) _window.Icon = IconFactory.ToImageSource(icon);
        // When "keep running in the tray" is off, closing the window must really quit (otherwise an invisible process keeps the controller plugged in).
        _window.Closed += (_, _) => { if (!_exiting) ExitApp(); };

        _tray = new TrayService(ShowWindow, () => Dispatcher.Invoke(() => _vm.ToggleServer()), () => Dispatcher.Invoke(() => { ShowWindow(); _vm.OpenPairing(); }), ExitApp);
        _tray.SetDarkMenu(_theme.Effective != EffectiveTheme.Light);
        _theme.Changed += () => _tray.SetDarkMenu(_theme.Effective != EffectiveTheme.Light);
        void UpdateTray() => _tray.Update(_host.IsRunning, _vm.TrayStatusLine, _vm.TrayBadge);
        _vm.ServerRunningChanged += UpdateTray;
        _vm.StatusChanged += UpdateTray;
        _vm.PhoneConnected += name => NotifyIfBackground(settings, "Phone connected", $"{name} is now your controller.");
        _vm.PhoneDisconnected += (name, reason) => NotifyIfBackground(settings, "Phone disconnected", $"{name} disconnected. All controls were released.");

        // Test aid: emulate larger text scaling (e.g. 1.2 ≈ 150% on a 125% display) without touching display settings.
        if (double.TryParse(Arg(args, "--ui-scale"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var uiScale) && uiScale is > 0.5 and < 3
            && _window.Content is FrameworkElement root)
            root.LayoutTransform = new System.Windows.Media.ScaleTransform(uiScale, uiScale);

        bool minimized = args.Contains("--minimized");
        if (!minimized) _window.Show();
        else _tray.Notify("Phone2Gamepad", "Running in the system tray.");

        if (settings.StartServerOnLaunch) _vm.ToggleServer();
        UpdateTray();
    }

    private void NotifyIfBackground(AppSettings settings, string title, string text)
    {
        if (!settings.NotifyOnPhoneConnect || _window is { IsVisible: true, IsActive: true, WindowState: not WindowState.Minimized }) return;
        _tray?.Notify(title, text);
    }

    private void ShowWindow()
    {
        Dispatcher.Invoke(() =>
        {
            if (_window == null) return;
            _window.Show();
            if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
            _window.Activate();
        });
    }

    /// <summary>Asks the PC user to approve a phone whose PIN was already verified. Denied on Esc/close, or if the phone gives up.</summary>
    private Task<bool> ConfirmPairingAsync(PairingRequestInfo req, CancellationToken ct)
    {
        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        Dispatcher.BeginInvoke(() =>
        {
            bool background = _window is not { IsVisible: true, IsActive: true };
            if (background) _tray?.Notify("Phone wants to pair", $"\"{req.DeviceName}\" entered the PIN. Approve or deny it in Phone2Gamepad.", ToolTipIcon.Warning);
            var prompt = new PairingPromptWindow(req.DeviceName, req.RemoteIp.ToString()) { Icon = _window?.Icon };
            if (_window is { IsVisible: true }) { prompt.Owner = _window; prompt.WindowStartupLocation = WindowStartupLocation.CenterOwner; }
            _theme?.ApplyTitleBar(prompt);
            using var reg = ct.Register(() => Dispatcher.BeginInvoke(prompt.Abandon));
            bool ok = prompt.ShowDialog() == true && !ct.IsCancellationRequested;
            _log?.CreateLogger("pairing").LogInformation("Pairing {Decision} on this PC ({Via})", ok ? "approved" : "declined", prompt.DecidedVia);
            tcs.TrySetResult(ok);
        });
        return tcs.Task;
    }

    private void ExitApp()
    {
        Dispatcher.Invoke(() =>
        {
            if (_exiting) return;
            _exiting = true;
            _window?.SavePlacement();
            try { _host?.Dispose(); }          // neutralizes the controller, ends the session, unplugs the virtual pad
            catch (Exception ex) { _log?.CreateLogger("app").LogError(ex, "Error during shutdown"); }
            _tray?.Dispose();
            _theme?.Dispose();
            if (_window != null) { _window.ForceClose = true; try { _window.Close(); } catch (InvalidOperationException) { /* already closed */ } }
            _log?.Dispose();
            _singleInstance?.ReleaseMutex();
            Shutdown();
        });
    }

    protected override void OnSessionEnding(SessionEndingCancelEventArgs e)
    {
        ExitApp(); // Windows sign-out / shutdown: never leave the virtual controller plugged in
        base.OnSessionEnding(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { _host?.Dispose(); } catch { }
        base.OnExit(e);
    }

    /// <summary>Test aid: a driver that is never installed, to show the driver-missing experience without touching the real driver.</summary>
    private sealed class MissingDriverGamepad : IVirtualGamepad
    {
        public VirtualGamepadStatus Status { get; } = new(VirtualGamepadStatusKind.DriverMissing, "ViGEmBus driver not found (simulated).");
        public event Action<VirtualGamepadStatus>? StatusChanged { add { } remove { } }
        public bool Connect() => false;
        public void Submit(in GamepadState state) { }
        public void Neutralize() { }
        public void Disconnect() { }
        public void Dispose() { }
    }
}
