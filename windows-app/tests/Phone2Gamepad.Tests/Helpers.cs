using System.Net;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.VirtualGamepad;

namespace Phone2Gamepad.Tests;

public sealed class ManualTimeProvider : TimeProvider
{
    private long _ticks = 1_000;           // 1 tick = 1 ms
    private DateTimeOffset _utc = new(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
    public override long TimestampFrequency => 1000;
    public override long GetTimestamp() => Interlocked.Read(ref _ticks);
    public override DateTimeOffset GetUtcNow() => _utc;
    public void Advance(TimeSpan t) { Interlocked.Add(ref _ticks, (long)t.TotalMilliseconds); _utc += t; }
}

/// <summary>Full server on loopback with ephemeral ports, a mock gamepad and an in-memory device store.</summary>
public sealed class ServerFixture : IDisposable
{
    public MockVirtualGamepad Pad = new();
    public InMemoryPairedDeviceStore Store = new();
    public ServerHost Host;
    public bool AutoAccept = true;
    public int ConfirmCalls;

    public ServerFixture(TimeProvider? time = null, string? serverId = null)
    {
        Host = new ServerHost(new ServerOptions
        {
            ControlPort = 0, InputPort = 0, EnableDiscovery = false, BindAddress = IPAddress.Loopback, ServerName = "TestPC", ServerId = serverId,
        }, Pad, Store, time: time)
        {
            ConfirmPairing = (_, _) => { ConfirmCalls++; return Task.FromResult(AutoAccept); },
        };
        Host.Start();
    }

    public string Host4 => "127.0.0.1";
    public int ControlPort => Host.ControlPort;

    public async Task<(PhoneClient, byte[] Secret)> PairAsync(string id = "dev-1", string name = "Test Phone")
    {
        var pin = Host.Pairing.Open();
        var c = new PhoneClient(id, name);
        var r = await c.PairAsync(Host4, ControlPort, pin, CancellationToken.None);
        Assert.True(r.Ok, r.Reason);
        return (c, r.DeviceSecret!);
    }

    public static async Task WaitFor(Func<bool> cond, int timeoutMs = 3000)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (!cond())
        {
            if (sw.ElapsedMilliseconds > timeoutMs) throw new TimeoutException("Condition not met in time.");
            await Task.Delay(10);
        }
    }

    public void Dispose() => Host.Dispose();
}
