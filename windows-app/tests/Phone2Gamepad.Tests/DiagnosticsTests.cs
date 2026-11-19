using Phone2Gamepad.Network;

namespace Phone2Gamepad.Tests;

public class DiagnosticsTests
{
    [Theory]
    [InlineData("pairing opened, PIN 123456 shown", "123456")]
    [InlineData("PIN: 123 456", "123 456")]
    [InlineData("pin=654321", "654321")]
    public void Pins_are_always_removed_even_with_network_details_enabled(string text, string pin)
    {
        foreach (var mask in new[] { true, false })
        {
            var r = Redactor.Redact(text, mask);
            Assert.DoesNotContain(pin, r);
            Assert.Contains("<redacted>", r);
        }
    }

    [Fact]
    public void Secrets_and_long_blobs_are_always_removed()
    {
        var hex = new string('a', 64);
        var b64 = Convert.ToBase64String(Enumerable.Range(0, 32).Select(i => (byte)(i * 7 + 1)).ToArray()) + Convert.ToBase64String(new byte[10]);
        var text = $"derived key {hex}; proof={b64}; secret: hunter2 token=abc123 blob {b64}";
        var r = Redactor.Redact(text, maskNetworkDetails: false);
        Assert.DoesNotContain(hex, r);
        Assert.DoesNotContain(b64, r);
        Assert.DoesNotContain("hunter2", r);
        Assert.DoesNotContain("abc123", r);
    }

    [Fact]
    public void Known_secrets_are_removed_by_exact_match()
    {
        var r = Redactor.Redact("the code was 9sH3-weird here", false, knownSecrets: new[] { "9sH3-weird" });
        Assert.DoesNotContain("9sH3-weird", r);
    }

    [Fact]
    public void Ip_addresses_guids_and_user_paths_are_masked_by_default()
    {
        var guid = Guid.NewGuid().ToString();
        var text = $"Session started from 192.168.100.17 ({guid}) fe80::1c2b:3d4e:5f60:7182 and 2001:db8::1 at C:\\Users\\Alice\\AppData\\x time 12:34:56.789";
        var r = Redactor.Redact(text);
        Assert.DoesNotContain("192.168.100.17", r);
        Assert.DoesNotContain(guid, r);
        Assert.DoesNotContain("fe80", r);
        Assert.DoesNotContain("2001:db8", r);
        Assert.DoesNotContain("Alice", r);
        Assert.Contains("12:34:56.789", r); // log timestamps must survive
    }

    [Fact]
    public void Version_numbers_are_not_mistaken_for_ip_addresses()
    {
        var r = Redactor.Redact("driver 1.17.333.0 on 10.0.26200 app 1.0.0");
        Assert.Contains("1.17.333.0", r);
        Assert.Contains("10.0.26200", r);
    }

    [Fact]
    public void Network_details_are_kept_when_the_user_opts_in()
    {
        var r = Redactor.Redact("from 192.168.100.17", maskNetworkDetails: false);
        Assert.Contains("192.168.100.17", r);
    }

    [Fact]
    public async Task Report_contains_useful_data_but_no_pin_secret_ip_or_device_identifiers()
    {
        using var f = new ServerFixture();
        var (client, secret) = await f.PairAsync("my-device-id-777", "Alice's Phone");
        var pin = f.Host.Pairing.Open();
        var lines = new[]
        {
            $"12:00:00.000 INFO Pairing window opened, PIN {pin}",
            "12:00:01.000 INFO Session started from 192.168.100.17 device Alice's Phone id my-device-id-777",
            $"12:00:02.000 DEBG key {Convert.ToHexString(secret)}",
        };
        var report = DiagnosticsReport.Build(f.Host, "9.9.9", "Information", "Default", lines);

        Assert.Contains("Phone2Gamepad diagnostics", report);
        Assert.Contains("App version: 9.9.9", report);
        Assert.Contains("OS:", report);
        Assert.Contains("Driver", report);
        Assert.Contains("Paired devices: 1", report);
        Assert.Contains("Received", report);
        Assert.DoesNotContain(pin, report);
        Assert.DoesNotContain(Convert.ToHexString(secret), report, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("192.168.100.17", report);
        Assert.DoesNotContain("my-device-id-777", report);
        Assert.DoesNotContain("Alice", report);
        _ = client;
    }

    [Fact]
    public async Task Report_with_network_details_enabled_still_hides_pin_and_secret()
    {
        using var f = new ServerFixture();
        var (_, secret) = await f.PairAsync();
        var pin = f.Host.Pairing.Open();
        var report = DiagnosticsReport.Build(f.Host, "1.0.0", "Debug", "Default",
            new[] { $"PIN {pin}", "from 192.168.100.17", Convert.ToBase64String(secret) + Convert.ToBase64String(secret) },
            new DiagnosticsOptions(MaskNetworkDetails: false));
        Assert.Contains("192.168.100.17", report);
        Assert.DoesNotContain(pin, report);
        Assert.DoesNotContain(Convert.ToBase64String(secret), report);
    }

    [Fact]
    public void Report_limits_log_lines()
    {
        using var f = new ServerFixture();
        var lines = Enumerable.Range(0, 1000).Select(i => $"line-{i};");
        var report = DiagnosticsReport.Build(f.Host, "1", "Information", "Default", lines, new DiagnosticsOptions(MaxLogLines: 10));
        Assert.Contains("line-999;", report);
        Assert.Contains("line-990;", report);
        Assert.DoesNotContain("line-989;", report);
        Assert.DoesNotContain("line-0;", report);
    }

    [Fact]
    public void WriteTo_creates_the_file()
    {
        var dir = Path.Combine(Path.GetTempPath(), "p2g-diag-" + Guid.NewGuid());
        var path = Path.Combine(dir, "sub", "d.txt");
        try { DiagnosticsReport.WriteTo(path, "hello"); Assert.Equal("hello", File.ReadAllText(path)); }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }
}
