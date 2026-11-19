using Phone2Gamepad.Network;

namespace Phone2Gamepad.Tests;

public class StoreTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "p2g-test-" + Guid.NewGuid().ToString("N"));
    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    [Fact]
    public void Dpapi_store_roundtrips_and_the_secret_is_not_stored_in_clear()
    {
        var path = Path.Combine(_dir, "devices.json");
        var secret = Enumerable.Range(0, 32).Select(i => (byte)(i * 7)).ToArray();
        var s1 = new DpapiPairedDeviceStore(path);
        s1.Save(new PairedDevice("d1", "Phone", secret, DateTimeOffset.UtcNow));
        var text = File.ReadAllText(path);
        Assert.DoesNotContain(Convert.ToBase64String(secret), text);
        Assert.DoesNotContain(Convert.ToHexString(secret), text, StringComparison.OrdinalIgnoreCase);

        var s2 = new DpapiPairedDeviceStore(path);
        Assert.Equal(secret, s2.Get("d1")!.Secret);
        Assert.True(s2.Remove("d1"));
        Assert.Empty(new DpapiPairedDeviceStore(path).List());
    }

    [Fact]
    public void Corrupt_store_is_set_aside_and_replaced_by_an_empty_one()
    {
        Directory.CreateDirectory(_dir);
        var path = Path.Combine(_dir, "devices.json");
        File.WriteAllText(path, "{ this is not json");
        var s = new DpapiPairedDeviceStore(path);
        Assert.Empty(s.List());
        Assert.Contains(Directory.GetFiles(_dir), f => f.Contains(".corrupt-"));
        s.Save(new PairedDevice("d", "n", new byte[32], DateTimeOffset.UtcNow));
        Assert.Single(new DpapiPairedDeviceStore(path).List());
    }

    [Fact]
    public void Unsupported_schema_version_is_treated_as_corrupt()
    {
        Directory.CreateDirectory(_dir);
        var path = Path.Combine(_dir, "devices.json");
        File.WriteAllText(path, "{\"schema\":99,\"devices\":[]}");
        Assert.Empty(new DpapiPairedDeviceStore(path).List());
        Assert.Contains(Directory.GetFiles(_dir), f => f.Contains(".corrupt-"));
    }
}
