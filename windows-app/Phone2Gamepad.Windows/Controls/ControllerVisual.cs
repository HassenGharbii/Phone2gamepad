using System.Globalization;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Phone2Gamepad.Core;

namespace Phone2Gamepad.Windows.Controls;

/// <summary>
/// Live Xbox-style controller drawing of the mapped state that is sent to the virtual controller. Colours come from the
/// current theme tokens (Ctl*, Accent), so it follows dark/light/high-contrast. Drawn in a 600x380 design space and scaled.
/// </summary>
public sealed class ControllerVisual : FrameworkElement
{
    public static readonly DependencyProperty StateProperty = DependencyProperty.Register(
        nameof(State), typeof(GamepadState), typeof(ControllerVisual),
        new FrameworkPropertyMetadata(default(GamepadState), FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty IsLiveProperty = DependencyProperty.Register(
        nameof(IsLive), typeof(bool), typeof(ControllerVisual), new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public GamepadState State { get => (GamepadState)GetValue(StateProperty); set => SetValue(StateProperty, value); }
    /// <summary>False = no active session: the drawing is dimmed so it never looks like live input.</summary>
    public bool IsLive { get => (bool)GetValue(IsLiveProperty); set => SetValue(IsLiveProperty, value); }

    private const double DW = 600, DH = 380;
    private static readonly Geometry BodyGeometry = Geometry.Parse(
        "M175,78 C240,62 360,62 425,78 C490,86 530,104 548,140 C575,195 598,290 590,335 C583,375 540,388 512,360 " +
        "C488,335 470,300 430,292 C380,284 220,284 170,292 C130,300 112,335 88,360 C60,388 17,375 10,335 " +
        "C2,290 25,195 52,140 C70,104 110,86 175,78 Z");

    private static readonly Color AColor = Color.FromRgb(0x5D, 0xB8, 0x3E), BColor = Color.FromRgb(0xE0, 0x4B, 0x3E),
        XColor = Color.FromRgb(0x3D, 0x8E, 0xF0), YColor = Color.FromRgb(0xE8, 0xA8, 0x00);

    static ControllerVisual() { BodyGeometry.Freeze(); }

    public ControllerVisual()
    {
        MinHeight = 200;
        SnapsToDevicePixels = true;
        // The state DP changes ~30 times/s from the view model; this timer only keeps theme changes and stale frames in sync.
        var t = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(250) };
        t.Tick += (_, _) => InvalidateVisual();
        Loaded += (_, _) => t.Start();
        Unloaded += (_, _) => t.Stop();
    }

    private Brush Res(string key, Brush fallback) => TryFindResource(key) as Brush ?? fallback;

    protected override Size MeasureOverride(Size available)
    {
        double w = double.IsInfinity(available.Width) ? DW : available.Width;
        double h = double.IsInfinity(available.Height) ? w * DH / DW : Math.Min(available.Height, w * DH / DW);
        return new Size(w, Math.Max(h, MinHeight));
    }

    protected override void OnRender(DrawingContext dc)
    {
        var s = State;
        double u = Math.Min(ActualWidth / DW, ActualHeight / DH);
        if (u <= 0) return;
        double ox = (ActualWidth - DW * u) / 2, oy = (ActualHeight - DH * u) / 2;
        double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;

        Brush body = Res("CtlBody", Brushes.DimGray), edge = Res("CtlBodyEdge", Brushes.Gray), well = Res("CtlWell", Brushes.Black),
              idle = Res("CtlIdle", Brushes.Gray), idleText = Res("CtlIdleText", Brushes.White), accent = Res("Accent", Brushes.DeepSkyBlue),
              onAccent = Res("OnAccent", Brushes.Black), shadow = Res("CtlShadow", Brushes.Transparent), muted = Res("FgTertiary", Brushes.Gray);
        var edgePen = new Pen(edge, 1.5);
        var accentPen = new Pen(accent, 3);

        dc.PushTransform(new TranslateTransform(ox, oy));
        dc.PushTransform(new ScaleTransform(u, u));
        if (!IsLive) dc.PushOpacity(0.7);

        bool B(GamepadButtons b) => (s.Buttons & b) != 0;
        bool D(DpadFlags d) => (s.Dpad & d) != 0;

        void Label(string text, double x, double y, double size, Brush brush, bool bold = true, bool icon = false)
        {
            var ft = new FormattedText(text, CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                new Typeface(new FontFamily(icon ? "Segoe Fluent Icons, Segoe MDL2 Assets" : "Segoe UI Variable Text, Segoe UI"), FontStyles.Normal, bold ? FontWeights.SemiBold : FontWeights.Normal, FontStretches.Normal),
                size, brush, dpi);
            dc.DrawText(ft, new Point(x - ft.Width / 2, y - ft.Height / 2));
        }

        // ---- triggers (analog fill) and bumpers, drawn behind the shell
        void Trigger(double cx, double cy, double angle, byte value, string name)
        {
            dc.PushTransform(new RotateTransform(angle, cx, cy));
            var r = new Rect(cx - 45, cy - 14, 90, 28);
            dc.DrawRoundedRectangle(well, edgePen, r, 10, 10);
            double f = value / 255.0;
            if (f > 0) dc.DrawRoundedRectangle(accent, null, new Rect(r.X + 2, r.Y + 2, Math.Max(4, (r.Width - 4) * f), r.Height - 4), 8, 8);
            Label(name, cx, cy, 13, f > 0.5 ? onAccent : idleText);
            dc.Pop();
        }
        Trigger(150, 30, -6, s.LeftTrigger, "LT");
        Trigger(450, 30, 6, s.RightTrigger, "RT");

        void Bumper(double cx, double cy, double angle, bool on, string name)
        {
            dc.PushTransform(new RotateTransform(angle, cx, cy));
            dc.DrawRoundedRectangle(on ? accent : idle, edgePen, new Rect(cx - 75, cy - 15, 150, 30), 15, 15);
            Label(name, cx, cy - 4, 12, on ? onAccent : idleText);
            dc.Pop();
        }
        Bumper(150, 70, -8, B(GamepadButtons.LeftBumper), "LB");
        Bumper(450, 70, 8, B(GamepadButtons.RightBumper), "RB");

        // ---- shell
        dc.PushTransform(new TranslateTransform(0, 5));
        dc.DrawGeometry(shadow, null, BodyGeometry);
        dc.Pop();
        dc.DrawGeometry(body, edgePen, BodyGeometry);

        // ---- sticks
        void Stick(double cx, double cy, short sx, short sy, bool click, string name)
        {
            dc.DrawEllipse(well, edgePen, new Point(cx, cy), 42, 42);
            double px = cx + sx / 32767.0 * 18, py = cy - sy / 32767.0 * 18;
            bool moved = sx != 0 || sy != 0;
            dc.DrawEllipse(click ? accent : idle, click ? accentPen : moved ? new Pen(accent, 2) : edgePen, new Point(px, py), 25, 25);
            dc.DrawEllipse(null, new Pen(edge, 1), new Point(px, py), 17, 17);
            if (click) Label(name, px, py, 12, onAccent);
        }
        Stick(178, 165, s.LeftX, s.LeftY, B(GamepadButtons.LeftStick), "LS");
        Stick(368, 240, s.RightX, s.RightY, B(GamepadButtons.RightStick), "RS");

        // ---- d-pad
        dc.DrawEllipse(well, edgePen, new Point(232, 240), 38, 38);
        void Arm(Rect r, bool on) => dc.DrawRoundedRectangle(on ? accent : idle, edgePen, r, 4, 4);
        Arm(new Rect(220, 205, 24, 30), D(DpadFlags.Up));
        Arm(new Rect(220, 245, 24, 30), D(DpadFlags.Down));
        Arm(new Rect(197, 228, 30, 24), D(DpadFlags.Left));
        Arm(new Rect(237, 228, 30, 24), D(DpadFlags.Right));
        dc.DrawRectangle(idle, null, new Rect(221, 229, 22, 22));

        // ---- face buttons (Xbox colours on the letters; filled when pressed)
        void Face(double cx, double cy, bool on, string name, Color c)
        {
            var cb = new SolidColorBrush(c); cb.Freeze();
            dc.DrawEllipse(on ? cb : idle, on ? new Pen(cb, 2) : edgePen, new Point(cx, cy), 18, 18);
            Label(name, cx, cy, 15, on ? Brushes.White : cb);
        }
        Face(430, 128, B(GamepadButtons.Y), "Y", YColor);
        Face(430, 202, B(GamepadButtons.A), "A", AColor);
        Face(393, 165, B(GamepadButtons.X), "X", XColor);
        Face(467, 165, B(GamepadButtons.B), "B", BColor);

        // ---- centre buttons
        bool guide = B(GamepadButtons.Guide);
        dc.DrawEllipse(guide ? accent : idle, guide ? accentPen : edgePen, new Point(300, 112), 18, 18);
        Label("", 300, 112, 16, guide ? onAccent : idleText, bold: false, icon: true);
        void Small(double cx, double cy, bool on, string name)
        {
            dc.DrawEllipse(on ? accent : idle, on ? accentPen : edgePen, new Point(cx, cy), 11, 11);
            Label(name, cx, cy + 24, 11, muted, bold: false);
        }
        Small(262, 160, B(GamepadButtons.Back), "View");
        Small(338, 160, B(GamepadButtons.Start), "Menu");

        if (!IsLive) dc.Pop();
        dc.Pop(); dc.Pop();
    }
}
