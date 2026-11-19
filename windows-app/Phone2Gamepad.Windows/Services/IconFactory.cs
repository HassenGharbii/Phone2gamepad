using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace Phone2Gamepad.Windows.Services;

public enum TrayBadge { None, Ready, Playing, Problem }

/// <summary>Builds the app/tray icon at runtime from the system "Game" glyph (Segoe Fluent Icons) plus a small status dot.</summary>
public static class IconFactory
{
    private const string GamepadGlyph = "";

    public static Icon Create(int size, Color glyph, TrayBadge badge)
    {
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            g.Clear(Color.Transparent);
            using var font = PickFont(size * 0.78f);
            using var brush = new SolidBrush(glyph);
            var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(GamepadGlyph, font, brush, new RectangleF(0, size * 0.02f, size, size), fmt);
            if (badge != TrayBadge.None)
            {
                var c = badge switch { TrayBadge.Playing => Color.FromArgb(0x6C, 0xCB, 0x5F), TrayBadge.Problem => Color.FromArgb(0xFF, 0x6B, 0x6B), _ => Color.FromArgb(0x60, 0xCD, 0xFF) };
                float d = size * 0.42f;
                using var ring = new SolidBrush(Color.FromArgb(0x20, 0x20, 0x20));
                using var dot = new SolidBrush(c);
                g.FillEllipse(ring, size - d - 0.5f, size - d - 0.5f, d + 1, d + 1);
                g.FillEllipse(dot, size - d + size * 0.06f, size - d + size * 0.06f, d - size * 0.12f, d - size * 0.12f);
            }
        }
        var h = bmp.GetHicon();
        try { return (Icon)Icon.FromHandle(h).Clone(); }
        finally { DestroyIcon(h); }
    }

    public static BitmapSource ToImageSource(Icon icon) =>
        Imaging.CreateBitmapSourceFromHIcon(icon.Handle, System.Windows.Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());

    private static Font PickFont(float px)
    {
        foreach (var name in new[] { "Segoe Fluent Icons", "Segoe MDL2 Assets" })
        {
            var f = new Font(name, px, GraphicsUnit.Pixel);
            if (f.Name == name) return f;
            f.Dispose();
        }
        return new Font(SystemFonts.DefaultFont.FontFamily, px, GraphicsUnit.Pixel);
    }

    [DllImport("user32.dll")] private static extern bool DestroyIcon(IntPtr h);
}
