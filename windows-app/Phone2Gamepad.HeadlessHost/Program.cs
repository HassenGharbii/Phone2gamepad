using System.Net;
using Microsoft.Extensions.Logging;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.VirtualGamepad;

// Console host for the Phone2Gamepad server (no UI): useful for diagnostics and for end-to-end testing without the WPF app.
// Usage: HeadlessHost [--diagnostics <file> [--include-network-details]] [--mock] [--auto-accept] [--open-pairing] [--store <file>] [--control-port N] [--input-port N] [--no-discovery] [--run-seconds N]
//   --mock         use the in-memory gamepad instead of ViGEmBus
//   --auto-accept  accept every pairing request that presents the correct PIN (TEST ONLY; normally the user is asked)
//   --open-pairing open a pairing window at startup and print the PIN on stdout
// Stdin commands: "pair" (open a new pairing window), "status", "quit".

var a = args.ToList();
bool Flag(string n) => a.Contains(n);
string? Opt(string n) { int i = a.IndexOf(n); return i >= 0 && i + 1 < a.Count ? a[i + 1] : null; }

using var lf = LoggerFactory.Create(b => b.AddSimpleConsole(o => { o.TimestampFormat = "HH:mm:ss.fff "; o.SingleLine = true; }).SetMinimumLevel(LogLevel.Information));
var log = lf.CreateLogger("host");

IVirtualGamepad pad = Flag("--mock") ? new MockVirtualGamepad() : new ViGEmVirtualGamepad(log);
var storePath = Opt("--store") ?? DpapiPairedDeviceStore.DefaultPath();
var opts = new ServerOptions
{
    ControlPort = int.Parse(Opt("--control-port") ?? ProtocolConstants.DefaultControlPort.ToString()),
    InputPort = int.Parse(Opt("--input-port") ?? ProtocolConstants.DefaultInputPort.ToString()),
    EnableDiscovery = !Flag("--no-discovery"),
    BindAddress = IPAddress.Any, ServerId = ServerIdentity.LoadOrCreate(), // peers are still restricted to private/loopback ranges (ServerOptions.PrivateNetworksOnly)
};
using var host = new ServerHost(opts, pad, new DpapiPairedDeviceStore(storePath, log), log);
host.ConfirmPairing = (req, ct) =>
{
    if (Flag("--auto-accept")) { Console.WriteLine($"CONFIRM auto-accepting pairing from '{req.DeviceName}'"); return Task.FromResult(true); }
    Console.Write($"Allow '{req.DeviceName}' ({req.RemoteIp}) to pair? [y/N] ");
    return Task.Run(() => Console.ReadLine()?.Trim().ToLowerInvariant() == "y", ct);
};
host.Sessions.SessionStarted += s => Console.WriteLine($"SESSION started device='{s.DeviceName}' id={s.Id:X8}");
host.Sessions.SessionEnded += e => Console.WriteLine($"SESSION ended reason='{e.Reason}'");
host.Sessions.InputStale += () => Console.WriteLine("INPUT stale -> neutralized");

host.Start();
Console.WriteLine($"READY control={host.ControlPort} input={host.InputPort} discovery={host.DiscoveryPort?.ToString() ?? "off"} pad={pad.Status.Kind}: {pad.Status.Message}");
if (pad is ViGEmVirtualGamepad v && v.UserIndex is int ui) Console.WriteLine($"XINPUT_USER_INDEX {ui}");
if (Flag("--open-pairing")) Console.WriteLine($"PIN {host.Pairing.Open()}");

void ExportDiagnostics(string file)
{
    var report = DiagnosticsReport.Build(host, DiagnosticsReport.AppVersion(), "Information", "Default", ["(headless host: no in-app log buffer)"],
        new DiagnosticsOptions(MaskNetworkDetails: !Flag("--include-network-details")));
    DiagnosticsReport.WriteTo(file, report);
    Console.WriteLine($"DIAGNOSTICS written to {Path.GetFullPath(file)}");
}
if (Opt("--diagnostics") is { } diagFile) ExportDiagnostics(diagFile);

var quit = new ManualResetEventSlim();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; quit.Set(); };
AppDomain.CurrentDomain.ProcessExit += (_, _) => quit.Set();
_ = Task.Run(() =>
{
    string? line;
    while ((line = Console.ReadLine()) != null)
    {
        switch (line.Trim())
        {
            case "pair": Console.WriteLine($"PIN {host.Pairing.Open()}"); break;
            case "status":
                var s = host.Sessions.Current;
                Console.WriteLine($"STATUS session={(s == null ? "none" : s.Id.ToString("X8"))} rx={host.Stats.Received} accepted={host.Stats.Accepted} invalid={host.Stats.InvalidTotal} dropped={host.Stats.DroppedTotal} rtt={(s?.RttMs?.ToString("F0") ?? "---")}");
                break;
            case "quit": quit.Set(); return;
            case "diag": ExportDiagnostics(Opt("--diagnostics") ?? "p2g-diagnostics.txt"); break;
        }
    }
});
if (Opt("--run-seconds") is { } rs) quit.Wait(TimeSpan.FromSeconds(double.Parse(rs))); else quit.Wait();
Console.WriteLine("Shutting down: neutralizing and unplugging virtual controller...");
host.Stop();
Console.WriteLine("DONE");
