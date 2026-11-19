using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace Phone2Gamepad.Network;

/// <summary>
/// Scrubs text for the diagnostics export. Secrets (PINs, keys, long hex/base64 blobs, "secret=..." pairs) are ALWAYS removed.
/// With <c>maskNetworkDetails</c> (the default) IP addresses, GUID-style device ids, known device ids/names and user names in paths are masked too.
/// </summary>
public static partial class Redactor
{
    [GeneratedRegex(@"(?i)(\bpin\b\W{0,12})\d{3}\s?\d{3}(?!\d)")] private static partial Regex PinRx();
    [GeneratedRegex(@"(?i)\b(secret|key|password|token|credential|proof|mac|nonce)\b(\s*[:=]\s*)\S+")] private static partial Regex KeyValueRx();
    [GeneratedRegex(@"(?<![0-9A-Fa-f])[0-9A-Fa-f]{32,}(?![0-9A-Fa-f])")] private static partial Regex HexBlobRx();
    [GeneratedRegex(@"(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{40,}={0,2}")] private static partial Regex Base64BlobRx();
    [GeneratedRegex(@"(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\d.]*\d)")] private static partial Regex Ipv4Rx();
    [GeneratedRegex(@"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])|(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){1,6}(?::[0-9A-Fa-f]{1,4}){1,6}(?![0-9A-Fa-f:])|(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){1,7}:(?![0-9A-Fa-f:])|(?<![0-9A-Fa-f:]):(?::[0-9A-Fa-f]{1,4}){1,7}(?![0-9A-Fa-f:])")] private static partial Regex Ipv6Rx();
    [GeneratedRegex(@"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")] private static partial Regex GuidRx();
    [GeneratedRegex(@"(?i)([A-Z]:\\Users\\)[^\\/\r\n]+")] private static partial Regex UserPathRx();

    /// <param name="knownSecrets">Exact strings to remove everywhere (e.g. the active PIN). Always applied.</param>
    /// <param name="knownIdentifiers">Exact identifiers (device ids, server id) to mask when <paramref name="maskNetworkDetails"/> is set.</param>
    public static string Redact(string text, bool maskNetworkDetails = true, IEnumerable<string?>? knownSecrets = null, IEnumerable<string?>? knownIdentifiers = null)
    {
        if (string.IsNullOrEmpty(text)) return text ?? "";
        foreach (var s in (knownSecrets ?? []).Where(x => !string.IsNullOrEmpty(x)).OrderByDescending(x => x!.Length))
            text = text.Replace(s!, "<redacted>", StringComparison.Ordinal);

        text = PinRx().Replace(text, "$1<redacted>");
        text = KeyValueRx().Replace(text, "$1$2<redacted>");
        text = HexBlobRx().Replace(text, "<redacted>");
        text = Base64BlobRx().Replace(text, "<redacted>");

        if (maskNetworkDetails)
        {
            foreach (var id in (knownIdentifiers ?? []).Where(x => !string.IsNullOrEmpty(x)).OrderByDescending(x => x!.Length))
                text = text.Replace(id!, "<device>", StringComparison.OrdinalIgnoreCase);
            text = GuidRx().Replace(text, "<device>");
            text = Ipv6Rx().Replace(text, "<ipv6>");
            text = Ipv4Rx().Replace(text, "x.x.x.x");
            text = UserPathRx().Replace(text, "$1<user>");
        }
        return text;
    }
}

public sealed record DiagnosticsOptions(bool MaskNetworkDetails = true, int MaxLogLines = 300);

/// <summary>Builds the plain-text diagnostics report. Contains no PINs, secrets or keys; network details are masked unless the user opts in.</summary>
public static class DiagnosticsReport
{
    public static string AppVersion() =>
        (Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0)).ToString(3);

    public static string Build(ServerHost host, string appVersion, string logLevel, string activeProfile, IEnumerable<string> logLines, DiagnosticsOptions? options = null)
    {
        options ??= new();
        var s = host.Stats;
        var cur = host.Sessions.Current;
        var sb = new StringBuilder();
        sb.AppendLine("Phone2Gamepad diagnostics");
        sb.AppendLine($"Generated (UTC): {DateTime.UtcNow:yyyy-MM-dd HH:mm:ss}");
        sb.AppendLine($"App version: {appVersion}");
        sb.AppendLine($"OS: {RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture})");
        sb.AppendLine($"Runtime: {RuntimeInformation.FrameworkDescription}");
        sb.AppendLine($"Network details masked: {(options.MaskNetworkDetails ? "yes" : "no")}");
        sb.AppendLine();
        sb.AppendLine("== Driver ==");
        sb.AppendLine($"Status: {host.Gamepad.Status.Kind}");
        sb.AppendLine($"Message: {host.Gamepad.Status.Message}");
        sb.AppendLine();
        sb.AppendLine("== Server ==");
        sb.AppendLine($"Running: {host.IsRunning}");
        sb.AppendLine($"Ports: control {host.ControlPort}, input {host.InputPort}, discovery {host.DiscoveryPort?.ToString() ?? "off"}");
        sb.AppendLine($"Pairing window open: {host.Pairing.IsOpen}");
        sb.AppendLine($"Paired devices: {host.Devices.List().Count}");
        sb.AppendLine($"Active session: {(cur == null ? "none" : "yes")}");
        sb.AppendLine($"Round-trip latency: {(cur?.RttMs is double r ? $"{r:F0} ms" : "---")}");
        sb.AppendLine($"Packets/s: {(host.PacketsPerSecond is double p ? p.ToString("F0") : "---")}");
        sb.AppendLine($"Log level: {logLevel}; active mapping profile: {activeProfile}");
        sb.AppendLine();
        sb.AppendLine("== Packet counters ==");
        sb.AppendLine($"Received {s.Received}, accepted {s.Accepted} (state {s.StatePackets})");
        sb.AppendLine($"Invalid {s.InvalidTotal} (no session {s.NoSession}, bad source {s.BadSource}), duplicates {s.Duplicates}, out of order {s.OutOfOrder}, dropped total {s.DroppedTotal}");
        sb.AppendLine();
        sb.AppendLine($"== Recent log (last {options.MaxLogLines}) ==");
        foreach (var l in logLines.TakeLast(options.MaxLogLines)) sb.AppendLine(l);

        var secrets = new List<string?> { host.Pairing.Pin };
        var ids = new List<string?> { cur?.DeviceId };
        foreach (var d in host.Devices.List()) { ids.Add(d.DeviceId); ids.Add(d.DeviceName); }
        ids.Add(cur?.DeviceName);
        return Redactor.Redact(sb.ToString(), options.MaskNetworkDetails, secrets, ids);
    }

    /// <summary>Writes the report as UTF-8 text.</summary>
    public static void WriteTo(string path, string report)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        File.WriteAllText(path, report, new UTF8Encoding(false));
    }
}
