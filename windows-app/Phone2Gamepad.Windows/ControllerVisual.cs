using System.Globalization;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Windows;

/// <summary>Live controller visualization drawn straight from the mapped state that is sent to the virtual controller.</summary>
public sealed class ControllerVisual : FrameworkElement
{
    public static readonly DependencyProperty StateProperty = DependencyProperty.Register(
        nameof(State), typeof(GamepadState), typeof(ControllerVisual),
        new FrameworkPropertyMetadata(default(GamepadState), FrameworkPropertyMetadataOptions.AffectsRender));

    public GamepadState State { get => (GamepadState)GetValue(StateProperty); set => SetValue(StateProperty, value); }

    private static readonly Brush Body = Freeze(new SolidColorBrush(Color.FromRgb(0x2B, 0x2D, 0x31)));
    private static readonly Brush Idle = Freeze(new SolidColorBrush(Color.FromRgb(0x3A, 0x3D, 0x43)));
    private static readonly Brush Active = Freeze(new SolidColorBrush(Color.FromRgb(0x4C, 0x8D, 0xFF)));
    private static readonly Brush Text = Freeze(new SolidColorBrush(Color.FromRgb(0xE6, 0xE6, 0xE6)));
    private static readonly Pen Outline = new Pen(Freeze(new SolidColorBrush(Color.FromRgb(0x55, 0x58, 0x5F))), 1.5);

    private static T Freeze<T>(T f) where T : Freezable { f.Freeze(); return f; }

    public ControllerVisual()
    {
        MinWidth = 520; MinHeight = 300;
        // Redraw at ~30 Hz from whatever state is bound; cheap, and avoids needing property change per packet.
        var t = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(33) };
        t.Tick += (_, _) => InvalidateVisual();
        Loaded += (_, _) => t.Start();
        Unloaded += (_, _) => t.Stop();
    }

    private void Dot(DrawingContext dc, double x, double y, double r, bool on, string label)
    {
        dc.DrawEllipse(on ? Active : Idle, Outline, new Point(x, y), r, r);
        var ft = new FormattedText(label, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, new Typeface("Segoe UI Semibold"), 13, Text, 1.0);
        dc.DrawText(ft, new Point(x - ft.Width / 2, y - ft.Height / 2));
    }

    private void Pill(DrawingContext dc, double x, double y, double w, double h, bool on, string label)
    {
        dc.DrawRoundedRectangle(on ? Active : Idle, Outline, new Rect(x, y, w, h), 6, 6);
        var ft = new FormattedText(label, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, new Typeface("Segoe UI Semibold"), 12, Text, 1.0);
        dc.DrawText(ft, new Point(x + (w - ft.Width) / 2, y + (h - ft.Height) / 2));
    }

    private void Stick(DrawingContext dc, double cx, double cy, double r, short sx, short sy, bool click, string label)
    {
        dc.DrawEllipse(Body, Outline, new Point(cx, cy), r, r);
        double px = cx + sx / 32767.0 * r * 0.65, py = cy - sy / 32767.0 * r * 0.65;
        dc.DrawEllipse(click ? Active : Idle, Outline, new Point(px, py), r * 0.38, r * 0.38);
        var ft = new FormattedText($"{label} {sx},{sy}", CultureInfo.InvariantCulture, FlowDirection.LeftToRight, new Typeface("Segoe UI"), 11, Text, 1.0);
        dc.DrawText(ft, new Point(cx - ft.Width / 2, cy + r + 4));
    }

    private void Trigger(DrawingContext dc, double x, double y, double w, double h, byte v, string label)
    {
        dc.DrawRoundedRectangle(Idle, Outline, new Rect(x, y, w, h), 4, 4);
        double fill = h * v / 255.0;
        if (fill > 0) dc.DrawRoundedRectangle(Active, null, new Rect(x + 1, y + h - fill, w - 2, fill), 3, 3);
        var ft = new FormattedText($"{label} {v}", CultureInfo.InvariantCulture, FlowDirection.LeftToRight, new Typeface("Segoe UI"), 11, Text, 1.0);
        dc.DrawText(ft, new Point(x + w / 2 - ft.Width / 2, y - 16));
    }

    protected override void OnRender(DrawingContext dc)
    {
        var s = State;
        double W = Math.Max(ActualWidth, MinWidth), H = Math.Max(ActualHeight, MinHeight);
        dc.DrawRoundedRectangle(null, Outline, new Rect(1, 1, W - 2, H - 2), 18, 18);
        bool B(GamepadButtons b) => (s.Buttons & b) != 0;
        bool D(DpadFlags d) => (s.Dpad & d) != 0;
        double u = Math.Min(W / 520, H / 300);
        double ox = (W - 520 * u) / 2, oy = (H - 300 * u) / 2;
        dc.PushTransform(new TranslateTransform(ox, oy));
        dc.PushTransform(new ScaleTransform(u, u));

        Trigger(dc, 60, 40, 60, 40, s.LeftTrigger, "LT");
        Trigger(dc, 400, 40, 60, 40, s.RightTrigger, "RT");
        Pill(dc, 60, 90, 60, 22, B(GamepadButtons.LeftBumper), "LB");
        Pill(dc, 400, 90, 60, 22, B(GamepadButtons.RightBumper), "RB");

        Stick(dc, 130, 170, 45, s.LeftX, s.LeftY, B(GamepadButtons.LeftStick), "L");
        Stick(dc, 330, 235, 45, s.RightX, s.RightY, B(GamepadButtons.RightStick), "R");

        // D-pad
        Dot(dc, 215, 235, 17, D(DpadFlags.Left), "◀"); Dot(dc, 275, 235, 17, D(DpadFlags.Right), "▶");
        Dot(dc, 245, 205, 17, D(DpadFlags.Up), "▲"); Dot(dc, 245, 265, 17, D(DpadFlags.Down), "▼");

        // Face buttons
        Dot(dc, 410, 170, 19, B(GamepadButtons.Y), "Y"); Dot(dc, 410, 220, 19, B(GamepadButtons.A), "A");
        Dot(dc, 385, 195, 19, B(GamepadButtons.X), "X"); Dot(dc, 435, 195, 19, B(GamepadButtons.B), "B");

        Pill(dc, 215, 150, 45, 22, B(GamepadButtons.Back), "Back"); Pill(dc, 270, 150, 45, 22, B(GamepadButtons.Start), "Start");
        Pill(dc, 238, 110, 44, 22, B(GamepadButtons.Guide), "Guide");

        dc.Pop(); dc.Pop();
    }
}
