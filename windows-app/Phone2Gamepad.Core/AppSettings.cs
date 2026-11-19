using System.Text.Json;
using System.Text.Json.Nodes;

namespace Phone2Gamepad.Core;

/// <summary>
/// User preferences and saved mapping profiles. Versioned (<see cref="SchemaVersion"/>) with a migration chain; unreadable or
/// newer-than-supported files are set aside (never overwritten) and defaults used; individually invalid profiles are dropped
/// without losing the rest of the settings.
/// </summary>
public sealed class AppSettings
{
    public const int CurrentSchema = 1;
    public static readonly string[] LogLevels = { "Debug", "Information", "Warning", "Error" };
    public static readonly string[] Themes = { "System", "Dark", "Light" };
    public const int MaxPhoneLabelLength = 40;

    public int SchemaVersion { get; set; } = CurrentSchema;
    public string ServerName { get; set; } = Environment.MachineName;
    public int ControlPort { get; set; } = ProtocolConstants.DefaultControlPort;
    public int InputPort { get; set; } = ProtocolConstants.DefaultInputPort;
    public int DiscoveryPort { get; set; } = ProtocolConstants.DefaultDiscoveryPort;
    public bool StartServerOnLaunch { get; set; } = true;
    public bool MinimizeToTrayOnClose { get; set; } = true;
    public bool StartWithWindows { get; set; } // off until the user enables it (and confirms)
    public string LogLevel { get; set; } = "Information";
    public string ActiveProfile { get; set; } = "Default";
    public List<MappingProfile> Profiles { get; set; } = new();

    // UI preferences (additive in schema 1: missing fields simply take these defaults)
    public string Theme { get; set; } = "System";
    public bool NotifyOnPhoneConnect { get; set; } = true;
    public WindowBounds? Window { get; set; }
    /// <summary>Local, user-chosen display names for paired phones, keyed by device id. Never sent anywhere.</summary>
    public Dictionary<string, string> PhoneLabels { get; set; } = new();
    /// <summary>When each paired phone last started a session on this PC (local only).</summary>
    public Dictionary<string, DateTimeOffset> PhoneLastSeen { get; set; } = new();

    /// <summary>File this instance was loaded from (and saves back to); null = <see cref="DefaultPath"/>.</summary>
    [System.Text.Json.Serialization.JsonIgnore] public string? FilePath { get; set; }

    public static string Directory_ => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Phone2Gamepad");
    public static string DefaultPath => Path.Combine(Directory_, "settings.json");

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    /// <summary>Loads settings; never throws for bad content. Problems and recoveries are reported through <paramref name="warn"/>.</summary>
    public static AppSettings Load(string? path = null, Action<string>? warn = null)
    {
        path ??= DefaultPath;
        var loaded = LoadCore(path, warn);
        loaded.FilePath = path == DefaultPath ? null : path;
        return loaded;
    }

    private static AppSettings LoadCore(string path, Action<string>? warn)
    {
        if (!File.Exists(path)) return new AppSettings();
        try
        {
            var root = JsonNode.Parse(File.ReadAllText(path)) as JsonObject ?? throw new JsonException("settings root is not an object");
            int version = root["SchemaVersion"] is JsonValue v && v.TryGetValue<int>(out var n) ? n : 0;
            if (version > CurrentSchema) throw new JsonException($"settings schema {version} is newer than this app understands ({CurrentSchema})");
            if (version < 0) throw new JsonException("invalid schema version");

            if (version < CurrentSchema)
            {
                warn?.Invoke($"Settings upgraded from schema {version} to {CurrentSchema}; a backup was kept.");
                try { File.Copy(path, $"{path}.v{version}.bak", overwrite: true); } catch (IOException) { }
                root = Migrate(root, version);
            }

            var profilesNode = root["Profiles"] as JsonArray;
            root.Remove("Profiles");
            var s = root.Deserialize<AppSettings>() ?? throw new JsonException("empty settings");
            s.Profiles = new();
            int dropped = 0;
            foreach (var pn in profilesNode ?? new JsonArray())
            {
                try
                {
                    var p = pn.Deserialize<MappingProfile>();
                    if (p != null && !string.IsNullOrWhiteSpace(p.Name) && p.Name != "Default" && p.Validate().Count == 0 && s.Profiles.All(x => x.Name != p.Name)) s.Profiles.Add(p);
                    else dropped++;
                }
                catch (JsonException) { dropped++; }
            }
            if (dropped > 0) warn?.Invoke($"{dropped} invalid mapping profile(s) were ignored.");
            return s.Sanitized(warn);
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException or NotSupportedException or InvalidOperationException)
        {
            warn?.Invoke($"Settings unreadable ({ex.Message}); using defaults. The old file was kept as a .corrupt copy.");
            try { File.Move(path, path + ".corrupt-" + DateTime.UtcNow.ToString("yyyyMMddHHmmssfff")); } catch (IOException) { }
            return new AppSettings();
        }
    }

    /// <summary>Upgrades a settings document one schema version at a time. Add a case here when <see cref="CurrentSchema"/> is raised.</summary>
    private static JsonObject Migrate(JsonObject root, int from)
    {
        for (int v = from; v < CurrentSchema; v++)
        {
            switch (v)
            {
                case 0: // files written before the schema field existed: same layout as v1
                    root["SchemaVersion"] = 1;
                    break;
                default: throw new JsonException($"no migration from schema {v}");
            }
        }
        return root;
    }

    private AppSettings Sanitized(Action<string>? warn)
    {
        static int Port(int p, int d) => p is >= 1024 and <= 65535 ? p : d;
        SchemaVersion = CurrentSchema;
        ControlPort = Port(ControlPort, ProtocolConstants.DefaultControlPort);
        InputPort = Port(InputPort, ProtocolConstants.DefaultInputPort);
        DiscoveryPort = Port(DiscoveryPort, ProtocolConstants.DefaultDiscoveryPort);
        if (new[] { ControlPort, InputPort, DiscoveryPort }.Distinct().Count() < 3)
        {
            warn?.Invoke("Ports must be distinct; default ports restored.");
            ControlPort = ProtocolConstants.DefaultControlPort; InputPort = ProtocolConstants.DefaultInputPort; DiscoveryPort = ProtocolConstants.DefaultDiscoveryPort;
        }
        if (string.IsNullOrWhiteSpace(ServerName) || ServerName.Length > 63) ServerName = Environment.MachineName;
        if (!LogLevels.Contains(LogLevel)) LogLevel = "Information";
        if (ActiveProfile != "Default" && Profiles.All(p => p.Name != ActiveProfile)) ActiveProfile = "Default";
        if (!Themes.Contains(Theme)) Theme = "System";
        PhoneLabels = (PhoneLabels ?? new())
            .Where(kv => !string.IsNullOrWhiteSpace(kv.Key) && !string.IsNullOrWhiteSpace(kv.Value))
            .Take(64)
            .ToDictionary(kv => kv.Key, kv => kv.Value.Trim().Length > MaxPhoneLabelLength ? kv.Value.Trim()[..MaxPhoneLabelLength] : kv.Value.Trim());
        PhoneLastSeen = (PhoneLastSeen ?? new()).Where(kv => !string.IsNullOrWhiteSpace(kv.Key)).Take(64).ToDictionary(kv => kv.Key, kv => kv.Value);
        if (Window is { } w && !(double.IsFinite(w.Left) && double.IsFinite(w.Top) && w.Width is > 0 and < 100000 && w.Height is > 0 and < 100000)) Window = null;
        return this;
    }

    public void Save(string? path = null)
    {
        path ??= FilePath ?? DefaultPath;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(this, Json));
        File.Move(tmp, path, overwrite: true);
    }
}

/// <summary>Restore bounds of the main window in device-independent pixels.</summary>
public sealed class WindowBounds
{
    public double Left { get; set; }
    public double Top { get; set; }
    public double Width { get; set; }
    public double Height { get; set; }
    public bool Maximized { get; set; }
}
