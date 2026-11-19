using System.ComponentModel;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Data;
using System.Windows.Input;
using Microsoft.Extensions.Logging;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.Windows.Services;

namespace Phone2Gamepad.Windows.ViewModels;

public sealed partial class MainViewModel
{
    private bool _includeNetworkDetails;
    private string _logFilter = "All";
    private string? _diagnosticsNotice;

    public ICollectionView LogView { get; private set; } = null!;
    public string[] LogFilters { get; } = { "All", "Warnings and errors", "Errors only" };
    public ICommand ExportDiagnosticsCommand { get; private set; } = null!;
    public ICommand CopyLogCommand { get; private set; } = null!;
    public ICommand OpenLogFolderCommand { get; private set; } = null!;

    private void InitDiagnostics()
    {
        LogView = CollectionViewSource.GetDefaultView(_log.Entries);
        LogView.Filter = o => o is LogEntry e && _logFilter switch
        {
            "Warnings and errors" => e.Level >= Microsoft.Extensions.Logging.LogLevel.Warning,
            "Errors only" => e.Level >= Microsoft.Extensions.Logging.LogLevel.Error,
            _ => true,
        };
        ExportDiagnosticsCommand = new RelayCommand(ExportDiagnostics);
        CopyLogCommand = new RelayCommand(CopyLog);
        OpenLogFolderCommand = new RelayCommand(() => OpenFolder(Path.Combine(AppSettings.Directory_, "logs")));
    }

    public string LogFilter
    {
        get => _logFilter;
        set { if (value != null && Set(ref _logFilter, value)) LogView.Refresh(); }
    }

    public bool IncludeNetworkDetails { get => _includeNetworkDetails; set => Set(ref _includeNetworkDetails, value); }
    public string? DiagnosticsNotice { get => _diagnosticsNotice; private set => Set(ref _diagnosticsNotice, value); }

    public string StatePacketsText => _host.IsRunning ? Core.Presentation.UiFormat.Count(_host.Stats.StatePackets) : Core.Presentation.UiFormat.Unavailable;
    public string DriverKindText => _host.Gamepad.Status.Kind.ToString();

    private void RefreshDiagnosticsCounters()
    {
        OnPropertyChanged(nameof(StatePacketsText));
        OnPropertyChanged(nameof(DriverKindText));
    }

    public string BuildDiagnostics() =>
        DiagnosticsReport.Build(_host, Version, _settings.LogLevel, _settings.ActiveProfile, _log.Snapshot().Select(l => l.Text),
            new DiagnosticsOptions(MaskNetworkDetails: !IncludeNetworkDetails));

    private void ExportDiagnostics()
    {
        var dlg = new Microsoft.Win32.SaveFileDialog { FileName = $"Phone2Gamepad-diagnostics-{DateTime.Now:yyyyMMdd-HHmm}.txt", Filter = "Text (*.txt)|*.txt" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            DiagnosticsReport.WriteTo(dlg.FileName, BuildDiagnostics());
            _logger.LogInformation("Diagnostics exported");
            DiagnosticsNotice = $"Saved {Path.GetFileName(dlg.FileName)}";
        }
        catch (IOException ex) { Error = $"Export failed: {ex.Message}"; }
    }

    /// <summary>Copies the visible log lines. Log lines never contain PINs or keys (see SECURITY.md).</summary>
    private void CopyLog()
    {
        var sb = new StringBuilder();
        foreach (var o in LogView) if (o is LogEntry e) sb.AppendLine(e.Text);
        if (sb.Length == 0) return;
        try { Clipboard.SetText(sb.ToString()); DiagnosticsNotice = "Log copied to the clipboard"; }
        catch (System.Runtime.InteropServices.COMException) { DiagnosticsNotice = "Couldn't copy (clipboard busy)"; }
    }
}
