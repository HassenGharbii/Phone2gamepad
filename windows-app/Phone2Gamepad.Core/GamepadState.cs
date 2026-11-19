namespace Phone2Gamepad.Core;

[Flags]
public enum GamepadButtons : ushort
{
    None = 0,
    A = 1 << 0, B = 1 << 1, X = 1 << 2, Y = 1 << 3,
    LeftBumper = 1 << 4, RightBumper = 1 << 5,
    Back = 1 << 6, Start = 1 << 7,
    LeftStick = 1 << 8, RightStick = 1 << 9,
    Guide = 1 << 10,
    /// <summary>Bits that carry meaning in protocol v1 (11-15 are reserved and ignored).</summary>
    DefinedMask = 0x07FF,
}

[Flags]
public enum DpadFlags : byte
{
    None = 0, Up = 1, Down = 2, Left = 4, Right = 8,
    DefinedMask = 0x0F,
}

/// <summary>Logical controller state as carried on the wire. Sticks: -32768..32767, positive Y = up. Triggers 0..255.</summary>
public readonly record struct GamepadState(
    GamepadButtons Buttons, DpadFlags Dpad,
    short LeftX, short LeftY, short RightX, short RightY,
    byte LeftTrigger, byte RightTrigger)
{
    public static GamepadState Neutral => default;
    public bool IsNeutral => this == default;
}
