using System.Net.Sockets;
using System.Text.Json.Nodes;
using Phone2Gamepad.Network;

namespace Phone2Gamepad.Tests;

public class ServerIdTests
{
    private static async Task<JsonObject> HelloAsync(ServerFixture f)
    {
        using var tcp = new TcpClient();
        await tcp.ConnectAsync(f.Host4, f.ControlPort);
        var ch = new ControlChannel(tcp.GetStream());
        await ch.WriteAsync(new JsonObject { ["t"] = "hello", ["ver"] = 1, ["deviceId"] = "d", ["deviceName"] = "n", ["caps"] = new JsonArray() }, CancellationToken.None);
        return (await ch.ReadAsync(CancellationToken.None))!;
    }

    [Fact]
    public async Task HelloOk_contains_serverId_when_configured()
    {
        var id = Guid.NewGuid().ToString("D");
        using var f = new ServerFixture(serverId: id);
        var r = await HelloAsync(f);
        Assert.Equal("hello_ok", ControlChannel.Type(r));
        Assert.Equal(id, ControlChannel.Str(r, "serverId"));
        Assert.Equal("TestPC", ControlChannel.Str(r, "serverName")); // existing fields unchanged (additive change)
    }

    [Fact]
    public async Task HelloOk_omits_serverId_when_not_configured()
    {
        using var f = new ServerFixture();
        var r = await HelloAsync(f);
        Assert.Equal("hello_ok", ControlChannel.Type(r));
        Assert.Null(r["serverId"]);
    }

    [Fact]
    public async Task PhoneClient_reports_serverId_after_pairing()
    {
        var id = Guid.NewGuid().ToString("D");
        using var f = new ServerFixture(serverId: id);
        var c = new PhoneClient("dev-x", "Phone");
        var r = await c.PairAsync(f.Host4, f.ControlPort, f.Host.Pairing.Open(), CancellationToken.None);
        Assert.True(r.Ok, r.Reason);
        Assert.Equal(id, r.ServerId);
    }

    [Fact]
    public void ServerIdentity_is_generated_once_and_persisted()
    {
        var dir = Path.Combine(Path.GetTempPath(), "p2g-sid-" + Guid.NewGuid());
        var path = Path.Combine(dir, "server-id.txt");
        try
        {
            var a = ServerIdentity.LoadOrCreate(path);
            Assert.True(Guid.TryParse(a, out var g) && g != Guid.Empty);
            Assert.Equal(a, ServerIdentity.LoadOrCreate(path));
            Assert.Equal(a, File.ReadAllText(path).Trim());
        }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }

    [Theory]
    [InlineData("not a guid")]
    [InlineData("00000000-0000-0000-0000-000000000000")]
    [InlineData("")]
    public void ServerIdentity_regenerates_when_file_is_malformed(string content)
    {
        var dir = Path.Combine(Path.GetTempPath(), "p2g-sid-" + Guid.NewGuid());
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, "server-id.txt");
        try
        {
            File.WriteAllText(path, content);
            var id = ServerIdentity.LoadOrCreate(path);
            Assert.True(Guid.TryParse(id, out var g) && g != Guid.Empty);
            Assert.Equal(id, ServerIdentity.LoadOrCreate(path));
        }
        finally { Directory.Delete(dir, true); }
    }
}
