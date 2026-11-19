using System.Globalization;

namespace Phone2Gamepad.Core.Presentation;

/// <summary>Formatting rules shared by the desktop UI. Unavailable measurements are shown as <see cref="Unavailable"/>, never as 0.</summary>
public static class UiFormat
{
    public const string Unavailable = "—";

    public static string Latency(double? ms) => ms is { } v && double.IsFinite(v) && v >= 0 ? $"{Math.Round(v):0} ms" : Unavailable;

    public static string Rate(double? perSecond) => perSecond is { } v && double.IsFinite(v) && v >= 0 ? $"{Math.Round(v):0}/s" : Unavailable;

    public static string Count(long? n) => n is { } v && v >= 0 ? v.ToString("N0", CultureInfo.CurrentCulture) : Unavailable;

    /// <summary>h:mm:ss for under a day, otherwise "Nd hh:mm".</summary>
    public static string Duration(TimeSpan? t)
    {
        if (t is not { } d || d < TimeSpan.Zero) return Unavailable;
        if (d.TotalDays >= 1) return $"{(int)d.TotalDays}d {d.Hours:00}:{d.Minutes:00}";
        return $"{(int)d.TotalHours}:{d.Minutes:00}:{d.Seconds:00}";
    }

    /// <summary>"Just now", "5 min ago", "3 h ago", "Yesterday", "4 days ago", or a short date.</summary>
    public static string RelativeTime(DateTimeOffset? when, DateTimeOffset now)
    {
        if (when is not { } w) return "Never";
        var d = now - w;
        if (d < TimeSpan.FromMinutes(1)) return "Just now";
        if (d < TimeSpan.FromHours(1)) return $"{(int)d.TotalMinutes} min ago";
        if (d < TimeSpan.FromDays(1)) return $"{(int)d.TotalHours} h ago";
        if (d < TimeSpan.FromDays(2)) return "Yesterday";
        if (d < TimeSpan.FromDays(14)) return $"{(int)d.TotalDays} days ago";
        return w.ToLocalTime().ToString("d", CultureInfo.CurrentCulture);
    }

    /// <summary>"123 456" grouping; placeholder dots when no PIN is active.</summary>
    public static string Pin(string? pin) => pin is { Length: 6 } p ? $"{p[..3]} {p[3..]}" : "••• •••";

    /// <summary>Seconds left (never negative) and the 0..1 fraction of the window remaining.</summary>
    public static (int Seconds, double Fraction) Countdown(DateTimeOffset? expires, DateTimeOffset now, TimeSpan window)
    {
        if (expires is not { } e || window <= TimeSpan.Zero) return (0, 0);
        var left = e - now;
        if (left <= TimeSpan.Zero) return (0, 0);
        return ((int)Math.Ceiling(left.TotalSeconds), Math.Clamp(left / window, 0, 1));
    }

    private static readonly Dictionary<GamepadButtons, (string Short, string Long)> Names = new()
    {
        [GamepadButtons.A] = ("A", "A"), [GamepadButtons.B] = ("B", "B"), [GamepadButtons.X] = ("X", "X"), [GamepadButtons.Y] = ("Y", "Y"),
        [GamepadButtons.LeftBumper] = ("LB", "Left bumper (LB)"), [GamepadButtons.RightBumper] = ("RB", "Right bumper (RB)"),
        [GamepadButtons.Back] = ("View", "View / Back"), [GamepadButtons.Start] = ("Menu", "Menu / Start"),
        [GamepadButtons.LeftStick] = ("LS", "Left stick click (L3)"), [GamepadButtons.RightStick] = ("RS", "Right stick click (R3)"),
        [GamepadButtons.Guide] = ("Guide", "Guide (Xbox button)"),
    };

    public static string ButtonShort(GamepadButtons b) => Names.TryGetValue(b, out var n) ? n.Short : b.ToString();
    public static string ButtonLong(GamepadButtons b) => Names.TryGetValue(b, out var n) ? n.Long : b.ToString();
}
