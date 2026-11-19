namespace Phone2Gamepad.Core;

/// <summary>User-configurable mapping between the phone's logical controls and the virtual controller (spec 6.4).</summary>
public sealed class MappingProfile
{
    public string Name { get; set; } = "Default";
    /// <summary>Source logical button to output button. Absent = identity.</summary>
    public Dictionary<GamepadButtons, GamepadButtons> ButtonMap { get; set; } = new();
    public bool InvertLeftX { get; set; }
    public bool InvertLeftY { get; set; }
    public bool InvertRightX { get; set; }
    public bool InvertRightY { get; set; }
    /// <summary>Radial dead zone as fraction of full scale, 0..0.9.</summary>
    public double LeftDeadZone { get; set; }
    public double RightDeadZone { get; set; }
    /// <summary>Output multiplier, 0.25..4 (1 = unchanged, clamped to full scale).</summary>
    public double LeftSensitivity { get; set; } = 1;
    public double RightSensitivity { get; set; } = 1;
    /// <summary>Trigger calibration: raw values at or below Min give 0, at or above Max give 255, linear between.</summary>
    public byte TriggerMin { get; set; } = 0;
    public byte TriggerMax { get; set; } = 255;
    public bool LeftTriggerDigital { get; set; }
    public bool RightTriggerDigital { get; set; }
    public byte DigitalThreshold { get; set; } = 128;

    public static MappingProfile CreateDefault() => new();

    private static readonly GamepadButtons[] Remappable =
    {
        GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y, GamepadButtons.LeftBumper, GamepadButtons.RightBumper,
        GamepadButtons.Back, GamepadButtons.Start, GamepadButtons.LeftStick, GamepadButtons.RightStick, GamepadButtons.Guide,
    };

    /// <summary>Returns human-readable problems; an empty list means the profile is valid.</summary>
    public IReadOnlyList<string> Validate()
    {
        var errors = new List<string>();
        foreach (var (src, dst) in ButtonMap)
        {
            if (!Remappable.Contains(src)) errors.Add($"'{src}' is not a single remappable button.");
            if (!Remappable.Contains(dst)) errors.Add($"Target '{dst}' is not a single supported virtual button.");
        }
        foreach (var g in ButtonMap.GroupBy(kv => kv.Value).Where(g => g.Count() > 1))
            errors.Add($"Duplicate mapping: {string.Join(", ", g.Select(k => k.Key))} all map to {g.Key}.");
        if (LeftDeadZone is < 0 or > 0.9 || RightDeadZone is < 0 or > 0.9) errors.Add("Dead zone must be between 0 and 0.9.");
        if (LeftSensitivity is < 0.25 or > 4 || RightSensitivity is < 0.25 or > 4) errors.Add("Sensitivity must be between 0.25 and 4.");
        if (TriggerMin >= TriggerMax) errors.Add("Trigger calibration minimum must be below maximum.");
        return errors;
    }
}

public static class ControllerMapper
{
    /// <summary>Applies the profile to a raw wire state. Pure function; neutral in gives neutral out.</summary>
    public static GamepadState Apply(in GamepadState s, MappingProfile p)
    {
        // D-pad: opposite directions cancel to neutral on that axis.
        var dpad = s.Dpad;
        if ((dpad & (DpadFlags.Up | DpadFlags.Down)) == (DpadFlags.Up | DpadFlags.Down)) dpad &= ~(DpadFlags.Up | DpadFlags.Down);
        if ((dpad & (DpadFlags.Left | DpadFlags.Right)) == (DpadFlags.Left | DpadFlags.Right)) dpad &= ~(DpadFlags.Left | DpadFlags.Right);

        var buttons = RemapButtons(s.Buttons, p);
        var (lx, ly) = Stick(s.LeftX, s.LeftY, p.InvertLeftX, p.InvertLeftY, p.LeftDeadZone, p.LeftSensitivity);
        var (rx, ry) = Stick(s.RightX, s.RightY, p.InvertRightX, p.InvertRightY, p.RightDeadZone, p.RightSensitivity);
        return new GamepadState(buttons, dpad, lx, ly, rx, ry,
            Trigger(s.LeftTrigger, p, p.LeftTriggerDigital), Trigger(s.RightTrigger, p, p.RightTriggerDigital));
    }

    private static GamepadButtons RemapButtons(GamepadButtons src, MappingProfile p)
    {
        src &= GamepadButtons.DefinedMask;
        if (p.ButtonMap.Count == 0) return src;
        var result = GamepadButtons.None;
        for (int i = 0; i < 11; i++)
        {
            var bit = (GamepadButtons)(1 << i);
            if ((src & bit) == 0) continue;
            result |= p.ButtonMap.TryGetValue(bit, out var dst) ? dst : bit;
        }
        return result;
    }

    private static (short, short) Stick(short x, short y, bool invX, bool invY, double dead, double sens)
    {
        if (dead == 0 && sens == 1) // pure pass-through (only inversion / symmetric clamp): keeps square-gated input untouched
            return (Axis(x, invX), Axis(y, invY));
        double fx = x / 32767.0, fy = y / 32767.0;
        double mag = Math.Sqrt(fx * fx + fy * fy);
        if (mag > 1) { fx /= mag; fy /= mag; mag = 1; }
        if (mag <= dead || mag == 0) return (0, 0);
        double scaled = Math.Min(1.0, (mag - dead) / (1.0 - dead) * sens);
        double k = scaled / mag;
        fx *= k; fy *= k;
        if (invX) fx = -fx;
        if (invY) fy = -fy;
        return (ToShort(fx), ToShort(fy));
    }

    private static short Axis(short v, bool invert) => (short)Math.Clamp(invert ? -(int)v : v, -32767, 32767);

    private static short ToShort(double f) => (short)Math.Clamp((int)Math.Round(f * 32767.0), -32767, 32767);

    private static byte Trigger(byte raw, MappingProfile p, bool digital)
    {
        if (digital) return raw >= p.DigitalThreshold ? (byte)255 : (byte)0;
        if (raw <= p.TriggerMin) return 0;
        if (raw >= p.TriggerMax) return 255;
        return (byte)Math.Round((raw - p.TriggerMin) * 255.0 / (p.TriggerMax - p.TriggerMin));
    }
}
