using System.Runtime.InteropServices;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.TestClient;

/// <summary>Reads real XInput state (xinput1_4.dll) - what games see - to verify the virtual controller end to end.</summary>
public static class XInput
{
    [StructLayout(LayoutKind.Sequential)]
    public struct Gamepad
    {
        public ushort Buttons; public byte LeftTrigger, RightTrigger;
        public short ThumbLX, ThumbLY, ThumbRX, ThumbRY;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct State { public uint PacketNumber; public Gamepad Gamepad; }

    [DllImport("xinput1_4.dll", EntryPoint = "XInputGetState")]
    private static extern uint XInputGetState(uint userIndex, out State state);

    public const ushort DPadUp = 0x1, DPadDown = 0x2, DPadLeft = 0x4, DPadRight = 0x8, Start = 0x10, Back = 0x20,
        LeftThumb = 0x40, RightThumb = 0x80, LeftShoulder = 0x100, RightShoulder = 0x200, A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;

    public static bool TryRead(int index, out Gamepad gp)
    {
        gp = default;
        if (XInputGetState((uint)index, out var s) != 0) return false; // ERROR_DEVICE_NOT_CONNECTED
        gp = s.Gamepad;
        return true;
    }

    public static IEnumerable<int> ConnectedSlots() => Enumerable.Range(0, 4).Where(i => TryRead(i, out _));

    /// <summary>The XInput representation we expect for a wire state (Guide is not exposed by XInputGetState and is ignored).</summary>
    public static Gamepad Expected(GamepadState s)
    {
        ushort b = 0;
        void F(GamepadButtons f, ushort x) { if ((s.Buttons & f) != 0) b |= x; }
        F(GamepadButtons.A, A); F(GamepadButtons.B, B); F(GamepadButtons.X, X); F(GamepadButtons.Y, Y);
        F(GamepadButtons.LeftBumper, LeftShoulder); F(GamepadButtons.RightBumper, RightShoulder);
        F(GamepadButtons.Back, Back); F(GamepadButtons.Start, Start);
        F(GamepadButtons.LeftStick, LeftThumb); F(GamepadButtons.RightStick, RightThumb);
        if ((s.Dpad & DpadFlags.Up) != 0) b |= DPadUp;
        if ((s.Dpad & DpadFlags.Down) != 0) b |= DPadDown;
        if ((s.Dpad & DpadFlags.Left) != 0) b |= DPadLeft;
        if ((s.Dpad & DpadFlags.Right) != 0) b |= DPadRight;
        return new Gamepad { Buttons = b, LeftTrigger = s.LeftTrigger, RightTrigger = s.RightTrigger, ThumbLX = C(s.LeftX), ThumbLY = C(s.LeftY), ThumbRX = C(s.RightX), ThumbRY = C(s.RightY) };
    }

    /// <summary>The default mapping clamps -32768 to -32767 so stick range is symmetric.</summary>
    private static short C(short v) => v == short.MinValue ? (short)-32767 : v;

    public static bool Equal(Gamepad a, Gamepad b) =>
        a.Buttons == b.Buttons && a.LeftTrigger == b.LeftTrigger && a.RightTrigger == b.RightTrigger
        && a.ThumbLX == b.ThumbLX && a.ThumbLY == b.ThumbLY && a.ThumbRX == b.ThumbRX && a.ThumbRY == b.ThumbRY;

    public static string Describe(Gamepad g) =>
        $"btn=0x{g.Buttons:X4} LT={g.LeftTrigger} RT={g.RightTrigger} L=({g.ThumbLX},{g.ThumbLY}) R=({g.ThumbRX},{g.ThumbRY})";
}
