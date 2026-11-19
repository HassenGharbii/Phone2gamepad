using System.Net;
using Phone2Gamepad.Core;
using Phone2Gamepad.Core.Presentation;

namespace Phone2Gamepad.Tests;

public class PresentationTests
{
    // ---------------------------------------------------------------- metrics never fabricate values
    [Fact]
    public void Metrics_ShowDash_WhenUnavailable()
    {
        Assert.Equal("—", UiFormat.Latency(null));
        Assert.Equal("—", UiFormat.Latency(double.NaN));
        Assert.Equal("—", UiFormat.Latency(-1));
        Assert.Equal("—", UiFormat.Rate(null));
        Assert.Equal("—", UiFormat.Count(null));
        Assert.Equal("—", UiFormat.Duration(null));
        Assert.Equal("—", UiFormat.Duration(TimeSpan.FromSeconds(-5)));
    }

    [Fact]
    public void Metrics_FormatRealValues()
    {
        Assert.Equal("6 ms", UiFormat.Latency(6.4));
        Assert.Equal("0 ms", UiFormat.Latency(0));
        Assert.Equal("33/s", UiFormat.Rate(32.6));
        Assert.Equal("0:01:09", UiFormat.Duration(TimeSpan.FromSeconds(69)));
        Assert.Equal("12:00:00", UiFormat.Duration(TimeSpan.FromHours(12)));
        Assert.Equal("2d 03:04", UiFormat.Duration(new TimeSpan(2, 3, 4, 5)));
    }

    [Fact]
    public void RelativeTime_Buckets()
    {
        var now = new DateTimeOffset(2026, 11, 19, 12, 0, 0, TimeSpan.Zero);
        Assert.Equal("Never", UiFormat.RelativeTime(null, now));
        Assert.Equal("Just now", UiFormat.RelativeTime(now.AddSeconds(-20), now));
        Assert.Equal("5 min ago", UiFormat.RelativeTime(now.AddMinutes(-5), now));
        Assert.Equal("3 h ago", UiFormat.RelativeTime(now.AddHours(-3), now));
        Assert.Equal("Yesterday", UiFormat.RelativeTime(now.AddHours(-30), now));
        Assert.Equal("4 days ago", UiFormat.RelativeTime(now.AddDays(-4), now));
    }

    [Fact]
    public void Pin_GroupedOrPlaceholder()
    {
        Assert.Equal("332 719", UiFormat.Pin("332719"));
        Assert.Equal("••• •••", UiFormat.Pin(null));
        Assert.Equal("••• •••", UiFormat.Pin("12"));
    }

    [Fact]
    public void Countdown_ClampsAndRoundsUp()
    {
        var now = DateTimeOffset.UnixEpoch;
        var win = TimeSpan.FromSeconds(120);
        Assert.Equal((0, 0.0), UiFormat.Countdown(null, now, win));
        Assert.Equal((0, 0.0), UiFormat.Countdown(now.AddSeconds(-1), now, win));
        var (s, f) = UiFormat.Countdown(now.AddSeconds(59.2), now, win);
        Assert.Equal(60, s);
        Assert.InRange(f, 0.49, 0.50);
        Assert.Equal(1.0, UiFormat.Countdown(now.AddSeconds(500), now, win).Fraction);
    }

    [Fact]
    public void ButtonNames_AreFriendly()
    {
        Assert.Equal("Left bumper (LB)", UiFormat.ButtonLong(GamepadButtons.LeftBumper));
        Assert.Equal("RS", UiFormat.ButtonShort(GamepadButtons.RightStick));
        Assert.Equal("View / Back", UiFormat.ButtonLong(GamepadButtons.Back));
    }

    [Fact]
    public void MappingRules_FlagEveryDuplicateTarget_ButAllowSwaps()
    {
        var dup = MappingRules.DuplicateProblems(new[] { (GamepadButtons.A, GamepadButtons.B), (GamepadButtons.B, GamepadButtons.B), (GamepadButtons.X, GamepadButtons.X) });
        Assert.Single(dup);
        Assert.StartsWith("A and B both send B.", dup[0]);
        Assert.Empty(MappingRules.DuplicateProblems(new[] { (GamepadButtons.A, GamepadButtons.B), (GamepadButtons.B, GamepadButtons.A) }));
    }

    // ---------------------------------------------------------------- home status priorities
    private static HomeFacts Facts(bool driverMissing = false, bool driverError = false, bool running = true, string? err = null,
        string? phone = null, bool stale = false, bool pairing = false, int paired = 1) =>
        new(driverMissing, driverError, running, err, phone, stale, pairing, paired);

    [Fact]
    public void HomeStatus_DriverMissingWinsOverEverything()
    {
        var s = HomeStatusRules.Compute(Facts(driverMissing: true, phone: "Pixel", pairing: true));
        Assert.Equal(StatusTone.Critical, s.Tone);
        Assert.Equal(HomeAction.DriverHelp, s.Action);
    }

    [Fact]
    public void HomeStatus_ServerErrorThenStopped()
    {
        Assert.Equal(HomeAction.StartServer, HomeStatusRules.Compute(Facts(running: false, err: "port busy")).Action);
        Assert.Equal("Server couldn't start", HomeStatusRules.Compute(Facts(running: false, err: "port busy")).Title);
        var off = HomeStatusRules.Compute(Facts(running: false));
        Assert.Equal("Server is off", off.Title);
        Assert.Equal(StatusTone.Neutral, off.Tone);
    }

    [Fact]
    public void HomeStatus_PlayingAndStale()
    {
        var p = HomeStatusRules.Compute(Facts(phone: "INFINIX Infinix X6882"));
        Assert.Equal("Playing — INFINIX Infinix X6882", p.Title);
        Assert.Equal(StatusTone.Success, p.Tone);
        Assert.Equal(HomeAction.None, p.Action);
        Assert.Equal(StatusTone.Attention, HomeStatusRules.Compute(Facts(phone: "X", stale: true)).Tone);
    }

    [Fact]
    public void HomeStatus_PairingAndIdleStates()
    {
        Assert.Equal("Waiting for your phone", HomeStatusRules.Compute(Facts(pairing: true)).Title);
        var first = HomeStatusRules.Compute(Facts(paired: 0));
        Assert.Equal("Ready to pair", first.Title);
        Assert.Equal(HomeAction.OpenPairing, first.Action);
        Assert.Equal("Ready — waiting for your phone", HomeStatusRules.Compute(Facts()).Title);
    }

    // ---------------------------------------------------------------- address ranking
    private static AdapterAddress A(string ip, string name, bool wifi = false, bool gw = false, string desc = "") =>
        new(IPAddress.Parse(ip), name, desc, wifi, gw);

    [Fact]
    public void Addresses_WifiWithGatewayFirst_VirtualAndLinkLocalLast()
    {
        var list = new[]
        {
            A("172.21.80.1", "vEthernet (WSL)", desc: "Hyper-V Virtual Ethernet Adapter"),
            A("169.254.161.202", "Ethernet 2"),
            A("192.168.100.208", "Wi-Fi", wifi: true, gw: true),
            A("172.19.80.1", "vEthernet (Default Switch)"),
        };
        var ranked = AddressRanking.Rank(list);
        Assert.Equal("192.168.100.208", ranked[0].Address.ToString());
        Assert.Equal("169.254.161.202", ranked[^1].Address.ToString());
        Assert.Equal("192.168.100.208", AddressRanking.Primary(list)!.Address.ToString());
    }

    [Fact]
    public void Addresses_NoPrimary_WhenOnlyVirtualOrLinkLocal()
    {
        Assert.Null(AddressRanking.Primary(new[] { A("172.21.80.1", "vEthernet (WSL)"), A("169.254.1.1", "Ethernet") }));
        Assert.True(AddressRanking.IsLinkLocal(IPAddress.Parse("169.254.0.9")));
        Assert.False(AddressRanking.IsLinkLocal(IPAddress.Parse("192.168.0.9")));
    }

    // ---------------------------------------------------------------- window placement
    private static readonly UiRect Work = new(0, 0, 1536, 816);

    [Fact]
    public void Placement_FirstRun_CentredAndFits()
    {
        var r = WindowPlacement.Resolve(null, new[] { Work }, (1100, 720), (820, 560));
        Assert.Equal(new UiRect(218, 48, 1100, 720), r);
        var small = WindowPlacement.Resolve(null, new[] { new UiRect(0, 0, 800, 600) }, (1100, 720), (820, 560));
        Assert.Equal(new UiRect(0, 0, 800, 600), small);
    }

    [Fact]
    public void Placement_SavedOffscreen_IsPulledBack()
    {
        var r = WindowPlacement.Resolve(new UiRect(1300, 700, 1225, 850), new[] { Work }, (1100, 720), (820, 560));
        Assert.True(r.Left >= 0 && r.Right <= 1536 && r.Top >= 0 && r.Bottom <= 816, r.ToString());
        Assert.Equal(816, r.Height);
    }

    [Fact]
    public void Placement_SavedOnSecondMonitor_Kept()
    {
        var second = new UiRect(1536, 0, 1920, 1040);
        var r = WindowPlacement.Resolve(new UiRect(1700, 100, 1000, 700), new[] { Work, second }, (1100, 720), (820, 560));
        Assert.Equal(new UiRect(1700, 100, 1000, 700), r);
    }

    [Fact]
    public void Placement_MonitorRemoved_FallsBackToOverlapOrPrimary()
    {
        var r = WindowPlacement.Resolve(new UiRect(4000, 100, 1000, 700), new[] { Work }, (1100, 720), (820, 560));
        Assert.True(r.Right <= 1536 && r.Left >= 0);
    }

    [Fact]
    public void Placement_GarbageSaved_UsesDefault()
    {
        var r = WindowPlacement.Resolve(new UiRect(double.NaN, 0, 10, 10), new[] { Work }, (1100, 720), (820, 560));
        Assert.Equal(1100, r.Width);
    }

    // ---------------------------------------------------------------- settings: new UI fields
    [Fact]
    public void Settings_UiFields_RoundTripAndSanitize()
    {
        var dir = Directory.CreateTempSubdirectory("p2g-ui-");
        try
        {
            var path = Path.Combine(dir.FullName, "settings.json");
            var s = new AppSettings
            {
                Theme = "Light", NotifyOnPhoneConnect = false,
                Window = new WindowBounds { Left = 10, Top = 20, Width = 900, Height = 600, Maximized = true },
            };
            s.PhoneLabels["dev-1"] = "  Living room phone  ";
            s.PhoneLastSeen["dev-1"] = DateTimeOffset.UnixEpoch;
            s.Save(path);
            var l = AppSettings.Load(path);
            Assert.Equal("Light", l.Theme);
            Assert.False(l.NotifyOnPhoneConnect);
            Assert.True(l.Window!.Maximized);
            Assert.Equal("Living room phone", l.PhoneLabels["dev-1"]);
            Assert.Equal(DateTimeOffset.UnixEpoch, l.PhoneLastSeen["dev-1"]);

            File.WriteAllText(path, """{"SchemaVersion":1,"Theme":"Neon","PhoneLabels":{"a":"   ","b":"0123456789012345678901234567890123456789XYZ"},"Window":{"Left":0,"Top":0,"Width":-5,"Height":10}}""");
            var bad = AppSettings.Load(path);
            Assert.Equal("System", bad.Theme);
            Assert.False(bad.PhoneLabels.ContainsKey("a"));
            Assert.Equal(AppSettings.MaxPhoneLabelLength, bad.PhoneLabels["b"].Length);
            Assert.Null(bad.Window);

            // older files without the new fields keep defaults
            File.WriteAllText(path, """{"SchemaVersion":1,"LogLevel":"Debug"}""");
            var old = AppSettings.Load(path);
            Assert.Equal("System", old.Theme);
            Assert.True(old.NotifyOnPhoneConnect);
            Assert.Empty(old.PhoneLabels);
        }
        finally { dir.Delete(true); }
    }
}
