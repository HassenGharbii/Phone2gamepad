using System.IO;
using System.Net;
using System.Threading;
using System.Windows;
using Microsoft.Extensions.Logging;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.VirtualGamepad;
using Phone2Gamepad.Windows.Services;
using Phone2Gamepad.Windows.ViewModels;

namespace Phone2Gamepad.Windows;

public partial class App : Application
{
    private Mutex? _singleInstance;
    private LogService? _log;
    private ServerHost? _host;
    private TrayService? _tray;
    private MainWindow? _window;
    private MainViewModel? _vm;
    private bool _exiting;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = ShutdownMode.OnExplicitShutdown;

        _singleInstance = new Mutex(true, @"Local\Phone2Gamepad.SingleInstance", out bool first);
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

        _log = new LogService(Path.Combine(AppSettings.Directory_, "logs", "app.log"));
        var logger = _log.CreateLogger("app");
        var settings = AppSettings.Load(warn: m => logger.LogWarning("{Message}", m));
        _log.MinimumLevel = Enum.TryParse<LogLevel>(settings.LogLevel, out var lvl) ? lvl : LogLevel.Information;

        var pad = new ViGEmVirtualGamepad(_log.CreateLogger("driver"));
        var store = new DpapiPairedDeviceStore(DpapiPairedDeviceStore.DefaultPath(), _log.CreateLogger("pairing"));
        _host = new ServerHost(new ServerOptions
        {
            ServerName = settings.ServerName, ControlPort = settings.ControlPort, InputPort = settings.InputPort,
            DiscoveryPort = settings.DiscoveryPort, BindAddress = IPAddress.Any, ServerId = ServerIdentity.LoadOrCreate(),
        }, pad, store, _log.CreateLogger("server"));

        _vm = new MainViewModel(_host, settings, _log, logger)
        {
            AskPairing = req => ConfirmPairingAsync(req, CancellationToken.None),
            Confirm = (title, text) => MessageBox.Show(_window, text, title, MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) == MessageBoxResult.Yes,
        };
        _window = new MainWindow(_vm) { HideOnClose = () => settings.MinimizeToTrayOnClose && !_exiting };
        // When "keep running in the tray" is off, closing the window must really quit (otherwise an invisible process keeps the controller plugged in).
        _window.Closed += (_, _) => { if (!_exiting) ExitApp(); };

        _tray = new TrayService(ShowWindow, () => Dispatcher.Invoke(() => { _vm.ToggleServer(); }), ExitApp);
        _vm.ServerRunningChanged += () => _tray.SetServerRunning(_host.IsRunning);

        bool minimized = e.Args.Contains("--minimized");
        if (!minimized) _window.Show();
        else _tray.Notify("Phone2Gamepad", "Running in the system tray.");

        if (settings.StartServerOnLaunch) _vm.ToggleServer();
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

    /// <summary>Asks the PC user to approve a phone whose PIN was already verified. Runs on the UI thread.</summary>
    private Task<bool> ConfirmPairingAsync(PairingRequestInfo req, CancellationToken ct)
    {
        var tcs = new TaskCompletionSource<bool>();
        Dispatcher.BeginInvoke(() =>
        {
            ShowWindow();
            var r = MessageBox.Show(_window,
                $"Allow \"{req.DeviceName}\" to control this PC as a game controller?\n\nIt entered the correct PIN. Only approve phones you recognise.",
                "Approve new phone", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No);
            tcs.TrySetResult(r == MessageBoxResult.Yes);
        });
        ct.Register(() => tcs.TrySetResult(false));
        return tcs.Task;
    }

    private void ExitApp()
    {
        Dispatcher.Invoke(() =>
        {
            if (_exiting) return;
            _exiting = true;
            try { _host?.Dispose(); }          // neutralizes the controller, ends the session, unplugs the virtual pad
            catch (Exception ex) { _log?.CreateLogger("app").LogError(ex, "Error during shutdown"); }
            _tray?.Dispose();
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
}
