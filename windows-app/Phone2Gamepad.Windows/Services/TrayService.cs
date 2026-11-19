using System.Drawing;
using System.Windows.Forms;

namespace Phone2Gamepad.Windows.Services;

/// <summary>System tray icon: status tooltip + badge, Open / Start-Stop server / Pair a phone / Exit, and notifications.</summary>
public sealed class TrayService : IDisposable
{
    private readonly NotifyIcon _icon;
    private readonly ToolStripMenuItem _toggle;
    private readonly ToolStripMenuItem _pair;
    private readonly ToolStripMenuItem _status;
    private TrayBadge _badge = (TrayBadge)(-1);

    public TrayService(Action open, Action toggleServer, Action openPairing, Action exit)
    {
        _status = new ToolStripMenuItem("Phone2Gamepad") { Enabled = false };
        _toggle = new ToolStripMenuItem("Start server", null, (_, _) => toggleServer());
        _pair = new ToolStripMenuItem("Pair a phone…", null, (_, _) => openPairing());
        var menu = new ContextMenuStrip { ShowImageMargin = false };
        menu.Items.Add(_status);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Open Phone2Gamepad", null, (_, _) => open()) { Font = new Font(menu.Font, FontStyle.Bold) });
        menu.Items.Add(_toggle);
        menu.Items.Add(_pair);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => exit()));
        _icon = new NotifyIcon { Text = "Phone2Gamepad", ContextMenuStrip = menu, Visible = true };
        _icon.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) open(); };
        _icon.BalloonTipClicked += (_, _) => open();
        SetBadge(TrayBadge.None);
    }

    /// <summary>Updates menu text, tooltip (truncated to the shell limit) and the badge dot.</summary>
    public void Update(bool serverRunning, string statusLine, TrayBadge badge)
    {
        _toggle.Text = serverRunning ? "Stop server" : "Start server";
        _pair.Enabled = serverRunning;
        _status.Text = statusLine;
        var tip = $"Phone2Gamepad — {statusLine}";
        _icon.Text = tip.Length > 127 ? tip[..126] + "…" : tip;
        SetBadge(badge);
    }

    public void SetDarkMenu(bool dark)
    {

        _icon.ContextMenuStrip!.Renderer = dark ? new ToolStripProfessionalRenderer(new DarkColors()) : new ToolStripProfessionalRenderer();
        foreach (ToolStripItem i in _icon.ContextMenuStrip.Items) i.ForeColor = dark ? Color.FromArgb(0xF0, 0xF0, 0xF0) : SystemColors.ControlText;
        _icon.ContextMenuStrip.BackColor = dark ? Color.FromArgb(0x2B, 0x2B, 0x2B) : SystemColors.Menu;
        var b = _badge; _badge = (TrayBadge)(-1); SetBadge(b);
    }

    private void SetBadge(TrayBadge badge)
    {
        if (badge == _badge) return;
        _badge = badge;
        var old = _icon.Icon;
        // The glyph follows the taskbar colour (Windows "SystemUsesLightTheme"), not the app theme.
        _icon.Icon = IconFactory.Create(32, TaskbarIsLight() ? Color.FromArgb(0x20, 0x20, 0x20) : Color.White, badge);
        old?.Dispose();
    }

    private static bool TaskbarIsLight()
    {
        try
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return k?.GetValue("SystemUsesLightTheme") is int v && v != 0;
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException or System.IO.IOException) { return false; }
    }

    public void Notify(string title, string text, ToolTipIcon kind = ToolTipIcon.Info) => _icon.ShowBalloonTip(4000, title, text, kind);

    public void Dispose() { _icon.Visible = false; _icon.Icon?.Dispose(); _icon.Dispose(); }

    private sealed class DarkColors : ProfessionalColorTable
    {
        private static readonly Color Bg = Color.FromArgb(0x2B, 0x2B, 0x2B), Hover = Color.FromArgb(0x3A, 0x3A, 0x3A), Line = Color.FromArgb(0x45, 0x45, 0x45);
        public override Color ToolStripDropDownBackground => Bg;
        public override Color ImageMarginGradientBegin => Bg;
        public override Color ImageMarginGradientMiddle => Bg;
        public override Color ImageMarginGradientEnd => Bg;
        public override Color MenuBorder => Line;
        public override Color MenuItemBorder => Hover;
        public override Color MenuItemSelected => Hover;
        public override Color MenuItemSelectedGradientBegin => Hover;
        public override Color MenuItemSelectedGradientEnd => Hover;
        public override Color SeparatorDark => Line;
        public override Color SeparatorLight => Line;
    }
}
