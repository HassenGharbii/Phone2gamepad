using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using Microsoft.Extensions.Logging;

namespace Phone2Gamepad.Windows.Services;

public sealed record LogEntry(DateTime Time, LogLevel Level, string Category, string Message)
{
    public string Text => $"{Time:HH:mm:ss.fff} {Level.ToString()[..4].ToUpperInvariant()} {Message}";
    public string TimeText => Time.ToString("HH:mm:ss");
    public string LevelText => Level switch { LogLevel.Warning => "Warning", LogLevel.Error or LogLevel.Critical => "Error", LogLevel.Debug or LogLevel.Trace => "Debug", _ => "Info" };
}

/// <summary>
/// In-memory ring buffer (for the Log tab / diagnostics export) plus a rolling text file. Level is switchable at runtime.
/// Messages come from our own code, which never logs PINs, secrets or keys.
/// </summary>
public sealed class LogService : ILoggerProvider
{
    private const int Capacity = 500;
    private readonly object _lock = new();
    private readonly Queue<LogEntry> _ring = new();
    private readonly string? _filePath;
    private StreamWriter? _file;

    public LogService(string? filePath)
    {
        _filePath = filePath;
        try
        {
            if (filePath != null)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(filePath)!);
                if (File.Exists(filePath) && new FileInfo(filePath).Length > 2_000_000) File.Move(filePath, filePath + ".old", true);
                _file = new StreamWriter(new FileStream(filePath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite)) { AutoFlush = true };
            }
        }
        catch (IOException) { _file = null; }
    }

    public LogLevel MinimumLevel { get; set; } = LogLevel.Information;
    public ObservableCollection<LogEntry> Entries { get; } = new();

    public ILogger CreateLogger(string categoryName) => new Logger(this, categoryName);

    private void Add(LogEntry e)
    {
        lock (_lock)
        {
            _ring.Enqueue(e);
            while (_ring.Count > Capacity) _ring.Dequeue();
            try { _file?.WriteLine(e.Text); } catch (IOException) { }
        }
        Application.Current?.Dispatcher.BeginInvoke(() =>
        {
            Entries.Add(e);
            while (Entries.Count > Capacity) Entries.RemoveAt(0);
        });
    }

    public IReadOnlyList<LogEntry> Snapshot() { lock (_lock) return _ring.ToList(); }

    public void Dispose() { lock (_lock) { _file?.Dispose(); _file = null; } }

    private sealed class Logger(LogService svc, string cat) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel l) => l >= svc.MinimumLevel && l != LogLevel.None;
        public void Log<TState>(LogLevel level, EventId id, TState state, Exception? ex, Func<TState, Exception?, string> fmt)
        {
            if (!IsEnabled(level)) return;
            var msg = fmt(state, ex);
            if (ex != null) msg += $" ({ex.GetType().Name}: {ex.Message})";
            svc.Add(new LogEntry(DateTime.Now, level, cat, msg));
        }
    }
}
