using Phone2Gamepad.Core;

namespace Phone2Gamepad.Tests;

public class MappingTests
{
    private static GamepadState S(GamepadButtons b = 0, DpadFlags d = 0, short lx = 0, short ly = 0, short rx = 0, short ry = 0, byte lt = 0, byte rt = 0) =>
        new(b, d, lx, ly, rx, ry, lt, rt);

    [Fact]
    public void Default_profile_is_identity_for_valid_input()
    {
        var s = S(GamepadButtons.A | GamepadButtons.Start | GamepadButtons.RightStick, DpadFlags.Up | DpadFlags.Left, 12000, -32767, -5, 32767, 7, 255);
        Assert.Equal(s, ControllerMapper.Apply(s, MappingProfile.CreateDefault()));
    }

    [Fact]
    public void Neutral_in_neutral_out_for_any_profile()
    {
        var p = new MappingProfile { InvertLeftX = true, LeftDeadZone = 0.3, LeftSensitivity = 2, ButtonMap = { [GamepadButtons.A] = GamepadButtons.B } };
        Assert.True(ControllerMapper.Apply(GamepadState.Neutral, p).IsNeutral);
    }

    [Fact]
    public void Full_scale_stays_full_scale_and_minus_32768_is_clamped_symmetric()
    {
        var o = ControllerMapper.Apply(S(lx: 32767, ly: -32768), MappingProfile.CreateDefault());
        Assert.Equal(32767, o.LeftX);
        Assert.True(o.LeftY >= -32767);
    }

    [Fact]
    public void Opposite_dpad_directions_cancel()
    {
        var o = ControllerMapper.Apply(S(d: DpadFlags.Up | DpadFlags.Down | DpadFlags.Left), MappingProfile.CreateDefault());
        Assert.Equal(DpadFlags.Left, o.Dpad);
    }

    [Fact]
    public void Button_remap_swaps_and_keeps_simultaneous_presses()
    {
        var p = new MappingProfile { ButtonMap = { [GamepadButtons.A] = GamepadButtons.B, [GamepadButtons.B] = GamepadButtons.A } };
        Assert.Empty(p.Validate());
        var o = ControllerMapper.Apply(S(GamepadButtons.A | GamepadButtons.X), p);
        Assert.Equal(GamepadButtons.B | GamepadButtons.X, o.Buttons);
    }

    [Fact]
    public void Duplicate_and_invalid_mappings_are_reported()
    {
        var dup = new MappingProfile { ButtonMap = { [GamepadButtons.A] = GamepadButtons.X, [GamepadButtons.B] = GamepadButtons.X } };
        Assert.Contains(dup.Validate(), e => e.Contains("Duplicate"));
        var multi = new MappingProfile { ButtonMap = { [GamepadButtons.A | GamepadButtons.B] = GamepadButtons.X } };
        Assert.NotEmpty(multi.Validate());
        Assert.NotEmpty(new MappingProfile { LeftDeadZone = 0.95 }.Validate());
        Assert.NotEmpty(new MappingProfile { TriggerMin = 200, TriggerMax = 100 }.Validate());
    }

    [Fact]
    public void Axis_inversion()
    {
        var p = new MappingProfile { InvertLeftY = true, InvertRightX = true };
        var o = ControllerMapper.Apply(S(lx: 1000, ly: 20000, rx: 3000, ry: 4000), p);
        Assert.Equal((short)1000, o.LeftX);
        Assert.Equal((short)-20000, o.LeftY);
        Assert.Equal((short)-3000, o.RightX);
        Assert.Equal((short)4000, o.RightY);
    }

    [Fact]
    public void Deadzone_zeroes_small_values_and_rescales_the_rest()
    {
        var p = new MappingProfile { LeftDeadZone = 0.2 };
        Assert.Equal(0, ControllerMapper.Apply(S(lx: 5000), p).LeftX);          // 0.15 < 0.2
        Assert.Equal(32767, ControllerMapper.Apply(S(lx: 32767), p).LeftX);     // full scale preserved
        var mid = ControllerMapper.Apply(S(lx: 19660), p).LeftX;                 // 0.6 -> (0.6-0.2)/0.8 = 0.5
        Assert.InRange(mid, 16300, 16450);
    }

    [Fact]
    public void Sensitivity_scales_and_clamps()
    {
        var p = new MappingProfile { RightSensitivity = 2 };
        Assert.InRange(ControllerMapper.Apply(S(ry: 10000), p).RightY, 19990, 20010);
        Assert.Equal(32767, ControllerMapper.Apply(S(ry: 20000), p).RightY);
    }

    [Fact]
    public void Trigger_calibration_and_digital_mode()
    {
        var p = new MappingProfile { TriggerMin = 50, TriggerMax = 200, RightTriggerDigital = true, DigitalThreshold = 100 };
        Assert.Equal(0, ControllerMapper.Apply(S(lt: 50), p).LeftTrigger);
        Assert.Equal(255, ControllerMapper.Apply(S(lt: 200), p).LeftTrigger);
        Assert.Equal(128, ControllerMapper.Apply(S(lt: 125), p).LeftTrigger);
        Assert.Equal(0, ControllerMapper.Apply(S(rt: 99), p).RightTrigger);
        Assert.Equal(255, ControllerMapper.Apply(S(rt: 100), p).RightTrigger);
    }
}
