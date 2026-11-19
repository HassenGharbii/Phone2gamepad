using System.Net;
using System.Net.Sockets;
using Phone2Gamepad.Core;
using Phone2Gamepad.Network;

namespace Phone2Gamepad.Tests;

/// <summary>Real TCP/UDP on loopback against a ServerHost with a mock gamepad.</summary>
public class IntegrationTests
{
    private static GamepadState Full => new(GamepadButtons.A | GamepadButtons.RightBumper, DpadFlags.Down, 1000, -2000, 3000, 4000, 200, 100);

    [Fact]
    public async Task Pair_start_session_send_state_and_disconnect_neutralizes()
    {
        using var fx = new ServerFixture();
        var (phone, secret) = await fx.PairAsync();
        Assert.Single(fx.Store.List());
        Assert.Equal(secret, fx.Store.Get("dev-1")!.Secret);   // both sides derived the same device secret
        Assert.False(fx.Host.Pairing.IsOpen);                   // PIN is single use

        using var s = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        await ServerFixture.WaitFor(() => fx.Host.Sessions.Current != null);
        Assert.Equal(s.SessionId, fx.Host.Sessions.Current!.Id);

        s.SendState(Full);
        await ServerFixture.WaitFor(() => fx.Pad.Current == Full);

        await s.DisconnectAsync();
        await ServerFixture.WaitFor(() => fx.Host.Sessions.Current == null);
        Assert.True(fx.Pad.Current.IsNeutral);
    }

    [Fact]
    public async Task Abrupt_connection_loss_neutralizes_immediately()
    {
        using var fx = new ServerFixture();
        var (phone, secret) = await fx.PairAsync();
        var s = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        s.SendState(Full);
        await ServerFixture.WaitFor(() => fx.Pad.Current == Full);
        s.Abort();
        await ServerFixture.WaitFor(() => fx.Host.Sessions.Current == null);
        Assert.True(fx.Pad.Current.IsNeutral);
    }

    [Fact]
    public async Task Silent_udp_neutralizes_within_about_half_a_second_while_tcp_stays_up()
    {
        using var fx = new ServerFixture();
        var (phone, secret) = await fx.PairAsync();
        using var s = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        s.StopHeartbeats();
        s.SendState(Full);
        await ServerFixture.WaitFor(() => fx.Pad.Current == Full);
        var sw = System.Diagnostics.Stopwatch.StartNew();
        await ServerFixture.WaitFor(() => fx.Pad.Current.IsNeutral, 2000);
        Assert.InRange(sw.ElapsedMilliseconds, 300, 1200);
        Assert.NotNull(fx.Host.Sessions.Current);
    }

    [Fact]
    public async Task Reconnect_creates_a_fresh_session_and_the_old_one_cannot_control_the_pad()
    {
        using var fx = new ServerFixture();
        var (phone, secret) = await fx.PairAsync();
        var s1 = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        s1.SendState(Full);
        await ServerFixture.WaitFor(() => fx.Pad.Current == Full);
        var oldKeyedPacket = s1.BuildState(1000, Full);
        s1.Abort();
        await ServerFixture.WaitFor(() => fx.Host.Sessions.Current == null);

        using var s2 = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        await ServerFixture.WaitFor(() => fx.Host.Sessions.Current != null);
        Assert.NotEqual(s1.SessionId, s2.SessionId);

        s2.SendRaw(oldKeyedPacket);                    // packet from the dead session, sent from the live socket
        await Task.Delay(150);
        Assert.True(fx.Pad.Current.IsNeutral);
        Assert.True(fx.Host.Stats.Invalid(PacketError.Session) >= 1);
        s2.SendState(Full);
        await ServerFixture.WaitFor(() => fx.Pad.Current == Full);
    }

    [Fact]
    public async Task Wrong_pin_is_rejected_then_rate_limited_and_never_pairs()
    {
        using var fx = new ServerFixture();
        var pin = fx.Host.Pairing.Open();
        var wrong = pin == "000000" ? "000001" : "000000";
        var c = new PhoneClient("dev-x", "Attacker");
        var r = await c.PairAsync(fx.Host4, fx.ControlPort, wrong, CancellationToken.None);
        Assert.False(r.Ok); Assert.Equal("bad_pin", r.Reason);
        var r2 = await c.PairAsync(fx.Host4, fx.ControlPort, pin, CancellationToken.None); // immediately retrying, even with the right PIN
        Assert.False(r2.Ok); Assert.Equal("rate_limited", r2.Reason);
        Assert.Empty(fx.Store.List());
        Assert.Equal(0, fx.ConfirmCalls);
    }

    [Fact]
    public async Task Pairing_window_closes_after_five_wrong_pins()
    {
        var time = new ManualTimeProvider();
        using var fx = new ServerFixture(time);
        var pin = fx.Host.Pairing.Open();
        var wrong = pin == "000000" ? "000001" : "000000";
        var c = new PhoneClient("dev-x", "Attacker");
        for (int i = 0; i < 5; i++)
        {
            time.Advance(TimeSpan.FromSeconds(2)); // keep each attempt outside the 1/s rate limit
            Assert.Equal("bad_pin", (await c.PairAsync(fx.Host4, fx.ControlPort, wrong, CancellationToken.None)).Reason);
        }
        Assert.False(fx.Host.Pairing.IsOpen);
        time.Advance(TimeSpan.FromSeconds(2));
        var late = await c.PairAsync(fx.Host4, fx.ControlPort, pin, CancellationToken.None);
        Assert.Equal("pairing_closed", late.Reason);
    }

    [Fact]
    public async Task Pin_expires_after_120_seconds_and_pairing_must_be_opened_explicitly()
    {
        var time = new ManualTimeProvider();
        using var fx = new ServerFixture(time);
        var pin = fx.Host.Pairing.Open();
        time.Advance(TimeSpan.FromSeconds(119));
        Assert.True(fx.Host.Pairing.IsOpen);
        time.Advance(TimeSpan.FromSeconds(2));
        Assert.False(fx.Host.Pairing.IsOpen);
        Assert.Null(fx.Host.Pairing.Pin);
        var r = await new PhoneClient("d", "n").PairAsync(fx.Host4, fx.ControlPort, pin, CancellationToken.None);
        Assert.Equal("pairing_closed", r.Reason);
    }

    [Fact]
    public async Task Pairing_without_an_open_window_is_refused()
    {
        using var fx = new ServerFixture();
        var r = await new PhoneClient("d", "n").PairAsync(fx.Host4, fx.ControlPort, "123456", CancellationToken.None);
        Assert.Equal("pairing_closed", r.Reason);
    }

    [Fact]
    public async Task User_declining_on_the_pc_prevents_pairing()
    {
        using var fx = new ServerFixture { AutoAccept = false };
        var pin = fx.Host.Pairing.Open();
        var r = await new PhoneClient("d", "Phone").PairAsync(fx.Host4, fx.ControlPort, pin, CancellationToken.None);
        Assert.Equal("rejected_by_user", r.Reason);
        Assert.Equal(1, fx.ConfirmCalls);
        Assert.Empty(fx.Store.List());
    }

    [Fact]
    public async Task Unpaired_device_cannot_start_a_session_and_bad_secret_fails_auth()
    {
        using var fx = new ServerFixture();
        var ex = await Assert.ThrowsAsync<ProtocolException>(() =>
            new PhoneClient("nobody", "x").StartSessionAsync(fx.Host4, fx.ControlPort, new byte[32], CancellationToken.None));
        Assert.Equal("not_paired", ex.Code);

        var (phone, _) = await fx.PairAsync();
        var ex2 = await Assert.ThrowsAsync<ProtocolException>(() =>
            phone.StartSessionAsync(fx.Host4, fx.ControlPort, new byte[32], CancellationToken.None));
        Assert.Equal("auth_failed", ex2.Code);
        Assert.Null(fx.Host.Sessions.Current);
    }

    [Fact]
    public async Task Revoking_a_device_ends_its_session_and_blocks_reconnect()
    {
        using var fx = new ServerFixture();
        var (phone, secret) = await fx.PairAsync();
        using var s = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        s.SendState(Full);
        await ServerFixture.WaitFor(() => fx.Pad.Current == Full);
        Assert.True(fx.Host.Revoke("dev-1"));
        await ServerFixture.WaitFor(() => fx.Host.Sessions.Current == null);
        Assert.True(fx.Pad.Current.IsNeutral);
        var ex = await Assert.ThrowsAsync<ProtocolException>(() => phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None));
        Assert.Equal("not_paired", ex.Code);
    }

    [Fact]
    public async Task Second_phone_gets_busy_while_first_is_active()
    {
        using var fx = new ServerFixture();
        var (p1, sec1) = await fx.PairAsync("dev-1", "Phone 1");
        var (p2, sec2) = await fx.PairAsync("dev-2", "Phone 2");
        using var s1 = await p1.StartSessionAsync(fx.Host4, fx.ControlPort, sec1, CancellationToken.None);
        var ex = await Assert.ThrowsAsync<ProtocolException>(() => p2.StartSessionAsync(fx.Host4, fx.ControlPort, sec2, CancellationToken.None));
        Assert.Equal("busy", ex.Code);
    }

    [Fact]
    public async Task Garbage_on_the_control_port_is_survived()
    {
        using var fx = new ServerFixture();
        using (var raw = new TcpClient())
        {
            await raw.ConnectAsync(fx.Host4, fx.ControlPort);
            var ns = raw.GetStream();
            await ns.WriteAsync(new byte[] { 0xFF, 0xFF, 0xFF, 0xFF, 1, 2, 3 });
            var buf = new byte[256];
            await Task.WhenAny(ns.ReadAsync(buf).AsTask(), Task.Delay(1000));
        }
        var (phone, secret) = await fx.PairAsync();      // server still works
        using var s = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        Assert.NotNull(s);
    }

    [Fact]
    public async Task Heartbeats_flow_both_ways_and_produce_a_measured_rtt()
    {
        using var fx = new ServerFixture();
        var (phone, secret) = await fx.PairAsync();
        using var s = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        s.SendState(GamepadState.Neutral); // lets the server learn the phone's UDP endpoint immediately
        await ServerFixture.WaitFor(() => fx.Host.Sessions.Current?.RttMs != null && s.RttMs != null, 5000);
        Assert.InRange(fx.Host.Sessions.Current!.RttMs!.Value, 0, 500);
        Assert.InRange(s.RttMs!.Value, 0, 500);
    }

    [Fact]
    public async Task Stopping_the_server_neutralizes_and_unplugs_the_pad()
    {
        var fx = new ServerFixture();
        var (phone, secret) = await fx.PairAsync();
        using var s = await phone.StartSessionAsync(fx.Host4, fx.ControlPort, secret, CancellationToken.None);
        s.SendState(Full);
        await ServerFixture.WaitFor(() => fx.Pad.Current == Full);
        fx.Host.Stop();
        Assert.True(fx.Pad.Current.IsNeutral);
        Assert.False(fx.Pad.Status.IsConnected);
        Assert.Null(fx.Host.Sessions.Current);
        fx.Dispose();
    }

    [Fact]
    public async Task Discovery_request_is_answered_with_name_and_ports()
    {
        var pad = new Phone2Gamepad.VirtualGamepad.MockVirtualGamepad();
        using var host = new ServerHost(new ServerOptions
        {
            ControlPort = 0, InputPort = 0, DiscoveryPort = 0, BindAddress = IPAddress.Loopback, ServerName = "DiscoPC",
        }, pad, new InMemoryPairedDeviceStore());
        host.Start();
        host.Pairing.Open();
        using var udp = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        var req = new byte[8] { 0x50, 0x32, 1, 0x10, 0x78, 0x56, 0x34, 0x12 };
        await udp.SendAsync(req, req.Length, new IPEndPoint(IPAddress.Loopback, host.DiscoveryPort!.Value));
        var r = await udp.ReceiveAsync(new CancellationTokenSource(2000).Token);
        var b = r.Buffer;
        Assert.Equal(0x11, b[3]);
        Assert.Equal(0x12345678u, BitConverter.ToUInt32(b, 4));
        Assert.Equal(host.ControlPort, BitConverter.ToUInt16(b, 8));
        Assert.Equal(host.InputPort, BitConverter.ToUInt16(b, 10));
        Assert.Equal(1, b[12] & 1);
        Assert.Equal("DiscoPC", System.Text.Encoding.UTF8.GetString(b, 14, b[13]));
    }
}
