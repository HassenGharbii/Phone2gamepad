using System.Text.Json.Nodes;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Tests;

public sealed class TempDir : IDisposable
{
    public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "p2g-set-" + Guid.NewGuid());
    public TempDir() => Directory.CreateDirectory(Path);
    public string File(string n) => System.IO.Path.Combine(Path, n);
    public void Dispose() { try { Directory.Delete(Path, true); } catch { } }
}

public class SettingsTests
{
    private static MappingProfile Swapped() => new()
    {
        Name = "Swapped",
        ButtonMap = new() { [GamepadButtons.A] = GamepadButtons.B, [GamepadButtons.B] = GamepadButtons.A },
        InvertLeftY = true, LeftDeadZone = 0.15,
    };

    [Fact]
    public void Missing_file_gives_defaults()
    {
        using var d = new TempDir();
        var s = AppSettings.Load(d.File("s.json"));
        Assert.Equal(AppSettings.CurrentSchema, s.SchemaVersion);
        Assert.Empty(s.Profiles);
        Assert.False(s.StartWithWindows); // opt-in only
        Assert.Equal("Information", s.LogLevel);
    }

    [Fact]
    public void Save_then_load_round_trips_settings_and_profiles()
    {
        using var d = new TempDir();
        var s = new AppSettings { LogLevel = "Debug", ServerName = "MyPC", ControlPort = 50000, InputPort = 50001, DiscoveryPort = 50002, ActiveProfile = "Swapped" };
        s.Profiles.Add(Swapped());
        s.Save(d.File("s.json"));

        var r = AppSettings.Load(d.File("s.json"));
        Assert.Equal("Debug", r.LogLevel);
        Assert.Equal("MyPC", r.ServerName);
        Assert.Equal(50000, r.ControlPort);
        Assert.Equal("Swapped", r.ActiveProfile);
        var p = Assert.Single(r.Profiles);
        Assert.Equal(GamepadButtons.B, p.ButtonMap[GamepadButtons.A]);
        Assert.True(p.InvertLeftY);
        Assert.Equal(0.15, p.LeftDeadZone);
    }

    [Fact]
    public void Corrupt_file_is_set_aside_and_defaults_used()
    {
        using var d = new TempDir();
        File.WriteAllText(d.File("s.json"), "{ this is not json");
        var warnings = new List<string>();
        var s = AppSettings.Load(d.File("s.json"), warnings.Add);
        Assert.Empty(s.Profiles);
        Assert.Single(warnings);
        Assert.False(File.Exists(d.File("s.json")));
        Assert.Single(Directory.GetFiles(d.Path, "s.json.corrupt-*"));
        s.Save(d.File("s.json")); // a later save works
        Assert.True(File.Exists(d.File("s.json")));
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("null")]
    [InlineData("\"text\"")]
    [InlineData("")]
    public void Wrong_json_shapes_are_recovered(string content)
    {
        using var d = new TempDir();
        File.WriteAllText(d.File("s.json"), content);
        var s = AppSettings.Load(d.File("s.json"));
        Assert.Equal(AppSettings.CurrentSchema, s.SchemaVersion);
        Assert.Single(Directory.GetFiles(d.Path, "s.json.corrupt-*"));
    }

    [Fact]
    public void Newer_schema_is_not_overwritten_and_defaults_are_used()
    {
        using var d = new TempDir();
        File.WriteAllText(d.File("s.json"), "{\"SchemaVersion\": 99, \"ServerName\": \"future\"}");
        var warnings = new List<string>();
        var s = AppSettings.Load(d.File("s.json"), warnings.Add);
        Assert.NotEqual("future", s.ServerName);
        Assert.Single(warnings);
        Assert.Contains("newer", warnings[0]);
        var kept = Assert.Single(Directory.GetFiles(d.Path, "s.json.corrupt-*"));
        Assert.Contains("future", File.ReadAllText(kept)); // the user's newer file is preserved
    }

    [Fact]
    public void Legacy_file_without_schema_is_migrated_and_backed_up()
    {
        using var d = new TempDir();
        File.WriteAllText(d.File("s.json"), "{\"ServerName\": \"OldPC\", \"LogLevel\": \"Warning\"}");
        var warnings = new List<string>();
        var s = AppSettings.Load(d.File("s.json"), warnings.Add);
        Assert.Equal("OldPC", s.ServerName);
        Assert.Equal("Warning", s.LogLevel);
        Assert.Equal(AppSettings.CurrentSchema, s.SchemaVersion);
        Assert.Contains(warnings, w => w.Contains("upgraded"));
        Assert.True(File.Exists(d.File("s.json.v0.bak")));
    }

    [Fact]
    public void Bad_profiles_are_dropped_without_losing_settings_or_other_profiles()
    {
        using var d = new TempDir();
        var s = new AppSettings { ServerName = "KeepMe" };
        s.Profiles.Add(Swapped());
        s.Save(d.File("s.json"));
        var root = JsonNode.Parse(File.ReadAllText(d.File("s.json")))!.AsObject();
        var arr = root["Profiles"]!.AsArray();
        arr.Add(JsonNode.Parse("{\"Name\":\"BadEnum\",\"ButtonMap\":{\"NotAButton\":\"A\"}}"));
        arr.Add(JsonNode.Parse("{\"Name\":\"BadRange\",\"LeftDeadZone\":5}"));
        arr.Add(JsonNode.Parse("{\"Name\":\"Dup\",\"ButtonMap\":{\"A\":\"B\",\"X\":\"B\"}}"));
        arr.Add(JsonNode.Parse("{\"Name\":\"Default\"}"));
        arr.Add(JsonNode.Parse("\"garbage\""));
        File.WriteAllText(d.File("s.json"), root.ToJsonString());

        var warnings = new List<string>();
        var r = AppSettings.Load(d.File("s.json"), warnings.Add);
        Assert.Equal("KeepMe", r.ServerName);
        Assert.Equal(new[] { "Swapped" }, r.Profiles.Select(p => p.Name).ToArray());
        Assert.Contains(warnings, w => w.Contains("5 invalid"));
    }

    [Fact]
    public void Out_of_range_values_are_sanitized()
    {
        using var d = new TempDir();
        File.WriteAllText(d.File("s.json"), "{\"SchemaVersion\":1,\"ControlPort\":80,\"InputPort\":99999,\"DiscoveryPort\":47820,\"LogLevel\":\"Chatty\",\"ServerName\":\"\",\"ActiveProfile\":\"Gone\"}");
        var s = AppSettings.Load(d.File("s.json"));
        Assert.Equal(ProtocolConstants.DefaultControlPort, s.ControlPort);
        Assert.Equal(ProtocolConstants.DefaultInputPort, s.InputPort);
        Assert.Equal("Information", s.LogLevel);
        Assert.False(string.IsNullOrWhiteSpace(s.ServerName));
        Assert.Equal("Default", s.ActiveProfile);
    }

    [Fact]
    public void Duplicate_ports_restore_defaults()
    {
        using var d = new TempDir();
        File.WriteAllText(d.File("s.json"), "{\"SchemaVersion\":1,\"ControlPort\":50000,\"InputPort\":50000,\"DiscoveryPort\":50001}");
        var s = AppSettings.Load(d.File("s.json"));
        Assert.Equal(ProtocolConstants.DefaultControlPort, s.ControlPort);
        Assert.Equal(ProtocolConstants.DefaultInputPort, s.InputPort);
        Assert.Equal(ProtocolConstants.DefaultDiscoveryPort, s.DiscoveryPort);
    }

    [Fact]
    public void Loaded_profile_changes_output_and_default_profile_restores_identity()
    {
        using var d = new TempDir();
        var s = new AppSettings(); s.Profiles.Add(Swapped()); s.Save(d.File("s.json"));
        var p = AppSettings.Load(d.File("s.json")).Profiles[0];
        var input = new GamepadState(GamepadButtons.A, DpadFlags.None, 0, 20000, 0, 0, 0, 0);
        var mapped = ControllerMapper.Apply(input, p);
        Assert.Equal(GamepadButtons.B, mapped.Buttons);
        Assert.True(mapped.LeftY < 0); // Y inverted (dead zone applied)
        var restored = ControllerMapper.Apply(input, MappingProfile.CreateDefault());
        Assert.Equal(GamepadButtons.A, restored.Buttons);
        Assert.Equal(20000, restored.LeftY);
    }

    [Fact]
    public void Save_is_atomic_and_leaves_no_temp_file()
    {
        using var d = new TempDir();
        new AppSettings().Save(d.File("s.json"));
        Assert.False(File.Exists(d.File("s.json.tmp")));
    }
}
