using System.Text.Json.Nodes;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;
using Phone2Gamepad.TestClient;

// Phone2Gamepad test client: behaves like the phone (real TCP pairing + authenticated UDP input) and can verify the
// result through XInput on this PC - no phone needed.
//
//   TestClient pair  --pin 123456 [--host 127.0.0.1] [--control-port 47821] [--store creds.json] [--name "Test Phone"]
//   TestClient e2e   [--host 127.0.0.1] [--control-port 47821] [--store creds.json] [--slot N] [--no-xinput]
//   TestClient hold  [--host ..] [--control-port ..] [--store creds.json] [--seconds 60]   (session held with A + LT + left stick until time is up or the server drops it; prints HOLD_ENDED <reason>)
//   TestClient xinput [--once]             (live dump of XInput slots 0-3 until Ctrl+C)
//   TestClient discover [--seconds 2]      (UDP broadcast discovery)

var a = args.ToList();
string cmd = a.Count > 0 ? a[0] : "help";
string? Opt(string n) { int i = a.IndexOf(n); return i >= 0 && i + 1 < a.Count ? a[i + 1] : null; }
string host = Opt("--host") ?? "127.0.0.1";
int port = int.Parse(Opt("--control-port") ?? ProtocolConstants.DefaultControlPort.ToString());
string store = Opt("--store") ?? Path.Combine(Path.GetTempPath(), "p2g-testclient-creds.json");

switch (cmd)
{
    case "pair": return await Pair();
    case "e2e": return await E2E();
    case "hold": return await Hold();
    case "xinput": return XInputDump();
    case "discover": return await Discover();
    default:
        Console.WriteLine("Commands: pair | e2e | xinput | discover  (see comments in Program.cs)");
        return 2;
}

async Task<int> Pair()
{
    var pin = Opt("--pin") ?? throw new ArgumentException("--pin required");
    var id = Opt("--device-id") ?? Guid.NewGuid().ToString();
    var client = new PhoneClient(id, Opt("--name") ?? "Test Phone");
    var r = await client.PairAsync(host, port, pin, CancellationToken.None);
    if (!r.Ok) { Console.WriteLine($"PAIR FAILED: {r.Reason}"); return 1; }
    File.WriteAllText(store, new JsonObject { ["deviceId"] = id, ["deviceName"] = client.DeviceName, ["secret"] = Convert.ToBase64String(r.DeviceSecret!) }.ToJsonString());
    Console.WriteLine($"PAIRED with '{r.ServerName}'. Test credentials saved to {store} (test tool only - unprotected file).");
    return 0;
}

(PhoneClient, byte[]) LoadCreds()
{
    var o = JsonNode.Parse(File.ReadAllText(store))!.AsObject();
    return (new PhoneClient(o["deviceId"]!.GetValue<string>(), o["deviceName"]!.GetValue<string>()), Convert.FromBase64String(o["secret"]!.GetValue<string>()));
}

async Task<int> E2E()
{
    bool useX = !a.Contains("--no-xinput");
    int slot = int.Parse(Opt("--slot") ?? "-1");
    var (client, secret) = LoadCreds();
    var before = useX ? XInput.ConnectedSlots().ToList() : new List<int>();
    Console.WriteLine($"XInput slots connected before session: [{string.Join(",", before)}]");

    int pass = 0, fail = 0;
    void Check(string name, bool ok, string detail = "")
    {
        if (ok) pass++; else fail++;
        Console.WriteLine($"{(ok ? "PASS" : "FAIL")}  {name} {detail}");
    }

    // ---------------------------------------------------------------- session 1: scripted states
    using var s = await client.StartSessionAsync(host, port, secret, CancellationToken.None);
    Console.WriteLine($"Session started id={s.SessionId:X8}");

    var steps = new (string Name, GamepadState State)[]
    {
        ("A+B",                  new(GamepadButtons.A | GamepadButtons.B, 0, 0, 0, 0, 0, 0, 0)),
        ("X+Y+LB+RB",            new(GamepadButtons.X | GamepadButtons.Y | GamepadButtons.LeftBumper | GamepadButtons.RightBumper, 0, 0, 0, 0, 0, 0, 0)),
        ("Start+Back+L3+R3",     new(GamepadButtons.Start | GamepadButtons.Back | GamepadButtons.LeftStick | GamepadButtons.RightStick, 0, 0, 0, 0, 0, 0, 0)),
        ("DPad up+right",        new(0, DpadFlags.Up | DpadFlags.Right, 0, 0, 0, 0, 0, 0)),
        ("DPad down+left",       new(0, DpadFlags.Down | DpadFlags.Left, 0, 0, 0, 0, 0, 0)),
        ("Sticks full scale",    new(0, 0, 32767, 32767, -32768, -32768, 0, 0)),
        ("Sticks partial",       new(0, 0, 12345, -23456, -30000, 8000, 0, 0)),
        ("LT only",              new(0, 0, 0, 0, 0, 0, 255, 0)),
        ("RT only",              new(0, 0, 0, 0, 0, 0, 0, 255)),
        ("Triggers analog",      new(0, 0, 0, 0, 0, 0, 100, 200)),
        ("Everything at once",   new(GamepadButtons.A | GamepadButtons.Y | GamepadButtons.LeftBumper | GamepadButtons.Start, DpadFlags.Up,
                                     -16000, 16000, 20000, -20000, 77, 188)),
        ("Neutral",              GamepadState.Neutral),
    };

    foreach (var (name, st) in steps)
    {
        for (int i = 0; i < 6; i++) { s.SendState(st); await Task.Delay(16); }
        await Task.Delay(60);
        if (!useX) { Check(name, true, "(sent; XInput verification skipped)"); continue; }
        var exp = XInput.Expected(st);
        if (slot < 0)
        {
            // Discover which slot is ours: the connected slot (new since before, else any) that reflects this step.
            var cands = XInput.ConnectedSlots().OrderBy(i => before.Contains(i) ? 1 : 0).ToList();
            foreach (var c in cands) { XInput.TryRead(c, out var g); if (!st.IsNeutral && XInput.Equal(g, exp)) { slot = c; break; } }
        }
        if (slot < 0) { Check(name, st.IsNeutral, "no matching XInput slot yet"); continue; }
        bool read = XInput.TryRead(slot, out var got);
        Check(name, read && XInput.Equal(got, exp), $"slot {slot}: expected [{XInput.Describe(exp)}] got [{(read ? XInput.Describe(got) : "disconnected")}]");
    }

    // ---------------------------------------------------------------- stale input: silence -> neutral within ~0.5 s
    var held = new GamepadState(GamepadButtons.A, DpadFlags.Up, 20000, 0, 0, 0, 255, 0);
    for (int i = 0; i < 6; i++) { s.SendState(held); await Task.Delay(16); }
    await Task.Delay(60);
    if (useX && slot >= 0) { XInput.TryRead(slot, out var g); Check("Held state visible before silence", XInput.Equal(g, XInput.Expected(held)), XInput.Describe(g)); }
    s.StopHeartbeats();
    var sw = System.Diagnostics.Stopwatch.StartNew();
    if (useX && slot >= 0)
    {
        bool neutral = false;
        while (sw.ElapsedMilliseconds < 1500) { await Task.Delay(20); if (XInput.TryRead(slot, out var g) && XInput.Equal(g, default)) { neutral = true; break; } }
        Check("Silence -> neutral (stale input)", neutral, $"after {sw.ElapsedMilliseconds} ms (limit 500 ms + timer slack)");
    }

    // ---------------------------------------------------------------- clean disconnect releases everything
    s.SendState(held); await Task.Delay(100);
    await s.DisconnectAsync();
    await Task.Delay(300);
    if (useX && slot >= 0) { XInput.TryRead(slot, out var g); Check("Clean disconnect -> neutral", XInput.Equal(g, default), XInput.Describe(g)); }

    // ---------------------------------------------------------------- reconnect = fresh session; abrupt loss releases everything
    using var s2 = await client.StartSessionAsync(host, port, secret, CancellationToken.None);
    Check("Reconnect gets a fresh session id", s2.SessionId != s.SessionId, $"{s.SessionId:X8} -> {s2.SessionId:X8}");
    for (int i = 0; i < 6; i++) { s2.SendState(held); await Task.Delay(16); }
    await Task.Delay(60);
    if (useX && slot >= 0) { XInput.TryRead(slot, out var g); Check("Fresh session controls the pad", XInput.Equal(g, XInput.Expected(held)), XInput.Describe(g)); }
    s2.Abort(); // Wi-Fi drop: nothing is sent to the server
    await Task.Delay(500);
    if (useX && slot >= 0) { XInput.TryRead(slot, out var g); Check("Abrupt connection loss -> neutral", XInput.Equal(g, default), XInput.Describe(g)); }

    Console.WriteLine($"RESULT: {pass} passed, {fail} failed");
    return fail == 0 ? 0 : 1;
}

int XInputDump()
{
    if (a.Contains("--once")) { for (int i = 0; i < 4; i++) Console.WriteLine($"slot {i}: {(XInput.TryRead(i, out var g) ? XInput.Describe(g) : "not connected")}"); return 0; }
    Console.WriteLine("Polling XInput slots 0-3 (Ctrl+C to stop)...");
    var last = new string[4];
    while (true)
    {
        for (int i = 0; i < 4; i++)
        {
            var d = XInput.TryRead(i, out var g) ? XInput.Describe(g) : "-";
            if (d != last[i]) { last[i] = d; Console.WriteLine($"slot {i}: {d}"); }
        }
        Thread.Sleep(20);
    }
}

async Task<int> Hold()
{
    var (client, secret) = LoadCreds();
    using var s = await client.StartSessionAsync(host, port, secret, CancellationToken.None);
    var held = new GamepadState(GamepadButtons.A, 0, 20000, 0, 0, 0, 255, 0);
    Console.WriteLine($"HOLDING session={s.SessionId:X8}");
    var until = DateTime.UtcNow.AddSeconds(int.Parse(Opt("--seconds") ?? "60"));
    while (DateTime.UtcNow < until && !s.IsClosed) { s.SendState(held); await Task.Delay(16); }
    Console.WriteLine($"HOLD_ENDED {(s.IsClosed ? "server closed: " + s.ClosedReason : "time up")}");
    if (!s.IsClosed) await s.DisconnectAsync();
    return 0;
}

async Task<int> Discover()
{
    using var udp = new System.Net.Sockets.UdpClient { EnableBroadcast = true };
    var req = new byte[] { 0x50, 0x32, 1, 0x10, 1, 2, 3, 4 };
    await udp.SendAsync(req, req.Length, new System.Net.IPEndPoint(System.Net.IPAddress.Broadcast, ProtocolConstants.DefaultDiscoveryPort));
    await udp.SendAsync(req, req.Length, new System.Net.IPEndPoint(System.Net.IPAddress.Loopback, ProtocolConstants.DefaultDiscoveryPort));
    var until = DateTime.UtcNow.AddSeconds(int.Parse(Opt("--seconds") ?? "2"));
    int found = 0;
    while (DateTime.UtcNow < until)
    {
        var t = udp.ReceiveAsync(new CancellationTokenSource(500).Token).AsTask();
        try
        {
            var r = await t; var b = r.Buffer;
            if (b.Length < 14 || b[3] != 0x11) continue;
            found++;
            Console.WriteLine($"FOUND '{System.Text.Encoding.UTF8.GetString(b, 14, b[13])}' at {r.RemoteEndPoint.Address} tcp={BitConverter.ToUInt16(b, 8)} udp={BitConverter.ToUInt16(b, 10)} pairingOpen={(b[12] & 1) != 0}");
        }
        catch (OperationCanceledException) { }
    }
    return found > 0 ? 0 : 1;
}
