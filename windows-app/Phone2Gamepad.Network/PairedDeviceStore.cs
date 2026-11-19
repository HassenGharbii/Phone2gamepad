using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;

namespace Phone2Gamepad.Network;

public sealed record PairedDevice(string DeviceId, string DeviceName, byte[] Secret, DateTimeOffset PairedAtUtc);

public interface IPairedDeviceStore
{
    IReadOnlyList<PairedDevice> List();
    PairedDevice? Get(string deviceId);
    void Save(PairedDevice device);
    bool Remove(string deviceId);
    event Action? Changed;
}

public sealed class InMemoryPairedDeviceStore : IPairedDeviceStore
{
    private readonly Dictionary<string, PairedDevice> _d = new();
    public event Action? Changed;
    public IReadOnlyList<PairedDevice> List() { lock (_d) return _d.Values.ToList(); }
    public PairedDevice? Get(string id) { lock (_d) return _d.GetValueOrDefault(id); }
    public void Save(PairedDevice d) { lock (_d) _d[d.DeviceId] = d; Changed?.Invoke(); }
    public bool Remove(string id) { bool r; lock (_d) r = _d.Remove(id); if (r) Changed?.Invoke(); return r; }
}

/// <summary>
/// Persists paired devices as JSON; each secret is protected with DPAPI (CurrentUser scope), so the file is useless
/// to other Windows accounts or machines. Corrupt or newer-schema files are set aside rather than crashing.
/// </summary>
public sealed class DpapiPairedDeviceStore : IPairedDeviceStore
{
    private const int SchemaVersion = 1;
    private static readonly byte[] Entropy = "Phone2Gamepad paired device v1"u8.ToArray();
    private readonly string _path;
    private readonly ILogger? _log;
    private readonly object _lock = new();
    private readonly Dictionary<string, PairedDevice> _d = new();

    public event Action? Changed;

    public DpapiPairedDeviceStore(string path, ILogger? log = null)
    {
        _path = path; _log = log;
        Load();
    }

    public static string DefaultPath() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Phone2Gamepad", "paired-devices.json");

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var root = JsonNode.Parse(File.ReadAllText(_path)) as JsonObject ?? throw new JsonException("root");
            int ver = root["schema"]?.GetValue<int>() ?? 0;
            if (ver != SchemaVersion) throw new JsonException($"unsupported schema {ver}");
            foreach (var n in root["devices"]!.AsArray())
            {
                var o = n!.AsObject();
                var secret = ProtectedData.Unprotect(Convert.FromBase64String(o["secret"]!.GetValue<string>()), Entropy, DataProtectionScope.CurrentUser);
                var dev = new PairedDevice(o["deviceId"]!.GetValue<string>(), o["deviceName"]!.GetValue<string>(), secret,
                    DateTimeOffset.Parse(o["pairedAt"]!.GetValue<string>()));
                _d[dev.DeviceId] = dev;
            }
        }
        catch (Exception ex) when (ex is JsonException or FormatException or CryptographicException or InvalidOperationException or NullReferenceException or IOException)
        {
            _log?.LogError(ex, "Paired-device store unreadable; moving it aside and starting empty (devices must be re-paired)");
            _d.Clear();
            try { File.Move(_path, _path + ".corrupt-" + DateTime.UtcNow.ToString("yyyyMMddHHmmss")); } catch (IOException) { }
        }
    }

    private void Persist()
    {
        var arr = new JsonArray();
        foreach (var d in _d.Values)
            arr.Add(new JsonObject
            {
                ["deviceId"] = d.DeviceId, ["deviceName"] = d.DeviceName, ["pairedAt"] = d.PairedAtUtc.ToString("O"),
                ["secret"] = Convert.ToBase64String(ProtectedData.Protect(d.Secret, Entropy, DataProtectionScope.CurrentUser)),
            });
        var root = new JsonObject { ["schema"] = SchemaVersion, ["devices"] = arr };
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var tmp = _path + ".tmp";
        File.WriteAllText(tmp, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        File.Move(tmp, _path, overwrite: true);
    }

    public IReadOnlyList<PairedDevice> List() { lock (_lock) return _d.Values.ToList(); }
    public PairedDevice? Get(string id) { lock (_lock) return _d.GetValueOrDefault(id); }
    public void Save(PairedDevice d) { lock (_lock) { _d[d.DeviceId] = d; Persist(); } Changed?.Invoke(); }
    public bool Remove(string id)
    {
        bool r;
        lock (_lock) { r = _d.Remove(id); if (r) Persist(); }
        if (r) Changed?.Invoke();
        return r;
    }
}
