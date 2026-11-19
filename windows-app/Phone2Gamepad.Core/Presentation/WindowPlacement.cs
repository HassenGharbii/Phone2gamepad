namespace Phone2Gamepad.Core.Presentation;

public readonly record struct UiRect(double Left, double Top, double Width, double Height)
{
    public double Right => Left + Width;
    public double Bottom => Top + Height;
}

/// <summary>Keeps a restored window fully on a visible work area: never larger than it and never off-screen.</summary>
public static class WindowPlacement
{
    /// <param name="saved">Last saved bounds, or null on first run.</param>
    /// <param name="workAreas">Work areas of all monitors (taskbar excluded), primary first.</param>
    /// <param name="preferred">Size used on first run.</param>
    /// <param name="min">Minimum window size (shrunk further only if the work area is smaller).</param>
    public static UiRect Resolve(UiRect? saved, IReadOnlyList<UiRect> workAreas, (double W, double H) preferred, (double W, double H) min)
    {
        if (workAreas.Count == 0) workAreas = new[] { new UiRect(0, 0, 1280, 720) };
        UiRect area = workAreas[0];
        double w = preferred.W, h = preferred.H, l, t;
        bool centre = true;

        if (saved is { } s && double.IsFinite(s.Left) && double.IsFinite(s.Top) && double.IsFinite(s.Width) && double.IsFinite(s.Height)
            && s.Width > 0 && s.Height > 0)
        {
            double cx = s.Left + s.Width / 2, cy = s.Top + s.Height / 2;
            var containing = workAreas.Where(a => cx >= a.Left && cx < a.Right && cy >= a.Top && cy < a.Bottom).ToList();
            area = containing.Count > 0 ? containing[0] : BestOverlap(workAreas, s);
            w = s.Width; h = s.Height;
            l = s.Left; t = s.Top;
            centre = false;
        }
        else { l = t = 0; }

        w = Math.Min(Math.Max(w, Math.Min(min.W, area.Width)), area.Width);
        h = Math.Min(Math.Max(h, Math.Min(min.H, area.Height)), area.Height);
        if (centre) { l = area.Left + (area.Width - w) / 2; t = area.Top + (area.Height - h) / 2; }
        l = Math.Clamp(l, area.Left, area.Right - w);
        t = Math.Clamp(t, area.Top, area.Bottom - h);
        return new UiRect(l, t, w, h);
    }

    private static UiRect BestOverlap(IReadOnlyList<UiRect> areas, UiRect r) =>
        areas.OrderByDescending(a =>
                Math.Max(0, Math.Min(a.Right, r.Right) - Math.Max(a.Left, r.Left)) * Math.Max(0, Math.Min(a.Bottom, r.Bottom) - Math.Max(a.Top, r.Top)))
             .First();
}
